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

class RainMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().getReference("users/$uid/fcmTokens/$token").setValue(true)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        RainNotifications.show(this, message.notification?.title ?: "ĐANG MƯA",
            message.notification?.body ?: "Cảm biến phát hiện mưa. Kiểm tra trạng thái giàn phơi.")
    }
}
