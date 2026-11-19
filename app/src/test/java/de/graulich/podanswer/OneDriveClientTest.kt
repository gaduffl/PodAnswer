package de.graulich.podanswer

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class OneDriveClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OneDriveClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OneDriveClient(OkHttpClient(), server.url("/auth").toString().trimEnd('/'), server.url("/graph").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun deviceLoginPollsUntilTokensArrive() {
        server.enqueue(MockResponse().setBody("""{"user_code":"ABCD-1234","device_code":"dev","verification_uri":"https://microsoft.com/devicelogin","interval":5,"expires_in":900}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""))

        val code = client.startDeviceLogin("client-1")
        assertEquals("ABCD-1234", code.userCode)
        val first = server.takeRequest()
        assertEquals("/auth/devicecode", first.path)
        val form = first.body.readUtf8()
        assertTrue(form.contains("client_id=client-1"))
        assertTrue(form.contains("Files.ReadWrite.AppFolder"))

        assertEquals(OneDriveClient.PollResult.Pending, client.pollDeviceLogin("client-1", code.deviceCode))
        assertEquals(OneDriveClient.PollResult.SlowDown, client.pollDeviceLogin("client-1", code.deviceCode))
        val success = client.pollDeviceLogin("client-1", code.deviceCode) as OneDriveClient.PollResult.Success
        assertEquals("rt", success.tokens.refreshToken)
    }

    @Test
    fun expiredCodeIsReportedAsFailure() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"expired_token"}"""))
        assertTrue(client.pollDeviceLogin("c", "d") is OneDriveClient.PollResult.Failed)
    }

    @Test
    fun uploadPutsJsonIntoAppFolder() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        client.upload("token", "Heart Doc - Folge 1 - a1b2c3d4.podanswer.json", "{\"x\":1}")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/graph/me/drive/special/approot:/Heart%20Doc%20-%20Folge%201%20-%20a1b2c3d4.podanswer.json:/content", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("{\"x\":1}", request.body.readUtf8())
    }

    @Test(expected = IOException::class)
    fun failedUploadThrows() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        client.upload("token", "x.podanswer.json", "{}")
    }
}
