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
     * @param id           The _ID to assign to the fake photo row. Must be unique per test.
     * @param displayName  The DISPLAY_NAME for the fake photo.
     * @param dateAddedSeconds  The DATE_ADDED value (epoch seconds) for the fake photo.
     */
    private fun seedMediaStoreWithPhoto(
        id: Long,
        displayName: String,
        dateAddedSeconds: Long,
    ) {
        // Set up a RoboCursor that the worker's query() call will receive.
        // The worker's projection is [_ID, DISPLAY_NAME, DATE_ADDED, MIME_TYPE].
        val cursor = RoboCursor()
        cursor.setColumnNames(
            listOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
            )
        )
        cursor.setResults(
            arrayOf(
                arrayOf(id, displayName, dateAddedSeconds, "image/jpeg"),
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

        // Build a worker that uses our mocks but will query the (empty) Robolectric MediaStore
        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(
                PhotoSyncWorkerFactory(mockUploader, mockPrefs)
            )
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        // No uploads should have occurred
        verify(exactly = 0) { mockUploader.upload(any(), any(), any()) }
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
        every { mockUploader.upload(any(), any(), any()) } returns
            PhotoUploader.UploadResult.AuthFailure("Authentication failed (HTTP 401) — check API key")

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs))
            .build()

        val result = worker.doWork()
        // Worker should stop the batch and schedule a retry
        assertEquals(ListenableWorker.Result.retry(), result)
        // The uploader must have been called (verifying we went through the upload path, not null-stream)
        verify(exactly = 1) { mockUploader.upload(any(), any(), any()) }
        // The sync timestamp must NOT have been advanced — photos must still be in window
        verify(exactly = 0) { mockPrefs.lastSyncTimestampMs = any() }
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
        every { mockUploader.upload(any(), any(), any()) } returns
            PhotoUploader.UploadResult.Failure("Network error", retryable = true)

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs))
            .build()

        val result = worker.doWork()
        // With a retryable failure, the worker should return retry() not success()
        assertEquals(ListenableWorker.Result.retry(), result)
        // The uploader must have been called (verifying we went through the upload path, not null-stream)
        verify(exactly = 1) { mockUploader.upload(any(), any(), any()) }
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
        every { mockUploader.upload(any(), any(), any()) } returns PhotoUploader.UploadResult.Success

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs))
            .build()

        val result = worker.doWork()
        // Successful upload should complete the sync
        assertEquals(ListenableWorker.Result.success(), result)
        // The uploader must have been called with the seeded photo
        verify(exactly = 1) { mockUploader.upload(any(), any(), any()) }
        // The sync timestamp must have been advanced to the photo's DATE_ADDED second (in ms).
        // This verifies the core invariant: after a successful upload, photos before this timestamp
        // will not be re-queried on the next sync run.
        verify(exactly = 1) { mockPrefs.lastSyncTimestampMs = 5000L * 1000L }
    }
}
