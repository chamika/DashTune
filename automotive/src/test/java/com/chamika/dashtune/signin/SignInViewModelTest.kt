package com.chamika.dashtune.signin

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.tls.CertificateInspector
import com.chamika.dashtune.tls.ServerCertificate
import com.chamika.dashtune.tls.TrustedCertificateStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.operations.SystemApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.mockk.coEvery
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SignInViewModelTest {

    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var jellyfin: Jellyfin
    private lateinit var accountManager: JellyfinAccountManager
    private lateinit var apiClient: ApiClient
    private lateinit var systemApi: SystemApi
    private lateinit var trustedCertificateStore: TrustedCertificateStore
    private lateinit var certificateInspector: CertificateInspector
    private lateinit var viewModel: SignInViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        accountManager = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        systemApi = mockk(relaxed = true)

        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns mockk(relaxed = true)

        // Stub options before createApi so createApi$default can resolve default parameters
        jellyfin = mockk(relaxed = true)
        val mockOptions = mockk<JellyfinOptions>(relaxed = true)
        every { jellyfin.options } returns mockOptions
        every { jellyfin.createApi(any()) } returns apiClient

        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { any<ApiClient>().systemApi } returns systemApi

        trustedCertificateStore = mockk(relaxed = true)
        certificateInspector = mockk(relaxed = true)

        viewModel = SignInViewModel()
        viewModel.jellyfin = jellyfin
        viewModel.accountManager = accountManager
        viewModel.trustedCertificateStore = trustedCertificateStore
        viewModel.certificateInspector = certificateInspector
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- JELLYFIN_SERVER_URL constant ---

    @Test
    fun `JELLYFIN_SERVER_URL constant has expected value`() {
        assertEquals("jellyfinServer", SignInViewModel.JELLYFIN_SERVER_URL)
    }

    // --- pingServer ---

    @Test
    fun `pingServer succeeds when server responds with status 200`() = runTest {
        val pingResponse: Response<String> = mockk { every { status } returns 200 }
        coEvery { systemApi.getPingSystem() } returns pingResponse

        val result = viewModel.pingServer("http://jellyfin.local:8096")

        assertEquals(PingResult.Success, result)
    }

    @Test
    fun `pingServer is unreachable when server responds with non-200 status`() = runTest {
        val pingResponse: Response<String> = mockk { every { status } returns 503 }
        coEvery { systemApi.getPingSystem() } returns pingResponse

        val result = viewModel.pingServer("http://jellyfin.local:8096")

        assertEquals(PingResult.Unreachable, result)
    }

    @Test
    fun `pingServer is unreachable when network exception is thrown`() = runTest {
        coEvery { systemApi.getPingSystem() } throws RuntimeException("connection refused")

        val result = viewModel.pingServer("http://unreachable.host")

        assertEquals(PingResult.Unreachable, result)
    }

    @Test
    fun `pingServer reports the certificate when the server presents an untrusted one`() = runTest {
        val certificate: ServerCertificate = mockk(relaxed = true)
        coEvery { systemApi.getPingSystem() } throws
                SSLHandshakeException("Trust anchor for certification path not found.")
        coEvery { certificateInspector.inspect(any()) } returns certificate

        val result = viewModel.pingServer("https://jellyfin.local:8920")

        assertEquals(PingResult.UntrustedCertificate(certificate), result)
    }

    @Test
    fun `pingServer is unreachable when the untrusted certificate cannot be read back`() = runTest {
        coEvery { systemApi.getPingSystem() } throws
                SSLHandshakeException("Trust anchor for certification path not found.")
        coEvery { certificateInspector.inspect(any()) } returns null

        val result = viewModel.pingServer("https://jellyfin.local:8920")

        assertEquals(PingResult.Unreachable, result)
    }

    @Test
    fun `trustCertificate pins the certificate for its host`() {
        val x509 = mockk<java.security.cert.X509Certificate>()
        val certificate: ServerCertificate = mockk {
            every { host } returns "jellyfin.local"
            every { this@mockk.certificate } returns x509
            every { fingerprintSha256 } returns "AA:BB"
        }

        viewModel.trustCertificate(certificate)

        verify { trustedCertificateStore.pin("jellyfin.local", x509) }
    }

    // --- login ---

    @Test
    fun `login returns false when authentication API returns non-200 status`() = runTest {
        // Relaxed userApi mock returns a Response with status 0 (not 200)
        val result = viewModel.login("http://server.local", "testuser", "wrongpassword")

        assertFalse(result)
    }

    @Test
    fun `login returns false when network exception is thrown`() = runTest {
        every { jellyfin.createApi(any()) } throws RuntimeException("network error")

        val result = viewModel.login("http://server.local", "testuser", "password")

        assertFalse(result)
    }

    // --- loginSuccess (tested via reflection) ---

    @Test
    fun `loginSuccess stores account in account manager`() = runTest {
        invokeLoginSuccess("http://server.local", "chamika", "access-token")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { accountManager.storeAccount("http://server.local", "chamika", "access-token") }
    }

    @Test
    fun `loginSuccess posts true to loggedIn LiveData`() = runTest {
        invokeLoginSuccess("http://server.local", "user", "token")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.loggedIn.value == true)
    }

    @Test
    fun `loggedIn LiveData starts as null before any authentication`() {
        assertFalse(viewModel.loggedIn.value == true)
    }

    private fun invokeLoginSuccess(server: String, username: String, token: String) {
        val method = SignInViewModel::class.java.getDeclaredMethod(
            "loginSuccess",
            String::class.java, String::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(viewModel, server, username, token)
    }
}
