package com.panakam.construction.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores per-project device-local data (photo URIs) in SharedPreferences.
 * These are NOT synced to the backend; they live only on this device.
 */
object LocalProjectStorage {

    private const val PREF_NAME = "construction_local"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ── Photos ───────────────────────────────────────────────────────────────

    fun getPhotoUris(projectId: String): List<String> {
        val raw = prefs?.getString("photos_$projectId", "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",")
    }

    fun savePhotoUris(projectId: String, uris: List<String>) {
        prefs?.edit()?.putString("photos_$projectId", uris.joinToString(","))?.apply()
    }

    fun deleteProject(projectId: String) {
        prefs?.edit()?.remove("photos_$projectId")?.apply()
    }
}

