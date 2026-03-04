package eu.l42.lucos_photos_android

import android.content.ContentValues
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoSyncWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    fun `worker retries on retryable upload failure`() = runBlocking {
        // Seed the Robolectric MediaStore with one photo so the worker has something to upload
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "test_photo.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, 1000L) // seconds since epoch
            put(MediaStore.Images.Media.DATA, "/sdcard/test_photo.jpg")
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        val mockUploader = mockk<PhotoUploader>()
        val mockPrefs = mockk<SyncPreferences>()
        every { mockPrefs.lastSyncTimestampMs } returns 0L
        every { mockPrefs.lastSyncTimestampMs = any() } returns Unit
        // Make the uploader return a retryable failure for any upload attempt
        every { mockUploader.upload(any(), any(), any()) } returns
            PhotoUploader.UploadResult.Failure("Network error", retryable = true)

        val worker = TestListenableWorkerBuilder<PhotoSyncWorker>(context)
            .setWorkerFactory(PhotoSyncWorkerFactory(mockUploader, mockPrefs))
            .build()

        val result = worker.doWork()
        // With a retryable failure, the worker should return retry() not success()
        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
