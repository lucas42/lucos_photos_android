package eu.l42.lucos_photos_android

/**
 * Configuration for the lucos_photos server connection.
 *
 * For v1, these are hardcoded. Future versions may expose a settings UI.
 */
object Config {
    /**
     * Base URL of the lucos_photos server, without trailing slash.
     * Example: "https://photos.example.com"
     */
    const val SERVER_URL: String = "https://photos.l42.eu"

    /**
     * API key for authentication.
     * This is the raw key value (not including the "key " prefix).
     * Must correspond to an entry in the server's CLIENT_KEYS environment variable.
     */
    const val API_KEY: String = "REPLACE_WITH_YOUR_API_KEY"

    /** How often to run the background sync, in hours. */
    const val SYNC_INTERVAL_HOURS: Long = 1L
}
