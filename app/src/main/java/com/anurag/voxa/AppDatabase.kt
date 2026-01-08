package com.anurag.voxa

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppDatabase {

    private val appCache = mutableMapOf<String, String>()

    fun getPackageForApp(appName: String): String? {
        // Check cache first
        val cached = appCache[appName.lowercase()]
        if (cached != null) return cached

        // Common apps mapping
        val commonApps = mapOf(
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "gallery" to "com.android.gallery3d",
            "settings" to "com.android.settings",
            "phone" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "messages" to "com.android.mms",
            "clock" to "com.android.deskclock",
            "calculator" to "com.android.calculator2",
            "calendar" to "com.google.android.calendar",
            "play store" to "com.android.vending",
            "files" to "com.android.documentsui"
        )

        return commonApps[appName.lowercase()]?.also {
            appCache[appName.lowercase()] = it
        }
    }

    fun getAllInstalledApps(context: Context): Map<String, String> {
        val apps = mutableMapOf<String, String>()
        val pm = context.packageManager

        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        packages.forEach { appInfo ->
            if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                apps[appName.lowercase()] = appInfo.packageName
                appCache[appName.lowercase()] = appInfo.packageName
            }
        }

        return apps
    }

    fun searchApp(context: Context, query: String): String? {
        val apps = getAllInstalledApps(context)

        return apps.entries.firstOrNull { (name, _) ->
            name.contains(query, ignoreCase = true)
        }?.value
    }
}