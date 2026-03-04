package eu.l42.lucos_photos_android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Handles HTTP uploads to the lucos_photos server.
 *
 * Uploads are authenticated using the `Authorization: key <API_KEY>` scheme.
 * The server performs SHA256 deduplication, so uploading the same photo twice
 * is safe — the second upload returns HTTP 200 instead of 201.
 */
class PhotoUploader(
    private val serverUrl: String = Config.SERVER_URL,
    private val apiKey: String = Config.API_KEY,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /**
     * Result of an upload attempt.
     */
    sealed class UploadResult {
        /** Photo successfully uploaded (HTTP 201) or already existed (HTTP 200). */
        data object Success : UploadResult()

        /**
         * Upload failed. If [retryable] is true, WorkManager's retry mechanism should
         * be relied upon to retry later. Non-retryable failures (e.g. auth errors) are
         * logged but not retried.
         */
        data class Failure(val message: String, val retryable: Boolean) : UploadResult()
    }

    /**
     * Upload a single photo to the server.
     *
     * @param inputStream  Raw bytes of the photo file. The caller is responsible for closing it.
     * @param filename     Filename to send with the upload (used by the server to infer file extension).
     * @param mimeType     MIME type of the photo (e.g. "image/jpeg").
     */
    fun upload(inputStream: InputStream, filename: String, mimeType: String): UploadResult {
        // Stream directly from the InputStream rather than buffering the whole photo in memory.
        // Large photos (e.g. RAW files, 30–50 MB) could otherwise cause OutOfMemoryError.
        val mediaType = mimeType.toMediaType()
        val streamingBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun writeTo(sink: BufferedSink) {
                try {
                    sink.writeAll(inputStream.source())
                } catch (e: IOException) {
                    throw e // Let OkHttp surface this as a network/IO failure
                }
            }
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = filename,
                body = streamingBody,
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/photos")
            .addHeader("Authorization", "key $apiKey")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 200 || response.code == 201 -> UploadResult.Success
                    response.code == 401 || response.code == 403 -> {
                        UploadResult.Failure(
                            "Authentication failed (HTTP ${response.code}) — check API key",
                            retryable = false,
                        )
                    }
                    response.code == 507 -> {
                        UploadResult.Failure(
                            "Server storage full (HTTP 507)",
                            retryable = false,
                        )
                    }
                    response.code >= 500 -> {
                        UploadResult.Failure(
                            "Server error (HTTP ${response.code})",
                            retryable = true,
                        )
                    }
                    else -> {
                        UploadResult.Failure(
                            "Unexpected response (HTTP ${response.code})",
                            retryable = false,
                        )
                    }
                }
            }
        } catch (e: IOException) {
            UploadResult.Failure("Network error: ${e.message}", retryable = true)
        }
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
