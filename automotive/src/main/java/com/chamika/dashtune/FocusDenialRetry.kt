package com.chamika.dashtune

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.chamika.dashtune.Constants.LOG_TAG

/**
 * Asks again when a play request's audio focus was refused outright.
 *
 * ExoPlayer requests focus inside play(). When the request is refused — the car's audio may
 * still be coming up right after it starts — the player turns playWhenReady straight back off
 * with reason AUDIO_FOCUS_LOSS and never asks again. Nothing is suppressed, so the service's
 * transient-loss watchdog does not see it either: the start-up auto-play, or a tap, is simply
 * lost.
 *
 * Deliberately narrow. Only a refusal that lands right after the request counts; a loss that
 * comes later is another app (a call, the radio) legitimately taking over and is left alone.
 * A few attempts at most, and an explicit pause cancels them.
 */
class FocusDenialRetry(
    private val player: Player,
    private val handler: Handler,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    companion object {
        /** A refusal arrives within milliseconds of the request; a real loss comes much later. */
        internal const val DENIAL_WINDOW_MS = 500L
        internal const val RETRY_DELAY_MS = 2_000L
        internal const val MAX_RETRIES = 5
    }

    private var lastPlayRequestAtMs = Long.MIN_VALUE
    private var retries = 0
    private var retrying = false

    private val retry = Runnable {
        if (!player.playWhenReady && player.mediaItemCount > 0) {
            Log.w(LOG_TAG, "Audio focus was refused; asking again ($retries/$MAX_RETRIES)")
            retrying = true
            player.play()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            // A fresh request from anyone but us gets the full set of attempts.
            if (!retrying) retries = 0
            retrying = false
            lastPlayRequestAtMs = clock()
            return
        }

        handler.removeCallbacks(retry)
        val refusedAtRequest = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
            clock() - lastPlayRequestAtMs <= DENIAL_WINDOW_MS
        if (!refusedAtRequest) {
            retries = 0
            return
        }
        if (retries < MAX_RETRIES) {
            retries++
            FirebaseUtils.safeLog("Audio focus refused at play; retry $retries scheduled")
            handler.postDelayed(retry, RETRY_DELAY_MS)
        } else {
            Log.w(LOG_TAG, "Audio focus still refused after $MAX_RETRIES attempts; giving up")
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            retries = 0
            handler.removeCallbacks(retry)
        }
    }

    fun release() {
        handler.removeCallbacks(retry)
    }
}
