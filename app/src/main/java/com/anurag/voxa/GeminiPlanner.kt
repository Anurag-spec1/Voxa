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

    // Your API key
    private const val GEMINI_API_KEY = "AIzaSyAUa62eX7r7h8A8V89pz76ydUvaPTbdGUA"

    // CORRECTED API URLs
    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    // Available models - try gemini-pro first (most stable)
    private const val GEMINI_PRO = "gemini-pro:generateContent"

    // Build URL
    private val geminiProUrl = "${GEMINI_BASE_URL}${GEMINI_PRO}?key=$GEMINI_API_KEY"

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

            // Always try local command first (no API call needed)
            val localActions = handleLocalCommand(command)
            if (localActions.isNotEmpty()) {
                Log.d(TAG, "✓ Using local actions: ${localActions.size}")
                return@withContext localActions
            }

            Log.d(TAG, "No local action found, trying Gemini API...")

            // Use the most reliable model
            return@withContext tryGeminiApi(command)

        } catch (e: Exception) {
            Log.e(TAG, "Error in planActions: ${e.message}")

            // Final fallback - ADD THIS FUNCTION
            return@withContext handleLocalCommandFallback(command)
        }
    }

    private suspend fun tryGeminiApi(command: String): List<Action> = withContext(Dispatchers.IO) {
        // Create a better prompt
        val systemPrompt = """
            You are Jarvis, an Android automation assistant. Convert voice commands into JSON actions.
            
            AVAILABLE ACTIONS (output ONLY JSON array):
            
            1. OPEN APP: {"type": "open_app", "packageName": "com.package.name", "delay": 2000}
            2. CLICK: {"type": "click", "target": "Button Text", "delay": 1000}
            3. TYPE: {"type": "type", "text": "text to type", "delay": 1000}
            4. SEND: {"type": "send", "delay": 500} (press Enter)
            5. BACK: {"type": "back", "delay": 500}
            6. HOME: {"type": "home", "delay": 500}
            7. RECENTS: {"type": "recents", "delay": 500}
            
            COMMON PACKAGE NAMES:
            - WhatsApp: com.whatsapp
            - YouTube: com.google.android.youtube  
            - Settings: com.android.settings
            - Camera: com.android.camera
            - Chrome: com.android.chrome
            - Gmail: com.google.android.gm
            - Maps: com.google.android.apps.maps
            - Instagram: com.instagram.android
            - Phone: com.android.dialer
            - Messages: com.google.android.apps.messaging
            
            EXAMPLES:
            User: "open youtube"
            Output: [{"type": "open_app", "packageName": "com.google.android.youtube", "delay": 2000}]
            
            User: "open settings and go back"
            Output: [
              {"type": "open_app", "packageName": "com.android.settings", "delay": 2000},
              {"type": "wait", "delay": 1000},
              {"type": "back", "delay": 500}
            ]
            
            IMPORTANT: Output ONLY the JSON array. No explanations. If unsure, return empty array [].
            
            User Command: "${command.trim()}"
        """.trimIndent()

        try {
            Log.d(TAG, "Trying model: gemini-pro")

            val request = createGeminiRequest(systemPrompt, geminiProUrl)
            val response = client.newCall(request).execute()

            Log.d(TAG, "Response code: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d(TAG, "✓ Success from gemini-pro")

                val actions = parseGeminiResponse(responseBody ?: "", command)
                if (actions.isNotEmpty()) {
                    Log.d(TAG, "✓ Parsed ${actions.size} actions")
                    return@withContext actions
                } else {
                    Log.w(TAG, "No actions parsed, using fallback")
                    return@withContext handleLocalCommandFallback(command)
                }
            } else {
                val error = response.body?.string()
                Log.e(TAG, "✗ gemini-pro failed: ${response.code} - $error")
                return@withContext handleLocalCommandFallback(command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with gemini-pro: ${e.message}")
            return@withContext handleLocalCommandFallback(command)
        }
    }

    private fun createGeminiRequest(prompt: String, url: String): Request {
        val requestBody = gson.toJson(
            GeminiRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = prompt)
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0.1,
                    topK = 1,
                    topP = 1.0,
                    maxOutputTokens = 500
                )
            )
        )

        return Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
    }

    // MAIN local command handler
    private fun handleLocalCommand(command: String): List<Action> {
        val lowerCommand = command.lowercase().trim()
        Log.d(TAG, "Local handler processing: '$lowerCommand'")

        // Handle empty or just "open"
        if (lowerCommand.isEmpty() || lowerCommand == "open") {
            Log.w(TAG, "Empty or incomplete command")
            // Return a default action - open settings
            return listOf(Action(type = "open_app", packageName = "com.android.settings", delay = 2000))
        }

        // Extract app name using regex patterns
        val patterns = listOf(
            Regex("""open\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""launch\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""start\s+(\w+)""", RegexOption.IGNORE_CASE)
        )

        var appName = ""
        for (pattern in patterns) {
            val match = pattern.find(lowerCommand)
            if (match != null) {
                appName = match.groupValues[1]
                Log.d(TAG, "Found app name via regex: $appName")
                break
            }
        }

        // If no regex match, try simple extraction
        if (appName.isEmpty() && lowerCommand.startsWith("open ")) {
            appName = lowerCommand.substring(5).split(" ")[0]
            Log.d(TAG, "Extracted app name: $appName")
        }

        if (appName.isNotEmpty()) {
            val packageName = getPackageNameForApp(appName)
            if (packageName.isNotEmpty()) {
                Log.d(TAG, "✓ Local handler opening: $appName -> $packageName")
                return listOf(Action(type = "open_app", packageName = packageName, delay = 2000))
            }
        }

        // Handle common commands
        return when {
            lowerCommand.contains("go home") || lowerCommand == "home" ->
                listOf(Action(type = "home", delay = 1000))

            lowerCommand.contains("go back") || lowerCommand == "back" ->
                listOf(Action(type = "back", delay = 1000))

            lowerCommand.contains("recent") ->
                listOf(Action(type = "recents", delay = 1000))

            // Direct app names (without "open")
            lowerCommand == "settings" ->
                listOf(Action(type = "open_app", packageName = "com.android.settings", delay = 2000))

            lowerCommand == "whatsapp" ->
                listOf(Action(type = "open_app", packageName = "com.whatsapp", delay = 2000))

            lowerCommand == "youtube" ->
                listOf(Action(type = "open_app", packageName = "com.google.android.youtube", delay = 2000))

            else -> emptyList()
        }
    }

    // ADD THIS FUNCTION - Fallback handler when local command returns empty
    private fun handleLocalCommandFallback(command: String): List<Action> {
        Log.d(TAG, "Fallback handler for: '$command'")

        val lowerCommand = command.lowercase().trim()

        // Try to extract any recognizable word
        val words = lowerCommand.split(" ")

        // Check each word for known apps
        for (word in words) {
            val packageName = getPackageNameForApp(word)
            if (packageName.isNotEmpty()) {
                Log.d(TAG, "Fallback found: $word -> $packageName")
                return listOf(Action(type = "open_app", packageName = packageName, delay = 2000))
            }
        }

        // Common fallback patterns
        return when {
            lowerCommand.contains("open") -> {
                // If command contains "open" but we couldn't parse app, open settings
                Log.d(TAG, "Command contains 'open', defaulting to settings")
                listOf(Action(type = "open_app", packageName = "com.android.settings", delay = 2000))
            }

            lowerCommand.contains("app") || lowerCommand.contains("application") -> {
                // Generic app command, open settings
                listOf(Action(type = "open_app", packageName = "com.android.settings", delay = 2000))
            }

            // If we can't understand at all, do nothing
            else -> {
                Log.w(TAG, "No fallback action found for: $command")
                emptyList()
            }
        }
    }

    private fun getPackageNameForApp(appName: String): String {
        return when (appName.lowercase()) {
            // Social
            "whatsapp", "whats", "whatsapp" -> "com.whatsapp"
            "youtube", "yt" -> "com.google.android.youtube"
            "instagram", "insta" -> "com.instagram.android"
            "facebook", "fb" -> "com.facebook.katana"

            // Google
            "gmail", "mail", "email" -> "com.google.android.gm"
            "chrome", "browser" -> "com.android.chrome"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "photos", "gallery" -> "com.google.android.apps.photos"

            // System
            "settings", "setting" -> "com.android.settings"
            "camera" -> "com.android.camera2"
            "phone", "dialer", "dial" -> "com.android.dialer"
            "messages", "sms", "text" -> "com.google.android.apps.messaging"
            "contacts" -> "com.android.contacts"
            "clock" -> "com.android.deskclock"
            "calculator" -> "com.android.calculator2"
            "calendar" -> "com.google.android.calendar"
            "files", "file manager" -> "com.android.documentsui"

            // Default
            else -> ""
        }
    }

    private fun parseGeminiResponse(responseBody: String, originalCommand: String): List<Action> {
        try {
            Log.d(TAG, "Parsing Gemini response...")

            // Clean response - remove markdown
            var cleaned = responseBody
                .replace("```json", "")
                .replace("```", "")
                .trim()

            // Find JSON array
            val jsonStart = cleaned.indexOf('[')
            val jsonEnd = cleaned.lastIndexOf(']')

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = cleaned.substring(jsonStart, jsonEnd + 1)
                Log.d(TAG, "Extracted JSON: $jsonString")

                try {
                    val actions = gson.fromJson(jsonString, Array<Action>::class.java)
                    Log.d(TAG, "✓ Successfully parsed ${actions.size} actions")
                    return actions.toList()
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parsing error: ${e.message}")
                    // Try to parse the full response as GeminiResponse
                    return parseFullResponse(responseBody)
                }
            }

            // Try to parse the full response
            return parseFullResponse(responseBody)

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            return emptyList()
        }
    }

    private fun parseFullResponse(responseBody: String): List<Action> {
        try {
            val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
            val text = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""

            if (text.isNotEmpty()) {
                Log.d(TAG, "Direct parse text: ${text.take(100)}...")
                // Try to extract JSON from text
                val textJsonStart = text.indexOf('[')
                val textJsonEnd = text.lastIndexOf(']')

                if (textJsonStart >= 0 && textJsonEnd > textJsonStart) {
                    val textJson = text.substring(textJsonStart, textJsonEnd + 1)
                    val actions = gson.fromJson(textJson, Array<Action>::class.java)
                    return actions.toList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Full parse error: ${e.message}")
        }

        return emptyList()
    }

    // TEST FUNCTION to verify API works
    suspend fun testApiConnection(): String {
        return withContext(Dispatchers.IO) {
            try {
                val testPrompt = "Say 'JARVIS is working' in one word."

                val requestBody = gson.toJson(
                    GeminiRequest(
                        contents = listOf(
                            Content(
                                parts = listOf(
                                    Part(text = testPrompt)
                                )
                            )
                        )
                    )
                )

                val request = Request.Builder()
                    .url(geminiProUrl)
                    .post(requestBody.toRequestBody(jsonMediaType))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                return@withContext if (response.isSuccessful) {
                    val body = response.body?.string()
                    "✓ API Connected Successfully (${response.code})\nResponse: ${body?.take(100)}..."
                } else {
                    "✗ API Failed: ${response.code} - ${response.body?.string()}"
                }
            } catch (e: Exception) {
                return@withContext "✗ Error: ${e.message}"
            }
        }
    }
}