package eu.l42.lucos_photos_android

import android.util.Log
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
import org.junit.Before
import org.junit.Test
import java.io.IOException

class VersionCheckerTest {

    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    private fun makeResponse(code: Int, body: String, request: Request): Response = Response.Builder()
        .code(code)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .request(request)
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun makeCheckerWithSlot(
        responseCode: Int,
        responseBody: String = """{"version":"2.0.0","download_url":"https://example.com/app.apk","released_at":"2026-03-10T00:00:00Z"}""",
        currentVersion: String = "1.0.0",
    ): Pair<VersionChecker, CapturingSlot<Request>> {
        val requestSlot = slot<Request>()
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()

        every { mockClient.newCall(capture(requestSlot)) } returns mockCall
        every { mockCall.execute() } answers {
            makeResponse(responseCode, responseBody, requestSlot.captured)
        }

        val checker = VersionChecker(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            currentVersion = currentVersion,
            httpClient = mockClient,
        )
        return Pair(checker, requestSlot)
    }

    private fun makeChecker(
        responseCode: Int,
        responseBody: String = """{"version":"2.0.0","download_url":"https://example.com/app.apk","released_at":"2026-03-10T00:00:00Z"}""",
        currentVersion: String = "1.0.0",
    ): VersionChecker = makeCheckerWithSlot(responseCode, responseBody, currentVersion).first

    // ---------------------------------------------------------------------------
    // UpdateAvailable results
    // ---------------------------------------------------------------------------

    @Test
    fun `check returns UpdateAvailable when server reports a different version`() {
        val checker = makeChecker(responseCode = 200, currentVersion = "1.0.0")
        val result = checker.check()
        assertTrue("Expected UpdateAvailable but got $result", result is VersionChecker.CheckResult.UpdateAvailable)
        assertEquals("2.0.0", (result as VersionChecker.CheckResult.UpdateAvailable).latestVersion)
    }

    @Test
    fun `check returns UpdateAvailable even if running version is newer than server`() {
        // Per spec: "Any mismatch triggers the banner" — no semver comparison needed
        val checker = makeChecker(
            responseCode = 200,
            responseBody = """{"version":"1.0.0"}""",
            currentVersion = "2.0.0",
        )
        val result = checker.check()
        assertTrue("Expected UpdateAvailable but got $result", result is VersionChecker.CheckResult.UpdateAvailable)
        assertEquals("1.0.0", (result as VersionChecker.CheckResult.UpdateAvailable).latestVersion)
    }

    // ---------------------------------------------------------------------------
    // UpToDate result
    // ---------------------------------------------------------------------------

    @Test
    fun `check returns UpToDate when server version matches installed version`() {
        val checker = makeChecker(
            responseCode = 200,
            responseBody = """{"version":"1.0.0"}""",
            currentVersion = "1.0.0",
        )
        val result = checker.check()
        assertEquals(VersionChecker.CheckResult.UpToDate, result)
    }

    // ---------------------------------------------------------------------------
    // CheckFailed results — HTTP error responses
    // ---------------------------------------------------------------------------

    @Test
    fun `check returns CheckFailed on HTTP 401`() {
        val checker = makeChecker(responseCode = 401, responseBody = """{"detail":"Unauthorized"}""")
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    @Test
    fun `check returns CheckFailed on HTTP 404`() {
        val checker = makeChecker(responseCode = 404, responseBody = """{"detail":"Not Found"}""")
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    @Test
    fun `check returns CheckFailed on HTTP 500`() {
        val checker = makeChecker(responseCode = 500, responseBody = """{"detail":"Server Error"}""")
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    // ---------------------------------------------------------------------------
    // CheckFailed results — malformed responses
    // ---------------------------------------------------------------------------

    @Test
    fun `check returns CheckFailed when version field is missing from response`() {
        val checker = makeChecker(
            responseCode = 200,
            responseBody = """{"download_url":"https://example.com/app.apk"}""",
        )
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    @Test
    fun `check returns CheckFailed when version field is blank`() {
        val checker = makeChecker(
            responseCode = 200,
            responseBody = """{"version":""}""",
        )
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    // ---------------------------------------------------------------------------
    // CheckFailed results — network errors
    // ---------------------------------------------------------------------------

    @Test
    fun `check returns CheckFailed on IOException`() {
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Connection refused")

        val checker = VersionChecker(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            currentVersion = "1.0.0",
            httpClient = mockClient,
        )
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    @Test
    fun `check returns CheckFailed on unexpected exception`() {
        val mockCall = mockk<Call>()
        val mockClient = mockk<OkHttpClient>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws RuntimeException("Unexpected crash")

        val checker = VersionChecker(
            serverUrl = "https://photos.example.com",
            apiKey = "test-key",
            currentVersion = "1.0.0",
            httpClient = mockClient,
        )
        val result = checker.check()
        assertTrue("Expected CheckFailed but got $result", result is VersionChecker.CheckResult.CheckFailed)
    }

    // ---------------------------------------------------------------------------
    // Request shape
    // ---------------------------------------------------------------------------

    @Test
    fun `check sends GET request to api app latest endpoint`() {
        val (checker, requestSlot) = makeCheckerWithSlot(200)
        checker.check()
        assertEquals("https://photos.example.com/api/app/latest", requestSlot.captured.url.toString())
        assertEquals("GET", requestSlot.captured.method)
    }

    @Test
    fun `check sends Authorization header with key scheme`() {
        val (checker, requestSlot) = makeCheckerWithSlot(200)
        checker.check()
        assertEquals("key test-key", requestSlot.captured.header("Authorization"))
    }
}
