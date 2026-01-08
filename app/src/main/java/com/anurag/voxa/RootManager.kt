package com.anurag.voxa

import android.content.Context
import android.util.Log
import androidx.test.shell.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object RootManager {

    private const val TAG = "RootManager"
    private var isRooted = false
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(context: Context) {
        scope.launch {
            isRooted = Shell.getShell().isRoot
            isInitialized = true

            if (isRooted) {
                Log.d(TAG, "Root access available")
                setupRootEnvironment()
            } else {
                Log.d(TAG, "No root access")
            }
        }
    }

    private fun setupRootEnvironment() {
        // Execute some setup commands
        Shell.cmd(
            "settings put global airplane_mode_on 0",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        ).exec()
    }

    fun executeCommand(command: String): String {
        if (!isRooted) return "No root access"

        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) {
                result.out.joinToString("\n")
            } else {
                result.err.joinToString("\n")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
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

            executeCommand("pm install -r $apkPath")
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

            executeCommand("screencap -p $path")
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

            executeCommand("pm backup $packageName -f $backupPath")
        }
    }

    fun restoreApp(backupPath: String) {
        scope.launch {
            if (!isRooted) return@launch

            executeCommand("pm restore $backupPath")
        }
    }

    // Safety: Check if we have root
    fun hasRootAccess(): Boolean {
        return isRooted && isInitialized
    }
}