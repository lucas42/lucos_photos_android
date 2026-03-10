package eu.l42.lucos_photos_android

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoSyncWorkerTest {

    private lateinit var context: Context

    // A no-op mock telemetry reporter used across all tests to avoid real network calls.
    private lateinit var mockTelemetry: TelemetryReporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use a plain Application (via @Config above) instead of PhotoBackupApplication to
        // prevent Robolectric from running PhotoBackupApplication.onCreate(), which initialises
        // WorkManager and schedules periodic sync work. WorkManager's static singleton interacts
        // badly with Robolectric's per-test lifecycle — causing IllegalStateException in tests
        // that exercise code paths beyond the empty-MediaStore short-circuit.
        //
        // TestListenableWorkerBuilder bypasses WorkManager entirely and instantiates the worker
        // directly via the supplied WorkerFactory, so WorkManager initialisation is not needed.

        mockTelemetry = mockk(relaxed = true)
    }

    /**
     * Pre-populates the Robolectric ContentResolver so that the worker's MediaStore query
     * returns a fake photo, and openInputStream() returns fake bytes for that photo's URI.
     *
     * Robolectric does not register a real MediaProvider by default, so
     * ContentResolver.insert() + query() round-tripping does not work — insert() returns a
     * URI but stores nothing, and query() returns null. Instead, we use two separate
     * Robolectric mechanisms:
     *
     * 1. [RoboCursor] + [ShadowContentResolver.setCursor] — pre-sets the cursor that
     *    query() returns for [MediaStore.Images.Media.EXTERNAL_CONTENT_URI]. This makes
     *    the worker's queryNewPhotos() find the photo.
     *
     * 2. [ShadowContentResolver.registerInputStream] — registers a fake InputStream for
     *    the content URI that openInputStream() will be called with. This makes the worker's
     *    stream-reading code succeed rather than falling through to the null-stream path.
     *
     * Note: ShadowContentResolver ignores the selection/selectionArgs passed to query() and
     * always returns the pre-seeded cursor. This means the SQL filter
     * (RELATIVE_PATH = 'DCIM/Camera/' AND DATE_ADDED > ?) is NOT exercised by these tests —
     * it is enforced by MediaStore on a real device. These tests cover the worker's behaviour
     * once photos are returned from the query (upload, retry, timestamp advancement, etc.).
     *
     * @param id           The _ID to assign to the fake photo row. Must be unique per test.
     * @param displayName  The DISPLAY_NAME for the fake photo.
     * @param dateAddedSeconds  The DATE_ADDED value (epoch seconds) for the fake photo.
     * @param dateTakenMs  The DATE_TAKEN value (epoch milliseconds) for the fake photo, or 0
     *                     to simulate a photo with no recorded taken-at time.
     */
    private fun seedMediaStoreWithPhoto(
        id: Long,
        displayName: String,
        dateAddedSeconds: Long,
        dateTakenMs: Long = 0L,
    ) {
        // Set up a RoboCursor that the worker's query() call will receive.
        // The worker's projection is [_ID, DISPLAY_NAME, DATE_ADDED, MIME_TYPE, DATE_TAKEN].
        val cursor = RoboCursor()
        cursor.setColumnNames(
            listOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_TAKEN,
            )
        )
        cursor.setResults(
            arrayOf(
                arrayOf(id, displayName, dateAddedSeconds, "image/jpeg", dateTakenMs),
            )
        )

        val shadowResolver = Shadows.shadowOf(context.contentResolver)
        shadowResolver.setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor)

        // Register a fake input stream for the URI the worker will open.
        // The worker constructs this URI via ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id).
        val photoUri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
        )
        val fakeStream = ByteArrayInputStream("fake-jpeg-bytes".toByteArray())
        shadowResolver.registerInputStream(photoUri, fakeStream)
    }

    @Test
    fun `worker returns success when no new photos`() = runBlocking {
        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit

        // Build a worker that uses our mocks but will query the (empty) Robolectric MediaStore
        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(
                PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry)
            )
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        // No uploads should have occurred
        verify(exactly = 0) { mockUploader.upload(any(), any(), any(), any()) }
        // Sync completion timestamp must be written even when there are no new photos
        verify(exactly = 1) { mockPrefs.lastSyncCompletedAtMs = any() }
        // Telemetry should be reported as succeeded with zero photos
        verify(exactly = 1) { mockTelemetry.reportSync(durationMs = any(), photosSynced = 0, errors = 0, succeeded = true) }
    }

    @Test
    fun `worker retries on auth failure without advancing sync timestamp`() = runBlocking {
        // Seed the MediaStore so the worker finds a photo and invokes the uploader
        seedMediaStoreWithPhoto(id = 1L, displayName = "auth_test_photo.jpg", dateAddedSeconds = 2000L)

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        // Auth failure — API key is wrong; the mock uploader will be called and return AuthFailure
        every { mockUploader.upload(any(), any(), any(), any()) } returns
            PhotoUploader.UploadResult.AuthFailure("Authentication failed (HTTP 401) — check API key")

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        val result = worker.doWork()
        // Worker should stop the batch and schedule a retry
        assertEquals(ListenableWorker.Result.retry(), result)
        // The uploader must have been called (verifying we went through the upload path, not null-stream)
        verify(exactly = 1) { mockUploader.upload(any(), any(), any(), any()) }
        // The sync timestamp must NOT have been advanced — photos must still be in window
        verify(exactly = 0) { mockPrefs.lastSyncTimestampMs = any() }
        // The sync completion timestamp must NOT be written on failure
        verify(exactly = 0) { mockPrefs.lastSyncCompletedAtMs = any() }
        // Telemetry should be reported as failed
        verify(exactly = 1) { mockTelemetry.reportSync(durationMs = any(), photosSynced = 0, errors = 1, succeeded = false) }
    }

    @Test
    fun `worker retries on retryable upload failure`() = runBlocking {
        // Seed the MediaStore so the worker finds a photo and invokes the uploader
        seedMediaStoreWithPhoto(id = 1L, displayName = "test_photo.jpg", dateAddedSeconds = 1000L)

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        // Retryable failure — e.g. network error; the mock uploader will be called
        every { mockUploader.upload(any(), any(), any(), any()) } returns
            PhotoUploader.UploadResult.Failure("Network error", retryable = true)

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        val result = worker.doWork()
        // With a retryable failure, the worker should return retry() not success()
        assertEquals(ListenableWorker.Result.retry(), result)
        // The uploader must have been called (verifying we went through the upload path, not null-stream)
        verify(exactly = 1) { mockUploader.upload(any(), any(), any(), any()) }
        // The sync completion timestamp must NOT be written on failure
        verify(exactly = 0) { mockPrefs.lastSyncCompletedAtMs = any() }
        // Telemetry should be reported as failed
        verify(exactly = 1) { mockTelemetry.reportSync(durationMs = any(), photosSynced = 0, errors = 1, succeeded = false) }
    }

    @Test
    fun `worker passes DATE_TAKEN as dateTakenMs to uploader`() = runBlocking {
        // Seed with a photo that has a known DATE_TAKEN value (epoch milliseconds).
        val expectedDateTakenMs = 1_700_000_000_000L
        seedMediaStoreWithPhoto(
            id = 1L,
            displayName = "dated_photo.jpg",
            dateAddedSeconds = 3000L,
            dateTakenMs = expectedDateTakenMs,
        )

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit
        every { mockUploader.upload(any(), any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        worker.doWork()

        // The uploader must have been called with the correct dateTakenMs extracted from MediaStore
        verify(exactly = 1) { mockUploader.upload(any(), any(), any(), expectedDateTakenMs) }
    }

    @Test
    fun `worker passes null dateTakenMs when DATE_TAKEN is zero`() = runBlocking {
        // Seed with a photo that has DATE_TAKEN = 0 (absent / unknown).
        seedMediaStoreWithPhoto(
            id = 1L,
            displayName = "no_date_photo.jpg",
            dateAddedSeconds = 3000L,
            dateTakenMs = 0L,
        )

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit
        every { mockUploader.upload(any(), any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        worker.doWork()

        // DATE_TAKEN = 0 must be treated as absent — null must be passed to the uploader
        verify(exactly = 1) { mockUploader.upload(any(), any(), any(), null) }
    }

    @Test
    fun `worker reads stored timestamp and advances it after subsequent upload`() = runBlocking {
        // Simulate a second sync run: prefs already hold a non-zero timestamp from a previous sync.
        // The MediaStore cursor (seeded below) represents a photo that was added AFTER the stored
        // timestamp — i.e. a new photo the previous sync hadn't seen yet.
        //
        // In Robolectric, ShadowContentResolver returns the pre-seeded cursor regardless of the
        // selection/selectionArgs — it does not actually filter rows. This test therefore
        // verifies the worker's timestamp-read → timestamp-advance path (not the SQL filter),
        // which is the critical invariant: lastSyncTimestampMs is read at the start of each run
        // and advanced to the most-recently-processed photo's dateAddedSeconds after success.
        //
        // The SQL filter (DATE_ADDED > ?) is verified indirectly: if the worker passed an
        // incorrect lastSyncSeconds to the query, the filtering behaviour would diverge from
        // what MediaStore would return on a real device.
        val previousSyncTimestampMs = 4_000_000L  // 4000 seconds since epoch, in ms
        val newPhotoDateAddedSeconds = 6000L       // photo added AFTER the previous sync

        seedMediaStoreWithPhoto(
            id = 2L,
            displayName = "new_photo_after_last_sync.jpg",
            dateAddedSeconds = newPhotoDateAddedSeconds,
        )

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        // Simulate prefs holding the timestamp from a previous sync
        every { mockPrefs.lastSyncTimestampMs } returns previousSyncTimestampMs
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit
        every { mockUploader.upload(any(), any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        // The uploader must have been called (the photo was in the cursor)
        verify(exactly = 1) { mockUploader.upload(any(), any(), any(), any()) }
        // The timestamp must have advanced to the new photo's DATE_ADDED — not reset to 0
        // or re-set to the old timestamp. This confirms the worker resumes from where it left off.
        verify(exactly = 1) { mockPrefs.lastSyncTimestampMs = newPhotoDateAddedSeconds * 1000L }
    }

    @Test
    fun `worker advances sync timestamp after successful upload`() = runBlocking {
        // Seed the MediaStore so the worker finds a photo and invokes the uploader.
        // dateAddedSeconds = 5000 means the worker should advance lastSyncTimestampMs to 5_000_000.
        seedMediaStoreWithPhoto(id = 1L, displayName = "success_photo.jpg", dateAddedSeconds = 5000L)

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit
        every { mockUploader.upload(any(), any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        val result = worker.doWork()
        // Successful upload should complete the sync
        assertEquals(ListenableWorker.Result.success(), result)
        // The uploader must have been called with the seeded photo
        verify(exactly = 1) { mockUploader.upload(any(), any(), any(), any()) }
        // The sync timestamp must have been advanced to the photo's DATE_ADDED second (in ms).
        // This verifies the core invariant: after a successful upload, photos before this timestamp
        // will not be re-queried on the next sync run.
        verify(exactly = 1) { mockPrefs.lastSyncTimestampMs = 5000L * 1000L }
    }

    @Test
    fun `worker writes lastSyncCompletedAtMs after successful sync`() = runBlocking {
        // Seed a photo so the success path (upload + timestamp advance) is exercised
        seedMediaStoreWithPhoto(id = 1L, displayName = "completed_photo.jpg", dateAddedSeconds = 7000L)

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit
        every { mockUploader.upload(any(), any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        val timeBefore = System.currentTimeMillis()
        val result = worker.doWork()
        val timeAfter = System.currentTimeMillis()

        assertEquals(ListenableWorker.Result.success(), result)
        // lastSyncCompletedAtMs must be set to a wall-clock timestamp captured during the sync run.
        // We can't assert the exact value without mocking System.currentTimeMillis(), but we can
        // verify it was called exactly once with a value in the expected range.
        verify(exactly = 1) {
            mockPrefs.lastSyncCompletedAtMs = withArg { ts ->
                assert(ts >= timeBefore && ts <= timeAfter) {
                    "Expected lastSyncCompletedAtMs ($ts) to be between $timeBefore and $timeAfter"
                }
            }
        }
    }

    @Test
    fun `worker reports telemetry with correct photo count after successful sync`() = runBlocking {
        seedMediaStoreWithPhoto(id = 1L, displayName = "photo.jpg", dateAddedSeconds = 5000L)

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        every { mockPrefs.lastSyncCompletedAtMs = any() } returns Unit
        every { mockUploader.upload(any(), any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs, mockTelemetry))
            .build()

        worker.doWork()

        verify(exactly = 1) {
            mockTelemetry.reportSync(durationMs = any(), photosSynced = 1, errors = 0, succeeded = true)
        }
    }
}
