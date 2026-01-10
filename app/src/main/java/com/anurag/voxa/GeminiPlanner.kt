package com.anurag.voxa

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object GeminiPlanner {
    private const val TAG = "GeminiPlanner"

    // API configuration
    private const val GEMINI_API_KEY = "AIzaSyCKErHRIdZKGxXH8esG3JU1OiqW-J__IuI"
    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val GEMINI_PRO_MODEL = "gemini-2.0-flash-exp:generateContent"
    private val geminiUrl = "${GEMINI_BASE_URL}${GEMINI_PRO_MODEL}?key=$GEMINI_API_KEY"

    // HTTP client
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Jarvis-Android-Assistant/1.0")
                .build()
            chain.proceed(request)
        }
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Data classes (keep as is)
    data class GeminiRequest(val contents: List<Content>, val generationConfig: GenerationConfig? = null, val safetySettings: List<SafetySetting>? = null, val systemInstruction: SystemInstruction? = null)
    data class Content(val parts: List<Part>, val role: String? = "user")
    data class Part(val text: String, val inlineData: InlineData? = null)
    data class InlineData(val mimeType: String, val data: String)
    data class GenerationConfig(val temperature: Double = 0.2, val topK: Int = 40, val topP: Double = 0.95, val maxOutputTokens: Int = 4096, val responseMimeType: String = "application/json", val responseSchema: ResponseSchema? = null)
    data class ResponseSchema(val type: String = "ARRAY", val items: SchemaItem = SchemaItem())
    data class SchemaItem(val type: String = "OBJECT", val properties: Map<String, PropertySchema> = mapOf(
        "type" to PropertySchema(type = "STRING", description = "Action type"),
        "target" to PropertySchema(type = "STRING", description = "UI element text to click"),
        "text" to PropertySchema(type = "STRING", description = "Text to type or search"),
        "packageName" to PropertySchema(type = "STRING", description = "Android package name"),
        "url" to PropertySchema(type = "STRING", description = "URL to open"),
        "x" to PropertySchema(type = "INTEGER", description = "X coordinate for click"),
        "y" to PropertySchema(type = "INTEGER", description = "Y coordinate for click"),
        "delay" to PropertySchema(type = "INTEGER", description = "Delay in milliseconds"),
        "searchEngine" to PropertySchema(type = "STRING", description = "Search engine to use"),
        "count" to PropertySchema(type = "INTEGER", description = "Number of times to repeat"),
        "direction" to PropertySchema(type = "STRING", description = "Direction for swipe/scroll"),
        "contactName" to PropertySchema(type = "STRING", description = "Contact name"),
        "phoneNumber" to PropertySchema(type = "STRING", description = "Phone number"),
        "appName" to PropertySchema(type = "STRING", description = "App display name")
    ))
    data class PropertySchema(val type: String, val description: String)
    data class SystemInstruction(val parts: List<Part>)
    data class SafetySetting(val category: String = "HARM_CATEGORY_HARASSMENT", val threshold: String = "BLOCK_NONE")
    data class GeminiResponse(val candidates: List<Candidate>?, val usageMetadata: UsageMetadata? = null, val modelVersion: String? = null, val responseId: String? = null, val promptFeedback: PromptFeedback? = null)
    data class Candidate(val content: Content?, val finishReason: String? = null, val index: Int = 0, val safetyRatings: List<SafetyRating>? = null)
    data class SafetyRating(val category: String? = null, val probability: String? = null)
    data class UsageMetadata(val promptTokenCount: Int = 0, val candidatesTokenCount: Int = 0, val totalTokenCount: Int = 0)
    data class PromptFeedback(val blockReason: String? = null, val safetyRatings: List<SafetyRating>? = null)

    // Enhanced Action data class
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
        val direction: String = "down",
        val contactName: String = "",
        val phoneNumber: String = "",
        val appName: String = "",
        val confidence: Double = 1.0,
        val requiresConfirmation: Boolean = false
    )

    // Enhanced App package database with MULTIPLE dialer package names
    private val appPackageDatabase = mapOf(
        // Communication Apps
        "whatsapp" to AppInfo("com.whatsapp", listOf("whatsapp", "wa", "whats app")),
        "telegram" to AppInfo("org.telegram.messenger", listOf("telegram", "tg")),
        "signal" to AppInfo("org.thoughtcrime.securesms", listOf("signal")),
        "messenger" to AppInfo("com.facebook.orca", listOf("messenger", "fb messenger")),
        "discord" to AppInfo("com.discord", listOf("discord")),

        // Google Apps
        "google" to AppInfo("com.google.android.googlequicksearchbox", listOf("google", "assistant")),
        "chrome" to AppInfo("com.android.chrome", listOf("chrome", "browser")),
        "gmail" to AppInfo("com.google.android.gm", listOf("gmail", "email")),
        "youtube" to AppInfo("com.google.android.youtube", listOf("youtube", "yt", "video")),
        "maps" to AppInfo("com.google.android.apps.maps", listOf("maps", "google maps")),
        "photos" to AppInfo("com.google.android.apps.photos", listOf("photos", "gallery")),
        "drive" to AppInfo("com.google.android.apps.docs", listOf("drive", "google drive")),

        // Social Media
        "instagram" to AppInfo("com.instagram.android", listOf("instagram", "insta")),
        "facebook" to AppInfo("com.facebook.katana", listOf("facebook", "fb")),
        "twitter" to AppInfo("com.twitter.android", listOf("twitter", "x")),
        "tiktok" to AppInfo("com.zhiliaoapp.musically", listOf("tiktok")),
        "reddit" to AppInfo("com.reddit.frontpage", listOf("reddit")),
        "linkedin" to AppInfo("com.linkedin.android", listOf("linkedin")),

        // System & Utilities
        "settings" to AppInfo("com.android.settings", listOf("settings")),
        "camera" to AppInfo("com.android.camera2", listOf("camera")),

        // MULTIPLE DIALER PACKAGE NAMES - Try these in order
        "phone" to AppInfo("com.android.dialer", listOf("phone", "dialer", "call")),
        "dialer" to AppInfo("com.google.android.dialer", listOf("phone", "dialer", "call")),
        "contacts" to AppInfo("com.android.contacts", listOf("contacts")),
        "messages" to AppInfo("com.google.android.apps.messaging", listOf("messages", "sms")),

        // Music & Media
        "spotify" to AppInfo("com.spotify.music", listOf("spotify", "music")),
        "netflix" to AppInfo("com.netflix.mediaclient", listOf("netflix")),

        // Shopping
        "amazon" to AppInfo("com.amazon.mShop.android.shopping", listOf("amazon")),
        "flipkart" to AppInfo("com.flipkart.android", listOf("flipkart")),

        // Finance
        "paytm" to AppInfo("net.one97.paytm", listOf("paytm")),
        "phonepe" to AppInfo("com.phonepe.app", listOf("phonepe")),
        "gpay" to AppInfo("com.google.android.apps.nbu.paisa.user", listOf("gpay", "google pay"))
    )

    // Additional dialer package names for different manufacturers
    private val dialerPackages = listOf(
        "com.android.dialer",  // Stock Android
        "com.google.android.dialer",  // Google Dialer
        "com.samsung.android.dialer",  // Samsung
        "com.oneplus.dialer",  // OnePlus
        "com.xiaomi.dialer",  // Xiaomi
        "com.miui.dialer",  // MIUI
        "com.android.phone",  // Some devices
        "com.android.incallui",  // In-call UI
        "com.android.contacts",  // Sometimes dialer is in contacts
        "com.google.android.contacts"  // Google Contacts
    )

    data class AppInfo(val packageName: String, val aliases: List<String>)

    // Action type constants
    object ActionTypes {
        const val OPEN_APP = "open_app"
        const val CLICK = "click"
        const val TYPE = "type"
        const val SEND = "send"
        const val BACK = "back"
        const val HOME = "home"
        const val RECENTS = "recents"
        const val WAIT = "wait"
        const val SWIPE = "swipe"
        const val SCROLL = "scroll"
        const val SEARCH = "search"
        const val OPEN_URL = "open_url"
        const val CALL = "call"
        const val MESSAGE = "message"
        const val OPEN_CONTACT = "open_contact"
        const val DIAL = "dial"
        const val SCREENSHOT = "screenshot"
        const val VOLUME_UP = "volume_up"
        const val VOLUME_DOWN = "volume_down"
        const val BRIGHTNESS_UP = "brightness_up"
        const val BRIGHTNESS_DOWN = "brightness_down"
        const val PLAY_PAUSE = "play_pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val LAUNCH_INTENT = "launch_intent"
        const val OPEN_DIALER = "open_dialer"  // New action type
    }

    // Context for contact resolution (must be set from Activity/Service)
    private var appContext: Context? = null

    fun setContext(context: Context) {
        this.appContext = context.applicationContext
    }

    suspend fun planActions(command: String, context: Context? = null): List<Action> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Planning actions for: '$command'")

            if (context != null) {
                setContext(context)
            }

            // Step 1: Enhanced pre-processing
            val processedCommand = preProcessCommand(command)

            // Step 2: Check for direct system actions (no API needed)
            val directActions = getDirectSystemActions(processedCommand)
            if (directActions.isNotEmpty()) {
                Log.d(TAG, "✅ Using direct system actions: ${directActions.size}")
                return@withContext directActions
            }

            // Step 3: Check for contact-related actions
            val contactActions = handleContactActions(processedCommand)
            if (contactActions.isNotEmpty()) {
                Log.d(TAG, "✅ Using contact actions: ${contactActions.size}")
                return@withContext contactActions
            }

            // Step 4: Hotfix for simple commands
            val hotfixActions = handleSimpleCommandsImmediately(processedCommand)
            if (hotfixActions.isNotEmpty()) {
                Log.d(TAG, "🔥 Using hotfix actions for simple command")
                return@withContext hotfixActions
            }

            // Step 5: Enhanced rule-based fallback BEFORE API (since API is rate-limited)
            Log.d(TAG, "🤖 Trying enhanced rule-based fallback before API...")
            val fallbackActions = generateEnhancedRuleBasedActions(processedCommand)
            if (fallbackActions.isNotEmpty()) {
                Log.d(TAG, "✅ Fallback generated ${fallbackActions.size} actions")
                return@withContext validateAndOptimizeActions(fallbackActions)
            }

            // Step 6: Try API with improved prompt and schema (only if other methods fail)
            Log.d(TAG, "🌐 Calling Gemini API with structured output...")
            val apiActions = tryStructuredGeminiApi(processedCommand)
            if (apiActions.isNotEmpty()) {
                Log.d(TAG, "✅ API generated ${apiActions.size} actions")
                return@withContext validateAndOptimizeActions(apiActions)
            }

            // Step 7: Smart ultimate fallback
            Log.w(TAG, "⚠️ All methods failed, using smart ultimate fallback")
            return@withContext smartUltimateFallback(processedCommand)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in planActions: ${e.message}", e)
            return@withContext listOf(
                Action(
                    type = ActionTypes.WAIT,
                    delay = 1000,
                    text = "Sorry, I couldn't process that command"
                )
            )
        }
    }

    private fun handleSimpleCommandsImmediately(command: String): List<Action> {
        // Check for VERY simple commands that should NEVER hit API
        return when {
            // Phone calls - IMPROVED: Searches for contact
            command.matches(Regex("""(?i)call\s+\w+""")) -> {
                Log.d(TAG, "🔥 Hotfix: Handling call command")
                val contact = extractContactName(command)
                val dialerPackage = findDialerPackage()

                val actions = mutableListOf<Action>()
                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1000))

                if (contact.isNotEmpty()) {
                    // Try to search for the contact
                    actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                    actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                    actions.add(Action(type = ActionTypes.TYPE, text = contact, delay = 1000))
                    actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                    // Try to click on the contact
                    actions.add(Action(type = ActionTypes.CLICK, target = contact, delay = 1000))
                }

                actions
            }

            // Open commands
            command.matches(Regex("""(?i)open\s+\w+""")) -> {
                Log.d(TAG, "🔥 Hotfix: Handling open command")
                val appName = command.substringAfter("open").trim()
                val packageName = findPackageName(appName)
                if (packageName.isNotEmpty()) {
                    listOf(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 2000))
                } else {
                    emptyList()
                }
            }

            // Search commands
            command.matches(Regex("""(?i)(?:search|google)\s+.+""")) -> {
                Log.d(TAG, "🔥 Hotfix: Handling search command")
                val query = extractSearchQuery(command)
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.googlequicksearchbox", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.TYPE, text = query, delay = 1500),
                    Action(type = ActionTypes.SEND, delay = 500)
                )
            }

            // Wi-Fi/Bluetooth - IMPROVED: Actually opens Wi-Fi/Bluetooth section
            command.matches(Regex("""(?i)(?:turn\s+)?(on|off|enable|disable|open)\s+(?:wi.?fi|wifi|bluetooth)""")) -> {
                Log.d(TAG, "🔥 Hotfix: Handling Wi-Fi/Bluetooth command")
                val isWifi = command.contains("wifi", ignoreCase = true) || command.contains("wi-fi", ignoreCase = true)
                val target = if (isWifi) "Wi-Fi" else "Bluetooth"

                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.android.settings", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = if (isWifi) "Network & internet" else "Connected devices", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = target, delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500)
                )
            }

            // Volume commands - NEW
            command.matches(Regex("""(?i)^volume$""")) -> {
                listOf(Action(type = ActionTypes.VOLUME_UP, count = 3, delay = 200))
            }

            command.matches(Regex("""(?i)volume\s+(up|increase)""")) -> {
                listOf(Action(type = ActionTypes.VOLUME_UP, count = 3, delay = 200))
            }

            command.matches(Regex("""(?i)volume\s+(down|decrease|lower)""")) -> {
                listOf(Action(type = ActionTypes.VOLUME_DOWN, count = 3, delay = 200))
            }

            command.matches(Regex("""(?i)mute""")) -> {
                listOf(Action(type = ActionTypes.VOLUME_DOWN, count = 15, delay = 100))
            }

            command.matches(Regex("""(?i)unmute""")) -> {
                listOf(Action(type = ActionTypes.VOLUME_UP, count = 5, delay = 200))
            }

            else -> emptyList()
        }
    }

    private fun preProcessCommand(command: String): String {
        var processed = command.trim()

        // Remove wake words and assistant names
        processed = processed.replace(Regex("(?i)(hey|ok|hi|hello|please)\\s+(jarvis|assistant|google|alexa|siri|cortana)\\s*"), "")

        // Remove common filler words
        processed = processed.replace(Regex("(?i)\\b(can you|could you|would you|will you|i want to|i need to)\\b"), "")

        // Normalize contact references
        processed = processed.replace(Regex("(?i)\\b(contact|person|number)\\s+of\\s+"), "")

        // Fix common typos
        val typos = mapOf(
            "wattsapp" to "whatsapp",
            "whatsup" to "whatsapp",
            "insta" to "instagram",
            "yt" to "youtube",
            "fb" to "facebook",
            "msg" to "message",
            "msgs" to "messages",
            "txt" to "text",
            "plz" to "please",
            "u" to "you",
            "ur" to "your",
            "pm" to "prime minister"
        )

        typos.forEach { (wrong, correct) ->
            processed = processed.replace(Regex("\\b$wrong\\b", RegexOption.IGNORE_CASE), correct)
        }

        // Normalize spaces and trim
        processed = processed.replace(Regex("\\s+"), " ").trim()

        Log.d(TAG, "Pre-processed command: '$processed'")
        return processed
    }

    private fun getDirectSystemActions(command: String): List<Action> {
        return when {
            // Wi-Fi/BT control
            command.matches(Regex("""(?i)(turn\s+)?(on|enable)\s+(wi.?fi|wifi)""")) ->
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.android.settings", appName = "Settings", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Network & internet", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Wi-Fi", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Use Wi-Fi", delay = 1000)
                )

            command.matches(Regex("""(?i)(turn\s+)?(off|disable)\s+(wi.?fi|wifi)""")) ->
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.android.settings", appName = "Settings", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Network & internet", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Wi-Fi", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Use Wi-Fi", delay = 1000)
                )

            command.matches(Regex("""(?i)(turn\s+)?(on|enable)\s+bluetooth""")) ->
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.android.settings", appName = "Settings", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Connected devices", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Bluetooth", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Use Bluetooth", delay = 1000)
                )

            command.matches(Regex("""(?i)(turn\s+)?(off|disable)\s+bluetooth""")) ->
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.android.settings", appName = "Settings", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Connected devices", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Bluetooth", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = "Use Bluetooth", delay = 1000)
                )

            // Basic navigation
            command.matches(Regex("""(?i)(go\s+)?(home|house)""")) ->
                listOf(Action(type = ActionTypes.HOME, delay = 500))

            command.matches(Regex("""(?i)(go\s+)?back""")) ->
                listOf(Action(type = ActionTypes.BACK, delay = 500))

            command.matches(Regex("""(?i)show\s+(recent|open|running)\s+apps""")) ->
                listOf(Action(type = ActionTypes.RECENTS, delay = 800))

            command.matches(Regex("""(?i)(close|hide)\s+(all\s+)?apps""")) ->
                listOf(Action(type = ActionTypes.HOME, delay = 300))

            // Volume control
            command.matches(Regex("""(?i)(increase|turn\s+up|raise)\s+(the\s+)?volume""")) ->
                listOf(Action(type = ActionTypes.VOLUME_UP, count = 3, delay = 200))

            command.matches(Regex("""(?i)(decrease|turn\s+down|lower)\s+(the\s+)?volume""")) ->
                listOf(Action(type = ActionTypes.VOLUME_DOWN, count = 3, delay = 200))

            command.matches(Regex("""(?i)(mute|silent|silence)\s+(the\s+)?(volume|sound)""")) ->
                listOf(Action(type = ActionTypes.VOLUME_DOWN, count = 15, delay = 100))

            command.matches(Regex("""(?i)(unmute|unsilence)\s+(the\s+)?(volume|sound)""")) ->
                listOf(Action(type = ActionTypes.VOLUME_UP, count = 5, delay = 200))

            // Brightness control
            command.matches(Regex("""(?i)(increase|turn\s+up)\s+(the\s+)?brightness""")) ->
                listOf(Action(type = ActionTypes.BRIGHTNESS_UP, count = 5, delay = 200))

            command.matches(Regex("""(?i)(decrease|turn\s+down)\s+(the\s+)?brightness""")) ->
                listOf(Action(type = ActionTypes.BRIGHTNESS_DOWN, count = 5, delay = 200))

            command.matches(Regex("""(?i)(max|maximum|full)\s+brightness""")) ->
                listOf(Action(type = ActionTypes.BRIGHTNESS_UP, count = 20, delay = 150))

            // Media control
            command.matches(Regex("""(?i)(play|pause|resume)\s+(the\s+)?(music|song|video|media)""")) ->
                listOf(Action(type = ActionTypes.PLAY_PAUSE, delay = 500))

            command.matches(Regex("""(?i)(next|skip)\s+(the\s+)?(song|track|video)""")) ->
                listOf(Action(type = ActionTypes.NEXT, delay = 500))

            command.matches(Regex("""(?i)(previous|go\s+back|last)\s+(song|track|video)""")) ->
                listOf(Action(type = ActionTypes.PREVIOUS, delay = 500))

            // Screenshot
            command.matches(Regex("""(?i)(take|capture)\s+(a\s+)?(screenshot|screen\s+shot|snapshot)""")) ->
                listOf(Action(type = ActionTypes.SCREENSHOT, delay = 1000))

            // Wait commands
            command.matches(Regex("""(?i)wait\s+(\d+)\s*(seconds|secs|sec|s)?""")) -> {
                val match = Regex("""(\d+)""").find(command)
                val seconds = match?.value?.toIntOrNull() ?: 1
                listOf(Action(type = ActionTypes.WAIT, delay = seconds * 1000))
            }

            else -> emptyList()
        }
    }

    private fun handleContactActions(command: String): List<Action> {
        val context = appContext ?: return emptyList()

        val callPattern = Regex("""(?i)call\s+(.+?)(?:\s+(?:through|on|using)\s+(phone|mobile))?""")
        val callMatch = callPattern.find(command)

        if (callMatch != null) {
            val contactName = callMatch.groupValues[1].trim()
            val usePhone = callMatch.groupValues[2].isNotEmpty()

            return if (usePhone) {
                // Direct phone call (not WhatsApp)
                findPhoneContactActions(contactName, "call")
            } else {
                // Try to find contact in phone book
                findContactActions(contactName, "call", "")
            }
        }
        // Pattern: message/text/whatsapp [contact] [message]
        val messagePattern = Regex("""(?i)(?:message|text|whatsapp|send)\s+(.+?)\s+(?:saying\s+)?(.+)""")
        val messageMatch = messagePattern.find(command)

        if (messageMatch != null) {
            val contactName = messageMatch.groupValues[1].trim()
            val message = messageMatch.groupValues[2].trim()

            val actions = findContactActions(contactName, "message", "whatsapp")
            if (actions.isNotEmpty()) {
                val enhancedActions = mutableListOf<Action>()
                enhancedActions.addAll(actions)
                enhancedActions.add(Action(type = ActionTypes.TYPE, text = message, delay = 1500))
                enhancedActions.add(Action(type = ActionTypes.SEND, delay = 500))
                return enhancedActions
            }
        }

        return emptyList()
    }

    private fun findPhoneContactActions(contactName: String, actionType: String): List<Action> {
        val context = appContext ?: return emptyList()

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val number = cursor.getString(numberIndex)?.replace(Regex("[^+0-9]"), "") ?: ""

                    if (number.isNotEmpty()) {
                        listOf(
                            Action(type = ActionTypes.DIAL, phoneNumber = number, delay = 2000)
                        )
                    } else {
                        // Fallback: Open dialer with contact name
                        val dialerPackage = findDialerPackage()
                        listOf(
                            Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000),
                            Action(type = ActionTypes.WAIT, delay = 1000),
                            Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
                            Action(type = ActionTypes.WAIT, delay = 500),
                            Action(type = ActionTypes.TYPE, text = contactName, delay = 1000),
                            Action(type = ActionTypes.WAIT, delay = 1000),
                            Action(type = ActionTypes.CLICK, target = contactName, delay = 1000)
                        )
                    }
                } else {
                    // No contact found, just open dialer
                    val dialerPackage = findDialerPackage()
                    listOf(
                        Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000)
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding phone contact: ${e.message}")
            // Fallback: Open dialer
            val dialerPackage = findDialerPackage()
            listOf(
                Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000)
            )
        }
    }

    private fun findContactActions(contactName: String, actionType: String, app: String = ""): List<Action> {
        val context = appContext ?: return emptyList()

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    val name = cursor.getString(nameIndex)
                    val number = cursor.getString(numberIndex)?.replace(Regex("[^+0-9]"), "") ?: ""

                    return when (actionType) {
                        "call" -> {
                            when (app) {
                                "whatsapp" -> listOf(
                                    Action(type = ActionTypes.OPEN_APP, packageName = "com.whatsapp", delay = 3000),
                                    Action(type = ActionTypes.WAIT, delay = 1000),
                                    Action(type = ActionTypes.CLICK, target = "Calls", delay = 500),
                                    Action(type = ActionTypes.WAIT, delay = 500),
                                    Action(type = ActionTypes.CLICK, target = "New call", delay = 500),
                                    Action(type = ActionTypes.WAIT, delay = 500),
                                    Action(type = ActionTypes.TYPE, text = name, delay = 1000),
                                    Action(type = ActionTypes.WAIT, delay = 1000),
                                    Action(type = ActionTypes.CLICK, target = name, delay = 1000)
                                )
                                else -> {
                                    if (number.isNotEmpty()) {
                                        listOf(
                                            Action(type = ActionTypes.DIAL, phoneNumber = number, delay = 2000)
                                        )
                                    } else {
                                        // Just open dialer and search
                                        val dialerPackage = findDialerPackage()
                                        listOf(
                                            Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000),
                                            Action(type = ActionTypes.WAIT, delay = 1000),
                                            Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
                                            Action(type = ActionTypes.WAIT, delay = 500),
                                            Action(type = ActionTypes.TYPE, text = name, delay = 1000),
                                            Action(type = ActionTypes.WAIT, delay = 1000)
                                        )
                                    }
                                }
                            }
                        }
                        "message" -> {
                            listOf(
                                Action(type = ActionTypes.OPEN_APP, packageName = "com.whatsapp", delay = 3000),
                                Action(type = ActionTypes.WAIT, delay = 1000),
                                Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
                                Action(type = ActionTypes.WAIT, delay = 500),
                                Action(type = ActionTypes.TYPE, text = name, delay = 1000),
                                Action(type = ActionTypes.WAIT, delay = 1000),
                                Action(type = ActionTypes.CLICK, target = name, delay = 1000),
                                Action(type = ActionTypes.WAIT, delay = 500)
                            )
                        }
                        else -> emptyList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding contact: ${e.message}")
        }

        return emptyList()
    }

    private fun findDialerPackage(): String {
        val context = appContext ?: return "com.android.dialer"

        // Try each dialer package to see which one is installed
        for (packageName in dialerPackages) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    Log.d(TAG, "✅ Found dialer package: $packageName")
                    return packageName
                }
            } catch (e: Exception) {
                // Continue to next package
            }
        }

        // Fallback to default
        Log.w(TAG, "⚠️ No dialer package found, using default")
        return "com.android.dialer"
    }

    private suspend fun tryStructuredGeminiApi(command: String): List<Action> = withContext(Dispatchers.IO) {
        try {
            val prompt = createStructuredPrompt(command)
            Log.d(TAG, "📤 Sending structured request to Gemini API...")

            val request = Request.Builder()
                .url(geminiUrl)
                .post(createStructuredRequestBody(prompt))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API request failed: ${response.code} - ${response.message}")
                if (response.code == 429) {
                    Log.e(TAG, "Rate limited - consider adding exponential backoff")
                }
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "📥 Raw response length: ${responseBody.length} chars")

            val actions = parseStructuredResponse(responseBody)
            return@withContext actions

        } catch (e: Exception) {
            Log.e(TAG, "❌ Structured API call error: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    private fun createStructuredPrompt(command: String): String {
        val availableApps = appPackageDatabase.entries.joinToString("\n") {
            "- ${it.key} (aliases: ${it.value.aliases.joinToString(", ")}) -> ${it.value.packageName}"
        }

        return """
            You are Jarvis, an advanced Android automation assistant. Convert the user's natural language command into a sequence of JSON actions.
            
            COMMAND: "$command"
            
            CRITICAL INSTRUCTIONS:
            1. Return ONLY a valid JSON array of action objects
            2. No explanations, no markdown, no additional text
            3. Use EXACTLY the action types defined below
            4. Always include appropriate delays (500-3000ms)
            5. Add wait actions between major operations
            6. Validate all package names against the provided database
            7. For messaging apps, include search and select contact steps
            
            ACTION TYPES & REQUIRED FIELDS:
            - open_app: { "type": "open_app", "packageName": "com.example.app", "delay": 2000 }
            - click: { "type": "click", "target": "Button text", "delay": 1000 }
            - type: { "type": "type", "text": "text to type", "delay": 1500 }
            - send: { "type": "send", "delay": 500 }
            - search: { "type": "search", "text": "search query", "delay": 1000 }
            - call: { "type": "call", "phoneNumber": "+1234567890", "delay": 2000 }
            - message: { "type": "message", "contactName": "John", "text": "Hello", "delay": 1000 }
            - wait: { "type": "wait", "delay": 1000 }
            - back: { "type": "back", "delay": 500 }
            - home: { "type": "home", "delay": 500 }
            
            COMMON APP PACKAGE NAMES:
            $availableApps
            
            Now generate actions for: "$command"
        """.trimIndent()
    }

    private fun createStructuredRequestBody(prompt: String): RequestBody {
        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(Part(text = prompt)),
                    role = "user"
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.1,
                maxOutputTokens = 4096,
                responseMimeType = "application/json",
                responseSchema = ResponseSchema()
            ),
            systemInstruction = SystemInstruction(
                parts = listOf(Part(text = "You are Jarvis, an Android automation expert. Always output valid JSON arrays."))
            ),
            safetySettings = listOf(
                SafetySetting(category = "HARM_CATEGORY_HARASSMENT", threshold = "BLOCK_ONLY_HIGH"),
                SafetySetting(category = "HARM_CATEGORY_HATE_SPEECH", threshold = "BLOCK_ONLY_HIGH"),
                SafetySetting(category = "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold = "BLOCK_ONLY_HIGH"),
                SafetySetting(category = "HARM_CATEGORY_DANGEROUS_CONTENT", threshold = "BLOCK_ONLY_HIGH")
            )
        )

        return gson.toJson(request).toRequestBody(jsonMediaType)
    }

    private fun parseStructuredResponse(responseBody: String): List<Action> {
        try {
            val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
            val text = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return emptyList()

            Log.d(TAG, "📝 Extracted response text")

            val cleanText = text
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .replace(Regex("^\\s*\\[\\s*"), "[")
                .replace(Regex("\\s*\\]\\s*$"), "]")
                .trim()

            val typeToken = object : TypeToken<List<Action>>() {}.type
            val actions = gson.fromJson<List<Action>>(cleanText, typeToken)

            if (actions != null && actions.isNotEmpty()) {
                Log.d(TAG, "✅ Successfully parsed ${actions.size} structured actions")
                return actions
            }

        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "❌ JSON parsing error: ${e.message}")
            return extractJsonFromText("$responseBody")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing structured response: ${e.message}", e)
        }

        return emptyList()
    }

    private fun extractJsonFromText(text: String): List<Action> {
        try {
            val pattern = Pattern.compile("\\[\\s*\\{.*\\}\\s*\\]", Pattern.DOTALL)
            val matcher = pattern.matcher(text)

            if (matcher.find()) {
                val jsonStr = matcher.group(0)
                val typeToken = object : TypeToken<List<Action>>() {}.type
                return gson.fromJson(jsonStr, typeToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract JSON from text: ${e.message}")
        }
        return emptyList()
    }

    private fun generateEnhancedRuleBasedActions(command: String): List<Action> {
        Log.d(TAG, "🤖 Generating enhanced rule-based actions for: '$command'")

        val actions = mutableListOf<Action>()

        // WhatsApp patterns
        when {
            Regex("""(?i)whatsapp\s+(\w+)\s+(.+)""").matches(command) -> {
                handleWhatsAppMessagePattern(command, actions)
                return actions
            }

            Regex("""(?i)send\s+(?:a\s+)?message\s+to\s+(\w+)\s+(?:in\s+)?whatsapp(?:\s+saying\s+)?(.+)""").matches(command) -> {
                handleWhatsAppSendMessagePattern(command, actions)
                return actions
            }

            Regex("""(?i)message\s+(\w+)\s+(?:on\s+)?whatsapp(?:\s*:\s*)?(.+)""").matches(command) -> {
                handleWhatsAppMessagePattern2(command, actions)
                return actions
            }
        }

        // Complex pattern matching
        when {
            Regex("""(?i)open\s+(\w+)\s+and\s+(.+)""").matches(command) -> {
                handleOpenAndPattern(command, actions)
            }

            Regex("""(?i)(?:search|find)\s+(?:for\s+)?(.+?)\s+in\s+(\w+)""").matches(command) -> {
                handleSearchInPattern(command, actions)
            }

            Regex("""(?i)(\w+)\s+(\w+)\s+(.+)""").matches(command) -> {
                handleAppContactMessagePattern(command, actions)
            }

            Regex("""(?i)take\s+screenshot\s+and\s+(.+)""").matches(command) -> {
                handleScreenshotAndPattern(command, actions)
            }

            Regex("""(?i)play\s+(.+?)\s+on\s+(\w+)""").matches(command) -> {
                handlePlayOnPattern(command, actions)
            }

            else -> handleSimplePatterns(command, actions)
        }

        return actions
    }

    private fun handleOpenAndPattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)open\s+(\w+)\s+and\s+(.+)""").find(command)
        if (match != null) {
            val appName = match.groupValues[1]
            val actionText = match.groupValues[2]
            val packageName = findPackageName(appName)

            if (packageName.isNotEmpty()) {
                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 2500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1500))

                when {
                    actionText.contains(Regex("""(?i)search""")) -> {
                        val query = extractSearchQuery(actionText)
                        if (query.isNotEmpty()) {
                            actions.addAll(generateEnhancedSearchActions(query, appName))
                        }
                    }
                    actionText.contains(Regex("""(?i)(?:message|text|whatsapp)""")) -> {
                        val message = extractMessage(actionText)
                        if (message.isNotEmpty()) {
                            actions.addAll(generateEnhancedMessagingActions(message, appName))
                        }
                    }
                    actionText.contains(Regex("""(?i)call""")) -> {
                        actions.add(Action(type = ActionTypes.CLICK, target = "Call", delay = 1000))
                    }
                    actionText.contains(Regex("""(?i)play""")) -> {
                        val media = extractMediaQuery(actionText)
                        if (media.isNotEmpty()) {
                            actions.addAll(generateMediaPlayActions(media, appName))
                        }
                    }
                }
            }
        }
    }

    private fun handleSearchInPattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)(?:search|find)\s+(?:for\s+)?(.+?)\s+in\s+(\w+)""").find(command)
        if (match != null) {
            val query = match.groupValues[1].trim()
            val appName = match.groupValues[2]
            val packageName = findPackageName(appName)

            if (packageName.isNotEmpty() && query.isNotEmpty()) {
                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 2500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                actions.addAll(generateEnhancedSearchActions(query, appName))
            }
        }
    }

    private fun handleAppContactMessagePattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)(\w+)\s+(\w+)\s+(.+)""").find(command)
        if (match != null) {
            val appName = match.groupValues[1]
            val contact = match.groupValues[2]
            val message = match.groupValues[3]
            val packageName = findPackageName(appName)

            if (packageName.isNotEmpty() && isMessagingApp(appName)) {
                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 3000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1500))
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = contact, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                actions.add(Action(type = ActionTypes.CLICK, target = contact, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = message, delay = 1000))
                actions.add(Action(type = ActionTypes.SEND, delay = 500))
            }
        }
    }

    private fun handleScreenshotAndPattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)take\s+screenshot\s+and\s+(.+)""").find(command)
        if (match != null) {
            val actionText = match.groupValues[1]
            actions.add(Action(type = ActionTypes.SCREENSHOT, delay = 1000))
            actions.add(Action(type = ActionTypes.WAIT, delay = 1500))

            when {
                actionText.contains(Regex("""(?i)share\s+on\s+(\w+)""")) -> {
                    val appMatch = Regex("""(?i)share\s+on\s+(\w+)""").find(actionText)
                    appMatch?.let {
                        val appName = it.groupValues[1]
                        val packageName = findPackageName(appName)
                        if (packageName.isNotEmpty()) {
                            actions.add(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 3000))
                            actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                            actions.add(Action(type = ActionTypes.CLICK, target = "New Post", delay = 1000))
                        }
                    }
                }
            }
        }
    }

    private fun handlePlayOnPattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)play\s+(.+?)\s+on\s+(\w+)""").find(command)
        if (match != null) {
            val media = match.groupValues[1].trim()
            val appName = match.groupValues[2]
            val packageName = findPackageName(appName)

            if (packageName.isNotEmpty()) {
                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 3000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1500))
                actions.addAll(generateMediaPlayActions(media, appName))
            }
        }
    }

    private fun handleSimplePatterns(command: String, actions: MutableList<Action>) {
        when {
            command.startsWith("open ", ignoreCase = true) -> {
                val appName = command.substring(5).trim()
                val packageName = findPackageName(appName)
                if (packageName.isNotEmpty()) {
                    actions.add(Action(type = ActionTypes.OPEN_APP, packageName = packageName, delay = 2000))
                }
            }

            Regex("""(?i)(?:search|find|google)\s+(?:for\s+)?(.+)""").matches(command) -> {
                val match = Regex("""(?i)(?:search|find|google)\s+(?:for\s+)?(.+)""").find(command)
                if (match != null) {
                    val query = match.groupValues[1].trim()
                    actions.add(Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.googlequicksearchbox", delay = 2500))
                    actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                    actions.addAll(generateEnhancedSearchActions(query, "google"))
                }
            }

            command.contains("weather", ignoreCase = true) -> {
                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.googlequicksearchbox", delay = 2500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = "weather", delay = 1000))
                actions.add(Action(type = ActionTypes.SEND, delay = 500))
            }
        }
    }

    private fun generateEnhancedSearchActions(query: String, appName: String): List<Action> {
        val actions = mutableListOf<Action>()

        when (appName.lowercase()) {
            "google", "chrome" -> {
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = query, delay = 1500))
                actions.add(Action(type = ActionTypes.SEND, delay = 500))
            }
            "youtube" -> {
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = query, delay = 1500))
                actions.add(Action(type = ActionTypes.SEND, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 2000))
                actions.add(Action(type = ActionTypes.CLICK, target = "First video", delay = 1000))
            }
            "spotify" -> {
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = query, delay = 1500))
                actions.add(Action(type = ActionTypes.SEND, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1500))
                actions.add(Action(type = ActionTypes.CLICK, target = "Play", delay = 1000))
            }
            else -> {
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = query, delay = 1500))
                actions.add(Action(type = ActionTypes.SEND, delay = 500))
            }
        }

        return actions
    }

    private fun generateEnhancedMessagingActions(message: String, appName: String): List<Action> {
        val actions = mutableListOf<Action>()

        actions.add(Action(type = ActionTypes.CLICK, target = "New chat", delay = 1000))
        actions.add(Action(type = ActionTypes.WAIT, delay = 500))
        actions.add(Action(type = ActionTypes.TYPE, text = message, delay = 2000))
        actions.add(Action(type = ActionTypes.SEND, delay = 500))

        return actions
    }

    private fun generateMediaPlayActions(media: String, appName: String): List<Action> {
        val actions = mutableListOf<Action>()

        when (appName.lowercase()) {
            "youtube" -> {
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = media, delay = 1500))
                actions.add(Action(type = ActionTypes.SEND, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 2000))
                actions.add(Action(type = ActionTypes.CLICK, target = "First result", delay = 1000))
            }
            "spotify" -> {
                actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.TYPE, text = media, delay = 1500))
                actions.add(Action(type = ActionTypes.SEND, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1500))
                actions.add(Action(type = ActionTypes.CLICK, target = media, delay = 1000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                actions.add(Action(type = ActionTypes.CLICK, target = "Play", delay = 1000))
            }
        }

        return actions
    }

    private fun smartUltimateFallback(command: String): List<Action> {
        Log.w(TAG, "🚨 Using smart ultimate fallback for: '$command'")

        // If command is just one word
        val words = command.split("\\s+".toRegex())
        if (words.size == 1) {
            return when (words[0].lowercase()) {
                "volume" -> listOf(Action(type = ActionTypes.VOLUME_UP, count = 3, delay = 200))
                "brightness" -> listOf(Action(type = ActionTypes.BRIGHTNESS_UP, count = 5, delay = 200))
                "music", "play" -> listOf(Action(type = ActionTypes.PLAY_PAUSE, delay = 500))
                "next" -> listOf(Action(type = ActionTypes.NEXT, delay = 500))
                "back" -> listOf(Action(type = ActionTypes.BACK, delay = 500))
                "home" -> listOf(Action(type = ActionTypes.HOME, delay = 500))
                "recents" -> listOf(Action(type = ActionTypes.RECENTS, delay = 800))
                "call" -> {
                    val dialerPackage = findDialerPackage()
                    listOf(Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000))
                }
                else -> defaultGoogleSearch(command)
            }
        }

        return when {
            // Google search
            command.contains(Regex("""(?i)(?:search|google|find)\s+(?:on\s+)?(?:google\s+)?(.+)""")) -> {
                val query = extractSearchQuery(command)
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.googlequicksearchbox", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.TYPE, text = query, delay = 1500),
                    Action(type = ActionTypes.SEND, delay = 500)
                )
            }

            // Wi-Fi/BT
            command.contains(Regex("""(?i)(?:turn\s+)?(on|off|enable|disable)\s+(?:wi.?fi|wifi|bluetooth)""")) -> {
                val actionWord = if (command.contains("on", ignoreCase = true)) "on" else "off"
                val isWifi = command.contains("wifi", ignoreCase = true) || command.contains("wi-fi", ignoreCase = true)
                val target = if (isWifi) "Wi-Fi" else "Bluetooth"

                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.android.settings", delay = 2000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = if (isWifi) "Network & internet" else "Connected devices", delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = target, delay = 1000),
                    Action(type = ActionTypes.WAIT, delay = 500),
                    Action(type = ActionTypes.CLICK, target = if (actionWord == "on") "Turn on" else "Turn off", delay = 1000)
                )
            }

            // Phone calls
            command.contains(Regex("""(?i)(?:call|dial|phone)\s+""")) -> {
                val contact = extractContactName(command)
                val dialerPackage = findDialerPackage()
                val actions = mutableListOf<Action>()

                actions.add(Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2000))
                actions.add(Action(type = ActionTypes.WAIT, delay = 1000))

                if (contact.isNotEmpty() && !contact.matches(Regex("""\d+"""))) {
                    actions.add(Action(type = ActionTypes.CLICK, target = "Search", delay = 500))
                    actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                    actions.add(Action(type = ActionTypes.TYPE, text = contact, delay = 1000))
                    actions.add(Action(type = ActionTypes.WAIT, delay = 1000))
                } else if (contact.isNotEmpty()) {
                    actions.add(Action(type = ActionTypes.CLICK, target = "Keypad", delay = 500))
                    actions.add(Action(type = ActionTypes.WAIT, delay = 500))
                    actions.add(Action(type = ActionTypes.TYPE, text = contact, delay = 1000))
                }

                actions
            }

            // WhatsApp
            command.contains(Regex("""(?i)whatsapp.*message""")) -> {
                val contact = extractWhatsAppContact(command)
                val message = extractWhatsAppMessage(command)

                if (contact.isNotEmpty() && message.isNotEmpty()) {
                    generateWhatsAppActions(contact, message)
                } else {
                    listOf(
                        Action(type = ActionTypes.OPEN_APP, packageName = "com.whatsapp", delay = 3000),
                        Action(type = ActionTypes.WAIT, delay = 1000),
                        Action(type = ActionTypes.CLICK, target = "Chats", delay = 500)
                    )
                }
            }

            command.contains(Regex("""(?i)whatsapp|message|text""")) -> {
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.whatsapp", delay = 3000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Chats", delay = 500)
                )
            }

            command.contains(Regex("""(?i)call|phone|dial""")) -> {
                val dialerPackage = findDialerPackage()
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = dialerPackage, delay = 2500)
                )
            }

            command.contains(Regex("""(?i)youtube|video|watch""")) -> {
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.youtube", delay = 3000),
                    Action(type = ActionTypes.WAIT, delay = 1000),
                    Action(type = ActionTypes.CLICK, target = "Search", delay = 500)
                )
            }

            command.contains(Regex("""(?i)music|song|spotify""")) -> {
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.spotify.music", delay = 3000)
                )
            }

            command.contains(Regex("""(?i)email|gmail""")) -> {
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.gm", delay = 3000)
                )
            }

            command.contains(Regex("""(?i)map|location|where""")) -> {
                listOf(
                    Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.apps.maps", delay = 3000)
                )
            }

            else -> defaultGoogleSearch(command)
        }
    }

    private fun defaultGoogleSearch(command: String): List<Action> {
        return listOf(
            Action(type = ActionTypes.OPEN_APP, packageName = "com.google.android.googlequicksearchbox", delay = 2500),
            Action(type = ActionTypes.WAIT, delay = 1000),
            Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
            Action(type = ActionTypes.WAIT, delay = 500),
            Action(type = ActionTypes.TYPE, text = command, delay = 1500),
            Action(type = ActionTypes.SEND, delay = 500)
        )
    }

    private fun extractContactName(command: String): String {
        val patterns = listOf(
            Regex("""(?i)call\s+(.+)"""),
            Regex("""(?i)dial\s+(.+)"""),
            Regex("""(?i)phone\s+(.+)""")
        )

        patterns.forEach { pattern ->
            val match = pattern.find(command)
            if (match != null) {
                var name = match.groupValues[1].trim()
                name = name.replace(Regex("""\s+(?:through|on|using)\s+.+""", RegexOption.IGNORE_CASE), "")
                return name
            }
        }
        return ""
    }

    private fun extractWhatsAppContact(command: String): String {
        val patterns = listOf(
            Regex("""(?i)to\s+(\w+)(?:\s+in\s+whatsapp|\s+on\s+whatsapp)"""),
            Regex("""(?i)whatsapp\s+(\w+)\s+"""),
            Regex("""(?i)message\s+(\w+)\s+(?:on\s+)?whatsapp""")
        )

        patterns.forEach { pattern ->
            val match = pattern.find(command)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }

    private fun extractWhatsAppMessage(command: String): String {
        val patterns = listOf(
            Regex("""(?i)saying\s+(.+)"""),
            Regex("""(?i)whatsapp\s+\w+\s+(.+)"""),
            Regex("""(?i):\s+(.+)""")
        )

        patterns.forEach { pattern ->
            val match = pattern.find(command)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }

    private fun validateAndOptimizeActions(actions: List<Action>): List<Action> {
        val validatedActions = mutableListOf<Action>()

        actions.forEachIndexed { index, action ->
            val validatedAction = when (action.type) {
                ActionTypes.OPEN_APP -> {
                    if (action.packageName.isEmpty()) {
                        val packageName = if (action.appName.isNotEmpty()) {
                            findPackageName(action.appName)
                        } else if (action.target.isNotEmpty()) {
                            findPackageName(action.target)
                        } else {
                            ""
                        }
                        action.copy(packageName = packageName)
                    } else {
                        action
                    }
                }
                ActionTypes.CLICK -> {
                    if (action.target.isEmpty() && (action.x == 0 || action.y == 0)) {
                        null
                    } else {
                        action
                    }
                }
                ActionTypes.TYPE -> {
                    if (action.text.isEmpty()) {
                        null
                    } else {
                        action
                    }
                }
                else -> action
            }

            validatedAction?.let {
                val delay = if (it.delay < 100) 500 else it.delay
                val optimizedAction = it.copy(delay = delay)
                validatedActions.add(optimizedAction)

                if (index < actions.size - 1 && isMajorAction(it.type)) {
                    validatedActions.add(Action(type = ActionTypes.WAIT, delay = 500))
                }
            }
        }

        return validatedActions
    }

    private fun isMajorAction(actionType: String): Boolean {
        return listOf(
            ActionTypes.OPEN_APP,
            ActionTypes.SEARCH,
            ActionTypes.CALL,
            ActionTypes.MESSAGE,
            ActionTypes.SCREENSHOT
        ).contains(actionType)
    }

    private fun findPackageName(appName: String): String {
        val normalizedAppName = appName.lowercase().trim()

        appPackageDatabase[normalizedAppName]?.let { return it.packageName }

        for ((_, appInfo) in appPackageDatabase) {
            if (appInfo.aliases.any { alias -> normalizedAppName.contains(alias, ignoreCase = true) }) {
                return appInfo.packageName
            }
        }

        for ((key, appInfo) in appPackageDatabase) {
            if (normalizedAppName.contains(key, ignoreCase = true) ||
                key.contains(normalizedAppName, ignoreCase = true)) {
                return appInfo.packageName
            }
        }

        return ""
    }

    private fun extractSearchQuery(text: String): String {
        val patterns = listOf(
            Regex("""(?i)search\s+(?:on\s+)?(?:google\s+)?(.+)"""),
            Regex("""(?i)google\s+(.+)"""),
            Regex("""(?i)find\s+(?:me\s+)?(.+)"""),
            Regex("""(?i)look\s+up\s+(.+)"""),
            Regex("""(.+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                var query = match.groupValues[1].trim()
                val wordsToRemove = listOf("search", "find", "google", "for", "on", "me", "please")
                wordsToRemove.forEach { word ->
                    query = query.replace(Regex("""\b$word\b""", RegexOption.IGNORE_CASE), "")
                }
                return query.trim()
            }
        }

        return text
    }

    private fun extractMessage(text: String): String {
        val patterns = listOf(
            Regex("""(?i)message\s+(?:saying\s+)?(.+)"""),
            Regex("""(?i)text\s+(?:saying\s+)?(.+)"""),
            Regex("""(?i)say\s+(.+)"""),
            Regex("""(?i)send\s+(?:a\s+)?(?:message|text)\s+(?:saying\s+)?(.+)"""),
            Regex("""(?i)whatsapp\s+(?:saying\s+)?(.+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                var message = match.groupValues[1].trim()
                message = message.replace(Regex("""\bto\s+\w+\b""", RegexOption.IGNORE_CASE), "").trim()
                return message
            }
        }

        return ""
    }

    private fun extractMediaQuery(text: String): String {
        val patterns = listOf(
            Regex("""(?i)play\s+(.+)"""),
            Regex("""(?i)listen\s+to\s+(.+)"""),
            Regex("""(?i)watch\s+(.+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                var media = match.groupValues[1].trim()
                media = media.replace(Regex("""\b(on|in|the|a|an)\b""", RegexOption.IGNORE_CASE), "").trim()
                return media
            }
        }

        return ""
    }

    private fun isMessagingApp(appName: String): Boolean {
        val messagingApps = listOf("whatsapp", "messenger", "telegram", "signal", "messages", "sms", "instagram", "facebook")
        return messagingApps.any { appName.contains(it, ignoreCase = true) }
    }

    private fun handleWhatsAppMessagePattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)whatsapp\s+(\w+)\s+(.+)""").find(command)
        if (match != null) {
            val contact = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            actions.addAll(generateWhatsAppActions(contact, message))
        }
    }

    private fun handleWhatsAppSendMessagePattern(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)send\s+(?:a\s+)?message\s+to\s+(\w+)\s+(?:in\s+)?whatsapp(?:\s+saying\s+)?(.+)""").find(command)
        if (match != null) {
            val contact = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            actions.addAll(generateWhatsAppActions(contact, message))
        }
    }

    private fun handleWhatsAppMessagePattern2(command: String, actions: MutableList<Action>) {
        val match = Regex("""(?i)message\s+(\w+)\s+(?:on\s+)?whatsapp(?:\s*:\s*)?(.+)""").find(command)
        if (match != null) {
            val contact = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            actions.addAll(generateWhatsAppActions(contact, message))
        }
    }

    private fun generateWhatsAppActions(contact: String, message: String): List<Action> {
        return listOf(
            Action(type = ActionTypes.OPEN_APP, packageName = "com.whatsapp", appName = "WhatsApp", delay = 3000),
            Action(type = ActionTypes.WAIT, delay = 1000),
            Action(type = ActionTypes.CLICK, target = "Search", delay = 500),
            Action(type = ActionTypes.WAIT, delay = 500),
            Action(type = ActionTypes.TYPE, text = contact, delay = 1000),
            Action(type = ActionTypes.WAIT, delay = 1000),
            Action(type = ActionTypes.CLICK, target = contact, delay = 1000),
            Action(type = ActionTypes.WAIT, delay = 500),
            Action(type = ActionTypes.TYPE, text = message, delay = 1500),
            Action(type = ActionTypes.WAIT, delay = 500),
            Action(type = ActionTypes.SEND, delay = 500)
        )
    }

    fun getAppPackage(appName: String): String {
        return findPackageName(appName)
    }

    // Debug function to test commands
    fun debugCommands() {
        val testCommands = listOf(
            "call Babu",
            "volume up",
            "open Bluetooth",
            "turn on Wi-Fi",
            "search Google",
            "whatsapp John hello there"
        )

        println("\n=== Testing Commands ===")
        testCommands.forEach { command ->
            println("\nCommand: '$command'")
            println("Hotfix: ${handleSimpleCommandsImmediately(command).size} actions")
            println("Direct: ${getDirectSystemActions(command).size} actions")
            println("Contact: ${handleContactActions(command).size} actions")
        }
    }
}