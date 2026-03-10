package eu.l42.lucos_photos_android

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that syncs new photos from the device's MediaStore to the lucos_photos server.
 *
 * This worker:
 * 1. Queries MediaStore for photos added after the last successful sync timestamp.
 * 2. Uploads each new photo to the server via [PhotoUploader].
 * 3. Updates the last sync timestamp after each successful upload to avoid re-uploading.
 * 4. Reports a telemetry event to the server after each sync run via [TelemetryReporter].
 *
 * On network failure, WorkManager's built-in exponential backoff retry handles rescheduling.
 * The server handles deduplication via SHA256, so re-uploading a photo is harmless.
 */
class PhotoSyncWorker(
    private val context: Context,
    params: WorkerParameters,
    private val uploader: PhotoUploader = PhotoUploader(),
    private val syncPrefs: SyncPreferences = SyncPreferences(context),
    private val telemetry: TelemetryReporter = TelemetryReporter(),
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting photo sync")

        val syncStartMs = System.currentTimeMillis()

        val lastSyncMs = syncPrefs.lastSyncTimestampMs
        // MediaStore DATE_ADDED is in seconds since epoch; convert for comparison
        val lastSyncSeconds = lastSyncMs / 1000L

        Log.i(TAG, "Querying for photos added after timestamp $lastSyncSeconds (epoch seconds)")

        val photos = queryNewPhotos(lastSyncSeconds)
        Log.i(TAG, "Found ${photos.size} new photo(s) to upload")

        var anyRetryableFailure = false
        var photosSynced = 0
        var errors = 0

        for ((index, photo) in photos.withIndex()) {
            Log.d(TAG, "Uploading photo: ${photo.displayName} (id=${photo.id})")

            val result = try {
                context.contentResolver.openInputStream(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)
                )?.use { stream ->
                    uploader.upload(
                        inputStream = stream,
                        filename = photo.displayName,
                        mimeType = photo.mimeType,
                        dateTakenMs = photo.dateTakenMs,
                    )
                } ?: PhotoUploader.UploadResult.Failure("Could not open input stream", retryable = true)
            } catch (e: Exception) {
                Log.e(TAG, "Exception uploading photo ${photo.id}", e)
                PhotoUploader.UploadResult.Failure("Exception: ${e.message}", retryable = true)
            }

            when (result) {
                is PhotoUploader.UploadResult.Success -> {
                    photosSynced++
                    Log.i(TAG, "Successfully uploaded photo ${photo.id} (${photo.displayName})")
                    // Only advance the sync timestamp once all photos at this DATE_ADDED second
                    // have been processed. DATE_ADDED has one-second granularity, so advancing to
                    // photo.dateAddedSeconds after the first photo of a batch-at-the-same-second
                    // would cause the query (DATE_ADDED > lastSyncSeconds) to skip remaining photos
                    // in that same second. We advance only when the next photo is in a later second
                    // (or there are no more photos).
                    val nextPhotoInDifferentSecond = index + 1 >= photos.size ||
                            photos[index + 1].dateAddedSeconds > photo.dateAddedSeconds
                    if (nextPhotoInDifferentSecond) {
                        syncPrefs.lastSyncTimestampMs = photo.dateAddedSeconds * 1000L
                    }
                }
                is PhotoUploader.UploadResult.AuthFailure -> {
                    // An auth error (401/403) almost certainly means our API key is wrong, which
                    // will affect every photo — not just this one. Do NOT advance the sync
                    // timestamp. Instead, stop the batch and schedule a retry so that once the
                    // key is corrected, all photos in the current window are still uploaded.
                    errors++
                    Log.e(TAG, "Auth failure uploading photo ${photo.id}: ${result.message} — stopping batch, will retry")
                    anyRetryableFailure = true
                    break
                }
                is PhotoUploader.UploadResult.Failure -> {
                    errors++
                    Log.w(TAG, "Failed to upload photo ${photo.id}: ${result.message} (retryable=${result.retryable})")
                    if (result.retryable) {
                        anyRetryableFailure = true
                        // Stop processing further photos — retry later with the same timestamp
                        break
                    }
                    // Non-retryable (e.g. storage full on server): log and skip this photo.
                    // Only advance the timestamp when all photos at this second are done
                    // (same logic as success).
                    val nextPhotoInDifferentSecond = index + 1 >= photos.size ||
                            photos[index + 1].dateAddedSeconds > photo.dateAddedSeconds
                    if (nextPhotoInDifferentSecond) {
                        syncPrefs.lastSyncTimestampMs = photo.dateAddedSeconds * 1000L
                    }
                }
            }
        }

        val durationMs = System.currentTimeMillis() - syncStartMs

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
     * Queries MediaStore for photos with DATE_ADDED > [afterSeconds] that were taken by the
     * device's camera (i.e. stored under DCIM/Camera/).
     *
     * We filter by RELATIVE_PATH (available since API 29 / Android 10) to exclude WhatsApp
     * downloads, screenshots, social media cache, and any other images that happen to be on
     * the device but were not captured by the camera app.
     *
     * Returns results ordered by DATE_ADDED ascending so we process oldest-first and can
     * advance the sync timestamp incrementally even if a batch is interrupted.
     */
    private fun queryNewPhotos(afterSeconds: Long): List<PhotoEntry> {
        val photos = mutableListOf<PhotoEntry>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        // Restrict to photos added after the last sync AND stored in the camera roll.
        // RELATIVE_PATH (available since API 29 / Android 10) is the directory path relative
        // to the storage volume root; it always ends with '/'.  Camera photos are stored in
        // "DCIM/Camera/" by the standard Android camera app.  Using an exact match avoids
        // accidentally picking up paths like "DCIM/Camera_uploads/" (WhatsApp, etc.).
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(afterSeconds.toString(), "DCIM/Camera/")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            while (cursor.moveToNext()) {
                // DATE_TAKEN is in milliseconds; it may be 0 for items with no recorded time.
                // Treat 0 as absent (null) so we don't send a misleading epoch timestamp to the server.
                val rawDateTaken = cursor.getLong(dateTakenCol)
                photos.add(
                    PhotoEntry(
                        id = cursor.getLong(idCol),
                        displayName = cursor.getString(nameCol) ?: "photo.jpg",
                        dateAddedSeconds = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "image/jpeg",
                        dateTakenMs = if (rawDateTaken > 0L) rawDateTaken else null,
                    )
                )
            }
        }

        return photos
    }

    /** Lightweight data class representing a photo from MediaStore. */
    data class PhotoEntry(
        val id: Long,
        val displayName: String,
        val dateAddedSeconds: Long,
        val mimeType: String,
        /** The time the photo was taken, in milliseconds since Unix epoch ([MediaStore.Images.Media.DATE_TAKEN]).
         *  Null if MediaStore does not have this information (value was 0 or missing). */
        val dateTakenMs: Long?,
    )

    companion object {
        private const val TAG = "PhotoSyncWorker"
    }
}
