package com.anurag.voxa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

object ActionExecutor {
    private const val TAG = "ActionExecutor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun execute(actions: List<GeminiPlanner.Action>, context: Context? = null) {
        scope.launch {
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
                    Log.d(TAG, "Executing action $index/${actions.size}: ${action.type}")

                    // Update HUD
                    context?.let {
                        withContext(Dispatchers.Main) {
                            FloatingHUD.update(it,
                                when (action.type) {
                                    GeminiPlanner.ActionTypes.OPEN_APP -> "📱 Opening ${action.appName}"
                                    GeminiPlanner.ActionTypes.CLICK -> "🎯 Clicking ${action.target}"
                                    GeminiPlanner.ActionTypes.TYPE -> "📝 Typing..."
                                    GeminiPlanner.ActionTypes.CALL -> "📞 Calling..."
                                    GeminiPlanner.ActionTypes.MESSAGE -> "💬 Messaging..."
                                    GeminiPlanner.ActionTypes.SEARCH -> "🔍 Searching..."
                                    else -> "⚡ ${action.type}"
                                },
                                FloatingHUD.STATE_EXECUTING
                            )
                        }
                    }

                    when (action.type) {
                        GeminiPlanner.ActionTypes.OPEN_APP -> {
                            if (action.packageName.isNotEmpty()) {
                                Log.d(TAG, "Opening app: ${action.packageName}")
                                accessibilityService.openApp(action.packageName)
                                delay(action.delay.toLong())
                            }
                        }
                        GeminiPlanner.ActionTypes.CLICK -> {
                            if (action.target.isNotEmpty()) {
                                Log.d(TAG, "Clicking: ${action.target}")
                                val success = accessibilityService.clickByText(action.target)
                                if (!success) {
                                    Log.w(TAG, "Click by text failed, trying alternative methods")
                                    // Try clicking by content description or ID
                                    accessibilityService.clickByDescription(action.target)
                                }
                                delay(action.delay.toLong())
                            }
                        }
                        GeminiPlanner.ActionTypes.TYPE -> {
                            if (action.text.isNotEmpty()) {
                                Log.d(TAG, "Typing: ${action.text}")
                                accessibilityService.typeText(action.text)
                                delay(action.delay.toLong())
                            }
                        }
                        GeminiPlanner.ActionTypes.SEND -> {
                            Log.d(TAG, "Sending (Enter key)")
                            accessibilityService.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.CALL -> {
                            Log.d(TAG, "Calling: ${action.contactName} - ${action.phoneNumber}")
                            if (action.phoneNumber.isNotEmpty()) {
                                accessibilityService.dialNumber(action.phoneNumber)
                            } else if (action.contactName.isNotEmpty()) {
                                accessibilityService.callContact(action.contactName)
                            }
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.DIAL -> {
                            Log.d(TAG, "Dialing: ${action.phoneNumber}")
                            accessibilityService.dialNumber(action.phoneNumber)
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.BACK -> {
                            Log.d(TAG, "Going back")
                            accessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            )
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.HOME -> {
                            Log.d(TAG, "Going home")
                            accessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            )
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.RECENTS -> {
                            Log.d(TAG, "Showing recents")
                            accessibilityService.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            )
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.WAIT -> {
                            Log.d(TAG, "Waiting for ${action.delay}ms")
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.SCREENSHOT -> {
                            Log.d(TAG, "Taking screenshot")
                            accessibilityService.takeScreenshot()
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.VOLUME_UP -> {
                            Log.d(TAG, "Volume up (${action.count} times)")
                            repeat(action.count) {
                                accessibilityService.volumeUp()
                                delay(200)
                            }
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.VOLUME_DOWN -> {
                            Log.d(TAG, "Volume down (${action.count} times)")
                            repeat(action.count) {
                                accessibilityService.volumeDown()
                                delay(200)
                            }
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.PLAY_PAUSE -> {
                            Log.d(TAG, "Play/Pause media")
                            accessibilityService.mediaPlayPause()
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.NEXT -> {
                            Log.d(TAG, "Next media")
                            accessibilityService.mediaNext()
                            delay(action.delay.toLong())
                        }
                        GeminiPlanner.ActionTypes.PREVIOUS -> {
                            Log.d(TAG, "Previous media")
                            accessibilityService.mediaPrevious()
                            delay(action.delay.toLong())
                        }
                        else -> {
                            Log.w(TAG, "Unknown action type: ${action.type}")
                            delay(500)
                        }
                    }

                    // Progress indicator - FIXED: Using update method instead of updateProgress
                    val progress = ((index + 1).toFloat() / actions.size * 100).toInt()
                    context?.let {
                        withContext(Dispatchers.Main) {
                            // Use the existing update method or create updateProgress method
                            FloatingHUD.update(it, "Progress: $progress%", FloatingHUD.STATE_EXECUTING)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error executing action $action: ${e.message}")
                    context?.let {
                        withContext(Dispatchers.Main) {
                            FloatingHUD.showError(it, "Action failed: ${action.type}")
                        }
                    }
                    // Continue with next action
                }
            }

            // Final success message
            context?.let {
                withContext(Dispatchers.Main) {
                    FloatingHUD.showSuccess(it, "✅ Task completed!")
                }
            }
        }
    }

    fun stopExecution() {
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "Execution stopped")
    }

    // Enhanced test function - FIXED: Made all calls within coroutine scope
    fun testAllActions(context: Context) {
        scope.launch {
            Log.d(TAG, "Testing all action types...")

            val accessibilityService = JarvisAccessibilityService.instance
            if (accessibilityService == null) {
                withContext(Dispatchers.Main) {
                    FloatingHUD.showError(context, "Accessibility service not available")
                }
                return@launch
            }

            val testActions = listOf(
                "Home" to suspend {
                    withContext(Dispatchers.Main) {
                        FloatingHUD.update(context, "Testing: Home", FloatingHUD.STATE_EXECUTING)
                    }
                    accessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    )
                    delay(2000)
                },
                "Open Settings" to suspend {
                    withContext(Dispatchers.Main) {
                        FloatingHUD.update(context, "Testing: Open Settings", FloatingHUD.STATE_EXECUTING)
                    }
                    accessibilityService.openApp("com.android.settings")
                    delay(3000)
                },
                "Back" to suspend {
                    withContext(Dispatchers.Main) {
                        FloatingHUD.update(context, "Testing: Back", FloatingHUD.STATE_EXECUTING)
                    }
                    accessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    )
                    delay(2000)
                },
                "Recents" to suspend {
                    withContext(Dispatchers.Main) {
                        FloatingHUD.update(context, "Testing: Recents", FloatingHUD.STATE_EXECUTING)
                    }
                    accessibilityService.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    )
                    delay(2000)
                }
            )

            testActions.forEach { (name, action) ->
                try {
                    action() // This is now a suspend function call
                    Log.d(TAG, "✓ $name test passed")
                } catch (e: Exception) {
                    Log.e(TAG, "✗ $name test failed: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                FloatingHUD.showSuccess(context, "✅ All tests completed!")
            }
        }
    }
}