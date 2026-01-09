package com.anurag.voxa

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class JarvisWakeWordService : Service() {

    companion object {
        const val TAG = "JarvisWakeWordService"
        const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel"
        const val NOTIFICATION_ID = 1002
        const val DEBUG_NOTIFICATION_ID = 1003
        const val SPEECH_DEBUG_TAG = "SPEECH_DEBUG"

        var isListening = false
            private set

        // Test function to send a command directly
        fun testCommand(context: Context, command: String) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                putExtra("test_command", command)
            }
            context.startService(intent)
        }
    }

    private lateinit var speechRecognizer: SpeechRecognizer

    private var commandListeningStarted = false
    private lateinit var vibrator: Vibrator
    private var isWakeWordDetected = false

    private var commandExpected = false
    private var commandTimeout = 0L

    private var cachedPartialText = ""
    private var partialTextTimestamp = 0L


    private var shouldRestartListening = true
    private val handler = Handler(Looper.getMainLooper())

    private var isRestarting = false
    private var restartPending = false
    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Debug variables
    private var debugOverlay: SpeechDebugOverlay? = null
    private var lastRecognizedText = ""
    private var lastConfidence = 0f
    private var lastAction = ""
    private var isDebugEnabled = true
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Jarvis WakeWord Service created")
        createNotificationChannel()
        startForegroundServiceWithProperType()

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        initializeSpeechRecognition()

        // Initialize debug overlay if permission is granted
        if (isDebugEnabled && Settings.canDrawOverlays(this)) {
            try {
                debugOverlay = SpeechDebugOverlay(this)
                Log.d(TAG, "Debug overlay initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize debug overlay: ${e.message}")
            }
        } else {
            Log.w(TAG, "Overlay permission not granted or debug disabled")
        }

        // Start listening immediately
        handler.post {
            startListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Jarvis WakeWord Service started command")

        // Handle test commands
        intent?.let {
            if (it.hasExtra("test_command")) {
                val command = it.getStringExtra("test_command")
                if (!command.isNullOrEmpty()) {
                    Log.d(TAG, "Test command received: $command")
                    processCommand(command)
                }
            }

            // Handle debug toggle
            if (it.hasExtra("toggle_debug")) {
                isDebugEnabled = it.getBooleanExtra("toggle_debug", true)
                updateNotification("Debug: ${if (isDebugEnabled) "ON" else "OFF"}")
                Log.d(TAG, "Debug toggled: $isDebugEnabled")
            }

            // Handle manual listening restart
            if (it.hasExtra("restart_listening")) {
                Log.d(TAG, "Manual listening restart requested")
                handler.post {
                    startListening()
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
        debugOverlay?.destroy()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Main service channel
            val channelName = "JARVIS Voice Control"
            val channelDescription = "Voice-controlled automation assistant"
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

            // Debug notification channel
            val debugChannelName = "JARVIS Debug Info"
            val debugChannel = NotificationChannel(
                "debug_channel",
                debugChannelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Speech recognition debug information"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(debugChannel)
        }
    }

    private fun startForegroundServiceWithProperType() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("JARVIS ∞ Voice Assistant")
            .setContentText("Say 'Jarvis' followed by command")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
            .setVibrate(null)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Debug",
                getPendingIntentForAction("toggle_debug")
            )
            .addAction(
                R.drawable.ic_launcher_background,
                "Restart",
                getPendingIntentForAction("restart_listening")
            )
            .build()
    }

    private fun getPendingIntentForAction(action: String): PendingIntent {
        val intent = Intent(this, JarvisWakeWordService::class.java).apply {
            putExtra(action, true)
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun initializeSpeechRecognition() {
        try {
            // Check if speech recognition is available
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.e(TAG, "Speech recognition is not available on this device")
                showDebugNotification("Speech recognition not available")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "✓ Ready for speech")
                    isListening = true
                    updateNotification("Listening...")
                    debugOverlay?.update("Listening...", "Waiting for wake word")
                    showDebugNotification("Status: Ready for speech")

                    synchronized(lock) {
                        isRestarting = false
                        restartPending = false
                    }
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "✓ Speech started")
                    debugOverlay?.update("Speech started", "Processing...")
                    showDebugNotification("Status: Speech started")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Log only when significant sound detected
                    if (rmsdB > 5.0) {
                        Log.v(TAG, "Sound level: ${String.format("%.1f", rmsdB)} dB")
                        debugOverlay?.update("Sound: ${String.format("%.1f", rmsdB)} dB", "Listening...")
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Not used
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "✓ Speech ended")
                    isListening = false
                    updateNotification("Processing...")
                    debugOverlay?.update("Speech ended", "Processing results...")
                    showDebugNotification("Status: Speech ended")
                }

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                        else -> "ERROR_UNKNOWN ($error)"
                    }

                    Log.w(TAG, "✗ Speech error: $errorMessage")
                    isListening = false

                    // Log debug info
                    logSpeechDebug("[ERROR: $errorMessage]", 0f, "Speech error - restarting")

                    synchronized(lock) {
                        isRestarting = false
                    }

                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            Log.d(TAG, "Recognizer busy - waiting 3 seconds")
                            handler.postDelayed({
                                if (shouldRestartListening) {
                                    startListening()
                                }
                            }, 3000) // Longer wait for busy error
                        }
                        SpeechRecognizer.ERROR_NO_MATCH -> {
                            Log.d(TAG, "No speech detected - restarting")
                            handler.postDelayed({
                                startListening()
                            }, 1000)
                        }
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            Log.d(TAG, "Speech timeout - restarting")
                            handler.postDelayed({
                                startListening()
                            }, 1000)
                        }
                        else -> {
                            Log.e(TAG, "Speech error $error - restarting in 2s")
                            handler.postDelayed({
                                startListening()
                            }, 2000)
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    Log.d(TAG, "✓ Got speech results")
                    isListening = false

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                    val confidence = if (scores != null && scores.isNotEmpty()) scores[0] else 0.0f

                    if (!matches.isNullOrEmpty()) {
                        val recognizedText = matches[0].trim()
                        lastRecognizedText = recognizedText
                        lastConfidence = confidence

                        Log.d(TAG, "Recognized text: '$recognizedText' (${(confidence * 100).roundToInt()}%)")

                        // LOG THE TEXT IMMEDIATELY
                        logSpeechDebug(recognizedText, confidence, "Checking for wake word...")

                        // Check for wake word
                        val lowerText = recognizedText.lowercase(Locale.getDefault())
                        if (lowerText.contains("jarvis")) {
                            Log.d(TAG, "✓✓✓ WAKE WORD DETECTED! ✓✓✓")
                            logSpeechDebug(recognizedText, confidence, "WAKE WORD DETECTED!")
                            handleWakeWordDetected(recognizedText)
                        } else {
                            Log.d(TAG, "No wake word found in: '$lowerText'")
                            logSpeechDebug(recognizedText, confidence, "No wake word - continuing")
                            // Wait longer before restarting to prevent rapid cycles
                            handler.postDelayed({
                                startListening()
                            }, 1500)
                        }
                    } else {
                        Log.w(TAG, "Empty recognition results")
                        logSpeechDebug("[EMPTY]", 0f, "Empty results - restarting")

                        // CRITICAL FIX: Check partial results before restarting
                        handler.postDelayed({
                            Log.d(TAG, "Checking partial results cache...")
                            // Try to get any cached text from partial results
                            val cachedText = getCachedPartialText()
                            if (cachedText.isNotEmpty()) {
                                Log.d(TAG, "Using cached partial text: '$cachedText'")
                                val lowerCached = cachedText.lowercase(Locale.getDefault())
                                if (lowerCached.contains("jarvis")) {
                                    handleWakeWordDetected(cachedText)
                                } else {
                                    startListening()
                                }
                            } else {
                                // Wait a bit longer before restarting
                                handler.postDelayed({
                                    startListening()
                                }, 1000)
                            }
                        }, 1000)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Show partial results for debugging
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partial.isNullOrEmpty()) {
                        val partialText = partial[0]
                        if (partialText.isNotBlank() && partialText.length > 2) {
                            Log.d(TAG, "Partial: '$partialText'")
                            debugOverlay?.update("Partial: $partialText", "Listening...")

                            // Store partial text for fallback
                            storePartialText(partialText)

                            // If we're expecting a command and get significant text, process it
                            if (commandExpected && partialText.length > 5 && !partialText.lowercase().contains("jarvis")) {
                                Log.d(TAG, "Command detected via partial: '$partialText'")
                                handler.post {
                                    processCommand(partialText)
                                    commandExpected = false
                                    isWakeWordDetected = false
                                    handler.postDelayed({
                                        startListening()
                                    }, 1000)
                                }
                            }

                            // IMPORTANT: If we get a significant partial result and speech has ended,
                            // process it immediately instead of waiting for final results
                            if (!isListening && partialText.length > 3 && !commandExpected) {
                                Log.d(TAG, "Processing partial result as final: '$partialText'")
                                handler.post {
                                    // Simulate onResults with partial text
                                    val fakeBundle = Bundle().apply {
                                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partialText))
                                        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.8f))
                                    }
                                    onResults(fakeBundle)
                                }
                            }
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Not used
                }
            })

            Log.d(TAG, "Speech recognizer initialized successfully")
            showDebugNotification("Speech recognizer ready")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognizer: ${e.message}")
            showDebugNotification("Error: ${e.message}")
        }
    }

    // Store partial text for fallback when final results are empty
    private fun storePartialText(text: String) {
        cachedPartialText = text
        partialTextTimestamp = System.currentTimeMillis()
    }

    private fun safeStopListening() {
        try {
            if (isListening) {
                speechRecognizer.stopListening()
                isListening = false
                Log.d(TAG, "Listening stopped safely")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listening: ${e.message}")
        }
    }


    private fun getCachedPartialText(): String {
        // Only return if cached within last 3 seconds
        return if (System.currentTimeMillis() - partialTextTimestamp < 3000) {
            cachedPartialText
        } else {
            ""
        }
    }

    private fun startListening() {
        synchronized(lock) {
            if (!shouldRestartListening || isRestarting) {
                Log.d(TAG, "Already restarting or shutting down")
                if (isRestarting) {
                    restartPending = true
                }
                return
            }

            if (isListening) {
                Log.d(TAG, "Already listening, stopping first...")
                safeStopListening()
                // Wait a bit before starting again
                handler.postDelayed({
                    startListening()
                }, 500)
                return
            }

            isRestarting = true
        }

        Log.d(TAG, "Starting continuous listening...")
        updateNotification("Say 'Jarvis'...")

        // Show debug overlay
        debugOverlay?.show("Listening...", "Waiting for wake word")
        showDebugNotification("Listening for wake word...")

        // CRITICAL: Clear any previous partial cache
        cachedPartialText = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // CRITICAL FIX: Try LANGUAGE_MODEL_FREE_FORM with different settings
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // CRITICAL FIX: Explicitly set language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())

            // CRITICAL FIX: Reduce MAX_RESULTS to 1 for faster response
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // CRITICAL FIX: Enable partial results
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // CRITICAL FIX: Adjust timeouts significantly
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)

            // CRITICAL FIX: Remove offline preference
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            // CRITICAL FIX: Add calling package
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

            // CRITICAL FIX: Add this for better recognition
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        try {
            speechRecognizer.startListening(intent)
            Log.d(TAG, "Continuous listening started")

            synchronized(lock) {
                isRestarting = false
                if (restartPending) {
                    restartPending = false
                    handler.postDelayed({
                        startListening()
                    }, 1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            debugOverlay?.update("ERROR: ${e.message}", "Restarting...")
            showDebugNotification("Start listening failed: ${e.message}")

            synchronized(lock) {
                isRestarting = false
            }

            handler.postDelayed({
                if (shouldRestartListening) {
                    Log.d(TAG, "Retrying to start listening...")
                    startListening()
                }
            }, 2000)
        }
    }

    private fun startCommandListening() {
        Log.d(TAG, "Starting command listening...")
        updateNotification("Listening for command...")
        vibrateShort()
        debugOverlay?.update("Wake word detected", "Say command...")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // CRITICAL FIX: Use WEB_SEARCH for commands too
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What can I do?")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

            // Longer timeouts for commands
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)

            // Don't use offline mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
        }

        try {
            speechRecognizer.startListening(intent)
            Log.d(TAG, "Command listening started")
            showDebugNotification("Listening for command...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start command listening: ${e.message}")
            debugOverlay?.update("ERROR: ${e.message}", "Restarting...")
            isWakeWordDetected = false
            handler.postDelayed({
                startListening()
            }, 1000)
        }
    }

    private fun handleWakeWordDetected(fullText: String) {
        Log.d(TAG, "✓✓✓ WAKE WORD DETECTED! Processing: '$fullText'")
        logSpeechDebug(fullText, lastConfidence, "Wake word detected!")

        isWakeWordDetected = true
        vibrateShort()
        updateNotification("Wake word detected!")

        // Extract command if it's in the same phrase
        val command = extractCommandFromText(fullText)
        if (command.isNotEmpty()) {
            Log.d(TAG, "Command extracted from same phrase: '$command'")
            logSpeechDebug(fullText, lastConfidence, "Processing: $command")
            processCommand(command)
            isWakeWordDetected = false
            commandExpected = false
            handler.postDelayed({
                startListening()
            }, 1000)
        } else {
            // Wait for separate command
            Log.d(TAG, "Waiting for separate command...")
            debugOverlay?.update("Wake word detected", "Say command...")
            updateNotification("Say your command...")

            // Set a flag to indicate we're expecting a command
            commandExpected = true
            commandTimeout = System.currentTimeMillis() + 5000 // 5 second timeout

            // Monitor for partial results that contain commands
            handler.postDelayed({
                if (commandExpected) {
                    Log.d(TAG, "Command timeout - no command received")
                    commandExpected = false
                    isWakeWordDetected = false
                    startListening()
                }
            }, 5000)
        }
    }


    private fun extractCommandFromText(text: String): String {
        // Try to extract command after "jarvis"
        val jarvisIndex = text.lowercase(Locale.getDefault()).indexOf("jarvis")
        if (jarvisIndex != -1 && jarvisIndex + 6 < text.length) {
            val afterJarvis = text.substring(jarvisIndex + 6).trim()
            if (afterJarvis.isNotEmpty()) {
                return afterJarvis
            }
        }
        return ""
    }

    private fun processCommand(command: String) {
        Log.d(TAG, "Processing command: '$command'")

        // SHOW WHAT WE'RE EXECUTING - on main thread
        handler.post {
            FloatingHUD.showCommand(this@JarvisWakeWordService, command)
            lastAction = "Processing: $command"
            debugOverlay?.update("Command: $command", "Processing...")
            showDebugNotification("Processing: $command")
        }

        logSpeechDebug(command, lastConfidence, "Processing command...")

        scope.launch {
            try {
                // Try local commands first
                val localActions = handleLocalCommand(command)
                if (localActions.isNotEmpty()) {
                    Log.d(TAG, "Executing local actions: ${localActions.size}")
                    lastAction = "Executing ${localActions.size} local actions"
                    logSpeechDebug(command, lastConfidence, "Executing ${localActions.size} local actions")

                    // SHOW EACH ACTION AS IT EXECUTES - on main thread
                    localActions.forEachIndexed { index, action ->
                        logSpeechDebug(command, lastConfidence, "Action ${index + 1}: ${action.type} (${action.packageName})")
                        handler.post {
                            debugOverlay?.update("Command: $command", "Action ${index + 1}: ${action.type}")
                        }
                    }

                    ActionExecutor.execute(localActions, this@JarvisWakeWordService)
                    handler.post {
                        FloatingHUD.showSuccess(this@JarvisWakeWordService, "Done!")
                    }
                    lastAction = "Local actions completed"
                    logSpeechDebug(command, lastConfidence, "Local actions completed")
                    return@launch
                }

                // Try Gemini
                handler.post {
                    FloatingHUD.update(this@JarvisWakeWordService, "Planning with AI...", FloatingHUD.STATE_THINKING)
                }

                lastAction = "Calling Gemini AI..."
                logSpeechDebug(command, lastConfidence, "Calling Gemini AI...")
                handler.post {
                    debugOverlay?.update("Command: $command", "Calling AI...")
                }

                val actions = GeminiPlanner.planActions(command)

                if (actions.isNotEmpty()) {
                    Log.d(TAG, "Executing ${actions.size} Gemini actions")
                    lastAction = "Executing ${actions.size} Gemini actions"
                    logSpeechDebug(command, lastConfidence, "Executing ${actions.size} Gemini actions")

                    // SHOW EACH GEMINI ACTION - on main thread
                    actions.forEachIndexed { index, action ->
                        val actionDesc = when {
                            action.type == "open_app" && action.packageName.isNotEmpty() ->
                                "Open ${getAppNameFromPackage(action.packageName)}"
                            action.type == "click" && action.target.isNotEmpty() ->
                                "Click '${action.target}'"
                            action.type == "type" && action.text.isNotEmpty() ->
                                "Type '${action.text.take(20)}...'"
                            else -> action.type
                        }
                        logSpeechDebug(command, lastConfidence, "Gemini Action ${index + 1}: $actionDesc")
                    }

                    ActionExecutor.execute(actions, this@JarvisWakeWordService)
                    handler.post {
                        FloatingHUD.showSuccess(this@JarvisWakeWordService, "Command executed!")
                    }
                    lastAction = "Gemini actions completed"
                    logSpeechDebug(command, lastConfidence, "Gemini actions completed")
                } else {
                    Log.w(TAG, "No actions found for command")
                    lastAction = "No actions found"
                    logSpeechDebug(command, lastConfidence, "No actions found")
                    handler.post {
                        FloatingHUD.showError(this@JarvisWakeWordService, "Command not understood")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing command: ${e.message}")
                lastAction = "ERROR: ${e.message}"
                logSpeechDebug(command, lastConfidence, "ERROR: ${e.message}")
                handler.post {
                    FloatingHUD.showError(this@JarvisWakeWordService, "Error: ${e.message}")
                }
            } finally {
                // Restart listening after command processing
                handler.postDelayed({
                    if (shouldRestartListening) {
                        Log.d(TAG, "Restarting listening after command")
                        startListening()
                    }
                }, 1000)
            }
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return when (packageName) {
            "com.android.settings" -> "Settings"
            "com.whatsapp" -> "WhatsApp"
            "com.google.android.youtube" -> "YouTube"
            "com.android.camera2" -> "Camera"
            "com.android.chrome" -> "Chrome"
            "com.google.android.gm" -> "Gmail"
            "com.google.android.apps.maps" -> "Maps"
            "com.instagram.android" -> "Instagram"
            "com.android.dialer" -> "Phone"
            "com.google.android.apps.messaging" -> "Messages"
            else -> packageName.substringAfterLast(".")
        }
    }

    private fun handleLocalCommand(command: String): List<GeminiPlanner.Action> {
        val lowerCommand = command.lowercase(Locale.getDefault())
        Log.d(TAG, "Local command handler: '$lowerCommand'")

        return when {
            lowerCommand.contains("open") -> {
                val appName = extractAppName(lowerCommand)
                if (appName.isNotEmpty()) {
                    val packageName = getPackageName(appName)
                    if (packageName.isNotEmpty()) {
                        listOf(GeminiPlanner.Action(type = "open_app", packageName = packageName, delay = 2000))
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            lowerCommand.contains("home") -> {
                listOf(GeminiPlanner.Action(type = "home", delay = 1000))
            }

            lowerCommand.contains("back") -> {
                listOf(GeminiPlanner.Action(type = "back", delay = 1000))
            }

            lowerCommand.contains("recent") -> {
                listOf(GeminiPlanner.Action(type = "recents", delay = 1000))
            }

            else -> emptyList()
        }
    }

    private fun extractAppName(command: String): String {
        // Simple extraction: take word after "open"
        val openIndex = command.indexOf("open")
        if (openIndex != -1) {
            val afterOpen = command.substring(openIndex + 4).trim()
            return afterOpen.split(" ").firstOrNull() ?: ""
        }
        return ""
    }

    private fun getPackageName(appName: String): String {
        return when (appName.lowercase()) {
            "settings" -> "com.android.settings"
            "whatsapp" -> "com.whatsapp"
            "youtube" -> "com.google.android.youtube"
            "camera" -> "com.android.camera2"
            "chrome" -> "com.android.chrome"
            "gmail" -> "com.google.android.gm"
            "phone" -> "com.android.dialer"
            "messages" -> "com.google.android.apps.messaging"
            "contacts" -> "com.android.contacts"
            "instagram" -> "com.instagram.android"
            "facebook" -> "com.facebook.katana"
            "maps" -> "com.google.android.apps.maps"
            "photos" -> "com.google.android.apps.photos"
            else -> ""
        }
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
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Debug",
                getPendingIntentForAction("toggle_debug")
            )
            .addAction(
                R.drawable.ic_launcher_background,
                "Restart",
                getPendingIntentForAction("restart_listening")
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showDebugNotification(text: String) {
        if (!isDebugEnabled) return

        val notification = NotificationCompat.Builder(this, "debug_channel")
            .setContentTitle("🎤 Speech Debug")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSound(null)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager?.notify(DEBUG_NOTIFICATION_ID, notification)
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
                Log.d(TAG, "Listening stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listening: ${e.message}")
        }
    }

    // ================== DEBUG FUNCTIONS ==================

    private fun logSpeechDebug(recognizedText: String, confidence: Float, action: String) {
        if (!isDebugEnabled) return

        val confidencePercent = (confidence * 100).roundToInt()

        // Log to console (can be done from any thread)
        Log.d(SPEECH_DEBUG_TAG, "=== SPEECH RECOGNITION ===")
        Log.d(SPEECH_DEBUG_TAG, "Text: \"$recognizedText\"")
        Log.d(SPEECH_DEBUG_TAG, "Confidence: $confidencePercent%")
        Log.d(SPEECH_DEBUG_TAG, "Action: $action")
        Log.d(SPEECH_DEBUG_TAG, "=== END ===")

        // Update debug overlay on MAIN THREAD
        handler.post {
            val displayText = if (recognizedText.length > 30)
                "${recognizedText.take(30)}..."
            else
                recognizedText

            debugOverlay?.show(displayText, action)
        }

        // Log to file (in background)
        logToFile(recognizedText, confidence, action)
    }

    private fun logToFile(text: String, confidence: Float, action: String) {
        scope.launch(Dispatchers.IO) {  // Explicitly use IO dispatcher
            try {
                val logFile = File(getExternalFilesDir(null), "speech_log.txt")
                val writer = FileWriter(logFile, true)

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val confidencePercent = (confidence * 100).roundToInt()

                val logEntry = """
                |=== $timestamp ===
                |Text: "$text"
                |Confidence: $confidencePercent%
                |Action: $action
                |
                """.trimMargin()

                writer.append(logEntry)
                writer.flush()
                writer.close()

                Log.d(TAG, "Logged to file: ${logFile.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }
    // Debug overlay class
    class SpeechDebugOverlay(context: Context) {
        private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private lateinit var debugView: View
        private lateinit var textView: TextView
        private lateinit var actionView: TextView
        private var isVisible = false
        private val hideHandler = Handler(Looper.getMainLooper())
        private var hideRunnable: Runnable? = null

        init {
            createOverlay(context)
        }

        private fun createOverlay(context: Context) {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            debugView = inflater.inflate(R.layout.debug_overlay, null)

            textView = debugView.findViewById(R.id.debug_text)
            actionView = debugView.findViewById(R.id.debug_action)

            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.END
                x = 20
                y = 150
                alpha = 0.85f
            }

            try {
                windowManager.addView(debugView, params)
                debugView.visibility = View.GONE
                isVisible = false
                Log.d("SpeechDebugOverlay", "Overlay created successfully")
            } catch (e: Exception) {
                Log.e("SpeechDebugOverlay", "Failed to create overlay: ${e.message}")
                throw e
            }
        }

        fun show(text: String, action: String) {
            update(text, action)
            if (!isVisible) {
                debugView.visibility = View.VISIBLE
                isVisible = true
                Log.d("SpeechDebugOverlay", "Overlay shown")
            }
            scheduleAutoHide()
        }

        fun update(text: String, action: String) {
            if (!::debugView.isInitialized) return

            textView.text = "🎤 $text"
            actionView.text = "⚡ $action"
            scheduleAutoHide()
        }

        private fun scheduleAutoHide() {
            // Cancel previous hide task
            hideRunnable?.let { hideHandler.removeCallbacks(it) }

            // Schedule new hide task
            hideRunnable = Runnable {
                if (isVisible) {
                    hide()
                }
            }
            hideHandler.postDelayed(hideRunnable!!, 5000) // Hide after 5 seconds
        }

        fun hide() {
            if (isVisible && ::debugView.isInitialized) {
                debugView.visibility = View.GONE
                isVisible = false
                Log.d("SpeechDebugOverlay", "Overlay hidden")
            }
            hideRunnable?.let { hideHandler.removeCallbacks(it) }
        }

        fun destroy() {
            try {
                hide()
                hideHandler.removeCallbacksAndMessages(null)
                if (::debugView.isInitialized) {
                    windowManager.removeView(debugView)
                }
                Log.d("SpeechDebugOverlay", "Overlay destroyed")
            } catch (e: Exception) {
                Log.e("SpeechDebugOverlay", "Failed to destroy overlay: ${e.message}")
            }
        }
    }
}