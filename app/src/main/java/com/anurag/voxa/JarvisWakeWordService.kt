package com.anurag.voxa

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*

class JarvisWakeWordService : Service() {

    companion object {
        const val TAG = "JarvisWakeWordService"
        const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_WAKE_WORD_DETECTED = "com.anurag.voxa.WAKE_WORD_DETECTED"
        const val ACTION_COMMAND_RECEIVED = "com.anurag.voxa.COMMAND_RECEIVED"

        var isListening = false
            private set
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var vibrator: Vibrator
    private var isWakeWordDetected = false
    private var isSpeechRecognizerBusy = false
    private var shouldRestartListening = true
    private var isNetworkAvailable = true
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Add these for timing control
    private var lastListeningStartTime = 0L
    private val MIN_LISTENING_INTERVAL = 3000L // 3 seconds minimum between restarts

    private val WAKE_WORDS = listOf(
        "hey jarvis", "hi jarvis", "hello jarvis",
        "jarvis", "hey jar", "ok jarvis", "okay jarvis"
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis WakeWord Service created")
        createNotificationChannel()
        startForegroundServiceWithProperType()

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        initializeSpeechRecognition()
        // Don't start immediately, wait a bit
        handler.postDelayed({
            startWakeWordListening()
        }, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Jarvis WakeWord Service started")

        intent?.let {
            when (it.action) {
                "START_LISTENING" -> startListeningForCommand()
                "STOP_LISTENING" -> stopListening()
                "RESTART" -> {
                    shouldRestartListening = true
                    startWakeWordListening()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Jarvis WakeWord Service destroyed")
        shouldRestartListening = false
        stopListening()
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer: ${e.message}")
        }
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Wake Word Detection"
            val channelDescription = "Listening for wake word commands"
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = channelDescription
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithProperType() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("JARVIS ∞ - Wake Word")
            .setContentText("Say 'Hey Jarvis' to activate")
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

    private fun initializeSpeechRecognition() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isListening = true
                isSpeechRecognizerBusy = true
                lastListeningStartTime = System.currentTimeMillis()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech detected")
                updateNotification("Listening...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Optional: Visual feedback for sound level
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Not used
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isListening = false
            }

            override fun onError(error: Int) {
                isSpeechRecognizerBusy = false

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.d(TAG, "No speech match detected")
                        // Longer delay before restarting
                        handler.postDelayed({
                            safeRestartListening()
                        }, 4000) // 4 second delay
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Log.d(TAG, "Speech timeout")
                        handler.postDelayed({
                            safeRestartListening()
                        }, 4000) // 4 second delay
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        Log.e(TAG, "Speech recognizer busy")
                        // Don't try to restart immediately when busy
                        handler.postDelayed({
                            safeRestartListening()
                        }, 5000) // 5 second delay
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        Log.e(TAG, "Client error")
                        handler.postDelayed({
                            resetSpeechRecognizer()
                        }, 3000)
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        Log.w(TAG, "Network error - switching to offline mode")
                        isNetworkAvailable = false
                        handler.postDelayed({
                            safeRestartListening()
                        }, 4000)
                    }
                    else -> {
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission error"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            else -> "Unknown error: $error"
                        }
                        Log.e(TAG, "Speech recognition error: $errorMessage")

                        handler.postDelayed({
                            if (isWakeWordDetected) {
                                startListeningForCommand()
                            } else {
                                safeRestartListening()
                            }
                        }, 3000)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isSpeechRecognizerBusy = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val bestMatch = matches[0].lowercase(Locale.getDefault())
                    Log.d(TAG, "Full speech result: '$bestMatch'")

                    if (isWakeWordDetected) {
                        Log.d(TAG, "Processing command from full results: $bestMatch")
                        processCommand(bestMatch)
                        isWakeWordDetected = false
                        // Return to wake word listening after longer delay
                        handler.postDelayed({
                            startWakeWordListening()
                        }, 3000)
                    } else {
                        // Check for wake words
                        val isWakeWord = WAKE_WORDS.any { wakeWord ->
                            bestMatch.contains(wakeWord, ignoreCase = true)
                        }

                        if (isWakeWord) {
                            Log.d(TAG, "Wake word found in results: $bestMatch")
                            handleWakeWordDetected()
                        } else {
                            Log.d(TAG, "No wake word found")
                            handler.postDelayed({
                                safeRestartListening()
                            }, 3000)
                        }
                    }
                } else {
                    Log.w(TAG, "No speech results")
                    handler.postDelayed({
                        safeRestartListening()
                    }, 3000)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partial.isNullOrEmpty()) {
                    val text = partial[0].lowercase(Locale.getDefault())
                    if (text.isNotEmpty() && text != "''" && text != "' '") {
                        Log.d(TAG, "Partial result: '$text'")

                        if (isWakeWordDetected) {
                            // We're in command mode
                            if (text.contains("open") || text.contains("launch") ||
                                text.contains("start") || text.contains("go to")) {
                                Log.d(TAG, "Command detected in partial: $text")
                                // Process immediately from partial
                                processCommand(text)
                                isWakeWordDetected = false
                                stopListening()
                                handler.postDelayed({
                                    startWakeWordListening()
                                }, 3000)
                            }
                        } else {
                            // Check for wake word in partial results
                            val isWakeWord = WAKE_WORDS.any { wakeWord ->
                                text.contains(wakeWord, ignoreCase = true) ||
                                        text.contains("jarvis", ignoreCase = true)
                            }

                            if (isWakeWord) {
                                Log.d(TAG, "Wake word detected in partial: $text")
                                handleWakeWordDetected()
                            }
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Not used
            }
        })
    }

    private fun safeRestartListening() {
        if (!shouldRestartListening) return

        // Check if enough time has passed since last start
        val now = System.currentTimeMillis()
        val timeSinceLastStart = now - lastListeningStartTime

        if (timeSinceLastStart < MIN_LISTENING_INTERVAL) {
            Log.d(TAG, "Too soon to restart listening, waiting...")
            handler.postDelayed({
                safeRestartListening()
            }, MIN_LISTENING_INTERVAL - timeSinceLastStart)
            return
        }

        isSpeechRecognizerBusy = false
        handler.postDelayed({
            try {
                if (isWakeWordDetected) {
                    startListeningForCommand()
                } else {
                    startWakeWordListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting listening: ${e.message}")
                handler.postDelayed({
                    if (shouldRestartListening) {
                        startWakeWordListening()
                    }
                }, 5000)
            }
        }, 2000) // Additional 2 second delay
    }

    private fun resetSpeechRecognizer() {
        Log.d(TAG, "Resetting speech recognizer")
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer: ${e.message}")
        }

        handler.postDelayed({
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@JarvisWakeWordService)
                initializeSpeechRecognition()

                if (isWakeWordDetected) {
                    startListeningForCommand()
                } else {
                    handler.postDelayed({
                        startWakeWordListening()
                    }, 3000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recreating speech recognizer: ${e.message}")
            }
        }, 3000)
    }

    private fun startWakeWordListening() {
        if (isSpeechRecognizerBusy) {
            Log.w(TAG, "Speech recognizer busy, delaying wake word listening")
            handler.postDelayed({
                startWakeWordListening()
            }, 2000)
            return
        }

        // Check minimum interval
        val now = System.currentTimeMillis()
        val timeSinceLastStart = now - lastListeningStartTime
        if (timeSinceLastStart < MIN_LISTENING_INTERVAL) {
            Log.d(TAG, "Waiting before starting wake word listening...")
            handler.postDelayed({
                startWakeWordListening()
            }, MIN_LISTENING_INTERVAL - timeSinceLastStart)
            return
        }

        Log.d(TAG, "Starting wake word listening")
        updateNotification("Ready for 'Hey Jarvis'")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // LONGER timeouts to reduce frequent restarts
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000) // 5 seconds
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 7000) // 7 seconds

            // Try offline first to avoid network errors
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        try {
            speechRecognizer.startListening(intent)
            Log.d(TAG, "Wake word listening started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word listening: ${e.message}")
            isSpeechRecognizerBusy = false
            handler.postDelayed({
                safeRestartListening()
            }, 5000)
        }
    }

    private fun startListeningForCommand() {
        if (isSpeechRecognizerBusy) {
            Log.w(TAG, "Speech recognizer busy, delaying command listening")
            handler.postDelayed({
                startListeningForCommand()
            }, 1000)
            return
        }

        Log.d(TAG, "Starting command listening")
        updateNotification("Listening for command...")
        vibrateShort()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What can I do for you?")
            // Longer timeouts for commands
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000)

            // For commands, we can try online if available for better accuracy
            if (isNetworkAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
        }

        try {
            speechRecognizer.startListening(intent)
            Log.d(TAG, "Command listening started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start command listening: ${e.message}")
            isSpeechRecognizerBusy = false
            isWakeWordDetected = false
            handler.postDelayed({
                startWakeWordListening()
            }, 3000)
        }
    }

    private fun handleWakeWordDetected() {
        Log.d(TAG, "Wake word detected!")
        isWakeWordDetected = true
        vibrateShort()
        updateNotification("Wake word detected! Listening...")

        // Start command listening with delay
        handler.postDelayed({
            startListeningForCommand()
        }, 1000)
    }

    private fun processCommand(command: String) {
        Log.d(TAG, "Processing command: $command")
        FloatingHUD.showCommand(this, command)

        if (command.isNotEmpty()) {
            scope.launch {
                try {
                    withContext(Dispatchers.Main) {
                        FloatingHUD.showThinking(this@JarvisWakeWordService, "Processing...")
                    }

                    // First try to handle locally for common commands
                    if (handleLocalCommand(command)) {
                        withContext(Dispatchers.Main) {
                            FloatingHUD.showSuccess(this@JarvisWakeWordService, "Done!")
                        }
                        return@launch
                    }

                    // Use Gemini for complex commands
                    withContext(Dispatchers.Main) {
                        FloatingHUD.update(this@JarvisWakeWordService, "Planning actions...", FloatingHUD.STATE_THINKING)
                    }

                    val actions = GeminiPlanner.planActions(command)

                    if (actions.isNotEmpty()) {
                        Log.d(TAG, "Executing ${actions.size} actions")
                        withContext(Dispatchers.Main) {
                            FloatingHUD.showExecuting(this@JarvisWakeWordService, "Executing...")
                        }
                        ActionExecutor.execute(actions, this@JarvisWakeWordService)
                        withContext(Dispatchers.Main) {
                            FloatingHUD.showSuccess(this@JarvisWakeWordService, "Command executed!")
                        }
                    } else {
                        Log.w(TAG, "No actions generated for command")
                        withContext(Dispatchers.Main) {
                            FloatingHUD.showError(this@JarvisWakeWordService, "No actions found")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing command: ${e.message}")
                    withContext(Dispatchers.Main) {
                        FloatingHUD.showError(this@JarvisWakeWordService, "Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleLocalCommand(command: String): Boolean {
        val lowerCommand = command.lowercase(Locale.getDefault())

        return when {
            lowerCommand.contains("open") -> {
                val appName = extractAppName(lowerCommand)
                if (appName.isNotEmpty()) {
                    openApp(appName)
                    true
                } else {
                    false
                }
            }

            lowerCommand.contains("go home") || lowerCommand.contains("home") -> {
                performHomeAction()
                true
            }

            lowerCommand.contains("go back") || lowerCommand.contains("back") -> {
                performBackAction()
                true
            }

            lowerCommand.contains("recent") || lowerCommand.contains("recents") -> {
                performRecentsAction()
                true
            }

            lowerCommand.contains("hello") || lowerCommand.contains("hi") -> {
                showResponse("Hello! How can I help you?")
                true
            }

            lowerCommand.contains("thank you") || lowerCommand.contains("thanks") -> {
                showResponse("You're welcome!")
                true
            }

            else -> false
        }
    }

    private fun extractAppName(command: String): String {
        val patterns = listOf(
            "open (\\w+)" to Regex("open (\\w+)"),
            "open the (\\w+)" to Regex("open the (\\w+)"),
            "launch (\\w+)" to Regex("launch (\\w+)"),
            "start (\\w+)" to Regex("start (\\w+)")
        )

        for ((pattern, regex) in patterns) {
            val match = regex.find(command)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return command.replace("open", "")
            .replace("launch", "")
            .replace("start", "")
            .trim()
    }

    private fun openApp(appName: String) {
        Log.d(TAG, "Opening app: $appName")

        val packageName = when (appName.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "settings", "setting" -> "com.android.settings"
            "camera" -> "com.android.camera"
            "gallery", "photos" -> "com.android.gallery3d"
            "messages", "sms" -> "com.android.mms"
            "phone", "dialer" -> "com.android.dialer"
            "contacts" -> "com.android.contacts"
            "chrome", "browser" -> "com.android.chrome"
            "youtube" -> "com.google.android.youtube"
            "gmail", "email" -> "com.google.android.gm"
            "maps" -> "com.google.android.apps.maps"
            "play store", "playstore" -> "com.android.vending"
            "calculator" -> "com.android.calculator2"
            "clock" -> "com.android.deskclock"
            "calendar" -> "com.google.android.calendar"
            "files", "file manager" -> "com.android.documentsui"
            "notes", "notepad" -> "com.google.android.keep"
            "instagram", "insta" -> "com.instagram.android"
            else -> findAppPackage(appName) ?: return
        }

        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                showResponse("Opening $appName")
            } else {
                showResponse("$appName not found or cannot be opened")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening $appName: ${e.message}")
            showResponse("Error opening $appName: ${e.message}")
        }
    }

    private fun findAppPackage(appName: String): String? {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val packages = packageManager.queryIntentActivities(intent, 0)

        for (info in packages) {
            val label = info.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
            val packageName = info.activityInfo.packageName

            if (label.contains(appName) || packageName.contains(appName)) {
                return packageName
            }
        }

        return null
    }

    private fun performHomeAction() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            showResponse("Going home")
        } catch (e: Exception) {
            Log.e(TAG, "Error going home: ${e.message}")
        }
    }

    private fun performBackAction() {
        try {
            JarvisAccessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            showResponse("Going back")
        } catch (e: Exception) {
            Log.e(TAG, "Error going back: ${e.message}")
        }
    }

    private fun performRecentsAction() {
        try {
            JarvisAccessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            )
            showResponse("Showing recent apps")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recents: ${e.message}")
        }
    }

    private fun showResponse(message: String) {
        FloatingHUD.showResponse(this, message)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("JARVIS ∞")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                if (vibratorManager.defaultVibrator.hasVibrator()) {
                    if (checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
                        vibratorManager.defaultVibrator.vibrate(
                            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (vibrator.hasVibrator()) {
                    if (checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(100)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating: ${e.message}")
        }
    }

    private fun stopListening() {
        try {
            if (isListening) {
                speechRecognizer.stopListening()
                isListening = false
                isSpeechRecognizerBusy = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listening: ${e.message}")
        }
    }
}