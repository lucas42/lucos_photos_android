package eu.l42.lucos_photos_android

import android.util.Log
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class TelemetryReporterTest {

    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    private fun makeResponse(code: Int, request: Request): Response = Response.Builder()
        .code(code)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .request(request)
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    private fun makeReporterWithSlot(responseCode: Int): Pair<TelemetryReporter, CapturingSlot<Request>> {
        val requestSlot = slot<Request>()
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()

        every { mockClient.newCall(capture(requestSlot)) } returns mockCall
        every { mockCall.execute() } answers {
            makeResponse(responseCode, requestSlot.captured)
        }

        val reporter = TelemetryReporter(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            appVersion = "1.2.3",
            httpClient = mockClient,
        )
        return Pair(reporter, requestSlot)
    }

    private fun makeReporter(responseCode: Int): TelemetryReporter = makeReporterWithSlot(responseCode).first

    // ---------------------------------------------------------------------------
    // reportSync — happy path
    // ---------------------------------------------------------------------------

    @Test
    fun `reportSync sends sync_completed event on success`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 4200, itemsFound = 15, photosFound = 15, videosFound = 0, photosSynced = 15, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val body = requestSlot.captured.body
        assertNotNull(body)
        val buffer = okio.Buffer()
        body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("sync_completed", json.getString("event_type"))
    }

    @Test
    fun `reportSync sends sync_failed event on failure`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 1, photosFound = 1, videosFound = 0, photosSynced = 0, alreadyUploaded = 0, errors = 1, errorBreakdown = mapOf("network" to 1), relativePathSample = null, succeeded = false)

        val body = requestSlot.captured.body
        assertNotNull(body)
        val buffer = okio.Buffer()
        body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("sync_failed", json.getString("event_type"))
    }

    @Test
    fun `reportSync includes app_version in payload`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 4200, itemsFound = 5, photosFound = 5, videosFound = 0, photosSynced = 5, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("1.2.3", json.getString("app_version"))
    }

    @Test
    fun `reportSync includes timestamp in payload`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 4200, itemsFound = 5, photosFound = 5, videosFound = 0, photosSynced = 5, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertTrue("timestamp should be present", json.has("timestamp"))
        val ts = json.getString("timestamp")
        assertTrue("timestamp should look like ISO-8601", ts.contains("T") && ts.endsWith("Z"))
    }

    @Test
    fun `reportSync includes data with duration_ms photos_synced and errors`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 4200, itemsFound = 20, photosFound = 18, videosFound = 2, photosSynced = 15, alreadyUploaded = 2, errors = 3, errorBreakdown = emptyMap(), relativePathSample = "DCIM/Camera/", succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertEquals(4200L, data.getLong("duration_ms"))
        assertEquals(15, data.getInt("photos_synced"))
        assertEquals(3, data.getInt("errors"))
    }

    @Test
    fun `reportSync includes items_found in data`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 42, photosFound = 42, videosFound = 0, photosSynced = 40, alreadyUploaded = 1, errors = 1, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertEquals(42, data.getInt("items_found"))
    }

    @Test
    fun `reportSync includes already_uploaded in data`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 10, photosFound = 10, videosFound = 0, photosSynced = 7, alreadyUploaded = 3, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertEquals(3, data.getInt("already_uploaded"))
    }

    @Test
    fun `reportSync includes error_breakdown when errors occurred`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(
            durationMs = 1000,
            itemsFound = 5,
            photosFound = 5,
            videosFound = 0,
            photosSynced = 3,
            alreadyUploaded = 0,
            errors = 2,
            errorBreakdown = mapOf("500" to 1, "network" to 1),
            relativePathSample = null,
            succeeded = false,
        )

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertTrue("error_breakdown should be present", data.has("error_breakdown"))
        val breakdown = data.getJSONObject("error_breakdown")
        assertEquals(1, breakdown.getInt("500"))
        assertEquals(1, breakdown.getInt("network"))
    }

    @Test
    fun `reportSync includes photos_found and videos_found in data`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 7, photosFound = 5, videosFound = 2, photosSynced = 6, alreadyUploaded = 0, errors = 1, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertEquals(5, data.getInt("photos_found"))
        assertEquals(2, data.getInt("videos_found"))
    }

    @Test
    fun `reportSync includes relative_path_sample when present`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 3, photosFound = 3, videosFound = 0, photosSynced = 3, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = "primary:DCIM/Camera/", succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertEquals("primary:DCIM/Camera/", data.getString("relative_path_sample"))
    }

    @Test
    fun `reportSync omits relative_path_sample when null`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 0, photosFound = 0, videosFound = 0, photosSynced = 0, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertTrue("relative_path_sample should be absent when null", !data.has("relative_path_sample"))
    }

    @Test
    fun `reportSync omits error_breakdown when no errors`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 5, photosFound = 5, videosFound = 0, photosSynced = 5, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        val buffer = okio.Buffer()
        requestSlot.captured.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val data = json.getJSONObject("data")
        assertTrue("error_breakdown should be absent when empty", !data.has("error_breakdown"))
    }

    @Test
    fun `reportSync sends Authorization header with Bearer scheme`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 1, photosFound = 1, videosFound = 0, photosSynced = 1, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        assertEquals("Bearer test-key", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `reportSync posts to api telemetry endpoint`() {
        val (reporter, requestSlot) = makeReporterWithSlot(201)
        reporter.reportSync(durationMs = 1000, itemsFound = 1, photosFound = 1, videosFound = 0, photosSynced = 1, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)

        assertEquals("https://photos.example.com/api/telemetry", requestSlot.captured.url.toString())
    }

    // ---------------------------------------------------------------------------
    // Error handling — telemetry failures must not propagate
    // ---------------------------------------------------------------------------

    @Test
    fun `reportSync does not throw on HTTP error response`() {
        val reporter = makeReporter(500)
        // Should not throw
        reporter.reportSync(durationMs = 1000, itemsFound = 0, photosFound = 0, videosFound = 0, photosSynced = 0, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)
    }

    @Test
    fun `reportSync does not throw on network IOException`() {
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Connection refused")

        val reporter = TelemetryReporter(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            appVersion = "1.0",
            httpClient = mockClient,
        )
        // Must not throw
        reporter.reportSync(durationMs = 1000, itemsFound = 0, photosFound = 0, videosFound = 0, photosSynced = 0, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)
    }

    @Test
    fun `reportSync does not throw on unexpected exception`() {
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws RuntimeException("Unexpected crash")

        val reporter = TelemetryReporter(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            appVersion = "1.0",
            httpClient = mockClient,
        )
        // Must not throw
        reporter.reportSync(durationMs = 1000, itemsFound = 0, photosFound = 0, videosFound = 0, photosSynced = 0, alreadyUploaded = 0, errors = 0, errorBreakdown = emptyMap(), relativePathSample = null, succeeded = true)
    }

    // ---------------------------------------------------------------------------
    // isoNow helper
    // ---------------------------------------------------------------------------

    @Test
    fun `isoNow returns ISO-8601 UTC string ending in Z`() {
        val ts = TelemetryReporter.isoNow()
        assertTrue("Should end with Z", ts.endsWith("Z"))
        assertTrue("Should contain T", ts.contains("T"))
        // Should look like 2026-03-10T00:00:00Z
        assertTrue("Should be 20 chars", ts.length == 20)
    }
}
