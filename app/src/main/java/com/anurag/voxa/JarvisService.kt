package com.anurag.voxa

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class JarvisService : Service() {

    companion object {
        const val TAG = "JarvisService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis Service created")

        // Start as foreground service
        startForeground(
            NotificationHelper.MAIN_NOTIFICATION_ID,
            NotificationHelper.createMainNotification(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Jarvis Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Jarvis Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}