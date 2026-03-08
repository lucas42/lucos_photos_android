package eu.l42.lucos_photos_android

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PhotoUploaderTest {

    private fun makeResponse(code: Int, request: Request): Response = Response.Builder()
        .code(code)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .request(request)
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    private fun makeUploaderWithSlot(responseCode: Int): Pair<PhotoUploader, CapturingSlot<Request>> {
        val requestSlot = slot<Request>()
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()

        every { mockClient.newCall(capture(requestSlot)) } returns mockCall
        every { mockCall.execute() } answers {
            makeResponse(responseCode, requestSlot.captured)
        }

        val uploader = PhotoUploader(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            httpClient = mockClient,
        )
        return Pair(uploader, requestSlot)
    }

    private fun makeUploader(responseCode: Int): PhotoUploader = makeUploaderWithSlot(responseCode).first

    @Test
    fun `upload returns Success on HTTP 201`() {
        val uploader = makeUploader(201)
        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertEquals(PhotoUploader.UploadResult.Success, result)
    }

    @Test
    fun `upload returns Success on HTTP 200 (duplicate)`() {
        val uploader = makeUploader(200)
        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertEquals(PhotoUploader.UploadResult.Success, result)
    }

    @Test
    fun `upload returns AuthFailure on HTTP 401`() {
        val uploader = makeUploader(401)
        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertTrue("Expected AuthFailure but got $result", result is PhotoUploader.UploadResult.AuthFailure)
    }

    @Test
    fun `upload returns AuthFailure on HTTP 403`() {
        val uploader = makeUploader(403)
        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertTrue("Expected AuthFailure but got $result", result is PhotoUploader.UploadResult.AuthFailure)
    }

    @Test
    fun `upload returns retryable Failure on HTTP 500`() {
        val uploader = makeUploader(500)
        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertTrue(result is PhotoUploader.UploadResult.Failure)
        assertEquals(true, (result as PhotoUploader.UploadResult.Failure).retryable)
    }

    @Test
    fun `upload returns non-retryable Failure on HTTP 507`() {
        val uploader = makeUploader(507)
        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertTrue(result is PhotoUploader.UploadResult.Failure)
        assertEquals(false, (result as PhotoUploader.UploadResult.Failure).retryable)
    }

    @Test
    fun `upload sends Authorization header using Bearer scheme`() {
        val (uploader, requestSlot) = makeUploaderWithSlot(201)
        uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertEquals("Bearer test-key", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `upload sends X-Taken-At header when dateTakenMs is provided`() {
        val (uploader, requestSlot) = makeUploaderWithSlot(201)
        uploader.upload(
            ByteArrayInputStream("fakedata".toByteArray()),
            "photo.jpg",
            "image/jpeg",
            dateTakenMs = 1700000000000L,
        )
        assertEquals("1700000000000", requestSlot.captured.header("X-Taken-At"))
    }

    @Test
    fun `upload omits X-Taken-At header when dateTakenMs is null`() {
        val (uploader, requestSlot) = makeUploaderWithSlot(201)
        uploader.upload(
            ByteArrayInputStream("fakedata".toByteArray()),
            "photo.jpg",
            "image/jpeg",
            dateTakenMs = null,
        )
        assertEquals(null, requestSlot.captured.header("X-Taken-At"))
    }

    @Test
    fun `upload returns retryable Failure on IOException`() {
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Connection refused")

        val uploader = PhotoUploader(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            httpClient = mockClient,
        )

        val result = uploader.upload(ByteArrayInputStream("fakedata".toByteArray()), "photo.jpg", "image/jpeg")
        assertTrue(result is PhotoUploader.UploadResult.Failure)
        assertEquals(true, (result as PhotoUploader.UploadResult.Failure).retryable)
    }
}
