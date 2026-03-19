package eu.l42.lucos_photos_android

import android.util.Log

/**
 * Classifies videos as TikTok downloads using a weighted multi-signal heuristic.
 *
 * On Android 11+, [android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME] is redacted to
 * null for media owned by other apps. A single package-name check is therefore insufficient. This
 * classifier combines several weaker signals to reach a reliable decision even when the owner
 * package is unavailable.
 *
 * ## Scoring table
 *
 * | Signal | Score |
 * |---|---|
 * | `OWNER_PACKAGE_NAME` matches a known TikTok package | 100 |
 * | `DATE_TAKEN` is null or zero | 40 |
 * | Filename matches TikTok pattern (`^v\d{8,}[a-z0-9]+`) | 30 |
 * | Portrait 9:16 aspect ratio (width < height and width * 16 == height * 9) | 10 |
 * | Duration < 180 seconds | 5 |
 *
 * **Threshold: score >= 60 → classified as TikTok.**
 *
 * Expected outcomes:
 * - Android <11 (owner package available): score = 100+, always filtered.
 * - Android 11+ downloaded TikTok video (null owner, no DATE_TAKEN, TikTok filename): score = 70, filtered.
 * - Android 11+ downloaded TikTok video (null owner, no DATE_TAKEN, generic filename): score = 40, not filtered.
 * - Camera-recorded portrait video (has DATE_TAKEN, camera filename): score ≤ 15, not filtered.
 */
object TikTokClassifier {

    private const val TAG = "TikTokClassifier"

    /** Score threshold at or above which a video is classified as TikTok. */
    const val THRESHOLD = 60

    /** Known TikTok app package names. */
    private val TIKTOK_PACKAGE_NAMES = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    /**
     * Regex matching TikTok's distinctive video filename pattern, e.g. `v09044190000ocqvhk...mp4`.
     * Starts with 'v', followed by 8+ digits, then alphanumeric characters.
     */
    private val TIKTOK_FILENAME_REGEX = Regex("^v\\d{8,}[a-z0-9]+", RegexOption.IGNORE_CASE)

    /**
     * Holds the result of a classification, including the total score and the set of signals
     * that contributed to it (for logging and telemetry).
     */
    data class Result(
        val score: Int,
        val signals: Set<Signal>,
    ) {
        val isTikTok: Boolean get() = score >= THRESHOLD
    }

    /** Individual scoring signals. */
    enum class Signal(val score: Int) {
        OWNER_PACKAGE(100),
        NO_DATE_TAKEN(40),
        TIKTOK_FILENAME(30),
        PORTRAIT_ASPECT(10),
        SHORT_DURATION(5),
    }

    /**
     * Classifies a video based on its metadata.
     *
     * @param ownerPackage  Value of [android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME],
     *                      or null if redacted (Android 11+).
     * @param dateTakenMs   Value of [android.provider.MediaStore.MediaColumns.DATE_TAKEN] in
     *                      milliseconds, or null/0 if absent.
     * @param displayName   The video filename (e.g. "v09044190000ocqvhk.mp4").
     * @param width         Width in pixels from [android.provider.MediaStore.MediaColumns.WIDTH],
     *                      or 0 if unavailable.
     * @param height        Height in pixels from [android.provider.MediaStore.MediaColumns.HEIGHT],
     *                      or 0 if unavailable.
     * @param durationMs    Duration in milliseconds from
     *                      [android.provider.MediaStore.Video.VideoColumns.DURATION], or 0 if unavailable.
     */
    fun classify(
        ownerPackage: String?,
        dateTakenMs: Long?,
        displayName: String,
        width: Int,
        height: Int,
        durationMs: Long,
    ): Result {
        val signals = mutableSetOf<Signal>()

        if (ownerPackage in TIKTOK_PACKAGE_NAMES) {
            signals += Signal.OWNER_PACKAGE
        }
        if (dateTakenMs == null || dateTakenMs == 0L) {
            signals += Signal.NO_DATE_TAKEN
        }
        if (TIKTOK_FILENAME_REGEX.containsMatchIn(displayName)) {
            signals += Signal.TIKTOK_FILENAME
        }
        if (width > 0 && height > 0 && width < height && width.toLong() * 16L == height.toLong() * 9L) {
            signals += Signal.PORTRAIT_ASPECT
        }
        if (durationMs in 1L until 180_000L) {
            signals += Signal.SHORT_DURATION
        }

        val score = signals.sumOf { it.score }
        val result = Result(score = score, signals = signals)

        Log.d(TAG, "classify: file=$displayName owner=$ownerPackage dateTaken=$dateTakenMs " +
            "size=${width}x${height} durationMs=$durationMs → score=$score " +
            "signals=${signals.map { it.name }} isTikTok=${result.isTikTok}")

        return result
    }
}
