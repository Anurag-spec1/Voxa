package com.anurag.voxa

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingHUD : Service() {

    companion object {
        const val TAG = "FloatingHUD"

        // Make these constants public
        const val STATE_LISTENING = "LISTENING"
        const val STATE_THINKING = "THINKING"
        const val STATE_EXECUTING = "EXECUTING"
        const val STATE_SPEAKING = "SPEAKING"
        const val STATE_ERROR = "ERROR"
        const val STATE_SUCCESS = "SUCCESS"
        const val STATE_IDLE = "IDLE"

        private var isShowing = false
        private var currentState = STATE_IDLE

        fun showCommand(context: Context, command: String) {
            show(context, "🎤 $command", STATE_LISTENING)
        }

        fun showResponse(context: Context, response: String) {
            show(context, "🤖 $response", STATE_SPEAKING)
            // Auto-hide after 3 seconds
            CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                hide(context)
            }
        }

        fun showThinking(context: Context, message: String = "Thinking...") {
            show(context, "💭 $message", STATE_THINKING)
        }

        fun showExecuting(context: Context, action: String) {
            show(context, "⚡ $action", STATE_EXECUTING)
        }

        fun showError(context: Context, error: String) {
            show(context, "❌ $error", STATE_ERROR)
            // Auto-hide after 2 seconds
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                hide(context)
            }
        }

        fun showSuccess(context: Context, message: String) {
            show(context, "✅ $message", STATE_SUCCESS)
            // Auto-hide after 2 seconds
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                hide(context)
            }
        }

        private fun show(context: Context, message: String, state: String = STATE_IDLE) {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "Overlay permission not granted")
                    return
                }

                currentState = state
                val intent = Intent(context, FloatingHUD::class.java).apply {
                    putExtra("message", message)
                    putExtra("state", state)
                    putExtra("action", "show")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show HUD: ${e.message}")
            }
        }

        fun update(context: Context, message: String, state: String = STATE_IDLE) {
            try {
                currentState = state
                val intent = Intent(context, FloatingHUD::class.java).apply {
                    putExtra("message", message)
                    putExtra("state", state)
                    putExtra("action", "update")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update HUD: ${e.message}")
            }
        }

        fun hide(context: Context) {
            try {
                val intent = Intent(context, FloatingHUD::class.java)
                context.stopService(intent)
                isShowing = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide HUD: ${e.message}")
            }
        }

        fun isVisible(): Boolean = isShowing
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var textView: TextView
    private lateinit var statusIndicator: View
    private lateinit var progressBar: View

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Floating HUD created")

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createFloatingView()
            startForeground(NotificationHelper.HUD_NOTIFICATION_ID,
                NotificationHelper.createHUDNotification(this))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create HUD: ${e.message}")
            stopSelf()
        }
    }

    private fun createFloatingView() {
        // Inflate the layout
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.hud_overlay, null)

        textView = floatingView.findViewById(R.id.hud_text)
        statusIndicator = floatingView.findViewById(R.id.status_indicator)
        progressBar = floatingView.findViewById(R.id.hud_progress)

        // Setup window parameters
        val params = createWindowParams()

        // Add to window
        try {
            windowManager.addView(floatingView, params)
            floatingView.visibility = View.GONE
            isShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HUD to window: ${e.message}")
            stopSelf()
        }
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            // Set window type based on Android version
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Window flags
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            format = PixelFormat.TRANSLUCENT

            // Position and size
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START

            // Initial position (top center)
            x = 0
            y = 100 // 100 pixels from top

            // Transparency
            alpha = 0.9f


        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.getStringExtra("action")) {
                "show" -> {
                    val message = it.getStringExtra("message") ?: "JARVIS"
                    val state = it.getStringExtra("state") ?: STATE_IDLE
                    showHUD(message, state)
                }
                "update" -> {
                    val message = it.getStringExtra("message") ?: "JARVIS"
                    val state = it.getStringExtra("state") ?: STATE_IDLE
                    updateHUD(message, state)
                }
                "hide" -> hideHUD()
            }
        }
        return START_STICKY
    }

    private fun showHUD(message: String, state: String) {
        try {
            if (::floatingView.isInitialized) {
                floatingView.visibility = View.VISIBLE
                updateHUD(message, state)
                isShowing = true

                // Auto-hide after delay based on state
                val autoHideDelay = when (state) {
                    STATE_LISTENING -> 10000L // 10 seconds for listening
                    STATE_THINKING -> 15000L // 15 seconds for thinking
                    STATE_EXECUTING -> 20000L // 20 seconds for executing
                    STATE_IDLE -> 5000L // 5 seconds for idle
                    else -> null
                }

                autoHideDelay?.let { delay ->
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(delay)
                        if (isShowing && currentState == state) {
                            hideHUD()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show HUD: ${e.message}")
        }
    }

    private fun updateHUD(message: String, state: String) {
        try {
            if (::floatingView.isInitialized) {
                // Update text
                textView.text = message

                // Update indicator color
                val color = when (state) {
                    STATE_LISTENING -> Color.parseColor("#2196F3") // Blue
                    STATE_THINKING -> Color.parseColor("#FFC107") // Amber
                    STATE_EXECUTING -> Color.parseColor("#4CAF50") // Green
                    STATE_SPEAKING -> Color.parseColor("#9C27B0") // Purple
                    STATE_SUCCESS -> Color.parseColor("#4CAF50") // Green
                    STATE_ERROR -> Color.parseColor("#F44336") // Red
                    else -> Color.parseColor("#9E9E9E") // Gray
                }

                statusIndicator.setBackgroundColor(color)

                // Show/hide progress bar
                progressBar.visibility = when (state) {
                    STATE_THINKING, STATE_EXECUTING -> View.VISIBLE
                    else -> View.GONE
                }

                // If hidden, show it
                if (floatingView.visibility != View.VISIBLE) {
                    floatingView.visibility = View.VISIBLE
                    isShowing = true
                }

                currentState = state
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update HUD: ${e.message}")
        }
    }

    private fun hideHUD() {
        try {
            if (::floatingView.isInitialized && floatingView.visibility == View.VISIBLE) {
                floatingView.visibility = View.GONE
                isShowing = false
                currentState = STATE_IDLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide HUD: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::floatingView.isInitialized && ::windowManager.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing HUD view: ${e.message}")
        }
        isShowing = false
        currentState = STATE_IDLE
        Log.d(TAG, "Floating HUD destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Helper function to update position if needed
    fun updatePosition(x: Int, y: Int) {
        try {
            if (::floatingView.isInitialized) {
                val params = floatingView.layoutParams as WindowManager.LayoutParams
                params.x = x
                params.y = y
                windowManager.updateViewLayout(floatingView, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update HUD position: ${e.message}")
        }
    }
}