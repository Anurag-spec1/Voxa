package com.anurag.voxa

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import android.util.Log
import org.json.JSONObject

object GeminiPlanner {

    private const val TAG = "GeminiPlanner"
    private const val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY" // Replace with your key
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"

    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    data class GeminiRequest(
        val contents: List<Content>
    )

    data class Content(
        val parts: List<Part>
    )

    data class Part(
        val text: String
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
            val systemPrompt = """
                You are Jarvis - an Android automation AI.
                Convert user commands into executable UI actions.
                
                Available Actions:
                1. open_app - {packageName: "com.app.package"}
                2. click - {target: "Button text"} OR {x: 100, y: 200}
                3. type - {text: "text to type"}
                4. send - Send message (Enter key)
                5. back - Go back
                6. home - Go to home
                7. recents - Show recent apps
                8. scroll - {direction: "up/down"}
                9. wait - {delay: 1000} (milliseconds)
                10. search - {text: "search query"}
                11. launch_url - {url: "https://..."}
                12. screenshot - Take screenshot
                
                Output ONLY valid JSON. No explanations.
                
                Example:
                User: "open whatsapp and message John hello there"
                Output: {
                  "actions": [
                    {"type": "open_app", "packageName": "com.whatsapp"},
                    {"type": "wait", "delay": 2000},
                    {"type": "click", "target": "John"},
                    {"type": "wait", "delay": 1000},
                    {"type": "type", "text": "hello there"},
                    {"type": "send"}
                  ]
                }
                
                Current Command: "$command"
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

            val request = Request.Builder()
                .url("$GEMINI_URL?key=$GEMINI_API_KEY")
                .post(requestBody.toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")
                .build()

            val response: Response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "API call failed: ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string()
            Log.d(TAG, "Gemini Response: $responseBody")

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val part = parts?.optJSONObject(0)
            val text = part?.optString("text") ?: ""

            // Extract JSON from response
            val jsonStart = text.indexOf("{")
            val jsonEnd = text.lastIndexOf("}") + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = text.substring(jsonStart, jsonEnd)
                val actionPlan = gson.fromJson(jsonString, ActionPlan::class.java)

                // Store in memory for context
                MemoryEngine.storeActions(actionPlan.actions)

                return@withContext actionPlan.actions
            }

            return@withContext emptyList()

        } catch (e: Exception) {
            Log.e(TAG, "Error planning actions: ${e.message}")
            return@withContext emptyList()
        }
    }
}