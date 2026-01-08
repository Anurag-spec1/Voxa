package com.anurag.voxa

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingHUD : Service() {

    companion object {
        const val TAG = "FloatingHUD"
        private var isShowing = false

        enum class State {
            LISTENING,
            THINKING,
            EXECUTING,
            ERROR,
            IDLE
        }

        fun show(context: Context, message: String, state: State = State.IDLE) {
            try {
                val intent = Intent(context, FloatingHUD::class.java).apply {
                    putExtra("message", message)
                    putExtra("state", state.name)
                    putExtra("action", "show")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show HUD: ${e.message}")
            }
        }

        fun update(context: Context, message: String, state: State = State.IDLE) {
            try {
                val intent = Intent(context, FloatingHUD::class.java).apply {
                    putExtra("message", message)
                    putExtra("state", state.name)
                    putExtra("action", "update")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update HUD: ${e.message}")
            }
        }

        fun hide(context: Context) {
            try {
                val intent = Intent(context, FloatingHUD::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide HUD: ${e.message}")
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var textView: TextView
    private lateinit var statusIndicator: View

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Floating HUD created")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create floating view
        floatingView = LayoutInflater.from(this).inflate(R.layout.hud_overlay, null)
        textView = floatingView.findViewById(R.id.hud_text)
        statusIndicator = floatingView.findViewById(R.id.status_indicator)

        // Setup window parameters
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL

            // Make it slightly transparent
            alpha = 0.9f

            // Position at top
            y = 100
        }

        // Add to window
        windowManager.addView(floatingView, params)
        floatingView.visibility = View.GONE

        // Start foreground
        startForeground(NotificationHelper.HUD_NOTIFICATION_ID,
            NotificationHelper.createHUDNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.getStringExtra("action")) {
                "show" -> {
                    val message = it.getStringExtra("message") ?: "JARVIS"
                    val state = try {
                        State.valueOf(it.getStringExtra("state") ?: "IDLE")
                    } catch (e: Exception) {
                        State.IDLE
                    }
                    showHUD(message, state)
                }
                "update" -> {
                    val message = it.getStringExtra("message") ?: "JARVIS"
                    val state = try {
                        State.valueOf(it.getStringExtra("state") ?: "IDLE")
                    } catch (e: Exception) {
                        State.IDLE
                    }
                    updateHUD(message, state)
                }
                "hide" -> hideHUD()
            }
        }
        return START_STICKY
    }

    private fun showHUD(message: String, state: State) {
        floatingView.visibility = View.VISIBLE
        updateHUD(message, state)
        isShowing = true

        // Auto-hide after 5 seconds if idle
        if (state == State.IDLE) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(5000)
                if (isShowing) {
                    hideHUD()
                }
            }
        }
    }

    private fun updateHUD(message: String, state: State) {
        textView.text = message

        // Update indicator color based on state
        val color = when (state) {
            State.LISTENING -> android.graphics.Color.BLUE
            State.THINKING -> android.graphics.Color.YELLOW
            State.EXECUTING -> android.graphics.Color.GREEN
            State.ERROR -> android.graphics.Color.RED
            State.IDLE -> android.graphics.Color.GRAY
        }

        statusIndicator.setBackgroundColor(color)

        // If hidden, show it
        if (floatingView.visibility != View.VISIBLE) {
            floatingView.visibility = View.VISIBLE
            isShowing = true
        }
    }

    private fun hideHUD() {
        floatingView.visibility = View.GONE
        isShowing = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        isShowing = false
        Log.d(TAG, "Floating HUD destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}