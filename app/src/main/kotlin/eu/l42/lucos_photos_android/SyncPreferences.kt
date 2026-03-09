package eu.l42.lucos_photos_android

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists sync-related state using SharedPreferences.
 *
 * Timestamps are stored as milliseconds since epoch. On first run, values default
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
     * This reflects the photo's DATE_ADDED metadata — not the wall-clock time the sync ran.
     * Use [lastSyncCompletedAtMs] to display "Last synced" in the UI.
     *
     * Returns 0 if no sync has ever completed (i.e., upload all photos).
     */
    var lastSyncTimestampMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP_MS, value).apply()
        }

    /**
     * The wall-clock time at which the most recent successful sync completed, in milliseconds
     * since epoch. Updated once per sync run (not per photo) when the worker finishes without
     * any retryable failures.
     *
     * This is the value to display in the "Last synced" UI label.
     *
     * Returns 0 if no sync has ever completed successfully.
     */
    var lastSyncCompletedAtMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_COMPLETED_AT_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_COMPLETED_AT_MS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "lucos_photos_sync"
        private const val KEY_LAST_SYNC_TIMESTAMP_MS = "last_sync_timestamp_ms"
        private const val KEY_LAST_SYNC_COMPLETED_AT_MS = "last_sync_completed_at_ms"
    }
}
