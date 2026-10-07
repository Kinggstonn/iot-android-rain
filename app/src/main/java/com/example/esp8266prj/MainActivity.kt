package com.example.esp8266prj

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.text.InputType
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

// Hallmark: native dashboard / Garden; household monitoring, calm utilitarian tone.
// Pre-emit critique: Philosophy 4, Hierarchy 4, Execution 3, Specificity 5, Restraint 4, Variety 3.
class MainActivity : AppCompatActivity() {
    private val paper = Color.rgb(245, 247, 242)
    private val ink = Color.rgb(25, 48, 39)
    private val muted = Color.rgb(86, 103, 94)
    private val green = Color.rgb(26, 106, 70)
    private val red = Color.rgb(173, 38, 44)
    private val amber = Color.rgb(176, 106, 12)
    private val surface = Color.WHITE
    private val handler = Handler(Looper.getMainLooper())
    private var repository: WeatherRepository? = null
    private lateinit var content: LinearLayout
    private lateinit var connection: TextView
    private lateinit var rain: TextView
    private lateinit var rainHint: TextView
    private lateinit var temperature: TextView
    private lateinit var humidity: TextView
    private lateinit var flow: TextView
    private lateinit var total: TextView
    private lateinit var servo: TextView
    private lateinit var feedback: TextView
    private lateinit var mode: MaterialSwitch
    private lateinit var retract: MaterialButton
    private lateinit var extend: MaterialButton
    private lateinit var resetTotal: MaterialButton
    private var rendering = false
    private var busy = false
    private var pendingId: String? = null
    private var pendingDeadline = 0L
    private var pendingSuccess = "ESP8266 đã thực thi yêu cầu."
    private var commandAttempt = 0L
    private val preferences by lazy { getSharedPreferences("weather", MODE_PRIVATE) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) repository?.retryRegistration()
        else Toast.makeText(this, "Thông báo chưa được cấp quyền. Có thể bật lại trong Cài đặt Android.", Toast.LENGTH_LONG).show()
    }
    private val tick = object : Runnable {
        override fun run() {
            repository?.let { repo ->
                if (busy && repo.now() > pendingDeadline) {
                    commandAttempt++
                    busy = false; pendingId = null
                    feedback.text = "Chưa nhận xác nhận. Kiểm tra trạng thái thực tế trước khi gửi lại."
                }
                render(repo.state)
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RainNotifications.channel(this)
        if (FirebaseApp.initializeApp(this) == null) {
            shell(); title("Giàn phơi thông minh")
            label("Chưa cấu hình Firebase", 22)
            label("Thêm app/google-services.json rồi Sync và build lại. Xem README.md để tạo Database, tài khoản và phân quyền.")
            return
        }
        if (FirebaseAuth.getInstance().currentUser == null) login() else dashboard()
    }
    override fun onStart() { super.onStart(); repository?.start(); handler.post(tick) }
    override fun onStop() { handler.removeCallbacks(tick); repository?.stop(); super.onStop() }
    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shell() {
        val scroll = ScrollView(this).apply { setBackgroundColor(paper); isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)) }
        scroll.addView(content); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }
    private fun label(value: String, size: Int = 15, parent: LinearLayout = content): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (size >= 22) ink else muted)
        setPadding(0, dp(6), 0, dp(6))
        if (size >= 22) setTypeface(typeface, Typeface.BOLD)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun title(text: String) { label("WEATHER / HOME", 12); label(text, 30) }
    private fun button(text: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text; isAllCaps = false; minHeight = dp(52)
        backgroundTintList = ColorStateList.valueOf(green); setTextColor(surface)
        cornerRadius = dp(12); setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun field(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint; setTextColor(ink); setHintTextColor(muted); minHeight = dp(52)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun login() {
        shell(); title("Chào mừng về nhà"); label("Đăng nhập tài khoản được cấp quyền cho giàn phơi.")
        val email = field("Email"); val password = field("Mật khẩu", true); val status = label("")
        lateinit var submit: MaterialButton
        submit = button("Đăng nhập") {
            val account = email.text.toString().trim()
            if (account.isEmpty() || password.text.isEmpty()) { status.text = "Nhập email và mật khẩu."; return@button }
            submit.isEnabled = false; status.text = "Đang đăng nhập…"
            FirebaseAuth.getInstance().signInWithEmailAndPassword(account, password.text.toString())
                .addOnCompleteListener(this) { task ->
                    submit.isEnabled = true
                    if (task.isSuccessful) dashboard()
                    else status.text = "Đăng nhập thất bại. Kiểm tra tài khoản và kết nối mạng."
                }
        }
    }
    private fun metric(caption: String, unit: String): TextView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(surface)
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(12)) }
        card.addView(inner); label(caption, 14, inner)
        val value = label("— $unit", 30, inner)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return value
    }
    private fun dashboard() {
        repository?.stop(); busy = false; pendingId = null
        shell(); title("Thời tiết tại nhà")
        val deviceId = preferences.getString("deviceId", "dryer-01") ?: "dryer-01"
        connection = label("Đang kết nối • $deviceId", 14)
        temperature = metric("☀  Nhiệt độ", "°C"); humidity = metric("◉  Độ ẩm", "%")
        flow = metric("≈  Lưu lượng nước", "mL/giây")
        label("Mức mưa", 14)
        rain = label("KHÔNG CÓ DỮ LIỆU", 22)
        rainHint = label("", 14)
        total = metric("▤  Tổng tích lũy", "mL")
        label("Giàn phơi", 24); servo = label("Chưa có trạng thái servo")
        mode = MaterialSwitch(this).apply {
            text = "TỰ ĐỘNG"; setTextColor(ink); minHeight = dp(56)
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) send(if (checked) "AUTO" else "MANUAL", repository?.state?.angle ?: 90)
            }
        }
        retract = button("Thu đồ vào ") { send("MANUAL", 90) }
        extend = button("Đưa đồ ra ") { send("MANUAL", 0) }
        resetTotal = button("Đặt lại tổng lượng mưa") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Đặt tổng tích lũy về 0?")
                .setMessage("ESP8266 sẽ xóa tổng lượng nước đã đếm từ lúc khởi động.")
                .setPositiveButton("Đặt lại") { _, _ ->
                    val state = repository?.state ?: return@setPositiveButton
                    val currentMode = state.mode ?: return@setPositiveButton
                    val currentAngle = state.angle ?: return@setPositiveButton
                    send(currentMode, currentAngle, resetTotal = true)
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
        feedback = label("Tự động: ESP8266 thu đồ khi mưa, đưa ra khi khô.")
        button("Cho phép cảnh báo mưa") {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Toast.makeText(this, "Có thể chỉnh chuông/rung trong Cài đặt thông báo Android.", Toast.LENGTH_LONG).show()
            repository?.retryRegistration()
        }
        button("Kết nối lại") { repository?.stop(); repository?.start() }
        button("Chọn thiết bị") {
            val input = EditText(this).apply { setText(deviceId); isSingleLine = true }
            android.app.AlertDialog.Builder(this).setTitle("Mã thiết bị").setView(input)
                .setPositiveButton("Kết nối") { _, _ ->
                    val id = input.text.toString().trim()
                    if (id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                        preferences.edit().putString("deviceId", id).apply(); dashboard()
                    } else Toast.makeText(this, "Mã gồm chữ, số, dấu - hoặc _ (tối đa 64 ký tự).", Toast.LENGTH_LONG).show()
                }.setNegativeButton("Hủy", null).show()
        }
        repository = WeatherRepository(deviceId, ::notificationsAllowed, ::render).also { it.start() }
        render(repository!!.state)
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("askedNotifications", false)) {
            preferences.edit().putBoolean("askedNotifications", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun send(newMode: String, angle: Int, resetTotal: Boolean = false) {
        val repo = repository ?: return
        if (busy) return
        val attempt = ++commandAttempt
        busy = true; pendingDeadline = repo.now() + 20000
        pendingSuccess = if (resetTotal) "Đã đặt tổng lượng mưa về 0." else "ESP8266 đã thực thi yêu cầu."
        feedback.text = if (resetTotal) "Đang gửi yêu cầu đặt lại…" else "Đang gửi yêu cầu…"
        render(repo.state)
        repo.command(newMode, angle, resetTotal) { id, error ->
            if (repository !== repo || attempt != commandAttempt) return@command
            if (error != null) { busy = false; feedback.text = error }
            else if (busy) { pendingId = id; feedback.text = "Đã lưu yêu cầu. Đang chờ ESP8266 xác nhận…" }
            render(repo.state)
        }
    }
    private fun render(state: WeatherState) {
        val repo = repository ?: return
        val fresh = state.fresh(repo.now())
        connection.text = when {
            state.error != null -> state.error
            !state.connected -> "Mất kết nối • đang tự kết nối lại"
            !fresh -> "ESP8266 chưa gửi dữ liệu mới • số liệu có thể đã cũ"
            else -> "Đã kết nối • cập nhật ${((repo.now() - state.updatedAt).coerceAtLeast(0) / 1000)} giây trước"
        }
        fun value(number: Double?, unit: String, digits: Int = 1) =
            if (number == null) "— $unit" else String.format(Locale.getDefault(), "%.${digits}f %s", number, unit)
        temperature.text = value(state.temperature, "°C"); humidity.text = value(state.humidity, "%")
        // Firebase flowLpm is in L/min: convert litres to mL and minutes to seconds.
        flow.text = value(state.flow?.let { it * 1000.0 / 60.0 }, "mL/giây", 3)
        total.text = value(state.total, "mL", 0)
        rain.text = when (state.level) {
            RainLevel.HEAVY -> "●  MƯA TO"
            RainLevel.LIGHT -> "●  MƯA NHỎ"
            RainLevel.NO_DATA -> "●  KHÔNG CÓ DỮ LIỆU"
        }
        rain.setTextColor(when (state.level) {
            RainLevel.HEAVY -> red
            RainLevel.LIGHT -> amber
            RainLevel.NO_DATA -> muted
        })
        rain.alpha = if (fresh && state.connected) 1f else 0.5f
        val heavyRainMlPerSecond = String.format(Locale.getDefault(), "%.3f", HEAVY_RAIN_LPM * 1000.0 / 60.0)
        rainHint.text = when (state.level) {
            RainLevel.HEAVY -> "Lưu lượng ≥ $heavyRainMlPerSecond mL/giây"
            RainLevel.LIGHT -> "Lưu lượng > 0 và < $heavyRainMlPerSecond mL/giây"
            RainLevel.NO_DATA -> "Lưu lượng nước bằng 0"
        }
        servo.text = when (state.angle) { 90 -> "ESP đã đặt servo: Thu vào"; 0 -> "ESP đã đặt servo: Đưa ra được"; else -> "Chưa có trạng thái servo" }
        rendering = true; mode.isChecked = state.mode == "AUTO"
        mode.text = when (state.mode) { "AUTO" -> "TỰ ĐỘNG"; "MANUAL" -> "THỦ CÔNG"; else -> "CHƯA XÁC ĐỊNH CHẾ ĐỘ" }
        rendering = false
        if (busy && pendingId != null && state.appliedId == pendingId) {
            busy = false; pendingId = null; feedback.text = pendingSuccess
        }
        val ready = state.connected && fresh && state.error == null && !busy && state.mode in listOf("AUTO", "MANUAL")
        if (!busy) {
            feedback.text = when {
                state.error != null -> "Điều khiển đang khóa: ${state.error}"
                !state.connected -> "Điều khiển đang khóa: điện thoại chưa kết nối Firebase."
                !fresh -> "Điều khiển đang khóa: chờ ESP8266 gửi dữ liệu mới (tối đa 20 giây)."
                state.mode !in listOf("AUTO", "MANUAL") -> "Điều khiển đang khóa: chưa nhận chế độ từ ESP8266."
                state.notificationError != null -> "${state.notificationError} Điều khiển vẫn sẵn sàng."
                state.mode == "AUTO" -> "Đang TỰ ĐỘNG. Gạt công tắc sang THỦ CÔNG để bật hai nút servo."
                else -> "Đang THỦ CÔNG. Chọn Thu đồ vào hoặc Đưa đồ ra."
            }
        }
        mode.isEnabled = ready; retract.isEnabled = ready && state.mode == "MANUAL"; extend.isEnabled = retract.isEnabled
        resetTotal.isEnabled = ready
        retract.alpha = if (retract.isEnabled) 1f else 0.4f; extend.alpha = retract.alpha
        resetTotal.alpha = if (resetTotal.isEnabled) 1f else 0.4f
    }
}
