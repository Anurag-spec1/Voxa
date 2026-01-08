package com.anurag.voxa

import android.content.Intent
import android.provider.Settings
import android.util.Log

object FastRuleEngine {

    private const val TAG = "FastRuleEngine"

    fun process(command: String): List<GeminiPlanner.Action>? {
        val cmd = command.lowercase().trim()
        Log.d(TAG, "Processing: $cmd")

        return when {
            // Navigation
            cmd.contains("go back") || cmd == "back" -> listOf(
                GeminiPlanner.Action(type = "back")
            )

            cmd.contains("go home") || cmd == "home" -> listOf(
                GeminiPlanner.Action(type = "home")
            )

            cmd.contains("recent") || cmd.contains("show recent") -> listOf(
                GeminiPlanner.Action(type = "recents")
            )

            // Volume Control
            cmd.contains("volume up") -> listOf(
                GeminiPlanner.Action(type = "volume_up")
            )

            cmd.contains("volume down") -> listOf(
                GeminiPlanner.Action(type = "volume_down")
            )

            cmd.contains("mute") -> listOf(
                GeminiPlanner.Action(type = "mute")
            )

            // Brightness
            cmd.contains("brightness") -> {
                val level = extractNumber(cmd) ?: 50
                listOf(GeminiPlanner.Action(type = "brightness", target = level.toString()))
            }

            // Quick Apps
            cmd.startsWith("open ") -> {
                val appName = cmd.removePrefix("open ")
                val packageName = AppDatabase.getPackageForApp(appName)
                if (packageName != null) {
                    listOf(GeminiPlanner.Action(type = "open_app", packageName = packageName))
                } else {
                    null // Let AI handle it
                }
            }

            // Quick Actions
            cmd.contains("take screenshot") -> listOf(
                GeminiPlanner.Action(type = "screenshot")
            )

            cmd.contains("open settings") -> listOf(
                GeminiPlanner.Action(type = "open_app", packageName = "com.android.settings")
            )

            // Repeat last action
            cmd.contains("repeat") || cmd.contains("again") -> {
                MemoryEngine.getLastActions()?.let { lastActions ->
                    // Convert to new list to avoid reference issues
                    lastActions.map { action ->
                        GeminiPlanner.Action(
                            type = action.type,
                            target = action.target,
                            text = action.text,
                            packageName = action.packageName,
                            x = action.x,
                            y = action.y,
                            delay = action.delay
                        )
                    }
                }
            }

            else -> null
        }
    }

    private fun extractNumber(text: String): Int? {
        val regex = Regex("\\d+")
        return regex.find(text)?.value?.toIntOrNull()
    }
}