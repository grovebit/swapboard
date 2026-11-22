package com.clipsync.android.data

import android.content.Context
import androidx.core.content.edit

/** First-run setup progress, plus whether the Quick Settings tile is in place. */
class SetupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Phones paired before the setup screens existed skip them. Only decides once. */
    fun decideOnce(alreadyPaired: Boolean) {
        if (!prefs.contains(KEY_DONE)) done = alreadyPaired
    }

    var done: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_DONE, value) }

    var started: Boolean
        get() = prefs.getBoolean(KEY_STARTED, false)
        set(value) = prefs.edit { putBoolean(KEY_STARTED, value) }

    var permissionsReviewed: Boolean
        get() = prefs.getBoolean(KEY_PERMISSIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_PERMISSIONS, value) }

    var tileReviewed: Boolean
        get() = prefs.getBoolean(KEY_TILE_REVIEWED, false)
        set(value) = prefs.edit { putBoolean(KEY_TILE_REVIEWED, value) }

    /** Kept up to date by the tile service itself. */
    var tileAdded: Boolean
        get() = prefs.getBoolean(KEY_TILE_ADDED, false)
        set(value) = prefs.edit { putBoolean(KEY_TILE_ADDED, value) }

    private companion object {
        const val KEY_DONE = "setup_done"
        const val KEY_STARTED = "setup_started"
        const val KEY_PERMISSIONS = "setup_permissions_reviewed"
        const val KEY_TILE_REVIEWED = "setup_tile_reviewed"
        const val KEY_TILE_ADDED = "tile_added"
    }
}
