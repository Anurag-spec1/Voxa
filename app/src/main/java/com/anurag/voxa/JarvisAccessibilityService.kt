package com.anurag.voxa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var _instance: JarvisAccessibilityService? = null
        val instance: JarvisAccessibilityService?
            get() = _instance

        // Helper methods
        fun isServiceEnabled(context: Context): Boolean {
            val serviceName = ComponentName(context, JarvisAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(serviceName.flattenToString()) == true
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val TAG = "JarvisAccessibility"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // System services
    private lateinit var audioManager: AudioManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var packageManager: PackageManager
    private var textToSpeech: TextToSpeech? = null

    // State tracking
    private var isInitialized = false
    private var currentPackage: String? = null
    private var lastActionTime = 0L
    private val actionCooldown = 300L // ms between actions

    override fun onCreate() {
        super.onCreate()
        _instance = this
        Log.d(TAG, "Accessibility service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        Log.d(TAG, "Accessibility service connected")

        try {
            // Initialize system services
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            packageManager = applicationContext.packageManager

            // Initialize TTS
            textToSpeech = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.getDefault()
                    Log.d(TAG, "TTS initialized successfully")
                } else {
                    Log.e(TAG, "TTS initialization failed")
                }
            }

            configureService()
            isInitialized = true

            // Show connection success
            handler.post {
                speak("Jarvis accessibility service connected")
                FloatingHUD.showSuccess(
                    applicationContext,
                    "✅ Service Connected"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed: ${e.message}", e)
            speak("Service initialization failed")
        }
    }

    private fun configureService() {
        val serviceInfo = AccessibilityServiceInfo().apply {
            // Event types
            eventTypes = (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)

            // Feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK

            // Timeout
            notificationTimeout = 100

            // Flags
            flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
            }
        }

        serviceInfo.packageNames = null // Monitor all apps

        this.serviceInfo = serviceInfo
        Log.d(TAG, "Service configured successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isInitialized) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(event)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Track UI changes
                    val packageName = event.packageName?.toString()
                    if (packageName != null) {
                        MemoryEngine.storeLastApp(packageName)
                    }
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleViewClicked(event)
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleTextChanged(event)
                }

                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    Log.d(TAG, "Notification: ${event.text?.joinToString()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event: ${e.message}", e)
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()

        if (packageName != null && packageName != currentPackage) {
            currentPackage = packageName
            MemoryEngine.storeLastApp(packageName)
            Log.d(TAG, "App changed: $packageName - $className")

            // Update HUD with current app
            if (packageName != "android" && packageName != "com.android.systemui") {
                val appName = getAppName(packageName)
                handler.post {
                    FloatingHUD.update(
                        applicationContext,
                        "📱 $appName",
                        FloatingHUD.STATE_IDLE
                    )
                }
            }
        }
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        val text = event.text?.firstOrNull()?.toString()
        if (!text.isNullOrEmpty()) {
            MemoryEngine.storeLastClicked(text)
            Log.d(TAG, "Clicked: $text")
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val text = event.text?.firstOrNull()?.toString()
        if (!text.isNullOrEmpty() && text.length > 2) {
            MemoryEngine.storeLastText(text)
            Log.d(TAG, "Text changed: ${text.take(30)}...")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        speak("Service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service destroying...")

        scope.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        _instance = null
        isInitialized = false

        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    // Enhanced Core Actions
    fun openApp(packageName: String) {
        scope.launch {
            openAppAsync(packageName)
        }
    }

    private suspend fun openAppAsync(packageName: String, retryCount: Int = 0) {
        try {
            Log.d(TAG, "Opening app: $packageName (attempt ${retryCount + 1})")

            // Check if app is installed
            val isInstalled = try {
                packageManager.getPackageInfo(packageName, 0) != null
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }

            if (!isInstalled) {
                Log.e(TAG, "App not installed: $packageName")
                handler.post {
                    FloatingHUD.showError(
                        applicationContext,
                        "App not installed: ${getAppNameFromPackage(packageName)}"
                    )
                }
                speak("Application not installed")
                return
            }

            // Check cooldown
            val now = System.currentTimeMillis()
            if (now - lastActionTime < actionCooldown) {
                delay(actionCooldown - (now - lastActionTime))
            }

            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                withContext(Dispatchers.Main) {
                    startActivity(intent)
                }

                lastActionTime = System.currentTimeMillis()
                Log.d(TAG, "Successfully opened app: $packageName")

                // Wait for app to open and verify
                delay(3000)
                if (getCurrentApp() != packageName && retryCount < 2) {
                    Log.w(TAG, "App open verification failed, retrying...")
                    openAppAsync(packageName, retryCount + 1)
                    return
                }

                val appName = getAppNameFromPackage(packageName)
                handler.post {
                    FloatingHUD.showSuccess(
                        applicationContext,
                        "✅ $appName"
                    )
                }
                speak("Opened $appName")
            } else {
                Log.e(TAG, "No launch intent for: $packageName")
                handler.post {
                    FloatingHUD.showError(
                        applicationContext,
                        "Cannot open: ${getAppNameFromPackage(packageName)}"
                    )
                }
                speak("Cannot open application")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app $packageName: ${e.message}", e)
            handler.post {
                FloatingHUD.showError(
                    applicationContext,
                    "Error: ${e.message?.take(30)}"
                )
            }
        }
    }

    fun clickByText(text: String, exactMatch: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "Clicking by text: '$text' (exact: $exactMatch)")

            var success = false
            var clickedNode: AccessibilityNodeInfo? = null

            rootInActiveWindow?.let { root ->
                // Method 1: Accessibility API search
                var nodes = root.findAccessibilityNodeInfosByText(text)
                if (!nodes.isNullOrEmpty()) {
                    clickedNode = nodes.find { node ->
                        node.text?.toString()?.equals(text, exactMatch) == true ||
                                node.contentDescription?.toString()?.equals(text, exactMatch) == true
                    }
                }

                // Method 2: Manual traversal for partial matches
                if (clickedNode == null && !exactMatch) {
                    val allNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectAllClickableNodes(root, allNodes)

                    clickedNode = allNodes.firstOrNull { node ->
                        node.text?.toString()?.contains(text, true) == true ||
                                node.contentDescription?.toString()?.contains(text, true) == true
                    }
                }

                // Method 3: Find by ID (for common UI elements)
                if (clickedNode == null) {
                    val commonIds = listOf(
                        "search", "btn", "button", "send", "submit", "ok", "next",
                        "continue", "done", "save", "confirm", "agree"
                    )

                    commonIds.forEach { id ->
                        if (text.contains(id, true)) {
                            nodes = root.findAccessibilityNodeInfosByViewId(".*$id.*".toString())
                            clickedNode = nodes?.firstOrNull()
                        }
                    }
                }

                // Perform click
                clickedNode?.let { node ->
                    success = performClick(node)
                    if (success) {
                        Log.d(TAG, "Successfully clicked: '$text'")
                        MemoryEngine.storeLastClicked(text)

                        handler.post {
                            FloatingHUD.showSuccess(
                                applicationContext,
                                "✅ Clicked: ${text.take(20)}..."
                            )
                        }
                    }
                }

                // Clean up
                clickedNode?.recycle()
                root.recycle()
            }

            if (!success) {
                Log.w(TAG, "Failed to click by text: '$text'")
                handler.post {
                    FloatingHUD.showError(
                        applicationContext,
                        "Couldn't find: ${text.take(20)}..."
                    )
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking by text '$text': ${e.message}", e)
            false
        }
    }

    fun clickByDescription(description: String): Boolean {
        return try {
            Log.d(TAG, "Clicking by description: '$description'")

            var success = false
            rootInActiveWindow?.let { root ->
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodes(root, allNodes)

                allNodes.firstOrNull { node ->
                    node.contentDescription?.toString()?.contains(description, true) == true &&
                            node.isClickable
                }?.let { node ->
                    success = performClick(node)
                    if (success) {
                        Log.d(TAG, "Clicked via description: '$description'")
                    }
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking by description: ${e.message}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun clickAtCoordinates(x: Int, y: Int, duration: Long = 50L): Boolean {
        return try {
            Log.d(TAG, "Clicking at coordinates: ($x, $y)")

            val gesture = createClickGesture(x, y, duration)
            val latch = CountDownLatch(1)
            var success = false

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    success = true
                    Log.d(TAG, "Gesture click completed")
                    latch.countDown()
                    handler.post {
                        FloatingHUD.showSuccess(
                            applicationContext,
                            "✅ Clicked at ($x, $y)"
                        )
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Gesture click cancelled")
                    latch.countDown()
                    handler.post {
                        FloatingHUD.showError(
                            applicationContext,
                            "Click cancelled"
                        )
                    }
                }
            }, null)

            // Wait for gesture to complete (max 5 seconds)
            latch.await(5, TimeUnit.SECONDS)
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking at coordinates: ${e.message}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun createClickGesture(x: Int, y: Int, duration: Long): GestureDescription {
        val clickPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        return GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    clickPath,
                    0L,
                    duration
                )
            )
            .build()
    }

    fun typeText(text: String, clearExisting: Boolean = true): Boolean {
        return runBlocking {
            typeTextAsync(text, clearExisting)
        }
    }

    private suspend fun typeTextAsync(text: String, clearExisting: Boolean = true): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Typing text: '$text' (clear: $clearExisting)")

                // Check cooldown
                val now = System.currentTimeMillis()
                if (now - lastActionTime < actionCooldown) {
                    delay(actionCooldown - (now - lastActionTime))
                }

                handler.post {
                    FloatingHUD.update(
                        applicationContext,
                        "⌨️ Typing...",
                        FloatingHUD.STATE_EXECUTING
                    )
                }

                // Find focused input field
                val root = rootInActiveWindow ?: run {
                    Log.w(TAG, "No active window for typing")
                    return@withContext false
                }

                val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findEditableField(root)

                if (focusedNode == null) {
                    Log.w(TAG, "No editable field found for typing")
                    handler.post {
                        FloatingHUD.showError(
                            applicationContext,
                            "No text field found"
                        )
                    }
                    return@withContext false
                }

                if (!focusedNode.isEditable) {
                    Log.w(TAG, "Field is not editable")
                    handler.post {
                        FloatingHUD.showError(
                            applicationContext,
                            "Field not editable"
                        )
                    }
                    return@withContext false
                }

                // Clear existing text if needed
                if (clearExisting) {
                    val clearArgs = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                    delay(300)
                }

                // Type the text (all at once for efficiency)
                val typeArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, typeArgs)

                if (success) {
                    lastActionTime = System.currentTimeMillis()
                    Log.d(TAG, "Successfully typed text")
                    MemoryEngine.storeLastText(text)

                    handler.post {
                        FloatingHUD.showSuccess(
                            applicationContext,
                            "✅ Typed: ${text.take(20)}..."
                        )
                    }
                } else {
                    Log.w(TAG, "Failed to type text")
                    handler.post {
                        FloatingHUD.showError(
                            applicationContext,
                            "Typing failed"
                        )
                    }
                }

                focusedNode.recycle()
                root.recycle()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error typing text: ${e.message}", e)
                handler.post {
                    FloatingHUD.showError(
                        applicationContext,
                        "Typing error"
                    )
                }
                false
            }
        }
    }

    fun sendEnterKey(): Boolean {
        return performKeyEvent(KeyEvent.KEYCODE_ENTER)
    }

    fun pressKey(keyCode: Int): Boolean {
        return performKeyEvent(keyCode)
    }

    private fun performKeyEvent(keyCode: Int): Boolean {
        return try {
            Log.d(TAG, "Pressing key: $keyCode")

            val success = when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
                }
                KeyEvent.KEYCODE_BACK -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                KeyEvent.KEYCODE_HOME -> {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                KeyEvent.KEYCODE_APP_SWITCH -> {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // Try media key dispatch first
                    try {
                        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        audioManager.dispatchMediaKeyEvent(downEvent)
                        audioManager.dispatchMediaKeyEvent(upEvent)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Media key dispatch failed, using fallback")
                        false
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported key code: $keyCode")
                    false
                }
            }

            Log.d(TAG, "Key press ${if (success) "successful" else "failed"}")

            if (success) {
                lastActionTime = System.currentTimeMillis()
                handler.post {
                    FloatingHUD.showSuccess(
                        applicationContext,
                        "✅ Key pressed"
                    )
                }
            } else {
                handler.post {
                    FloatingHUD.showError(
                        applicationContext,
                        "Key press failed"
                    )
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing key: ${e.message}", e)
            false
        }
    }

    fun scroll(direction: String = "down", amount: Int = 1): Boolean {
        val result = AtomicBoolean(false)

        runBlocking {
            result.set(scrollAsync(direction, amount))
        }

        return result.get()
    }

    private suspend fun scrollAsync(direction: String = "down", amount: Int = 1): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Scrolling $direction ($amount times)")

                var success = false
                rootInActiveWindow?.let { root ->
                    val scrollableNode = findScrollableNode(root)
                    if (scrollableNode != null) {
                        val action = when (direction.lowercase()) {
                            "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }

                        repeat(amount) {
                            success = scrollableNode.performAction(action)
                            if (success) {
                                delay(200)
                            }
                        }

                        Log.d(TAG, "Scroll ${if (success) "successful" else "failed"}")
                        handler.post {
                            if (success) {
                                FloatingHUD.showSuccess(
                                    applicationContext,
                                    "✅ Scrolled $direction"
                                )
                            } else {
                                FloatingHUD.showError(
                                    applicationContext,
                                    "Scroll failed"
                                )
                            }
                        }
                    } else {
                        Log.w(TAG, "No scrollable node found")
                        handler.post {
                            FloatingHUD.showError(
                                applicationContext,
                                "Nothing to scroll"
                            )
                        }
                    }
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error scrolling: ${e.message}", e)
                false
            }
        }
    }

    fun takeScreenshot(): Boolean {
        return try {
            Log.d(TAG, "Taking screenshot")

            val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

            if (success) {
                handler.post {
                    FloatingHUD.showSuccess(
                        applicationContext,
                        "✅ Screenshot taken"
                    )
                }
                speak("Screenshot taken")
            } else {
                handler.post {
                    FloatingHUD.showError(
                        applicationContext,
                        "Screenshot failed"
                    )
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            false
        }
    }

    fun volumeUp(steps: Int = 1): Boolean {
        return try {
            Log.d(TAG, "Increasing volume ($steps steps)")

            repeat(steps) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                Thread.sleep(100)
            }

            handler.post {
                FloatingHUD.showSuccess(
                    applicationContext,
                    "✅ Volume increased"
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error increasing volume: ${e.message}")
            false
        }
    }

    fun volumeDown(steps: Int = 1): Boolean {
        return try {
            Log.d(TAG, "Decreasing volume ($steps steps)")

            repeat(steps) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                Thread.sleep(100)
            }

            handler.post {
                FloatingHUD.showSuccess(
                    applicationContext,
                    "✅ Volume decreased"
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error decreasing volume: ${e.message}")
            false
        }
    }

    fun mediaPlayPause(): Boolean {
        return try {
            Log.d(TAG, "Play/Pause media")

            // Try to send media key via AudioManager
            val success = try {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Media key dispatch failed: ${e.message}")
                false
            }

            if (success) {
                handler.post {
                    FloatingHUD.showSuccess(
                        applicationContext,
                        "✅ Media play/pause"
                    )
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error with media play/pause: ${e.message}")
            false
        }
    }

    fun mediaNext(): Boolean {
        return try {
            Log.d(TAG, "Next media")

            // Try to send media key via AudioManager
            val success = try {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Media key dispatch failed: ${e.message}")
                false
            }

            if (success) {
                handler.post {
                    FloatingHUD.showSuccess(
                        applicationContext,
                        "✅ Next track"
                    )
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error with media next: ${e.message}")
            false
        }
    }

    fun mediaPrevious(): Boolean {
        return try {
            Log.d(TAG, "Previous media")

            // Try to send media key via AudioManager
            val success = try {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Media key dispatch failed: ${e.message}")
                false
            }

            if (success) {
                handler.post {
                    FloatingHUD.showSuccess(
                        applicationContext,
                        "✅ Previous track"
                    )
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error with media previous: ${e.message}")
            false
        }
    }

    fun dialNumber(phoneNumber: String): Boolean {
        return try {
            Log.d(TAG, "Dialing number: $phoneNumber")

            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)

            handler.post {
                FloatingHUD.showSuccess(
                    applicationContext,
                    "✅ Dialing..."
                )
            }
            speak("Dialing number")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error dialing number: ${e.message}")
            false
        }
    }

    fun callContact(contactName: String): Boolean {
        val result = AtomicBoolean(false)

        runBlocking {
            result.set(callContactAsync(contactName))
        }

        return result.get()
    }

    private suspend fun callContactAsync(contactName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Calling contact: $contactName")

                val intent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                withContext(Dispatchers.Main) {
                    startActivity(intent)
                }

                // Wait for dialer to open
                delay(2000)

                // Type the contact name in dialer
                typeTextAsync(contactName, clearExisting = false)
                delay(1000)

                // Try to click on the contact
                clickByText(contactName)

                true
            } catch (e: Exception) {
                Log.e(TAG, "Error calling contact: ${e.message}")
                false
            }
        }
    }

    // Helper Functions
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            var success = false

            if (node.isClickable) {
                success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            if (!success && node.isFocusable) {
                // Try to focus and press enter
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100)
                success = performKeyEvent(KeyEvent.KEYCODE_ENTER)
            }

            if (!success) {
                // Try parent node
                node.parent?.let { parent ->
                    success = performClick(parent)
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click: ${e.message}")
            false
        } finally {
            node.recycle()
        }
    }

    private fun collectAllClickableNodes(root: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        try {
            if (root.isClickable || root.isFocusable) {
                list.add(AccessibilityNodeInfo.obtain(root))
            }

            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    collectAllClickableNodes(child, list)
                }
            }
        } catch (e: Exception) {
            // Continue with other nodes
        }
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        try {
            list.add(AccessibilityNodeInfo.obtain(root))

            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    collectAllNodes(child, list)
                }
            }
        } catch (e: Exception) {
            // Continue with other nodes
        }
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            if (root.isScrollable) return AccessibilityNodeInfo.obtain(root)

            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    findScrollableNode(child)?.let { found ->
                        return found
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding scrollable node: ${e.message}")
            null
        }
    }

    private fun findEditableField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            if (root.isEditable ||
                root.className?.toString()?.contains("EditText", true) == true ||
                root.className?.toString()?.contains("TextField", true) == true) {
                return AccessibilityNodeInfo.obtain(root)
            }

            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    findEditableField(child)?.let { found ->
                        return found
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding editable field: ${e.message}")
            null
        }
    }

    fun getCurrentApp(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current app: ${e.message}")
            null
        }
    }

    fun getScreenText(): List<String> {
        return try {
            val texts = mutableListOf<String>()
            rootInActiveWindow?.let { root ->
                collectTexts(root, texts)
                root.recycle()
            }
            texts
        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen text: ${e.message}")
            emptyList()
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        try {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    collectTexts(child, list)
                }
            }
        } catch (e: Exception) {
            // Continue with other nodes
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // Extract name from package name
            packageName.substringAfterLast(".").replace("[^a-zA-Z]".toRegex(), " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        }
    }

    private fun speak(text: String) {
        try {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_tts")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking: ${e.message}")
        }
    }

    // Utility function to check if service is ready
    fun isReady(): Boolean {
        return isInitialized && _instance != null
    }

    // Force stop all actions
    fun stopAllActions() {
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "All actions stopped")
    }

    // Get battery level
    fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            (level * 100 / scale.toFloat()).toInt()
        } catch (e: Exception) {
            -1
        }
    }
}