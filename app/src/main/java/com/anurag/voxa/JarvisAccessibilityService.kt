package com.anurag.voxa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set
    }

    private val TAG = "JarvisAccessibility"
    private var windowManager: WindowManager? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        // Configure service - Use correct flag constants
        val serviceInfo = AccessibilityServiceInfo()

        // Convert BigInteger to Int using toInt()
        serviceInfo.eventTypes = (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED.toInt() or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.toInt() or
                AccessibilityEvent.TYPE_VIEW_CLICKED.toInt() or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED.toInt())

        serviceInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo.notificationTimeout = 100

        // Use the correct API level checks for flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            serviceInfo.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        this.serviceInfo = serviceInfo

        // Initialize window manager for overlay
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                val className = event.className?.toString()

                if (packageName != null) {
                    MemoryEngine.storeLastApp(packageName)
                    Log.d(TAG, "App changed: $packageName - $className")
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
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // Core Actions
    fun openApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "Opening app: $packageName")
        } else {
            Log.e(TAG, "Cannot open app: $packageName")
        }
    }

    fun clickByText(text: String): Boolean {
        rootInActiveWindow?.let { root ->
            val nodes = root.findAccessibilityNodeInfosByText(text)
            nodes?.firstOrNull()?.let { node ->
                return performClick(node)
            }

            // Try with contains
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, allNodes)

            allNodes.firstOrNull { node ->
                node.text?.toString()?.contains(text, ignoreCase = true) == true
            }?.let { node ->
                return performClick(node)
            }
        }
        return false
    }

    fun clickAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use correct method name and API
            val gesture = createClickGesture(x, y)
            dispatchGesture(gesture, null, null)
        } else {
            Log.w(TAG, "Gesture API not available for Android < N")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun createClickGesture(x: Int, y: Int): GestureDescription {
        val clickPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val clickStroke = GestureDescription.StrokeDescription(
            clickPath, 0, 10
        )

        return GestureDescription.Builder()
            .addStroke(clickStroke)
            .build()
    }

    fun typeText(text: String) {
        scope.launch {
            typeTextWithDelay(text)
        }
    }

    private suspend fun typeTextWithDelay(text: String) {
        // Find focused field
        withContext(Dispatchers.Main) {
            rootInActiveWindow?.let { root ->
                val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    // Clear if needed
                    val clearBundle = android.os.Bundle()
                    clearBundle.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        ""
                    )
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)

                    // Type character by character (simulate real typing)
                    scope.launch {
                        for (char in text) {
                            delay(50) // Natural typing delay
                            withContext(Dispatchers.Main) {
                                val args = android.os.Bundle()
                                // Put CharSequence instead of Char
                                args.putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    char.toString()
                                )
                                focusedNode.performAction(
                                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                                    args
                                )
                            }
                        }
                    }
                } else {
                    // Fallback: Use keyboard input
                    // There's no GLOBAL_ACTION_PASTE constant, so we'll use a different approach
                    Log.w(TAG, "No focused input field found")
                    // Try to find any editable field
                    findEditableField(root)?.let { editableNode ->
                        val args = android.os.Bundle()
                        args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                        editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
            }
        }
    }

    private fun findEditableField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root

        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findEditableField(child)?.let { return it }
            }
        }
        return null
    }

    fun pressKey(keyCode: Int) {
        performGlobalAction(
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                else -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
            }
        )
    }

    fun scroll(down: Boolean) {
        rootInActiveWindow?.let { root ->
            val scrollable = findScrollableNode(root)
            scrollable?.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        }
    }

    // Helper Functions
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(root)
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                collectAllNodes(child, list)
            }
        }
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root

        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findScrollableNode(child)?.let { return it }
            }
        }
        return null
    }

    fun getCurrentApp(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    fun getScreenText(): List<String> {
        val texts = mutableListOf<String>()
        rootInActiveWindow?.let { root ->
            collectTexts(root, texts)
        }
        return texts
    }

    private fun collectTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectTexts(child, list)
            }
        }
    }
}