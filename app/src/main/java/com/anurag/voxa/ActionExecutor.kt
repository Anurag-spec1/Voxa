package com.anurag.voxa

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat.startActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ActionExecutor {

    private const val TAG = "ActionExecutor"
    private var executionCount = 0
    private val MAX_ACTIONS = 25
    private val scope = CoroutineScope(Dispatchers.IO)

    data class ExecutionResult(
        val success: Boolean,
        val message: String,
        val requiresFallback: Boolean = false
    )

    fun execute(actions: List<GeminiPlanner.Action>) {
        executionCount += actions.size

        // Safety check
        if (executionCount > MAX_ACTIONS) {
            Log.w(TAG, "Safety triggered: Too many actions")
            performSafetyReset()
            return
        }

        scope.launch {
            executeActionsSafely(actions)
        }
    }

    private suspend fun executeActionsSafely(actions: List<GeminiPlanner.Action>) {
        for ((index, action) in actions.withIndex()) {
            // Add delay between actions
            if (index > 0) {
                delay((index * 800).toLong())
            }

            // Execute action
            withContext(Dispatchers.Main) {
                executeSingleAction(action)
            }
        }

        // Reset counter after execution
        withContext(Dispatchers.Main) {
            Handler(Looper.getMainLooper()).postDelayed({
                executionCount = 0
            }, 10000)
        }
    }

    private fun executeSingleAction(action: GeminiPlanner.Action) {
        Log.d(TAG, "Executing: ${action.type} - ${action.target}")

        when (action.type) {
            "open_app" -> openApp(action.packageName)
            "click" -> click(action.target, action.x, action.y)
            "type" -> type(action.text)
            "send" -> send()
            "back" -> goBack()
            "home" -> goHome()
            "recents" -> showRecents()
            "scroll" -> scroll(action.target)
            "wait" -> scope.launch { delay(action.delay.toLong()) }
            "search" -> search(action.text)
            "launch_url" -> launchUrl(action.target)
            "screenshot" -> takeScreenshot()
            "volume_up" -> adjustVolume(1)
            "volume_down" -> adjustVolume(-1)
            "mute" -> adjustVolume(0)
            "brightness" -> setBrightness(action.target.toIntOrNull() ?: 50)
            else -> Log.w(TAG, "Unknown action type: ${action.type}")
        }
    }

    private fun openApp(packageName: String) {
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            service.openApp(packageName)
            MemoryEngine.storeLastApp(packageName)
        } else {
            // Fallback to intent
            val context = JarvisApplication.instance
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private fun click(target: String, x: Int = 0, y: Int = 0) {
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            if (x > 0 && y > 0) {
                service.clickAtCoordinates(x, y)
            } else if (target.isNotEmpty()) {
                service.clickByText(target)
            }
        } else {
            Log.w(TAG, "Accessibility service not available for click")
            // Try vision fallback
            scope.launch {
                VisionEngine.performClick(target, x, y)
            }
        }
    }

    private fun type(text: String) {
        JarvisAccessibilityService.instance?.typeText(text)
    }

    private fun send() {
        JarvisAccessibilityService.instance?.pressKey(KeyEvent.KEYCODE_ENTER)
    }

    private fun goBack() {
        JarvisAccessibilityService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
    }

    private fun goHome() {
        JarvisAccessibilityService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_HOME
        )
    }

    private fun showRecents() {
        JarvisAccessibilityService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_RECENTS
        )
    }

    private fun scroll(direction: String) {
        JarvisAccessibilityService.instance?.scroll(direction == "down")
    }

    private fun search(query: String) {
        // Open Google search or search in current app
        val service = JarvisAccessibilityService.instance
        service?.typeText(query)
        service?.pressKey(KeyEvent.KEYCODE_ENTER)
    }

    private fun launchUrl(url: String) {
        val context = JarvisApplication.instance
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun takeScreenshot() {
        // Requires MediaProjection API
        scope.launch {
            ScreenshotManager.capture()
        }
    }

    private fun adjustVolume(direction: Int) {
        scope.launch {
            RootManager.adjustVolume(direction)
        }
    }

    private fun setBrightness(level: Int) {
        scope.launch {
            RootManager.setBrightness(level)
        }
    }

    private fun performSafetyReset() {
        Log.w(TAG, "Performing safety reset")
        goHome()
        executionCount = 0

        // Show notification
        NotificationHelper.showSafetyNotification(
            JarvisApplication.instance,
            "Jarvis stopped: Too many actions"
        )
    }
}