package eu.l42.lucos_photos_android

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
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
     * Seeds the Robolectric MediaStore with a fake photo and registers a fake InputStream
     * via ShadowContentResolver so that PhotoSyncWorker's openInputStream() call returns
     * real (fake) bytes rather than null.
     *
     * Without the ShadowContentResolver registration, openInputStream() returns null for
     * MediaStore URIs that don't point to real files, and the worker exits via the
     * "Could not open input stream" path without ever calling uploader.upload(). This means
     * mock expectations on the uploader would never be triggered.
     *
     * @return the URI of the inserted MediaStore entry.
     */
    private fun seedMediaStoreWithPhoto(
        displayName: String,
        dateAddedSeconds: Long,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, dateAddedSeconds)
            put(MediaStore.Images.Media.DATA, "/sdcard/$displayName")
        }
        val insertedUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Failed to insert test photo into MediaStore")

        // Register a fake input stream so openInputStream() returns non-null bytes.
        // PhotoSyncWorker opens the stream via ContentUris.withAppendedId, which produces
        // the same URI that the insert returned — so we register on that URI.
        val fakeStream = ByteArrayInputStream("fake-jpeg-bytes".toByteArray())
        Shadows.shadowOf(context.contentResolver).registerInputStream(insertedUri, fakeStream)

        return insertedUri
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
        // Seed the MediaStore and register a real (fake) stream so the uploader is actually invoked
        seedMediaStoreWithPhoto("auth_test_photo.jpg", dateAddedSeconds = 2000L)

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
        // Seed the MediaStore and register a real (fake) stream so the uploader is actually invoked
        seedMediaStoreWithPhoto("test_photo.jpg", dateAddedSeconds = 1000L)

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
        // Seed the MediaStore and register a real (fake) stream so the uploader is actually invoked.
        // dateAddedSeconds = 5000 means the worker should advance lastSyncTimestampMs to 5_000_000.
        seedMediaStoreWithPhoto("success_photo.jpg", dateAddedSeconds = 5000L)

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
