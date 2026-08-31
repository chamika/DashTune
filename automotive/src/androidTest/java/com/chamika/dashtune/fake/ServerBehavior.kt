package com.chamika.dashtune.fake

/**
 * How [FakeJellyfinServer] should answer API calls. Swapped mid-test to drive the
 * failure-mode suite without tearing the session down.
 */
sealed interface ServerBehavior {

    /** Answer every request from the fixture library. */
    data object Healthy : ServerBehavior

    /** Every API call returns 401, as it would with an expired access token. */
    data object Unauthorized : ServerBehavior

    /** Every API call returns 500. */
    data object ServerError : ServerBehavior

    /** Return 200 with a body the SDK's strict (`isLenient = false`) parser cannot decode. */
    data object MalformedJson : ServerBehavior

    /**
     * Accept the request but stall past the app's 7s request timeout, simulating a server
     * that has hung rather than one that refuses connections.
     */
    data class Hang(val delayMillis: Long = 20_000) : ServerBehavior
}
