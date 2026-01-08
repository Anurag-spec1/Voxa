package com.anurag.voxa

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiPlanner {

    private const val TAG = "GeminiPlanner"

    // FIX THIS: Your API key might be invalid or have issues
    private const val GEMINI_API_KEY = "AIzaSyAUa62eX7r7h8A8V89pz76ydUvaPTbdGUA"

    // Updated URL for Gemini 1.5 Pro
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent"

    // Or try Gemini 1.0 Pro (more stable)
    private const val GEMINI_URL_1_0 = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    data class GeminiRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig? = null
    )

    data class Content(
        val parts: List<Part>
    )

    data class Part(
        val text: String
    )

    data class GenerationConfig(
        val temperature: Double = 0.1,
        val topK: Int = 1,
        val topP: Double = 1.0,
        val maxOutputTokens: Int = 500
    )

    data class GeminiResponse(
        val candidates: List<Candidate>?
    )

    data class Candidate(
        val content: Content?
    )

    data class ActionPlan(
        val actions: List<Action>
    )

    data class Action(
        val type: String,
        val target: String = "",
        val text: String = "",
        val packageName: String = "",
        val x: Int = 0,
        val y: Int = 0,
        val delay: Int = 0
    )

    suspend fun planActions(command: String): List<Action> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Planning actions for command: '$command'")

            // First try to handle simple commands locally (FASTER, NO API CALL)
            val localActions = handleLocalCommand(command)
            if (localActions.isNotEmpty()) {
                Log.d(TAG, "Using local actions: ${localActions.size}")
                return@withContext localActions
            }

            // For complex commands, use Gemini
            val systemPrompt = """
                You are Jarvis - an Android automation AI assistant.
                Convert user commands into executable UI actions in JSON format.
                
                Available Actions (output as JSON array):
                1. {"type": "open_app", "packageName": "com.app.package"}
                2. {"type": "click", "target": "Button text"} 
                3. {"type": "type", "text": "text to type"}
                4. {"type": "send"} (press Enter key)
                5. {"type": "back"} (go back)
                6. {"type": "home"} (go to home screen)
                7. {"type": "recents"} (show recent apps)
                8. {"type": "scroll", "direction": "up/down"}
                9. {"type": "wait", "delay": 1000} (milliseconds)
                
                IMPORTANT: Output ONLY valid JSON array. No explanations, no markdown.
                
                Example 1:
                User: "open whatsapp"
                Output: [{"type": "open_app", "packageName": "com.whatsapp"}]
                
                Example 2:
                User: "open youtube and search for music"
                Output: [
                  {"type": "open_app", "packageName": "com.google.android.youtube"},
                  {"type": "wait", "delay": 2000},
                  {"type": "click", "target": "Search"},
                  {"type": "wait", "delay": 1000},
                  {"type": "type", "text": "music"},
                  {"type": "send"}
                ]
                
                User Command: "$command"
            """.trimIndent()

            val requestBody = """
                {
                  "contents": [{
                    "parts": [{
                      "text": "$systemPrompt"
                    }]
                  }],
                  "generationConfig": {
                    "temperature": 0.1,
                    "topK": 1,
                    "topP": 1,
                    "maxOutputTokens": 500
                  }
                }
            """.trimIndent()

            Log.d(TAG, "Sending request to Gemini API...")

            // Try multiple endpoints if one fails
            val urlsToTry = listOf(
                "$GEMINI_URL_1_0?key=$GEMINI_API_KEY",
                "$GEMINI_URL?key=$GEMINI_API_KEY"
            )

            var responseBody: String? = null
            var lastException: Exception? = null

            for (url in urlsToTry) {
                try {
                    Log.d(TAG, "Trying URL: ${url.substring(0, minOf(50, url.length))}...")

                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody.toRequestBody(jsonMediaType))
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val response = client.newCall(request).execute()

                    Log.d(TAG, "Response code: ${response.code}")

                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        Log.d(TAG, "Response body (first 500 chars): ${responseBody?.take(500)}")
                        break
                    } else {
                        Log.e(TAG, "API call failed with code: ${response.code}")
                        Log.e(TAG, "Response error: ${response.body?.string()}")
                        lastException = Exception("API error ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with URL $url: ${e.message}")
                    lastException = e
                }
            }

            if (responseBody == null) {
                Log.e(TAG, "All API attempts failed")
                throw lastException ?: Exception("No response from API")
            }

            // Parse response
            return@withContext parseGeminiResponse(responseBody, command)

        } catch (e: Exception) {
            Log.e(TAG, "Error in planActions: ${e.message}")
            e.printStackTrace()

            // Fallback to local handling if API fails
            return@withContext handleLocalCommandFallback(command)
        }
    }

    private fun handleLocalCommand(command: String): List<Action> {
        val lowerCommand = command.lowercase()

        return when {
            // Open app commands
            lowerCommand.contains("open") && !lowerCommand.contains("open") -> {
                val appName = extractAppNameFromCommand(command)
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameForApp(appName)
                    if (packageName.isNotEmpty()) {
                        listOf(Action(type = "open_app", packageName = packageName))
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            // Simple system commands
            lowerCommand.contains("go home") || lowerCommand.contains("home") -> {
                listOf(Action(type = "home"))
            }

            lowerCommand.contains("go back") || lowerCommand.contains("back") -> {
                listOf(Action(type = "back"))
            }

            lowerCommand.contains("recent") || lowerCommand.contains("recents") -> {
                listOf(Action(type = "recents"))
            }

            else -> emptyList()
        }
    }

    private fun handleLocalCommandFallback(command: String): List<Action> {
        Log.d(TAG, "Using fallback for command: $command")

        val lowerCommand = command.lowercase()

        return when {
            // Try to extract and open app
            lowerCommand.contains("open") -> {
                val appName = extractAppNameFromCommand(command)
                if (appName.isNotEmpty()) {
                    val packageName = getPackageNameForApp(appName)
                    if (packageName.isNotEmpty()) {
                        listOf(Action(type = "open_app", packageName = packageName))
                    } else {
                        // Try common apps
                        when {
                            lowerCommand.contains("whatsapp") -> listOf(Action(type = "open_app", packageName = "com.whatsapp"))
                            lowerCommand.contains("youtube") -> listOf(Action(type = "open_app", packageName = "com.google.android.youtube"))
                            lowerCommand.contains("instagram") -> listOf(Action(type = "open_app", packageName = "com.instagram.android"))
                            lowerCommand.contains("settings") -> listOf(Action(type = "open_app", packageName = "com.android.settings"))
                            lowerCommand.contains("camera") -> listOf(Action(type = "open_app", packageName = "com.android.camera"))
                            else -> emptyList()
                        }
                    }
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private fun extractAppNameFromCommand(command: String): String {
        val patterns = listOf(
            "open (\\w+)" to Regex("open (\\w+)", RegexOption.IGNORE_CASE),
            "open the (\\w+)" to Regex("open the (\\w+)", RegexOption.IGNORE_CASE),
            "launch (\\w+)" to Regex("launch (\\w+)", RegexOption.IGNORE_CASE),
            "start (\\w+)" to Regex("start (\\w+)", RegexOption.IGNORE_CASE)
        )

        for ((_, regex) in patterns) {
            val match = regex.find(command)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        // Fallback: extract word after "open"
        val openIndex = command.indexOf("open", ignoreCase = true)
        if (openIndex != -1) {
            val afterOpen = command.substring(openIndex + 4).trim()
            return afterOpen.split(" ").firstOrNull() ?: ""
        }

        return ""
    }

    private fun getPackageNameForApp(appName: String): String {
        return when (appName.lowercase()) {
            "whatsapp", "whats app", "whats" -> "com.whatsapp"
            "youtube", "yt", "you tube" -> "com.google.android.youtube"
            "instagram", "insta", "ig" -> "com.instagram.android"
            "settings", "setting" -> "com.android.settings"
            "camera" -> "com.android.camera"
            "gallery", "photos" -> "com.android.gallery3d"
            "messages", "sms" -> "com.android.mms"
            "phone", "dialer" -> "com.android.dialer"
            "contacts" -> "com.android.contacts"
            "chrome", "browser" -> "com.android.chrome"
            "gmail", "email" -> "com.google.android.gm"
            "maps" -> "com.google.android.apps.maps"
            "play store", "playstore" -> "com.android.vending"
            "calculator" -> "com.android.calculator2"
            "clock" -> "com.android.deskclock"
            "calendar" -> "com.google.android.calendar"
            "files", "file manager" -> "com.android.documentsui"
            else -> ""
        }
    }

    private fun parseGeminiResponse(responseBody: String, originalCommand: String): List<Action> {
        try {
            Log.d(TAG, "Parsing Gemini response...")

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")

            if (candidates == null || candidates.length() == 0) {
                Log.e(TAG, "No candidates in response")
                return handleLocalCommandFallback(originalCommand)
            }

            val candidate = candidates.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val part = parts?.optJSONObject(0)
            val text = part?.optString("text") ?: ""

            Log.d(TAG, "Gemini response text: ${text.take(200)}...")

            // Try to extract JSON from the response
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = text.substring(jsonStart, jsonEnd)
                Log.d(TAG, "Extracted JSON: $jsonString")

                try {
                    val actionPlan = gson.fromJson(jsonString, Array<Action>::class.java)
                    return actionPlan.toList()
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JSON array: ${e.message}")
                }
            }

            // Alternative: try to parse as ActionPlan object
            try {
                val actionPlan = gson.fromJson(text, ActionPlan::class.java)
                if (actionPlan.actions.isNotEmpty()) {
                    return actionPlan.actions
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing as ActionPlan: ${e.message}")
            }

            // If no JSON found, use fallback
            Log.w(TAG, "No valid JSON found in response, using fallback")
            return handleLocalCommandFallback(originalCommand)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}")
            return handleLocalCommandFallback(originalCommand)
        }
    }
}