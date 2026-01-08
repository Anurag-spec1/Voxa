package com.anurag.voxa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val OVERLAY_PERMISSION_REQUEST = 1002
        private const val ACCESSIBILITY_SETTINGS = 1003
    }

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var switchWakeWord: SwitchMaterial
    private lateinit var switchVision: SwitchMaterial
    private lateinit var switchRoot: SwitchMaterial
    private lateinit var textStatus: TextView
    private lateinit var textMemory: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val accessibilityCheckRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        updateStatus()
    }

    private fun initializeViews() {
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        switchWakeWord = findViewById(R.id.switch_wake_word)
        switchVision = findViewById(R.id.switch_vision)
        switchRoot = findViewById(R.id.switch_root)
        textStatus = findViewById(R.id.text_status)
        textMemory = findViewById(R.id.text_memory)

        // Set defaults
        switchWakeWord.isChecked = true
        switchVision.isChecked = true
        switchRoot.isChecked = false
    }

    private fun setupClickListeners() {
        btnStart.setOnClickListener {
            if (checkAllRequirements()) {
                startJarvis()
            } else {
                showMissingRequirementsDialog()
            }
        }

        btnStop.setOnClickListener {
            stopJarvis()
        }

        switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasRecordAudioPermission()) {
                requestMissingPermissions() // Fixed: This function exists now
            }
        }

        switchRoot.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                RootManager.initialize(this)
                Toast.makeText(this,
                    if (RootManager.hasRootAccess()) "Root access granted"
                    else "Root not available",
                    Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            requestAllMissingPermissions()
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            testJarvis()
        }

        findViewById<Button>(R.id.btn_clear_memory).setOnClickListener {
            MemoryEngine.clearMemory()
            updateMemoryStats()
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show()
        }

        // Test button for debugging
        findViewById<Button>(R.id.btn_test_accessibility)?.setOnClickListener {
            testAccessibility()
        }
    }

    // ADD THIS MISSING FUNCTION:
    private fun requestMissingPermissions() {
        val permissions = mutableListOf<String>()

        if (!hasRecordAudioPermission()) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasVibrationPermission()) {
                permissions.add(Manifest.permission.VIBRATE)
            }
            if (!hasNotificationPermission()) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        if (!hasOverlayPermission()) {
            requestOverlayPermission()
        }
    }

    private fun startJarvis() {
        // Start services
        startJarvisService()
        startWakeWordService()

        // Initialize Vision Engine
        if (switchVision.isChecked) {
            VisionEngine.initialize(this)
        }

        // Initialize Root Manager
        if (switchRoot.isChecked) {
            RootManager.initialize(this)
        }

        Toast.makeText(this, "JARVIS activated", Toast.LENGTH_SHORT).show()
    }

    private fun stopJarvis() {
        stopJarvisService()
        stopWakeWordService()
        FloatingHUD.hide(this)

        Toast.makeText(this, "JARVIS deactivated", Toast.LENGTH_SHORT).show()
    }

    private fun startJarvisService() {
        try {
            val serviceIntent = Intent(this, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "JarvisService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
            Toast.makeText(this, "Failed to start service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopJarvisService() {
        try {
            val serviceIntent = Intent(this, JarvisService::class.java)
            stopService(serviceIntent)
            Log.d(TAG, "JarvisService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop service: ${e.message}")
        }
    }

    private fun startWakeWordService() {
        try {
            val serviceIntent = Intent(this, JarvisWakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "WakeWordService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word service: ${e.message}")
            Toast.makeText(this, "Failed to start wake word service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopWakeWordService() {
        try {
            val serviceIntent = Intent(this, JarvisWakeWordService::class.java)
            stopService(serviceIntent)
            Log.d(TAG, "WakeWordService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop wake word service: ${e.message}")
        }
    }

    private fun testJarvis() {
        if (!checkAllRequirements()) {
            Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show()
            return
        }

        val testCommand = "open settings"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val actions = GeminiPlanner.planActions(testCommand)
            if (actions.isNotEmpty()) {
                ActionExecutor.execute(actions, this@MainActivity)
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Test command executed", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "No actions generated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun testAccessibility() {
        if (isAccessibilityEnabled()) {
            Toast.makeText(this, "Accessibility is enabled ✓", Toast.LENGTH_SHORT).show()

            if (JarvisAccessibilityService.instance != null) {
                Toast.makeText(this, "Service instance is active", Toast.LENGTH_SHORT).show()

                // Test a simple action
                val success = JarvisAccessibilityService.clickByText("START")
                Toast.makeText(this, "Click test: ${if (success) "Success" else "Failed"}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Service instance is null (restart app)", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Accessibility is disabled ✗", Toast.LENGTH_SHORT).show()
            showAccessibilityDialog()
        }
    }

    private fun checkAllRequirements(): Boolean {
        return hasRecordAudioPermission() &&
                hasVibrationPermission() &&
                hasOverlayPermission() &&
                isAccessibilityEnabled()
    }

    private fun showMissingRequirementsDialog() {
        val missing = StringBuilder()

        if (!hasRecordAudioPermission()) missing.append("• Microphone permission\n")
        if (!hasVibrationPermission()) missing.append("• Vibration permission\n")
        if (!hasOverlayPermission()) missing.append("• Display over other apps\n")
        if (!isAccessibilityEnabled()) missing.append("• Accessibility service\n")

        AlertDialog.Builder(this)
            .setTitle("Required Permissions")
            .setMessage("Please grant these permissions:\n\n$missing")
            .setPositiveButton("Grant All") { _, _ ->
                requestAllMissingPermissions()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun requestAllMissingPermissions() {
        val permissions = mutableListOf<String>()

        // Audio permission
        if (!hasRecordAudioPermission()) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Vibration permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasVibrationPermission()) {
                permissions.add(Manifest.permission.VIBRATE)
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request runtime permissions
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        // Overlay permission (special intent)
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
        }

        // Accessibility (needs manual enabling)
        if (!isAccessibilityEnabled()) {
            showAccessibilityDialog()
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage("JARVIS needs accessibility permission to:\n" +
                    "• Click buttons automatically\n" +
                    "• Type text for you\n" +
                    "• Navigate between apps\n" +
                    "• Read screen content\n\n" +
                    "Please go to Accessibility settings and enable 'JARVIS Accessibility Service'")
            .setPositiveButton("Open Settings") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, ACCESSIBILITY_SETTINGS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasVibrationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.VIBRATE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Vibration permission not required before Android 13
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            // Method 1: Check via AccessibilityManager (most reliable)
            val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager

            // Get list of enabled services
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            val serviceName = "$packageName/.JarvisAccessibilityService"
            val serviceNameWithNoLeadingDot = "$packageName/${JarvisAccessibilityService::class.java.simpleName}"

            Log.d(TAG, "Checking accessibility service: $serviceName")
            Log.d(TAG, "Enabled services: $enabledServices")

            // Check multiple possible service name formats
            val isEnabled = enabledServices?.let {
                it.contains(serviceName, ignoreCase = true) ||
                        it.contains(serviceNameWithNoLeadingDot, ignoreCase = true) ||
                        it.contains("JarvisAccessibilityService", ignoreCase = true)
            } ?: false

            // Also check if the service is running
            val isRunning = JarvisAccessibilityService.instance != null

            Log.d(TAG, "Accessibility enabled: $isEnabled, Running: $isRunning")

            return isEnabled || isRunning
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility: ${e.message}")
            false
        }
    }

    private fun updateStatus() {
        val status = StringBuilder()

        // Permissions
        status.append("1. Microphone: ")
        status.append(if (hasRecordAudioPermission()) "✓ GRANTED" else "✗ MISSING")
        status.append("\n")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status.append("2. Vibration: ")
            status.append(if (hasVibrationPermission()) "✓ GRANTED" else "✗ MISSING")
            status.append("\n")

            status.append("3. Notifications: ")
            status.append(if (hasNotificationPermission()) "✓ GRANTED" else "✗ MISSING")
            status.append("\n")
        }

        status.append("4. Overlay: ")
        status.append(if (hasOverlayPermission()) "✓ GRANTED" else "✗ MISSING")
        status.append("\n")

        status.append("5. Accessibility: ")
        if (isAccessibilityEnabled()) {
            status.append("✓ ENABLED")
            if (JarvisAccessibilityService.instance != null) {
                status.append(" (RUNNING)")
            }
        } else {
            status.append("✗ DISABLED")
            status.append("\n")
            status.append("   → Go to Settings > Accessibility > Installed Services")
            status.append("\n")
            status.append("   → Enable 'JARVIS Accessibility Service'")
        }
        status.append("\n\n")

        status.append("Features:\n")
        status.append("• Wake Word: ${if (switchWakeWord.isChecked) "ON" else "OFF"}\n")
        status.append("• Vision: ${if (switchVision.isChecked) "ON" else "OFF"}\n")
        status.append("• Root: ${if (switchRoot.isChecked && RootManager.hasRootAccess()) "ON" else "OFF"}")

        textStatus.text = status.toString()
        updateMemoryStats()
    }

    private fun updateMemoryStats() {
        val stats = MemoryEngine.getMemoryStats()
        val memoryText = """
            Last App: ${stats["last_app"]}
            Last Contact: ${stats["last_contact"]}
            Context Entries: ${stats["context_entries"]}
            Command History: ${stats["command_history_count"]}
        """.trimIndent()

        textMemory.text = memoryText
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                updateStatus()
                // Check if we can start JARVIS now
                if (checkAllRequirements()) {
                    Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST -> {
                updateStatus()
                if (hasOverlayPermission()) {
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                }
            }
            ACCESSIBILITY_SETTINGS -> {
                updateStatus()
                if (isAccessibilityEnabled()) {
                    Toast.makeText(this, "Accessibility enabled!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // Start checking accessibility status periodically
        handler.post(accessibilityCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(accessibilityCheckRunnable)
    }
}