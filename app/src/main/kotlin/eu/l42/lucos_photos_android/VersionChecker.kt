package eu.l42.lucos_photos_android

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks whether a newer version of the app is available by calling the
 * `/api/app/latest` endpoint on the lucos_photos server.
 *
 * The endpoint returns the version number of the latest published GitHub release.
 * If it differs from the currently installed version, an update is available.
 *
 * Note: Any mismatch (not just a newer version) triggers the banner — as per the
 * issue spec: "Any mismatch between the API and the currently running version
 * should trigger the banner and notification."
 */
class VersionChecker(
    private val serverUrl: String = Config.SERVER_URL,
    private val apiKey: String = Config.API_KEY,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * Checks whether the installed app version matches the latest release.
     *
     * @return [CheckResult.UpdateAvailable] if the API reports a different version,
     *         [CheckResult.UpToDate] if versions match,
     *         [CheckResult.CheckFailed] if the check could not be completed (network error,
     *         non-200 response, etc.). A failed check should be silently ignored — it must
     *         never cause a sync failure or be surfaced as an error to the user.
     */
    fun check(): CheckResult {
        return try {
            val request = Request.Builder()
                .url("$serverUrl/api/app/latest")
                .addHeader("Authorization", "key $apiKey")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Version check returned HTTP ${response.code} — skipping")
                    return CheckResult.CheckFailed("HTTP ${response.code}")
                }

                val body = response.body?.string() ?: run {
                    Log.d(TAG, "Version check returned empty body — skipping")
                    return CheckResult.CheckFailed("Empty response body")
                }

                val latestVersion = JSONObject(body).optString("version", "").ifBlank {
                    Log.d(TAG, "Version check response missing 'version' field — skipping")
                    return CheckResult.CheckFailed("Missing version field")
                }

                if (latestVersion != currentVersion) {
                    Log.i(TAG, "Version mismatch: running=$currentVersion, latest=$latestVersion")
                    CheckResult.UpdateAvailable(latestVersion)
                } else {
                    Log.d(TAG, "App is up to date (version=$currentVersion)")
                    CheckResult.UpToDate
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Version check failed (network): ${e.message}")
            CheckResult.CheckFailed("IOException: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "Version check failed (unexpected): ${e.message}")
            CheckResult.CheckFailed("Exception: ${e.message}")
        }
    }

    sealed class CheckResult {
        /** The running version matches the latest release. No action needed. */
        object UpToDate : CheckResult()

        /** The API reports a different version from what is currently installed. */
        data class UpdateAvailable(val latestVersion: String) : CheckResult()

        /** The check could not be completed. Treat as a no-op; do not surface to the user. */
        data class CheckFailed(val reason: String) : CheckResult()
    }

    companion object {
        private const val TAG = "VersionChecker"
    }
}
