package com.anurag.voxa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

object ActionExecutor {
    private const val TAG = "ActionExecutor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun execute(actions: List<GeminiPlanner.Action>, context: Context? = null) {
        scope.launch {
            for ((index, action) in actions.withIndex()) {
                try {
                    Log.d(TAG, "Executing action $index: $action")

                    // Show in HUD
                    context?.let {
                        withContext(Dispatchers.Main) {
                            FloatingHUD.update(it, "⚡ ${action.type}", FloatingHUD.STATE_EXECUTING)
                        }
                    }

                    when (action.type) {
                        "open_app" -> {
                            if (action.packageName.isNotEmpty()) {
                                JarvisAccessibilityService.openApp(action.packageName)
                                delay(action.delay.toLong())
                            }
                        }
                        "click" -> {
                            if (action.target.isNotEmpty()) {
                                JarvisAccessibilityService.clickByText(action.target)
                                delay(action.delay.toLong())
                            } else if (action.x > 0 && action.y > 0) {
                                // For coordinate clicks
                                JarvisAccessibilityService.instance?.clickAtCoordinates(action.x, action.y)
                                delay(action.delay.toLong())
                            }
                        }
                        "type" -> {
                            if (action.text.isNotEmpty()) {
                                JarvisAccessibilityService.typeText(action.text)
                                delay(action.delay.toLong())
                            }
                        }
                        "send" -> {
                            JarvisAccessibilityService.instance?.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
                            delay(action.delay.toLong())
                        }
                        "back" -> {
                            JarvisAccessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            )
                            delay(action.delay.toLong())
                        }
                        "home" -> {
                            JarvisAccessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            )
                            delay(action.delay.toLong())
                        }
                        "recents" -> {
                            JarvisAccessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            )
                            delay(action.delay.toLong())
                        }
                        "wait" -> {
                            delay(action.delay.toLong())
                        }
                        else -> {
                            Log.w(TAG, "Unknown action type: ${action.type}")
                        }
                    }

                    // Small delay between actions
                    if (index < actions.size - 1) {
                        delay(500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing action $action: ${e.message}")
                    context?.let {
                        withContext(Dispatchers.Main) {
                            FloatingHUD.showError(it, "Action failed: ${action.type}")
                        }
                    }
                }
            }

            // Final success message
            context?.let {
                withContext(Dispatchers.Main) {
                    FloatingHUD.showSuccess(it, "All actions completed")
                }
            }
        }
    }
}