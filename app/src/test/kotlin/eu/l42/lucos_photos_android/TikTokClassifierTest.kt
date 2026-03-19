package eu.l42.lucos_photos_android

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TikTokClassifierTest {

    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    // ---------------------------------------------------------------------------
    // OWNER_PACKAGE_NAME signal (score = 100, always above threshold alone)
    // ---------------------------------------------------------------------------

    @Test
    fun `known TikTok package name classifies as TikTok`() {
        val result = TikTokClassifier.classify(
            ownerPackage = "com.zhiliaoapp.musically",
            dateTakenMs = 1700000000000L,
            displayName = "normal_camera_video.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertTrue("should be TikTok when owner package matches", result.isTikTok)
        assertTrue(TikTokClassifier.Signal.OWNER_PACKAGE in result.signals)
    }

    @Test
    fun `trill package name classifies as TikTok`() {
        val result = TikTokClassifier.classify(
            ownerPackage = "com.ss.android.ugc.trill",
            dateTakenMs = 1700000000000L,
            displayName = "normal_camera_video.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertTrue(result.isTikTok)
        assertTrue(TikTokClassifier.Signal.OWNER_PACKAGE in result.signals)
    }

    @Test
    fun `unknown package name does not trigger owner package signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = "com.example.camera",
            dateTakenMs = 1700000000000L,
            displayName = "normal.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.OWNER_PACKAGE in result.signals)
    }

    @Test
    fun `null owner package does not trigger owner package signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "normal.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.OWNER_PACKAGE in result.signals)
    }

    // ---------------------------------------------------------------------------
    // DATE_TAKEN signal (score = 40)
    // ---------------------------------------------------------------------------

    @Test
    fun `null dateTaken triggers no-date-taken signal`() {
        // Use landscape + long duration to isolate NO_DATE_TAKEN signal only
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = null,
            displayName = "video.mp4",
            width = 1920,
            height = 1080,
            durationMs = 300_000L,
        )
        assertTrue(TikTokClassifier.Signal.NO_DATE_TAKEN in result.signals)
        assertEquals(40, result.score)
    }

    @Test
    fun `zero dateTaken triggers no-date-taken signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 0L,
            displayName = "video.mp4",
            width = 1920,
            height = 1080,
            durationMs = 300_000L,
        )
        assertTrue(TikTokClassifier.Signal.NO_DATE_TAKEN in result.signals)
    }

    @Test
    fun `positive dateTaken does not trigger no-date-taken signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.NO_DATE_TAKEN in result.signals)
    }

    // ---------------------------------------------------------------------------
    // Filename signal (score = 30)
    // ---------------------------------------------------------------------------

    @Test
    fun `TikTok filename pattern triggers filename signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "v09044190000ocqvhkfeaaaaaaaa.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertTrue(TikTokClassifier.Signal.TIKTOK_FILENAME in result.signals)
    }

    @Test
    fun `short digit sequence does not trigger filename signal`() {
        // Pattern requires 8+ digits — only 7 here
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "v1234567abc.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.TIKTOK_FILENAME in result.signals)
    }

    @Test
    fun `normal camera filename does not trigger filename signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "VID_20260101_120000.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.TIKTOK_FILENAME in result.signals)
    }

    // ---------------------------------------------------------------------------
    // Portrait aspect ratio signal (score = 10)
    // ---------------------------------------------------------------------------

    @Test
    fun `portrait 9x16 triggers aspect ratio signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 1080,
            height = 1920,
            durationMs = 60_000L,
        )
        assertTrue(TikTokClassifier.Signal.PORTRAIT_ASPECT in result.signals)
    }

    @Test
    fun `landscape 16x9 does not trigger aspect ratio signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 1920,
            height = 1080,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.PORTRAIT_ASPECT in result.signals)
    }

    @Test
    fun `portrait but non-9x16 does not trigger aspect ratio signal`() {
        // 3:4 portrait — not TikTok aspect ratio
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 720,
            height = 960,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.PORTRAIT_ASPECT in result.signals)
    }

    @Test
    fun `zero dimensions do not trigger aspect ratio signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 0,
            height = 0,
            durationMs = 60_000L,
        )
        assertFalse(TikTokClassifier.Signal.PORTRAIT_ASPECT in result.signals)
    }

    // ---------------------------------------------------------------------------
    // Duration signal (score = 5)
    // ---------------------------------------------------------------------------

    @Test
    fun `60 second video triggers short duration signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 1920,
            height = 1080,
            durationMs = 60_000L,
        )
        assertTrue(TikTokClassifier.Signal.SHORT_DURATION in result.signals)
    }

    @Test
    fun `exactly 180 second video does not trigger short duration signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 1920,
            height = 1080,
            durationMs = 180_000L,
        )
        assertFalse(TikTokClassifier.Signal.SHORT_DURATION in result.signals)
    }

    @Test
    fun `zero duration does not trigger short duration signal`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "video.mp4",
            width = 1920,
            height = 1080,
            durationMs = 0L,
        )
        assertFalse(TikTokClassifier.Signal.SHORT_DURATION in result.signals)
    }

    // ---------------------------------------------------------------------------
    // Threshold / combined scoring
    // ---------------------------------------------------------------------------

    @Test
    fun `Android 11+ TikTok video with no-date-taken and TikTok filename scores 70 and is filtered`() {
        // Typical Android 11+ scenario: owner redacted, no DATE_TAKEN, TikTok filename.
        // Use landscape + long duration to isolate only these two signals.
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = null,
            displayName = "v09044190000ocqvhkfea.mp4",
            width = 1920,
            height = 1080,
            durationMs = 300_000L,
        )
        // NO_DATE_TAKEN(40) + TIKTOK_FILENAME(30) = 70
        assertEquals(70, result.score)
        assertTrue(result.isTikTok)
    }

    @Test
    fun `downloaded video with no date taken but non-TikTok filename scores 40 and is not filtered`() {
        // Non-TikTok downloaded video (e.g. YouTube download saved to DCIM).
        // Landscape + long duration to isolate NO_DATE_TAKEN only.
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = null,
            displayName = "downloaded_clip.mp4",
            width = 1920,
            height = 1080,
            durationMs = 300_000L,
        )
        // NO_DATE_TAKEN(40) only
        assertEquals(40, result.score)
        assertFalse(result.isTikTok)
    }

    @Test
    fun `camera-recorded portrait video does not exceed threshold`() {
        // Camera-shot portrait video: has DATE_TAKEN, normal filename, portrait 9:16, short
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = 1700000000000L,
            displayName = "VID_20260101_120000.mp4",
            width = 1080,
            height = 1920,
            durationMs = 30_000L,
        )
        // PORTRAIT_ASPECT(10) + SHORT_DURATION(5) = 15
        assertEquals(15, result.score)
        assertFalse(result.isTikTok)
    }

    @Test
    fun `score is sum of triggered signal scores`() {
        val result = TikTokClassifier.classify(
            ownerPackage = null,
            dateTakenMs = null,      // +40
            displayName = "v09044190000abc.mp4",  // +30
            width = 1080,
            height = 1920,           // +10 (9:16)
            durationMs = 60_000L,    // +5
        )
        assertEquals(85, result.score)
    }
}
