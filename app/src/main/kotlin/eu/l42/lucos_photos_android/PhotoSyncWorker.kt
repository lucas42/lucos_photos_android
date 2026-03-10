package eu.l42.lucos_photos_android

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that syncs new photos and videos from the device's MediaStore to the
 * lucos_photos server.
 *
 * This worker:
 * 1. Queries MediaStore for photos and videos added after the last successful sync timestamp.
 * 2. Uploads each new item to the server via [PhotoUploader].
 * 3. Updates the last sync timestamp after each successful upload to avoid re-uploading.
 * 4. Reports a telemetry event to the server after each sync run via [TelemetryReporter].
 * 5. Checks whether a newer version of the app is available via [VersionChecker] and, if so,
 *    stores the latest version in [SyncPreferences] and posts a notification via [UpdateNotifier].
 *
 * On network failure, WorkManager's built-in exponential backoff retry handles rescheduling.
 * The server handles deduplication via SHA256, so re-uploading an item is harmless.
 */
class PhotoSyncWorker(
    private val context: Context,
    params: WorkerParameters,
    private val uploader: PhotoUploader = PhotoUploader(),
    private val syncPrefs: SyncPreferences = SyncPreferences(context),
    private val telemetry: TelemetryReporter = TelemetryReporter(),
    private val versionChecker: VersionChecker = VersionChecker(),
    private val updateNotifier: UpdateNotifier = UpdateNotifier(context),
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting media sync")

        val syncStartMs = System.currentTimeMillis()

        val lastSyncMs = syncPrefs.lastSyncTimestampMs
        // MediaStore DATE_ADDED is in seconds since epoch; convert for comparison
        val lastSyncSeconds = lastSyncMs / 1000L

        Log.i(TAG, "Querying for media added after timestamp $lastSyncSeconds (epoch seconds)")

        val photos = queryNewPhotos(lastSyncSeconds)
        val videos = queryNewVideos(lastSyncSeconds)

        // Merge and sort by DATE_ADDED ascending so we process oldest-first and can
        // advance the sync timestamp incrementally even if a batch is interrupted.
        val mediaItems = (photos + videos).sortedBy { it.dateAddedSeconds }
        Log.i(TAG, "Found ${mediaItems.size} new media item(s) to upload (${photos.size} photo(s), ${videos.size} video(s))")

        var anyRetryableFailure = false
        // Note: this counter is named photosSynced for backwards compatibility with the
        // telemetry schema — it now counts both photos and videos.
        var photosSynced = 0
        var errors = 0

        for ((index, item) in mediaItems.withIndex()) {
            Log.d(TAG, "Uploading media: ${item.displayName} (id=${item.id})")

            val result = try {
                context.contentResolver.openInputStream(
                    ContentUris.withAppendedId(item.contentUri, item.id)
                )?.use { stream ->
                    uploader.upload(
                        inputStream = stream,
                        filename = item.displayName,
                        mimeType = item.mimeType,
                        dateTakenMs = item.dateTakenMs,
                    )
                } ?: PhotoUploader.UploadResult.Failure("Could not open input stream", retryable = true)
            } catch (e: Exception) {
                Log.e(TAG, "Exception uploading media ${item.id}", e)
                PhotoUploader.UploadResult.Failure("Exception: ${e.message}", retryable = true)
            }

            when (result) {
                is PhotoUploader.UploadResult.Success -> {
                    photosSynced++
                    Log.i(TAG, "Successfully uploaded media ${item.id} (${item.displayName})")
                    // Only advance the sync timestamp once all items at this DATE_ADDED second
                    // have been processed. DATE_ADDED has one-second granularity, so advancing to
                    // item.dateAddedSeconds after the first item of a batch-at-the-same-second
                    // would cause the query (DATE_ADDED > lastSyncSeconds) to skip remaining items
                    // in that same second. We advance only when the next item is in a later second
                    // (or there are no more items).
                    val nextItemInDifferentSecond = index + 1 >= mediaItems.size ||
                            mediaItems[index + 1].dateAddedSeconds > item.dateAddedSeconds
                    if (nextItemInDifferentSecond) {
                        syncPrefs.lastSyncTimestampMs = item.dateAddedSeconds * 1000L
                    }
                }
                is PhotoUploader.UploadResult.AuthFailure -> {
                    // An auth error (401/403) almost certainly means our API key is wrong, which
                    // will affect every item — not just this one. Do NOT advance the sync
                    // timestamp. Instead, stop the batch and schedule a retry so that once the
                    // key is corrected, all items in the current window are still uploaded.
                    errors++
                    Log.e(TAG, "Auth failure uploading media ${item.id}: ${result.message} — stopping batch, will retry")
                    anyRetryableFailure = true
                    break
                }
                is PhotoUploader.UploadResult.Failure -> {
                    errors++
                    Log.w(TAG, "Failed to upload media ${item.id}: ${result.message} (retryable=${result.retryable})")
                    if (result.retryable) {
                        anyRetryableFailure = true
                        // Stop processing further items — retry later with the same timestamp
                        break
                    }
                    // Non-retryable (e.g. storage full on server): log and skip this item.
                    // Only advance the timestamp when all items at this second are done
                    // (same logic as success).
                    val nextItemInDifferentSecond = index + 1 >= mediaItems.size ||
                            mediaItems[index + 1].dateAddedSeconds > item.dateAddedSeconds
                    if (nextItemInDifferentSecond) {
                        syncPrefs.lastSyncTimestampMs = item.dateAddedSeconds * 1000L
                    }
                }
            }
        }

        val durationMs = System.currentTimeMillis() - syncStartMs

        // Check for app updates regardless of sync outcome — best-effort, never affects result.
        checkForUpdate()

        if (anyRetryableFailure) {
            Log.i(TAG, "Sync incomplete — scheduling retry via WorkManager backoff")
            telemetry.reportSync(durationMs = durationMs, photosSynced = photosSynced, errors = errors, succeeded = false)
            Result.retry()
        } else {
            Log.i(TAG, "Sync complete")
            syncPrefs.lastSyncCompletedAtMs = System.currentTimeMillis()
            telemetry.reportSync(durationMs = durationMs, photosSynced = photosSynced, errors = errors, succeeded = true)
            Result.success()
        }
    }

    /**
     * Checks whether a newer version of the app is available and updates [SyncPreferences] and
     * posts a notification if so. Errors are silently absorbed — a version check failure must
     * never affect the sync result.
     */
    private fun checkForUpdate() {
        when (val result = versionChecker.check()) {
            is VersionChecker.CheckResult.UpdateAvailable -> {
                syncPrefs.latestVersionAvailable = result.latestVersion
                updateNotifier.notifyUpdateAvailable(result.latestVersion)
            }
            is VersionChecker.CheckResult.UpToDate -> {
                // Clear any previously stored mismatch — app is now up to date.
                syncPrefs.latestVersionAvailable = null
                updateNotifier.cancelUpdateNotification()
            }
            is VersionChecker.CheckResult.CheckFailed -> {
                // Leave the stored value unchanged — we can't tell whether an update is available.
                Log.d(TAG, "Version check failed: ${result.reason}")
            }
        }
    }

    /**
     * Queries MediaStore for photos with DATE_ADDED > [afterSeconds] that were taken by the
     * device's camera (i.e. stored under DCIM/Camera/).
     *
     * We filter by RELATIVE_PATH (available since API 29 / Android 10) to exclude WhatsApp
     * downloads, screenshots, social media cache, and any other images that happen to be on
     * the device but were not captured by the camera app.
     */
    private fun queryNewPhotos(afterSeconds: Long): List<MediaEntry> {
        return queryNewMedia(
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Images.Media._ID,
            displayNameColumn = MediaStore.Images.Media.DISPLAY_NAME,
            dateAddedColumn = MediaStore.Images.Media.DATE_ADDED,
            mimeTypeColumn = MediaStore.Images.Media.MIME_TYPE,
            dateTakenColumn = MediaStore.Images.Media.DATE_TAKEN,
            afterSeconds = afterSeconds,
            defaultDisplayName = "photo.jpg",
            defaultMimeType = "image/jpeg",
        )
    }

    /**
     * Queries MediaStore for videos with DATE_ADDED > [afterSeconds] that were recorded by the
     * device's camera (i.e. stored under DCIM/Camera/).
     *
     * We filter by RELATIVE_PATH (available since API 29 / Android 10) to exclude WhatsApp
     * downloads, social media cache, and any other videos that happen to be on the device but
     * were not captured by the camera app.
     */
    private fun queryNewVideos(afterSeconds: Long): List<MediaEntry> {
        return queryNewMedia(
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            displayNameColumn = MediaStore.Video.Media.DISPLAY_NAME,
            dateAddedColumn = MediaStore.Video.Media.DATE_ADDED,
            mimeTypeColumn = MediaStore.Video.Media.MIME_TYPE,
            dateTakenColumn = MediaStore.Video.Media.DATE_TAKEN,
            afterSeconds = afterSeconds,
            defaultDisplayName = "video.mp4",
            defaultMimeType = "video/mp4",
        )
    }

    /**
     * Generic MediaStore query used by [queryNewPhotos] and [queryNewVideos].
     *
     * @param contentUri         The MediaStore collection URI to query (images or videos).
     * @param idColumn           Column name for the item ID.
     * @param displayNameColumn  Column name for the display name.
     * @param dateAddedColumn    Column name for DATE_ADDED.
     * @param mimeTypeColumn     Column name for MIME_TYPE.
     * @param dateTakenColumn    Column name for DATE_TAKEN.
     * @param afterSeconds       Only return items with DATE_ADDED > this value (epoch seconds).
     * @param defaultDisplayName Fallback display name if MediaStore returns null.
     * @param defaultMimeType    Fallback MIME type if MediaStore returns null.
     */
    private fun queryNewMedia(
        contentUri: Uri,
        idColumn: String,
        displayNameColumn: String,
        dateAddedColumn: String,
        mimeTypeColumn: String,
        dateTakenColumn: String,
        afterSeconds: Long,
        defaultDisplayName: String,
        defaultMimeType: String,
    ): List<MediaEntry> {
        val items = mutableListOf<MediaEntry>()

        val projection = arrayOf(
            idColumn,
            displayNameColumn,
            dateAddedColumn,
            mimeTypeColumn,
            dateTakenColumn,
        )
        // Restrict to items added after the last sync AND stored in the camera roll.
        // RELATIVE_PATH (available since API 29 / Android 10) is the directory path relative
        // to the storage volume root; it always ends with '/'.  Camera items are stored in
        // "DCIM/Camera/" by the standard Android camera app.  Using an exact match avoids
        // accidentally picking up paths like "DCIM/Camera_uploads/" (WhatsApp, etc.).
        val selection = "$dateAddedColumn > ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(afterSeconds.toString(), "DCIM/Camera/")
        val sortOrder = "$dateAddedColumn ASC"

        context.contentResolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(idColumn)
            val nameCol = cursor.getColumnIndexOrThrow(displayNameColumn)
            val dateCol = cursor.getColumnIndexOrThrow(dateAddedColumn)
            val mimeCol = cursor.getColumnIndexOrThrow(mimeTypeColumn)
            val dateTakenCol = cursor.getColumnIndexOrThrow(dateTakenColumn)

            while (cursor.moveToNext()) {
                // DATE_TAKEN is in milliseconds; it may be 0 for items with no recorded time.
                // Treat 0 as absent (null) so we don't send a misleading epoch timestamp to the server.
                val rawDateTaken = cursor.getLong(dateTakenCol)
                items.add(
                    MediaEntry(
                        id = cursor.getLong(idCol),
                        displayName = cursor.getString(nameCol) ?: defaultDisplayName,
                        dateAddedSeconds = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: defaultMimeType,
                        dateTakenMs = if (rawDateTaken > 0L) rawDateTaken else null,
                        contentUri = contentUri,
                    )
                )
            }
        }

        return items
    }

    /** Lightweight data class representing a photo or video from MediaStore. */
    data class MediaEntry(
        val id: Long,
        val displayName: String,
        val dateAddedSeconds: Long,
        val mimeType: String,
        /** The time the item was captured, in milliseconds since Unix epoch
         *  ([MediaStore.MediaColumns.DATE_TAKEN]).
         *  Null if MediaStore does not have this information (value was 0 or missing). */
        val dateTakenMs: Long?,
        /** The MediaStore content URI collection for this item (e.g. Images or Video). Used to
         *  construct the content URI for [android.content.ContentResolver.openInputStream]. */
        val contentUri: Uri,
    )

    companion object {
        private const val TAG = "PhotoSyncWorker"
    }
}
