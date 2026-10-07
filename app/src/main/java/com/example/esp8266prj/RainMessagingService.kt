package com.example.esp8266prj

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

object RainNotifications {
    const val CHANNEL = "rain_alerts"
    fun channel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Cảnh báo mưa", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Cảnh báo khi cảm biến chuyển từ khô sang mưa"
                    enableVibration(true)
                })
        }
    }
    fun show(context: Context, title: String, body: String) {
        channel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_rain).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify("rain", 1, notification)
    }
}

object RainTokens {
    /** FCM can start this service in a fresh process before FirebaseAuth restores the session. */
    fun save(token: String) {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { write(it, token) } ?: run {
            val listener = object : FirebaseAuth.AuthStateListener {
                override fun onAuthStateChanged(firebaseAuth: FirebaseAuth) {
                    val uid = firebaseAuth.currentUser?.uid ?: return
                    firebaseAuth.removeAuthStateListener(this)
                    write(uid, token)
                }
            }
            auth.addAuthStateListener(listener)
        }
    }
    private fun write(uid: String, token: String) {
        FirebaseDatabase.getInstance().getReference("users/$uid/fcmTokens/$token").setValue(true)
    }
}

class RainMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        RainTokens.save(token)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val fallback = when (message.data["level"]) {
            "HEAVY" -> "MƯA TO"
            "LIGHT" -> "MƯA NHỎ"
            else -> "ĐANG MƯA"
        }
        RainNotifications.show(this, message.notification?.title ?: fallback,
            message.notification?.body ?: "Cảm biến phát hiện mưa. Kiểm tra trạng thái giàn phơi.")
    }
}
