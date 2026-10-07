package com.example.esp8266prj

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

/** Flow-based rain level. Thresholds are L/min; a zero or missing reading means no data. */
const val HEAVY_RAIN_LPM = 0.5

enum class RainLevel { NO_DATA, LIGHT, HEAVY }

fun rainLevel(flow: Double?): RainLevel = when {
    flow == null || flow <= 0.0 -> RainLevel.NO_DATA
    flow >= HEAVY_RAIN_LPM -> RainLevel.HEAVY
    else -> RainLevel.LIGHT
}

data class WeatherState(
    val connected: Boolean = false,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val flow: Double? = null,
    val total: Double? = null,
    val raining: Boolean? = null,
    val updatedAt: Long = 0,
    val mode: String? = null,
    val angle: Int? = null,
    val appliedId: String? = null,
    val error: String? = null,
    val notificationError: String? = null
) {
    val level: RainLevel get() = rainLevel(flow)
    fun fresh(now: Long) = updatedAt > 0 && now - updatedAt in -5000L..20000L
}

/** Telemetry/reported belong to the ESP; control belongs to the phone. */
class WeatherRepository(
    private val deviceId: String,
    private val notificationsEnabled: () -> Boolean,
    private val changed: (WeatherState) -> Unit
) {
    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val root = db.getReference("devices/$deviceId")
    private val subscriptions = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    var state = WeatherState(); private set
    private var active = false
    private var offset = 0L
    fun now() = System.currentTimeMillis() + offset
    private fun emit(value: WeatherState) { state = value; changed(value) }
    fun start() {
        if (active) return
        active = true
        listen(db.getReference(".info/serverTimeOffset")) { offset = it.getValue(Long::class.java) ?: 0 }
        listen(db.getReference(".info/connected")) {
            emit(state.copy(connected = it.getValue(Boolean::class.java) == true))
        }
        listen(root) { snapshot ->
            val t = snapshot.child("telemetry")
            val r = snapshot.child("reported")
            fun number(key: String) = (t.child(key).value as? Number)?.toDouble()?.takeIf { it.isFinite() }
            emit(state.copy(temperature = number("temperatureC"), humidity = number("humidityPct"),
                flow = number("flowLpm"), total = number("totalMl"),
                raining = t.child("raining").getValue(Boolean::class.java),
                updatedAt = (t.child("updatedAt").value as? Number)?.toLong() ?: 0,
                mode = r.child("mode").getValue(String::class.java),
                angle = (r.child("servoAngle").value as? Number)?.toInt(),
                appliedId = r.child("appliedCommandId").getValue(String::class.java), error = null))
        }
        registerToken()
    }
    private fun listen(ref: DatabaseReference, block: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { if (active) block(snapshot) }
            override fun onCancelled(error: DatabaseError) {
                if (active) emit(state.copy(error = "Không đọc được dữ liệu: ${error.message}"))
            }
        }
        subscriptions += ref to listener
        ref.addValueEventListener(listener)
    }
    fun registerToken() {
        val uid = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!active) return@addOnSuccessListener
            db.getReference("users/$uid/fcmTokens/$token").setValue(true)
                .addOnSuccessListener { if (active) emit(state.copy(notificationError = null)) }
                .addOnFailureListener { emit(state.copy(notificationError = "Cảnh báo mưa chưa đăng ký: ${it.localizedMessage}")) }
        }.addOnFailureListener { if (active) emit(state.copy(notificationError = "Chưa lấy được token thông báo; nút điều khiển vẫn dùng được.")) }
    }
    /** Re-registers the current token; the permission dialog may have been answered after the first attempt. */
    fun retryRegistration() {
        if (!notificationsEnabled()) {
            emit(state.copy(notificationError = "Thông báo đang bị tắt. Bật lại trong Cài đặt Android để nhận cảnh báo mưa."))
            return
        }
        registerToken()
    }
    fun command(mode: String, angle: Int, resetTotal: Boolean = false,
                done: (String?, String?) -> Unit) {
        if (!state.connected || !state.fresh(now()) || state.error != null) {
            done(null, "Thiết bị chưa sẵn sàng. Không gửi lệnh khi mất kết nối."); return
        }
        if (mode !in listOf("AUTO", "MANUAL") || angle !in listOf(0, 90)) {
            done(null, "Lệnh không hợp lệ"); return
        }
        // The prefix adds a reset action without changing the deployed RTDB rules/schema.
        val id = (if (resetTotal) "reset-total:" else "") + UUID.randomUUID().toString()
        // Absolute expiry also protects against Firebase replay after a network interruption.
        root.child("control").setValue(mapOf("mode" to mode, "targetAngle" to angle,
            "commandId" to id, "issuedAt" to now(), "expiresAt" to now() + 15000))
            .addOnCompleteListener { task ->
                val failure = task.exception?.localizedMessage.orEmpty()
                val message = if (failure.contains("permission denied", ignoreCase = true)) {
                    "Firebase từ chối lệnh. Dùng tài khoản điện thoại đã được cấp quyền cho thiết bị."
                } else "Gửi lệnh thất bại: $failure"
                done(if (task.isSuccessful) id else null,
                    if (task.isSuccessful) null else message)
            }
    }
    fun stop() {
        active = false
        subscriptions.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        subscriptions.clear()
    }
}
