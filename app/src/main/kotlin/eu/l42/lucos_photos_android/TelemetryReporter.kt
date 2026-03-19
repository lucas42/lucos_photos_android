package eu.l42.lucos_photos_android

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Sends telemetry events to the lucos_photos server's `/api/telemetry` endpoint.
 *
 * Telemetry is best-effort: failures are logged but never surfaced to the caller.
 * A telemetry failure must never cause a sync to be retried or fail.
 */
class TelemetryReporter(
    private val serverUrl: String = Config.SERVER_URL,
    private val apiKey: String = Config.API_KEY,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * Reports a sync run outcome to the telemetry endpoint.
     *
     * @param durationMs            Total elapsed time of the sync run in milliseconds.
     * @param itemsFound            Total number of media items returned by the MediaStore query
     *                              (photos + videos combined; kept for backwards compatibility).
     * @param photosFound           Number of photo items returned by the MediaStore query.
     * @param videosFound           Number of video items returned by the MediaStore query.
     * @param photosSynced          Number of items successfully uploaded as new (HTTP 201).
     * @param alreadyUploaded       Number of items already present on the server (HTTP 200).
     * @param errors                Number of upload failures (retryable or non-retryable).
     * @param errorBreakdown        Map of error key (HTTP status code string or "network"/"stream"/
     *                              "exception") to count of items that failed with that error.
     * @param relativePathSample    The RELATIVE_PATH of the first item found, or null if none.
     *                              Surfaces the actual path seen by the app, including any volume
     *                              prefix (e.g. "primary:DCIM/Camera/"), to aid filter debugging.
     * @param tiktokFiltered        Number of videos excluded by the TikTok heuristic classifier.
     * @param tiktokSignalBreakdown For each [TikTokClassifier.Signal] that contributed to a
     *                              filtered video, the count of videos that triggered that signal.
     *                              Empty map if no videos were filtered.
     * @param succeeded             Whether the sync completed fully (true) or was retried (false).
     */
    fun reportSync(
        durationMs: Long,
        itemsFound: Int,
        photosFound: Int,
        videosFound: Int,
        photosSynced: Int,
        alreadyUploaded: Int,
        errors: Int,
        errorBreakdown: Map<String, Int>,
        relativePathSample: String?,
        tiktokFiltered: Int = 0,
        tiktokSignalBreakdown: Map<String, Int> = emptyMap(),
        succeeded: Boolean,
    ) {
        val eventType = if (succeeded) "sync_completed" else "sync_failed"
        val data = JSONObject().apply {
            put("duration_ms", durationMs)
            put("items_found", itemsFound)
            put("photos_found", photosFound)
            put("videos_found", videosFound)
            put("photos_synced", photosSynced)
            put("already_uploaded", alreadyUploaded)
            put("errors", errors)
            if (errorBreakdown.isNotEmpty()) {
                put("error_breakdown", JSONObject(errorBreakdown))
            }
            if (relativePathSample != null) {
                put("relative_path_sample", relativePathSample)
            }
            put("tiktok_filtered", tiktokFiltered)
            if (tiktokSignalBreakdown.isNotEmpty()) {
                put("tiktok_signal_breakdown", JSONObject(tiktokSignalBreakdown))
            }
        }
        sendEvent(eventType, data)
    }

    /**
     * Sends a single telemetry event. Errors are caught and logged; never propagated.
     */
    private fun sendEvent(eventType: String, data: JSONObject?) {
        try {
            val payload = JSONObject().apply {
                put("event_type", eventType)
                put("app_version", appVersion)
                put("timestamp", isoNow())
                if (data != null) put("data", data)
            }

            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$serverUrl/api/telemetry")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Telemetry event '$eventType' rejected: HTTP ${response.code}")
                } else {
                    Log.d(TAG, "Telemetry event '$eventType' recorded (HTTP ${response.code})")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send telemetry event '$eventType': ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error sending telemetry event '$eventType': ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TelemetryReporter"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Format the current time as an ISO-8601 UTC string (e.g. "2026-03-10T00:00:00Z"). */
        internal fun isoNow(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date())
        }
    }
}
