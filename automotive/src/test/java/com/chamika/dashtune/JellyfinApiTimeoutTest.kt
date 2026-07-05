package com.chamika.dashtune

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the HTTP timeout configuration used by DashTuneMusicService (issue #31):
 * an API call against a server that accepts the TCP connection but never responds
 * must fail with a bounded timeout instead of hanging indefinitely. A hanging call
 * inside a MediaLibrarySession callback wedges the Media3 command queue permanently.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinApiTimeoutTest {

    private lateinit var serverSocket: ServerSocket

    @Before
    fun setUp() {
        // Accepts connections but never reads or writes: simulates a hung server.
        serverSocket = ServerSocket(0)
    }

    @After
    fun tearDown() {
        serverSocket.close()
    }

    @Test
    fun `api call against unresponsive server fails within the configured timeout`() {
        val jellyfin = createJellyfin {
            clientInfo = ClientInfo("DashTuneTest", "0.0")
            deviceInfo = DeviceInfo("test-device", "test")
            context = RuntimeEnvironment.getApplication()
        }
        val api = jellyfin.createApi(
            baseUrl = "http://127.0.0.1:${serverSocket.localPort}",
            accessToken = "test-token",
            httpClientOptions = HttpClientOptions(
                connectTimeout = 5.seconds,
                requestTimeout = 7.seconds,
                socketTimeout = 7.seconds,
            )
        )

        val startMs = System.currentTimeMillis()
        try {
            runBlocking { api.systemApi.getPublicSystemInfo() }
            fail("Expected the request to fail against an unresponsive server")
        } catch (e: ApiClientException) {
            // Expected: the SDK surfaces the OkHttp timeout as a typed exception.
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(
            "Request should fail within 10s (took ${elapsedMs}ms)",
            elapsedMs < 10_000
        )
    }
}
