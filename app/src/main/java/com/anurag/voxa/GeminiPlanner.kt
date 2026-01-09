package com.anurag.voxa

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.util.concurrent.TimeUnit

object GeminiPlanner {
    private const val TAG = "GeminiPlanner"

    // API configuration
    private const val GEMINI_API_KEY = "AIzaSyAjAW15X0NUz05esZ0U3Y-VY438n49Mu7o"
    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val GEMINI_FLASH = "gemini-2.5-flash:generateContent"
    private val geminiUrl = "${GEMINI_BASE_URL}${GEMINI_FLASH}?key=$GEMINI_API_KEY"

    // HTTP client with better configuration
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Data classes
    data class GeminiRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig? = null,
        val safetySettings: List<SafetySetting>? = null
    )

    data class Content(
        val parts: List<Part>,
        val role: String? = "user"
    )

    data class Part(
        val text: String
    )

    data class GenerationConfig(
        val temperature: Double = 0.1,
        val topK: Int = 1,
        val topP: Double = 1.0,
        val maxOutputTokens: Int = 1024,
        val responseMimeType: String = "application/json"
    )

    data class SafetySetting(
        val category: String = "HARM_CATEGORY_HARASSMENT",
        val threshold: String = "BLOCK_NONE"
    )

    data class GeminiResponse(
        val candidates: List<Candidate>?,
        val usageMetadata: UsageMetadata? = null,
        val modelVersion: String? = null,
        val responseId: String? = null
    )

    data class Candidate(
        val content: Content?,
        val finishReason: String? = null,
        val index: Int = 0,
        val safetyRatings: List<SafetyRating>? = null
    )

    data class SafetyRating(
        val category: String? = null,
        val probability: String? = null
    )

    data class UsageMetadata(
        val promptTokenCount: Int = 0,
        val candidatesTokenCount: Int = 0,
        val totalTokenCount: Int = 0
    )

    // Action data class with all possible fields
    data class Action(
        val type: String,
        val target: String = "",
        val text: String = "",
        val packageName: String = "",
        val url: String = "",
        val x: Int = 0,
        val y: Int = 0,
        val delay: Int = 1000,
        val searchEngine: String = "google",
        val count: Int = 1,
        val direction: String = "down"
    )

    // App package database
    private val appPackageDatabase = mapOf(
        // Google Apps
        "google" to "com.google.android.googlequicksearchbox",
        "chrome" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "calendar" to "com.google.android.calendar",
        "keep" to "com.google.android.keep",
        "translate" to "com.google.android.apps.translate",
        "meet" to "com.google.android.apps.meetings",
        "duo" to "com.google.android.apps.tachyon",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",

        // Social Media
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "twitter" to "com.twitter.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "snapchat" to "com.snapchat.android",
        "reddit" to "com.reddit.frontpage",
        "linkedin" to "com.linkedin.android",
        "telegram" to "org.telegram.messenger",
        "discord" to "com.discord",
        "signal" to "org.thoughtcrime.securesms",

        // Communication
        "messages" to "com.google.android.apps.messaging",
        "phone" to "com.android.dialer",
        "contacts" to "com.android.contacts",
        "email" to "com.google.android.gm",

        // Entertainment
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "prime" to "com.amazon.avod.thirdpartyclient",
        "hotstar" to "in.startv.hotstar",
        "sonyliv" to "com.sonyliv",
        "zee5" to "com.graymatrix.did",
        "mxplayer" to "com.mxtech.videoplayer.ad",

        // Shopping & Food
        "amazon" to "com.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "myntra" to "com.myntra.android",
        "zomato" to "com.application.zomato",
        "swiggy" to "in.swiggy.android",
        "bigbasket" to "com.bigbasket",

        // Finance & Payments
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "bhim" to "in.org.npci.upiapp",
        "bank" to "com.android.bank",

        // Travel
        "makemytrip" to "com.makemytrip",
        "goibibo" to "com.goibibo",
        "irctc" to "cris.org.in.prs.ima",
        "uber" to "com.ubercab",
        "ola" to "com.olacabs.customer",

        // Indian Government
        "digilocker" to "in.gov.digitallocker",
        "aadhaar" to "in.gov.uidai",
        "umang" to "in.umang.gov",

        // System Apps
        "settings" to "com.android.settings",
        "camera" to "com.android.camera2",
        "gallery" to "com.android.gallery3d",
        "files" to "com.android.documentsui",
        "notes" to "com.google.android.keep",
        "music" to "com.android.music",
        "weather" to "com.android.weather",
        "clock" to "com.android.deskclock",

        // Browsers
        "browser" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "edge" to "com.microsoft.emmx",
        "opera" to "com.opera.browser",

        // Office & Productivity
        "word" to "com.microsoft.office.word",
        "excel" to "com.microsoft.office.excel",
        "powerpoint" to "com.microsoft.office.powerpoint",
        "pdf" to "com.adobe.reader",
        "wps" to "cn.wps.moffice_eng"
    )

    // Action type definitions
    private sealed class ActionType(val value: String) {
        object OPEN_APP : ActionType("open_app")
        object CLICK : ActionType("click")
        object TYPE : ActionType("type")
        object SEND : ActionType("send")
        object BACK : ActionType("back")
        object HOME : ActionType("home")
        object RECENTS : ActionType("recents")
        object WAIT : ActionType("wait")
        object SWIPE : ActionType("swipe")
        object SCROLL : ActionType("scroll")
        object SEARCH : ActionType("search")
        object OPEN_URL : ActionType("open_url")
        object TAKE_SCREENSHOT : ActionType("screenshot")
        object VOLUME_UP : ActionType("volume_up")
        object VOLUME_DOWN : ActionType("volume_down")
        object BRIGHTNESS_UP : ActionType("brightness_up")
        object BRIGHTNESS_DOWN : ActionType("brightness_down")
        object PLAY_PAUSE : ActionType("play_pause")
        object NEXT : ActionType("next")
        object PREVIOUS : ActionType("previous")
    }

    suspend fun planActions(command: String): List<Action> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Planning actions for: '$command'")

            // Step 1: Pre-process command
            val processedCommand = preProcessCommand(command)

            // Step 2: Check for quick actions (no API needed)
            val quickActions = getQuickActions(processedCommand)
            if (quickActions.isNotEmpty()) {
                Log.d(TAG, "✅ Using quick actions: ${quickActions.size}")
                return@withContext quickActions
            }

            // Step 3: Try API with improved prompt
            Log.d(TAG, "🌐 Calling Gemini API...")
            val apiActions = tryGeminiApi(processedCommand)
            if (apiActions.isNotEmpty()) {
                Log.d(TAG, "✅ API generated ${apiActions.size} actions")
                return@withContext apiActions
            }

            // Step 4: Fallback to rule-based actions
            Log.d(TAG, "🔄 API failed, using rule-based fallback")
            val fallbackActions = generateRuleBasedActions(processedCommand)
            if (fallbackActions.isNotEmpty()) {
                Log.d(TAG, "✅ Fallback generated ${fallbackActions.size} actions")
                return@withContext fallbackActions
            }

            // Step 5: Ultimate fallback
            Log.w(TAG, "⚠️ All methods failed, using ultimate fallback")
            return@withContext ultimateFallback(processedCommand)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in planActions: ${e.message}", e)
            return@withContext listOf(
                Action(type = "open_app", packageName = "com.android.settings", delay = 2000)
            )
        }
    }

    private fun preProcessCommand(command: String): String {
        var processed = command.trim()

        // Remove assistant name if present
        processed = processed.replace(Regex("(?i)(hey|ok|hi|hello)\\s+(jarvis|assistant|google|alexa|siri)\\s*"), "")

        // Normalize spaces
        processed = processed.replace(Regex("\\s+"), " ")

        // Convert to lowercase for easier processing
        processed = processed.lowercase()

        Log.d(TAG, "Pre-processed command: '$processed'")
        return processed
    }

    private fun getQuickActions(command: String): List<Action> {
        return when {
            // Basic navigation
            command.matches(Regex("""(go\s+)?home""")) ->
                listOf(Action(type = "home", delay = 500))

            command.matches(Regex("""(go\s+)?back""")) ->
                listOf(Action(type = "back", delay = 500))

            command.matches(Regex("""show\s+(recent|open)\s+apps""")) ->
                listOf(Action(type = "recents", delay = 500))

            // Volume control
            command.matches(Regex("""(increase|turn\s+up)\s+volume""")) ->
                listOf(Action(type = "volume_up", delay = 200))

            command.matches(Regex("""(decrease|turn\s+down)\s+volume""")) ->
                listOf(Action(type = "volume_down", delay = 200))

            command.matches(Regex("""mute\s+volume""")) ->
                listOf(Action(type = "volume_down", count = 15, delay = 100))

            // Brightness control
            command.matches(Regex("""(increase|turn\s+up)\s+brightness""")) ->
                listOf(Action(type = "brightness_up", delay = 200))

            command.matches(Regex("""(decrease|turn\s+down)\s+brightness""")) ->
                listOf(Action(type = "brightness_down", delay = 200))

            // Media control
            command.matches(Regex("""(play|pause)\s+(music|video)""")) ->
                listOf(Action(type = "play_pause", delay = 500))

            command.matches(Regex("""next\s+(song|track|video)""")) ->
                listOf(Action(type = "next", delay = 500))

            command.matches(Regex("""previous\s+(song|track|video)""")) ->
                listOf(Action(type = "previous", delay = 500))

            // Simple app openings
            command.startsWith("open ") -> {
                val appName = command.substring(5).trim()
                val packageName = findPackageName(appName)
                if (packageName.isNotEmpty()) {
                    listOf(Action(type = "open_app", packageName = packageName, delay = 2000))
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private suspend fun tryGeminiApi(command: String): List<Action> = withContext(Dispatchers.IO) {
        try {
            val prompt = createSmartPrompt(command)
            Log.d(TAG, "📤 Sending to Gemini API...")

            val request = Request.Builder()
                .url(geminiUrl)
                .post(createRequestBody(prompt))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API request failed: ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "📥 Raw response: ${responseBody.take(500)}...")

            val actions = parseApiResponse(responseBody)
            if (actions.isNotEmpty()) {
                Log.d(TAG, "✅ Successfully parsed ${actions.size} actions from API")
                return@withContext actions
            }

            return@withContext emptyList()

        } catch (e: Exception) {
            Log.e(TAG, "❌ API call error: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    private fun createSmartPrompt(command: String): String {
        return """
            You are Jarvis, an advanced Android automation assistant. Convert the user's natural language command into a sequence of JSON actions.
            
            IMPORTANT: Return ONLY a valid JSON array of action objects. No explanations, no markdown, no additional text.
            
            COMMAND TO PROCESS: "$command"
            
            AVAILABLE ACTION TYPES:
            1. open_app - Open an application. Requires "packageName".
            2. click - Click on UI element. Requires "target" (text on button/link).
            3. type - Type text. Requires "text".
            4. send - Press Enter/Submit key.
            5. search - Perform a search. Requires "text" (search query) and optionally "searchEngine".
            6. open_url - Open a URL. Requires "url".
            7. back - Press back button.
            8. home - Press home button.
            9. recents - Show recent apps.
            10. wait - Wait for specified milliseconds. Requires "delay".
            11. swipe - Swipe gesture. Requires "direction" (up/down/left/right).
            12. scroll - Scroll. Requires "direction" (up/down).
            
            COMMON PACKAGE NAMES:
            ${appPackageDatabase.entries.take(20).joinToString("\n") { "- ${it.key}: ${it.value}" }}
            
            ACTION SEQUENCE EXAMPLES:
            
            Example 1: "open google and search for weather today"
            [
              {"type": "open_app", "packageName": "com.google.android.googlequicksearchbox", "delay": 2000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Search", "delay": 500},
              {"type": "wait", "delay": 500},
              {"type": "type", "text": "weather today", "delay": 1000},
              {"type": "send", "delay": 500}
            ]
            
            Example 2: "open whatsapp and message mom saying hi"
            [
              {"type": "open_app", "packageName": "com.whatsapp", "delay": 3000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Chats", "delay": 500},
              {"type": "wait", "delay": 500},
              {"type": "click", "target": "Mom", "delay": 1000},
              {"type": "wait", "delay": 500},
              {"type": "type", "text": "Hi Mom!", "delay": 1000},
              {"type": "send", "delay": 500}
            ]
            
            Example 3: "open youtube, search for music videos and play the first one"
            [
              {"type": "open_app", "packageName": "com.google.android.youtube", "delay": 3000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Search", "delay": 500},
              {"type": "wait", "delay": 500},
              {"type": "type", "text": "music videos", "delay": 1000},
              {"type": "send", "delay": 1000},
              {"type": "wait", "delay": 2000},
              {"type": "click", "target": "First video", "delay": 1000}
            ]
            
            Example 4: "take a screenshot and share it on instagram"
            [
              {"type": "screenshot", "delay": 1000},
              {"type": "wait", "delay": 1000},
              {"type": "open_app", "packageName": "com.instagram.android", "delay": 3000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "New Post", "delay": 1000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Gallery", "delay": 1000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Recent", "delay": 1000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Share", "delay": 1000}
            ]
            
            Example 5: "open chrome and go to google.com"
            [
              {"type": "open_app", "packageName": "com.android.chrome", "delay": 3000},
              {"type": "wait", "delay": 1000},
              {"type": "click", "target": "Address bar", "delay": 500},
              {"type": "wait", "delay": 500},
              {"type": "type", "text": "https://google.com", "delay": 1000},
              {"type": "send", "delay": 1000}
            ]
            
            GENERAL RULES:
            1. ALWAYS include reasonable "delay" between actions (500-3000ms).
            2. Use "wait" actions when needed for UI to load.
            3. For complex searches, break into multiple actions.
            4. If uncertain about package name, use the most common one.
            5. For "search in [app]" commands, open that app first.
            6. Include sufficient wait times after opening apps (2000-3000ms).
            
            NOW, generate actions for: "$command"
            
            RETURN ONLY JSON ARRAY:
        """.trimIndent()
    }

    private fun createRequestBody(prompt: String): okhttp3.RequestBody {
        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(Part(text = prompt)),
                    role = "user"
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.1,
                maxOutputTokens = 2048,
                responseMimeType = "application/json"
            ),
            safetySettings = listOf(
                SafetySetting(category = "HARM_CATEGORY_HARASSMENT", threshold = "BLOCK_NONE"),
                SafetySetting(category = "HARM_CATEGORY_HATE_SPEECH", threshold = "BLOCK_NONE"),
                SafetySetting(category = "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold = "BLOCK_NONE"),
                SafetySetting(category = "HARM_CATEGORY_DANGEROUS_CONTENT", threshold = "BLOCK_NONE")
            )
        )

        return gson.toJson(request).toRequestBody(jsonMediaType)
    }

    private fun parseApiResponse(responseBody: String): List<Action> {
        try {
            // Try to parse as full Gemini response first
            val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
            val text = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return emptyList()

            Log.d(TAG, "📝 Extracted text: ${text.take(200)}...")

            // Extract JSON array from text
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']')

            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonArrayStr = text.substring(jsonStart, jsonEnd + 1)
                Log.d(TAG, "🔧 Extracted JSON: $jsonArrayStr")

                val typeToken = object : TypeToken<List<Action>>() {}.type
                return gson.fromJson(jsonArrayStr, typeToken)
            }

            // Try direct parsing
            try {
                val typeToken = object : TypeToken<List<Action>>() {}.type
                return gson.fromJson(text, typeToken)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "❌ Failed to parse JSON: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing API response: ${e.message}", e)
        }

        return emptyList()
    }

    private fun generateRuleBasedActions(command: String): List<Action> {
        Log.d(TAG, "🤖 Generating rule-based actions for: '$command'")

        val actions = mutableListOf<Action>()

        // Pattern matching for common complex commands
        when {
            // Pattern: "open [app] and [action]"
            Regex("""open\s+(\w+)\s+and\s+(.+)""").matches(command) -> {
                val match = Regex("""open\s+(\w+)\s+and\s+(.+)""").find(command)
                if (match != null) {
                    val appName = match.groupValues[1]
                    val actionText = match.groupValues[2]
                    val packageName = findPackageName(appName)

                    if (packageName.isNotEmpty()) {
                        actions.add(Action(type = "open_app", packageName = packageName, delay = 2500))
                        actions.add(Action(type = "wait", delay = 1000))

                        // Handle the action part
                        when {
                            actionText.contains("search") -> {
                                val searchQuery = extractSearchQuery(actionText)
                                if (searchQuery.isNotEmpty()) {
                                    actions.addAll(generateSearchActions(searchQuery, appName))
                                }
                            }
                            actionText.contains("message") || actionText.contains("text") -> {
                                val message = extractMessage(actionText)
                                if (message.isNotEmpty()) {
                                    actions.addAll(generateMessagingActions(message, appName))
                                }
                            }
                            actionText.contains("call") -> {
                                actions.add(Action(type = "click", target = "Call", delay = 1000))
                            }
                        }
                    }
                }
            }

            // Pattern: "search for [query] in [app]"
            Regex("""search\s+(?:for\s+)?(.+?)\s+in\s+(\w+)""").matches(command) -> {
                val match = Regex("""search\s+(?:for\s+)?(.+?)\s+in\s+(\w+)""").find(command)
                if (match != null) {
                    val searchQuery = match.groupValues[1].trim()
                    val appName = match.groupValues[2]
                    val packageName = findPackageName(appName)

                    if (packageName.isNotEmpty() && searchQuery.isNotEmpty()) {
                        actions.add(Action(type = "open_app", packageName = packageName, delay = 2500))
                        actions.add(Action(type = "wait", delay = 1000))
                        actions.addAll(generateSearchActions(searchQuery, appName))
                    }
                }
            }

            // Pattern: "[app] [contact] [message]"
            Regex("""(\w+)\s+(\w+)\s+(.+)""").matches(command) -> {
                val match = Regex("""(\w+)\s+(\w+)\s+(.+)""").find(command)
                if (match != null) {
                    val appName = match.groupValues[1]
                    val contact = match.groupValues[2]
                    val message = match.groupValues[3]
                    val packageName = findPackageName(appName)

                    if (packageName.isNotEmpty() && isMessagingApp(appName)) {
                        actions.add(Action(type = "open_app", packageName = packageName, delay = 3000))
                        actions.add(Action(type = "wait", delay = 1000))
                        actions.add(Action(type = "click", target = "Search", delay = 500))
                        actions.add(Action(type = "wait", delay = 500))
                        actions.add(Action(type = "type", text = contact, delay = 1000))
                        actions.add(Action(type = "wait", delay = 500))
                        actions.add(Action(type = "click", target = contact, delay = 1000))
                        actions.add(Action(type = "wait", delay = 500))
                        actions.add(Action(type = "type", text = message, delay = 1000))
                        actions.add(Action(type = "send", delay = 500))
                    }
                }
            }

            // Simple app open
            command.startsWith("open ") -> {
                val appName = command.substring(5).trim()
                val packageName = findPackageName(appName)
                if (packageName.isNotEmpty()) {
                    actions.add(Action(type = "open_app", packageName = packageName, delay = 2000))
                }
            }

            // Web search
            Regex("""(?:search|find)\s+(?:for\s+)?(.+)""").matches(command) -> {
                val match = Regex("""(?:search|find)\s+(?:for\s+)?(.+)""").find(command)
                if (match != null) {
                    val searchQuery = match.groupValues[1].trim()
                    actions.add(Action(type = "open_app", packageName = "com.google.android.googlequicksearchbox", delay = 2500))
                    actions.add(Action(type = "wait", delay = 1000))
                    actions.addAll(generateSearchActions(searchQuery, "google"))
                }
            }
        }

        return actions
    }

    private fun generateSearchActions(query: String, appName: String): List<Action> {
        val actions = mutableListOf<Action>()

        when (appName.lowercase()) {
            "google", "chrome" -> {
                actions.add(Action(type = "click", target = "Search", delay = 500))
                actions.add(Action(type = "wait", delay = 500))
                actions.add(Action(type = "type", text = query, delay = 1000))
                actions.add(Action(type = "send", delay = 500))
            }
            "youtube" -> {
                actions.add(Action(type = "click", target = "Search", delay = 500))
                actions.add(Action(type = "wait", delay = 500))
                actions.add(Action(type = "type", text = query, delay = 1000))
                actions.add(Action(type = "send", delay = 1000))
                actions.add(Action(type = "wait", delay = 2000))
                actions.add(Action(type = "click", target = "First video", delay = 1000))
            }
            else -> {
                actions.add(Action(type = "click", target = "Search", delay = 500))
                actions.add(Action(type = "wait", delay = 500))
                actions.add(Action(type = "type", text = query, delay = 1000))
                actions.add(Action(type = "send", delay = 500))
            }
        }

        return actions
    }

    private fun generateMessagingActions(message: String, appName: String): List<Action> {
        val actions = mutableListOf<Action>()

        actions.add(Action(type = "click", target = "New chat", delay = 1000))
        actions.add(Action(type = "wait", delay = 500))
        actions.add(Action(type = "type", text = message, delay = 1500))
        actions.add(Action(type = "send", delay = 500))

        return actions
    }

    private fun ultimateFallback(command: String): List<Action> {
        Log.w(TAG, "🚨 Using ultimate fallback for: '$command'")

        return when {
            command.contains("search") || command.contains("find") -> {
                val query = extractSearchQuery(command)
                listOf(
                    Action(type = "open_app", packageName = "com.google.android.googlequicksearchbox", delay = 2500),
                    Action(type = "wait", delay = 1000),
                    Action(type = "click", target = "Search", delay = 500),
                    Action(type = "wait", delay = 500),
                    Action(type = "type", text = if (query.isNotEmpty()) query else "search", delay = 1000),
                    Action(type = "send", delay = 500)
                )
            }
            command.contains("message") || command.contains("text") || command.contains("whatsapp") -> {
                listOf(
                    Action(type = "open_app", packageName = "com.whatsapp", delay = 3000),
                    Action(type = "wait", delay = 1000),
                    Action(type = "click", target = "Chats", delay = 500)
                )
            }
            command.contains("youtube") || command.contains("video") -> {
                listOf(
                    Action(type = "open_app", packageName = "com.google.android.youtube", delay = 3000)
                )
            }
            command.contains("music") || command.contains("song") -> {
                listOf(
                    Action(type = "open_app", packageName = "com.spotify.music", delay = 3000)
                )
            }
            else -> {
                listOf(
                    Action(type = "open_app", packageName = "com.android.settings", delay = 2000)
                )
            }
        }
    }

    // Helper functions
    private fun findPackageName(appName: String): String {
        val normalizedAppName = appName.lowercase().trim()

        // Direct match
        appPackageDatabase[normalizedAppName]?.let { return it }

        // Partial match
        for ((key, value) in appPackageDatabase) {
            if (normalizedAppName.contains(key) || key.contains(normalizedAppName)) {
                return value
            }
        }

        // Common patterns
        return when {
            normalizedAppName.contains("google") -> "com.google.android.googlequicksearchbox"
            normalizedAppName.contains("browser") -> "com.android.chrome"
            normalizedAppName.contains("mail") -> "com.google.android.gm"
            normalizedAppName.contains("map") -> "com.google.android.apps.maps"
            normalizedAppName.contains("photo") -> "com.google.android.apps.photos"
            normalizedAppName.contains("video") -> "com.google.android.youtube"
            normalizedAppName.contains("music") -> "com.spotify.music"
            normalizedAppName.contains("shop") -> "com.amazon.mShop.android.shopping"
            else -> ""
        }
    }

    private fun extractSearchQuery(text: String): String {
        val patterns = listOf(
            Regex("""search\s+(?:for\s+)?(.+)"""),
            Regex("""find\s+(?:me\s+)?(.+)"""),
            Regex("""look\s+up\s+(.+)"""),
            Regex("""google\s+(.+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text.lowercase())
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        // If no pattern matches, try to extract after "for" or the last part
        if (text.contains(" for ")) {
            return text.substringAfter(" for ").trim()
        }

        return text
    }

    private fun extractMessage(text: String): String {
        val patterns = listOf(
            Regex("""message\s+(?:saying\s+)?(.+)"""),
            Regex("""text\s+(?:saying\s+)?(.+)"""),
            Regex("""say\s+(.+)"""),
            Regex("""send\s+(?:a\s+)?message\s+(?:saying\s+)?(.+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text.lowercase())
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        // Extract text after certain keywords
        val keywords = listOf("message", "text", "say", "send")
        for (keyword in keywords) {
            if (text.contains(keyword)) {
                val parts = text.split(keyword)
                if (parts.size > 1) {
                    return parts[1].trim()
                }
            }
        }

        return ""
    }

    private fun isMessagingApp(appName: String): Boolean {
        val messagingApps = listOf("whatsapp", "messenger", "telegram", "signal", "messages", "sms")
        return messagingApps.any { appName.contains(it, ignoreCase = true) }
    }

    // Test functions
    suspend fun testComplexCommand(command: String = "open google and search for hello world"): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🧪 Testing complex command: '$command'")
                val actions = planActions(command)

                return@withContext if (actions.isNotEmpty()) {
                    val json = gson.toJson(actions)
                    """
                    ✅ TEST SUCCESSFUL!
                    
                    Command: "$command"
                    Actions Generated: ${actions.size}
                    
                    Action Sequence:
                    ${actions.joinToString("\n") { "  • ${it.type}${if (it.packageName.isNotEmpty()) " -> ${it.packageName}" else ""}${if (it.text.isNotEmpty()) " -> '${it.text}'" else ""}" }}
                    
                    Full JSON:
                    $json
                    """.trimIndent()
                } else {
                    "❌ TEST FAILED: No actions generated for command: '$command'"
                }
            } catch (e: Exception) {
                return@withContext "❌ TEST ERROR: ${e.message}"
            }
        }
    }

    suspend fun testApi(): String {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(geminiUrl)
                    .post(createRequestBody("Say only 'API_TEST_SUCCESS'"))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                return@withContext if (response.isSuccessful) {
                    "✅ API Connection Successful!\nStatus: ${response.code}\nModel: gemini-2.5-flash"
                } else {
                    "❌ API Connection Failed!\nStatus: ${response.code}\nError: ${response.body?.string()?.take(200)}"
                }
            } catch (e: Exception) {
                return@withContext "❌ API Test Error: ${e.message}"
            }
        }
    }

    fun getAppSuggestions(): List<String> {
        return appPackageDatabase.keys.sorted()
    }
}