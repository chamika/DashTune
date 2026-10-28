package com.chamika.dashtune

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
import androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class FocusDenialRetryTest {

    private lateinit var player: Player
    private lateinit var retry: FocusDenialRetry
    private var nowMs = 1_000L

    @Before
    fun setUp() {
        player = mockk(relaxed = true)
        every { player.playWhenReady } returns false
        every { player.mediaItemCount } returns 3
        retry = FocusDenialRetry(player, Handler(Looper.getMainLooper())) { nowMs }
    }

    /** What ExoPlayer reports when the focus request inside play() is refused. */
    private fun playRefused() {
        retry.onPlayWhenReadyChanged(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        nowMs += 5
        retry.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
    }

    private fun advance(ms: Long) {
        nowMs += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun `a play refused at request time is asked again`() {
        playRefused()
        advance(FocusDenialRetry.RETRY_DELAY_MS)

        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `losing focus well after playing started is another app taking over and is left alone`() {
        retry.onPlayWhenReadyChanged(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        nowMs += 60_000
        retry.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        advance(FocusDenialRetry.RETRY_DELAY_MS * 2)

        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `a pause cancels a pending retry`() {
        playRefused()
        retry.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        advance(FocusDenialRetry.RETRY_DELAY_MS * 2)

        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `retries stop after the limit`() {
        // Every retried play() is refused too, the way it would be while the car holds focus.
        every { player.play() } answers { playRefused() }

        playRefused()
        repeat(FocusDenialRetry.MAX_RETRIES + 3) { advance(FocusDenialRetry.RETRY_DELAY_MS) }

        verify(exactly = FocusDenialRetry.MAX_RETRIES) { player.play() }
    }

    @Test
    fun `a new tap after giving up gets a fresh set of retries`() {
        every { player.play() } answers { playRefused() }
        playRefused()
        repeat(FocusDenialRetry.MAX_RETRIES + 1) { advance(FocusDenialRetry.RETRY_DELAY_MS) }

        playRefused()
        advance(FocusDenialRetry.RETRY_DELAY_MS)

        verify(exactly = FocusDenialRetry.MAX_RETRIES + 1) { player.play() }
    }

    @Test
    fun `a play request is not retried when the queue is empty`() {
        every { player.mediaItemCount } returns 0
        playRefused()
        advance(FocusDenialRetry.RETRY_DELAY_MS)

        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `ExoPlayer reports a refused focus request as an immediate focus loss`() {
        // Pins the event sequence the retry relies on, against the real player.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val exo = TestExoPlayerBuilder(context).build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        exo.setMediaSource(FakeMediaSource())
        exo.prepare()
        runUntil { exo.playbackState == Player.STATE_READY }

        val changes = mutableListOf<Pair<Boolean, Int>>()
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                changes += playWhenReady to reason
            }
        })
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        exo.play()
        runUntil { changes.size >= 2 }

        assertEquals(
            listOf(
                true to PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                false to PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            ),
            changes
        )
        exo.release()
    }

    private fun runUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in ${timeoutMs}ms")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    // --- After the service's watchdog recovers focus ---

    /** The car refuses every focus request, the way it does while a call holds focus. */
    private fun carRefusesFocus() {
        every { player.playWhenReady = any() } answers {
            if (firstArg<Boolean>()) playRefused()
            else retry.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
        every { player.play() } answers { playRefused() }
    }

    @Test
    fun `a recovery refused during a call keeps asking slowly instead of giving up`() {
        carRefusesFocus()

        retry.recoverFocus()
        // Well past the five quick retries a refused play() would get.
        repeat(20) { advance(FocusDenialRetry.SLOW_RETRY_DELAY_MS) }

        verify(exactly = 20) { player.play() }
    }

    @Test
    fun `slow retries stop once playback resumes`() {
        carRefusesFocus()
        retry.recoverFocus()
        advance(FocusDenialRetry.SLOW_RETRY_DELAY_MS)

        // The call ended and the next request was granted.
        retry.onIsPlayingChanged(true)
        every { player.play() } answers { }
        advance(FocusDenialRetry.SLOW_RETRY_DELAY_MS * 3)

        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `a pause by the user ends the slow retries`() {
        carRefusesFocus()
        retry.recoverFocus()

        retry.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        advance(FocusDenialRetry.SLOW_RETRY_DELAY_MS * 3)

        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `slow retries give up when the window runs out`() {
        carRefusesFocus()
        retry.recoverFocus()

        val attempts = (FocusDenialRetry.SLOW_RETRY_WINDOW_MS / FocusDenialRetry.SLOW_RETRY_DELAY_MS).toInt()
        repeat(attempts + 10) { advance(FocusDenialRetry.SLOW_RETRY_DELAY_MS) }

        verify(atMost = attempts + 1) { player.play() }
        verify(atLeast = attempts - 1) { player.play() }
    }
}
