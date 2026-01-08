package com.anurag.voxa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set

        // Global action helpers for external use
        fun performGlobalAction(action: Int) {
            instance?.performGlobalAction(action)
        }

        fun clickByText(text: String): Boolean {
            return instance?.clickByText(text) ?: false
        }

        fun typeText(text: String) {
            instance?.typeText(text)
        }

        fun openApp(packageName: String) {
            instance?.openApp(packageName)
        }

        fun getCurrentApp(): String? {
            return instance?.getCurrentApp()
        }
    }

    private val TAG = "JarvisAccessibility"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        configureService()
    }

    private fun configureService() {
        val serviceInfo = AccessibilityServiceInfo().apply {
            // Event types
            eventTypes = (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED)

            // Feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Timeout
            notificationTimeout = 100

            // Flags
            flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }

            // For Android 4.3+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            }

            // For Android 5.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }

            // For Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES
            }
        }

        // Set capabilities
        serviceInfo.packageNames = null // Monitor all apps

        this.serviceInfo = serviceInfo
        Log.d(TAG, "Service configured successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val packageName = event.packageName?.toString()
                    val className = event.className?.toString()

                    if (packageName != null) {
                        MemoryEngine.storeLastApp(packageName)
                        Log.d(TAG, "App changed: $packageName - $className")

                        // Update HUD with current app
                        packageName.takeIf { it != "android" && it != "com.android.systemui" }?.let {
                            handler.post {
                                FloatingHUD.update(
                                    this,
                                    "📱 $packageName",
                                    FloatingHUD.STATE_IDLE
                                )
                            }
                        }
                    }
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val text = event.text?.firstOrNull()?.toString()
                    if (!text.isNullOrEmpty()) {
                        MemoryEngine.storeLastClicked(text)
                        Log.d(TAG, "Clicked: $text")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text?.firstOrNull()?.toString()
                    if (!text.isNullOrEmpty() && text.length > 3) {
                        MemoryEngine.storeLastText(text)
                        Log.d(TAG, "Text changed: ${text.take(50)}...")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    event.text?.firstOrNull()?.toString()?.let { text ->
                        if (text.isNotBlank()) {
                            Log.d(TAG, "Focused: $text")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        instance = null
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    // Core Actions Implementation
    fun openApp(packageName: String) {
        try {
            Log.d(TAG, "Attempting to open app: $packageName")

            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Successfully opened app: $packageName")

                handler.post {
                    FloatingHUD.showSuccess(
                        this,
                        "Opened $packageName"
                    )
                }
            } else {
                Log.e(TAG, "No launch intent found for package: $packageName")

                handler.post {
                    FloatingHUD.showError(
                        this,
                        "App not found: $packageName"
                    )
                }

                // Try alternative method - open in Play Store
                try {
                    val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("market://details?id=$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(playStoreIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't open Play Store either: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app $packageName: ${e.message}")

            handler.post {
                FloatingHUD.showError(
                    this,
                    "Error: ${e.message}"
                )
            }
        }
    }

    fun clickByText(text: String): Boolean {
        return try {
            Log.d(TAG, "Looking for text to click: '$text'")

            var success = false

            rootInActiveWindow?.let { root ->
                // Method 1: Exact text match
                var nodes = root.findAccessibilityNodeInfosByText(text)
                if (!nodes.isNullOrEmpty()) {
                    nodes.firstOrNull()?.let { node ->
                        success = performClick(node)
                        if (success) {
                            Log.d(TAG, "Clicked exact match: '$text'")
                        }
                    }
                }

                if (!success) {
                    // Method 2: Contains match (case insensitive)
                    val allNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodes(root, allNodes)

                    allNodes.firstOrNull { node ->
                        node.text?.toString()?.contains(text, ignoreCase = true) == true
                    }?.let { node ->
                        success = performClick(node)
                        if (success) {
                            Log.d(TAG, "Clicked contains match: '$text'")
                        }
                    }
                }

                if (!success) {
                    // Method 3: Content description
                    nodes = root.findAccessibilityNodeInfosByViewId("android:id/content")
                    if (!nodes.isNullOrEmpty()) {
                        nodes.firstOrNull { node ->
                            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
                        }?.let { node ->
                            success = performClick(node)
                            if (success) {
                                Log.d(TAG, "Clicked via content description: '$text'")
                            }
                        }
                    }
                }
            }

            handler.post {
                if (success) {
                    FloatingHUD.showSuccess(this, "Clicked: $text")
                } else {
                    Log.w(TAG, "Could not find text to click: '$text'")
                    FloatingHUD.showError(this, "Couldn't find: $text")
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking by text '$text': ${e.message}")
            false
        }
    }

    fun clickAtCoordinates(x: Int, y: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.d(TAG, "Clicking at coordinates: ($x, $y)")

                val gesture = createClickGesture(x, y)
                dispatchGesture(gesture, null, null)

                handler.postDelayed({
                    FloatingHUD.showSuccess(this, "Clicked at ($x, $y)")
                }, 100)
            } else {
                Log.w(TAG, "Gesture API not available for Android < N")

                // Fallback for older Android versions
                handler.post {
                    val root = rootInActiveWindow
                    val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    if (node != null) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)

                        // Check if coordinates are within bounds
                        if (bounds.contains(x, y)) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Clicked via fallback method")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking at coordinates: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun createClickGesture(x: Int, y: Int): GestureDescription {
        val clickPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        return GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    clickPath,
                    0L,
                    50L // Click duration
                )
            )
            .build()
    }

    fun typeText(text: String) {
        scope.launch {
            try {
                Log.d(TAG, "Typing text: '$text'")

                // Use Main dispatcher for UI updates
                withContext(Dispatchers.Main) {
                    FloatingHUD.update(this@JarvisAccessibilityService, "⌨️ Typing...", FloatingHUD.STATE_EXECUTING)
                }

                typeTextWithDelay(text)

                withContext(Dispatchers.Main) {
                    FloatingHUD.showSuccess(this@JarvisAccessibilityService, "Typed: ${text.take(20)}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error typing text: ${e.message}")

                withContext(Dispatchers.Main) {
                    FloatingHUD.showError(this@JarvisAccessibilityService, "Typing failed")
                }
            }
        }
    }

    private suspend fun typeTextWithDelay(text: String) {
        withContext(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@withContext

            // Find focused input field
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null && focusedNode.isEditable) {
                // Clear existing text
                val clearArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

                // Launch a coroutine to type character by character
                scope.launch {
                    for ((index, char) in text.withIndex()) {
                        delay(if (index == 0) 200L else 50L) // First char delay longer

                        // Type accumulated text up to this point
                        val charArgs = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text.substring(0, index + 1)
                            )
                        }

                        withContext(Dispatchers.Main) {
                            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, charArgs)
                        }
                    }
                }
            } else {
                // Find any editable field
                val editableNode = findEditableField(root)
                if (editableNode != null) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } else {
                    // Last resort: Use keyboard simulation (if device has root)
                    Log.w(TAG, "No editable field found for typing")
                    // You could add root-based typing here
                }
            }
        }
    }

    fun pressKey(keyCode: Int) {
        try {
            Log.d(TAG, "Pressing key: $keyCode")

            val action = when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                    GLOBAL_ACTION_DPAD_CENTER
                KeyEvent.KEYCODE_BACK ->
                    GLOBAL_ACTION_BACK
                KeyEvent.KEYCODE_HOME ->
                    GLOBAL_ACTION_HOME
                KeyEvent.KEYCODE_APP_SWITCH ->
                    GLOBAL_ACTION_RECENTS
                else -> {
                    Log.w(TAG, "Unsupported key code: $keyCode")
                    -1
                }
            }

            if (action != -1) {
                val success = performGlobalAction(action)
                Log.d(TAG, "Key press ${if (success) "successful" else "failed"}")

                handler.post {
                    if (success) {
                        FloatingHUD.showSuccess(this, "Key pressed")
                    } else {
                        FloatingHUD.showError(this, "Key press failed")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pressing key: ${e.message}")
        }
    }

    fun scroll(down: Boolean) {
        try {
            Log.d(TAG, "Scrolling ${if (down) "down" else "up"}")

            rootInActiveWindow?.let { root ->
                val scrollableNode = findScrollableNode(root)
                if (scrollableNode != null) {
                    val action = if (down) {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }

                    val success = scrollableNode.performAction(action)
                    Log.d(TAG, "Scroll ${if (success) "successful" else "failed"}")

                    handler.post {
                        if (success) {
                            FloatingHUD.showSuccess(this, "Scrolled ${if (down) "down" else "up"}")
                        } else {
                            FloatingHUD.showError(this, "Scroll failed")
                        }
                    }
                } else {
                    Log.w(TAG, "No scrollable node found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling: ${e.message}")
        }
    }

    // Helper Functions
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // Try parent or simulate click
                node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: run {
                    // Simulate click by focusing and pressing enter
                    if (node.isFocusable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        scope.launch {
                            delay(100)
                            performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click: ${e.message}")
            false
        }
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        try {
            list.add(root)
            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    collectAllNodes(child, list)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting nodes: ${e.message}")
        }
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            if (root.isScrollable) return root

            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    findScrollableNode(child)?.let { return it }
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
            if (root.isEditable || root.className?.toString()?.contains("EditText") == true) {
                return root
            }

            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    findEditableField(child)?.let { return it }
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

    // Additional helper for button text matching
    fun findButtonByText(text: String): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.let { root ->
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodes(root, allNodes)

                allNodes.firstOrNull { node ->
                    (node.text?.toString()?.equals(text, ignoreCase = true) == true ||
                            node.contentDescription?.toString()?.equals(text, ignoreCase = true) == true) &&
                            (node.className?.toString()?.contains("Button") == true ||
                                    node.isClickable)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding button: ${e.message}")
            null
        }
    }

    // Get node bounds for debugging
    fun getNodeBounds(text: String): Rect? {
        return try {
            rootInActiveWindow?.let { root ->
                val nodes = root.findAccessibilityNodeInfosByText(text)
                nodes?.firstOrNull()?.let { node ->
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    bounds
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting node bounds: ${e.message}")
            null
        }
    }
}