package com.anurag.voxa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object RootManager {

    private const val TAG = "RootManager"
    private var isRooted = false
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.IO)

    // Using Runtime.exec() as fallback since we don't have libsu dependency
    fun initialize(context: Context) {
        scope.launch {
            isRooted = checkRootAccess()
            isInitialized = true

            if (isRooted) {
                Log.d(TAG, "Root access available")
                setupRootEnvironment()
            } else {
                Log.d(TAG, "No root access")
            }
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            // Try to execute a simple root command
            val process = Runtime.getRuntime().exec("su -c id")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    private fun setupRootEnvironment() {
        // Execute some setup commands
        executeCommand("settings put global airplane_mode_on 0")
        executeCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
    }

    fun executeCommand(command: String): String {
        if (!isRooted) return "No root access"

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()

            if (process.exitValue() == 0) {
                output
            } else {
                "Error: $error"
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }

    fun executeCommands(vararg commands: String): List<String> {
        if (!isRooted) return listOf("No root access")

        return commands.map { executeCommand(it) }
    }

    // System Controls
    fun setBrightness(level: Int) {
        scope.launch {
            if (!isRooted) return@launch

            val normalizedLevel = level.coerceIn(0, 255)
            executeCommand("settings put system screen_brightness $normalizedLevel")
        }
    }

    fun adjustVolume(direction: Int) {
        scope.launch {
            if (!isRooted) return@launch

            when (direction) {
                1 -> executeCommand("input keyevent KEYCODE_VOLUME_UP")
                -1 -> executeCommand("input keyevent KEYCODE_VOLUME_DOWN")
                0 -> executeCommand("input keyevent KEYCODE_VOLUME_MUTE")
            }
        }
    }

    fun toggleWifi(enable: Boolean) {
        scope.launch {
            if (!isRooted) return@launch

            val state = if (enable) "1" else "0"
            executeCommand("svc wifi $state")
        }
    }

    fun toggleMobileData(enable: Boolean) {
        scope.launch {
            if (!isRooted) return@launch

            val state = if (enable) "enable" else "disable"
            executeCommand("svc data $state")
        }
    }

    fun killApp(packageName: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("am force-stop $packageName")
        }
    }

    fun installApk(apkPath: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("pm install -r \"$apkPath\"")
        }
    }

    fun uninstallApp(packageName: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("pm uninstall $packageName")
        }
    }

    fun takeScreenshot(path: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("screencap -p \"$path\"")
        }
    }

    fun inputTap(x: Int, y: Int) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("input tap $x $y")
        }
    }

    fun inputText(text: String) {
        scope.launch {
            if (!isRooted) return@launch

            // Escape special characters
            val escapedText = text.replace(" ", "%s")
                .replace("'", "'\"'\"'")
            executeCommand("input text '$escapedText'")
        }
    }

    fun inputKeyEvent(keyCode: Int) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("input keyevent $keyCode")
        }
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Int = 100) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("input swipe $fromX $fromY $toX $toY $duration")
        }
    }

    // Advanced System Controls
    fun setCpuFrequency(core: Int, frequency: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("echo '$frequency' > /sys/devices/system/cpu/cpu$core/cpufreq/scaling_max_freq")
        }
    }

    fun setGovernor(governor: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("echo '$governor' > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        }
    }

    fun backupApp(packageName: String, backupPath: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("pm backup $packageName -f \"$backupPath\"")
        }
    }

    fun restoreApp(backupPath: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("pm restore \"$backupPath\"")
        }
    }

    // Safety: Check if we have root
    fun hasRootAccess(): Boolean {
        return isRooted && isInitialized
    }
}