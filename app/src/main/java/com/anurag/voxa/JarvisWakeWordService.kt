package com.anurag.voxa

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ai.picovoice.porcupine.*
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisWakeWordService : Service() {

    companion object {
        const val TAG = "JarvisWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 4096
    }

    private var isRunning = false
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private lateinit var handler: Handler
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        startForeground(NotificationHelper.WAKE_WORD_NOTIFICATION_ID,
            NotificationHelper.createWakeWordNotification(this))
        startWakeWordDetection()
    }

    private fun startWakeWordDetection() {
        scope.launch {
            try {
                // Initialize Porcupine with custom wake word
                porcupine = Porcupine.Builder()
                    .setAccessKey("YOUR_PICOVOICE_ACCESS_KEY") // Get from picovoice.ai
                    .setKeywordPath("hey-jarvis.ppn") // Place in assets folder
                    .build(applicationContext)

                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    maxOf(BUFFER_SIZE, minBufferSize * 2)
                )

                audioRecord?.startRecording()
                isRunning = true

                val buffer = ShortArray(porcupine?.frameLength ?: 512)

                while (isRunning) {
                    audioRecord?.read(buffer, 0, buffer.size)
                    val keywordIndex = porcupine?.process(buffer) ?: -1

                    if (keywordIndex >= 0) {
                        Log.d(TAG, "Wake word detected!")
                        handler.post {
                            onWakeWordDetected()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Wake word error: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun onWakeWordDetected() {
        // Stop listening temporarily
        isRunning = false

        // Start speech recognition
        SpeechRecognitionManager.startListening(this)

        // Show HUD
        FloatingHUD.show(this, "Listening...", FloatingHUD.State.LISTENING)

        // Restart wake word after 10 seconds
        handler.postDelayed({
            isRunning = true
            scope.launch { startWakeWordDetection() }
        }, 10000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        porcupine?.delete()
        audioRecord?.stop()
        audioRecord?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}