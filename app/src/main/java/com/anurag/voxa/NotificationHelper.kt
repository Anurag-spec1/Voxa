package com.anurag.voxa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val HUD_CHANNEL_ID = "hud_service_channel"
    const val HUD_NOTIFICATION_ID = 1003

    fun createHUDNotification(context: Context): Notification {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, HUD_CHANNEL_ID)
            .setContentTitle("JARVIS ∞ HUD")
            .setContentText("Voice assistant overlay")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
            .setVibrate(null)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "HUD Overlay"
            val channelDescription = "Shows voice assistant overlay"
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(
                HUD_CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = channelDescription
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}