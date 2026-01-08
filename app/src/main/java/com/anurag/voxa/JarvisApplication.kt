package com.anurag.voxa

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer

class JarvisApplication : Application() {

    companion object {
        lateinit var instance: JarvisApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Memory Engine
        MemoryEngine.initialize(this)

        // Initialize WorkManager for scheduling
        // WorkManager is automatically initialized in modern versions
        // Just get the instance
        WorkManager.getInstance(this)

        // Start Jarvis on boot
        startJarvisService()
    }

    private fun startJarvisService() {
        try {
            // Start the main service
            val serviceIntent = android.content.Intent(this, JarvisService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Also start wake word service
            val wakeWordIntent = android.content.Intent(this, JarvisWakeWordService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(wakeWordIntent)
            } else {
                startService(wakeWordIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("JarvisApplication", "Failed to start services: ${e.message}")
        }
    }
}