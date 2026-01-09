package com.anurag.voxa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*


object ActionExecutor {
    private const val TAG = "ActionExecutor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun execute(actions: List<GeminiPlanner.Action>, context: Context? = null) {
        scope.launch {
            // Check if accessibility service is available
            val accessibilityService = JarvisAccessibilityService.instance
            if (accessibilityService == null) {
                Log.e(TAG, "Accessibility service not available")
                context?.let {
                    withContext(Dispatchers.Main) {
                        FloatingHUD.showError(it, "Enable accessibility service first")
                    }
                }
                return@launch
            }

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
                                Log.d(TAG, "Opening app: ${action.packageName}")
                                accessibilityService.openApp(action.packageName)
                                delay(action.delay.toLong())
                            }
                        }
                        "click" -> {
                            if (action.target.isNotEmpty()) {
                                Log.d(TAG, "Clicking: ${action.target}")
                                val success = accessibilityService.clickByText(action.target)
                                Log.d(TAG, "Click result: $success")
                                delay(action.delay.toLong())
                            } else if (action.x > 0 && action.y > 0) {
                                Log.d(TAG, "Clicking at coordinates: (${action.x}, ${action.y})")
                                accessibilityService.clickAtCoordinates(action.x, action.y)
                                delay(action.delay.toLong())
                            } else {
                                Log.e(TAG, "No valid click target provided")
                            }
                        }
                        "type" -> {
                            if (action.text.isNotEmpty()) {
                                Log.d(TAG, "Typing: ${action.text}")
                                accessibilityService.typeText(action.text)
                                delay(action.delay.toLong())
                            }
                        }
                        "send" -> {
                            Log.d(TAG, "Sending (press Enter)")
                            accessibilityService.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
                            delay(action.delay.toLong())
                        }
                        "back" -> {
                            Log.d(TAG, "Going back")
                            accessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            )
                            delay(action.delay.toLong())
                        }
                        "home" -> {
                            Log.d(TAG, "Going home")
                            accessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            )
                            delay(action.delay.toLong())
                        }
                        "recents" -> {
                            Log.d(TAG, "Showing recents")
                            accessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            )
                            delay(action.delay.toLong())
                        }
                        "scroll_up" -> {
                            Log.d(TAG, "Scrolling up")
                            accessibilityService.scroll(false)
                            delay(action.delay.toLong())
                        }
                        "scroll_down" -> {
                            Log.d(TAG, "Scrolling down")
                            accessibilityService.scroll(true)
                            delay(action.delay.toLong())
                        }
                        "wait" -> {
                            Log.d(TAG, "Waiting for ${action.delay}ms")
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

    // Add test function
    fun testActions(context: Context) {
        scope.launch {
            Log.d(TAG, "Testing basic actions...")

            val accessibilityService = JarvisAccessibilityService.instance
            if (accessibilityService == null) {
                withContext(Dispatchers.Main) {
                    FloatingHUD.showError(context, "Accessibility service not available")
                }
                return@launch
            }

            // Test 1: Go home
            withContext(Dispatchers.Main) {
                FloatingHUD.update(context, "Testing: Going Home", FloatingHUD.STATE_EXECUTING)
            }

            accessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            )
            delay(2000)

            // Test 2: Open settings
            withContext(Dispatchers.Main) {
                FloatingHUD.update(context, "Testing: Opening Settings", FloatingHUD.STATE_EXECUTING)
            }

            accessibilityService.openApp("com.android.settings")
            delay(3000)

            withContext(Dispatchers.Main) {
                FloatingHUD.showSuccess(context, "Test completed!")
            }
        }
    }
}