package com.anurag.voxa

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

object MemoryEngine {

    private const val PREFS_NAME = "jarvis_memory"
    private const val KEY_LAST_APP = "last_app"
    private const val KEY_LAST_ACTIONS = "last_actions"
    private const val KEY_LAST_CONTACT = "last_contact"
    private const val KEY_LAST_MESSAGE = "last_message"
    private const val KEY_CONTEXT = "context"
    private const val KEY_COMMAND_HISTORY = "command_history"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // App Context
    fun storeLastApp(packageName: String) {
        prefs.edit().putString(KEY_LAST_APP, packageName).apply()
    }

    fun getLastApp(): String? {
        return prefs.getString(KEY_LAST_APP, null)
    }

    // Action Context
    fun storeActions(actions: List<GeminiPlanner.Action>) {
        val json = gson.toJson(actions)
        prefs.edit().putString(KEY_LAST_ACTIONS, json).apply()
    }

    fun getLastActions(): List<GeminiPlanner.Action>? {
        val json = prefs.getString(KEY_LAST_ACTIONS, null)
        return if (json != null) {
            val type = object : TypeToken<List<GeminiPlanner.Action>>() {}.type
            gson.fromJson(json, type)
        } else {
            null
        }
    }

    // Communication Context
    fun storeLastContact(contact: String) {
        prefs.edit().putString(KEY_LAST_CONTACT, contact).apply()
    }

    fun getLastContact(): String? {
        return prefs.getString(KEY_LAST_CONTACT, null)
    }

    fun storeLastMessage(message: String) {
        prefs.edit().putString(KEY_LAST_MESSAGE, message).apply()
    }

    fun getLastMessage(): String? {
        return prefs.getString(KEY_LAST_MESSAGE, null)
    }

    // Click Context
    fun storeLastClicked(text: String) {
        prefs.edit().putString("last_clicked", text).apply()
    }

    fun getLastClicked(): String? {
        return prefs.getString("last_clicked", null)
    }

    // Text Context
    fun storeLastText(text: String) {
        prefs.edit().putString("last_text", text).apply()
    }

    fun getLastText(): String? {
        return prefs.getString("last_text", null)
    }

    // General Context Storage
    fun storeContext(key: String, value: String) {
        val currentContext = getContextMap()
        currentContext[key] = value
        val json = gson.toJson(currentContext)
        prefs.edit().putString(KEY_CONTEXT, json).apply()
    }

    fun getContext(key: String): String? {
        return getContextMap()[key]
    }

    private fun getContextMap(): MutableMap<String, String> {
        val json = prefs.getString(KEY_CONTEXT, "{}")
        val type = object : TypeToken<MutableMap<String, String>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    // Command History
    fun addToHistory(command: String) {
        val history = getCommandHistory().toMutableList()
        history.add("${Date().time}: $command")

        // Keep only last 100 commands
        if (history.size > 100) {
            history.removeAt(0)
        }

        val json = gson.toJson(history)
        prefs.edit().putString(KEY_COMMAND_HISTORY, json).apply()
    }

    fun getCommandHistory(): List<String> {
        val json = prefs.getString(KEY_COMMAND_HISTORY, "[]")
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    // Clear Memory
    fun clearMemory() {
        prefs.edit().clear().apply()
    }

    // Memory Stats
    fun getMemoryStats(): Map<String, Any> {
        return mapOf(
            "last_app" to (getLastApp() ?: "none"),
            "last_contact" to (getLastContact() ?: "none"),
            "context_entries" to getContextMap().size,
            "command_history_count" to getCommandHistory().size
        )
    }
}