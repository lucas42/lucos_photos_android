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
 *
 * On network failure, WorkManager's built-in exponential backoff retry handles rescheduling.
 * The server handles deduplication via SHA256, so re-uploading a photo is harmless.
 */
class PhotoSyncWorker(
    private val context: Context,
    params: WorkerParameters,
    private val uploader: PhotoUploader = PhotoUploader(),
    private val syncPrefs: SyncPreferences = SyncPreferences(context),
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting photo sync")

        val lastSyncMs = syncPrefs.lastSyncTimestampMs
        // MediaStore DATE_ADDED is in seconds since epoch; convert for comparison
        val lastSyncSeconds = lastSyncMs / 1000L

        Log.i(TAG, "Querying for photos added after timestamp $lastSyncSeconds (epoch seconds)")

        val photos = queryNewPhotos(lastSyncSeconds)
        Log.i(TAG, "Found ${photos.size} new photo(s) to upload")

        var anyRetryableFailure = false

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
                    )
                } ?: PhotoUploader.UploadResult.Failure("Could not open input stream", retryable = true)
            } catch (e: Exception) {
                Log.e(TAG, "Exception uploading photo ${photo.id}", e)
                PhotoUploader.UploadResult.Failure("Exception: ${e.message}", retryable = true)
            }

            when (result) {
                is PhotoUploader.UploadResult.Success -> {
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
                is PhotoUploader.UploadResult.Failure -> {
                    Log.w(TAG, "Failed to upload photo ${photo.id}: ${result.message} (retryable=${result.retryable})")
                    if (result.retryable) {
                        anyRetryableFailure = true
                        // Stop processing further photos — retry later with the same timestamp
                        break
                    }
                    // Non-retryable (e.g. auth error): log and skip this photo. Only advance the
                    // timestamp when all photos at this second are done (same logic as success).
                    val nextPhotoInDifferentSecond = index + 1 >= photos.size ||
                            photos[index + 1].dateAddedSeconds > photo.dateAddedSeconds
                    if (nextPhotoInDifferentSecond) {
                        syncPrefs.lastSyncTimestampMs = photo.dateAddedSeconds * 1000L
                    }
                }
            }
        }

        if (anyRetryableFailure) {
            Log.i(TAG, "Sync incomplete — scheduling retry via WorkManager backoff")
            Result.retry()
        } else {
            Log.i(TAG, "Sync complete")
            Result.success()
        }
    }

    /**
     * Queries MediaStore for photos with DATE_ADDED > [afterSeconds].
     * Returns them ordered by DATE_ADDED ascending so we process oldest-first and can
     * advance the sync timestamp incrementally even if a batch is interrupted.
     */
    private fun queryNewPhotos(afterSeconds: Long): List<PhotoEntry> {
        val photos = mutableListOf<PhotoEntry>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(afterSeconds.toString())
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

            while (cursor.moveToNext()) {
                photos.add(
                    PhotoEntry(
                        id = cursor.getLong(idCol),
                        displayName = cursor.getString(nameCol) ?: "photo.jpg",
                        dateAddedSeconds = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "image/jpeg",
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
    )

    companion object {
        private const val TAG = "PhotoSyncWorker"
    }
}
