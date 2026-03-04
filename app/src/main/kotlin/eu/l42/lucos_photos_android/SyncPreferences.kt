package eu.l42.lucos_photos_android

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the last successful sync timestamp using SharedPreferences.
 *
 * The timestamp is stored as milliseconds since epoch. On first run, it defaults
 * to 0 so that all existing photos are uploaded.
 */
class SyncPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * The timestamp of the last successfully uploaded photo, in milliseconds since epoch.
     * Photos with [MediaStore.Images.Media.DATE_ADDED] strictly greater than this value
     * will be candidates for upload.
     *
     * Returns 0 if no sync has ever completed (i.e., upload all photos).
     */
    var lastSyncTimestampMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP_MS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "lucos_photos_sync"
        private const val KEY_LAST_SYNC_TIMESTAMP_MS = "last_sync_timestamp_ms"
    }
}
