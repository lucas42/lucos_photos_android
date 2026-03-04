package eu.l42.lucos_photos_android

/**
 * Configuration for the lucos_photos server connection.
 *
 * For v1, these are hardcoded. Future versions may expose a settings UI.
 *
 * The API key is injected at build time from `local.properties` (which is gitignored).
 * To set the real key before sideloading, add `photos_api_key=YOUR_KEY` to `local.properties`
 * in the project root. Never commit the real key to the repository.
 */
object Config {
    /**
     * Base URL of the lucos_photos server, without trailing slash.
     * Example: "https://photos.example.com"
     */
    const val SERVER_URL: String = "https://photos.l42.eu"

    /**
     * API key for authentication. Injected at build time from [BuildConfig.PHOTOS_API_KEY],
     * which is read from `local.properties` (gitignored). If `local.properties` does not contain
     * a `photos_api_key` entry, this will be the placeholder value and uploads will fail with 401.
     *
     * This is the raw key value (not including the "key " prefix).
     * Must correspond to an entry in the server's CLIENT_KEYS environment variable.
     */
    val API_KEY: String = BuildConfig.PHOTOS_API_KEY

    /** How often to run the background sync, in hours. */
    const val SYNC_INTERVAL_HOURS: Long = 1L
}
