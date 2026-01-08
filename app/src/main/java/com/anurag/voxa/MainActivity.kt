package com.anurag.voxa

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        checkPermissions()
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
            startJarvis()
        }

        btnStop.setOnClickListener {
            stopJarvis()
        }

        switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasRecordAudioPermission()) {
                requestPermissions()
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
            requestPermissions()
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            testJarvis()
        }

        findViewById<Button>(R.id.btn_clear_memory).setOnClickListener {
            MemoryEngine.clearMemory()
            updateMemoryStats()
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startJarvis() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "Grant all permissions first", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }

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

        updateStatus()
        Toast.makeText(this, "JARVIS activated", Toast.LENGTH_SHORT).show()
    }

    private fun stopJarvis() {
        stopJarvisService()
        stopWakeWordService()
        FloatingHUD.hide(this)

        updateStatus()
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
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopJarvisService() {
        try {
            val serviceIntent = Intent(this, JarvisService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to stop service: ${e.message}", Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start wake word service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopWakeWordService() {
        try {
            val serviceIntent = Intent(this, JarvisWakeWordService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to stop wake word service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testJarvis() {
        // Test command
        val testCommand = "open settings and show battery"

        // Use coroutine scope
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val actions = GeminiPlanner.planActions(testCommand)
            if (actions.isNotEmpty()) {
                ActionExecutor.execute(actions)
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Test command executed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val missingPermissions = getMissingPermissions()

        if (missingPermissions.isNotEmpty()) {
            textStatus.text = "Missing ${missingPermissions.size} permissions"
            return false
        }

        if (!hasOverlayPermission()) {
            textStatus.text = "Need overlay permission"
            return false
        }

        if (!isAccessibilityEnabled()) {
            textStatus.text = "Enable accessibility service"
            return false
        }

        textStatus.text = "Ready"
        return true
    }

    private fun requestPermissions() {
        val permissions = getMissingPermissions().toTypedArray()

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions,
                PERMISSION_REQUEST_CODE
            )
        }

        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
        }
    }

    private fun getMissingPermissions(): List<String> {
        val requiredPermissions = mutableListOf<String>()

        if (!hasRecordAudioPermission()) {
            requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        return requiredPermissions
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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

    private fun hasAllPermissions(): Boolean {
        return hasRecordAudioPermission() &&
                hasOverlayPermission() &&
                isAccessibilityEnabled()
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Check if our accessibility service is enabled
        val serviceName = "$packageName/.JarvisAccessibilityService"
        val accessibilityEnabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        return accessibilityEnabled?.contains(serviceName) == true
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, ACCESSIBILITY_SETTINGS)
    }

    private fun updateStatus() {
        val status = StringBuilder()

        status.append("Permissions: ")
        status.append(if (hasRecordAudioPermission()) "✓" else "✗")
        status.append(" ")

        status.append("Overlay: ")
        status.append(if (hasOverlayPermission()) "✓" else "✗")
        status.append(" ")

        status.append("Accessibility: ")
        status.append(if (isAccessibilityEnabled()) "✓" else "✗")
        status.append("\n")

        status.append("Wake Word: ${if (switchWakeWord.isChecked) "ON" else "OFF"}\n")
        status.append("Vision: ${if (switchVision.isChecked) "ON" else "OFF"}\n")
        status.append("Root: ${if (switchRoot.isChecked && RootManager.hasRootAccess()) "ON" else "OFF"}")

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
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updateStatus()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST, ACCESSIBILITY_SETTINGS -> {
                updateStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}