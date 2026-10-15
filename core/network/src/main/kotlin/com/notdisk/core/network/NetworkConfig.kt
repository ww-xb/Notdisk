package com.notdisk.core.network

/**
 * The complete, declarative description of how this app talks to a cloud drive over HTTP.
 *
 * ### Why a data class instead of a builder or a properties file
 * Transport behaviour has to be *inspectable from a test*: the suite that proves the timeout →
 * [com.notdisk.core.common.error.CloudError.Timeout] mapping needs to construct a client with a
 * 250 ms read timeout, and it must be obvious that this is the same knob production turns. A
 * builder exposes knobs but hides the effective values; a properties file is invisible to the type
 * checker. An immutable data class gives one reviewable list of every value that can change
 * transport behaviour, and [copy] makes the test-time overrides one-liners without any side door.
 *
 * ### Why the four timeouts are separate
 * A single global timeout cannot express the real constraints of the providers this app integrates:
 *  - **connect** must be short, because a black-holed route means the user is looking at a spinner,
 *    and a fast failure is the only way to show "network problem" instead of "app is broken";
 *  - **read** must be long, because the 115 QR-login status endpoint is documented as throttling to
 *    roughly one effective response per 30 s, and a file listing over a slow mobile link is
 *    legitimately slow even though DNS and TCP were instant;
 *  - **write** is separate from read because uploading a chunk from a phone is bounded by the uplink
 *    and by the radio waking up, and it fails for different reasons than a stalled download;
 *  - **call** is the backstop: it bounds the *whole* exchange (redirects, retries, auth challenges)
 *    so that no combination of per-socket timeouts can keep one request alive forever.
 *
 * Defaults follow the split already used by the 115 transport (15/30/30/60 s) so that a client built
 * from this config behaves identically to the hand-rolled one it replaces.
 *
 * @property connectTimeoutMs TCP connect budget. Short on purpose — see the class KDoc.
 * @property readTimeoutMs Per-socket-read budget, i.e. the longest gap between two bytes of a
 *   response. Deliberately larger than [connectTimeoutMs] for throttled polling endpoints.
 * @property writeTimeoutMs Per-socket-write budget, i.e. the longest gap while uploading a body.
 * @property callTimeoutMs Budget for the entire call including redirects and connection retries.
 *   Must be greater than the other three to be meaningful; [create] coerces an inconsistent value
 *   instead of silently producing an ineffective setting.
 * @property enableLogging Whether an `HttpLoggingInterceptor` is installed at all. False by default
 *   because production builds must not emit request metadata; see [HttpClientFactory].
 * @property userAgent The `User-Agent` header value. Not cosmetic: providers use it as a device
 *   fingerprint and several CDNs reject an unknown client.
 * @property retryOnConnectionFailure Whether OkHttp may silently re-dial a *different* route when a
 *   pooled connection turns out to be dead. This is not an application-level retry — it never
 *   re-sends a request that the server already received — so it stays on even though the
 *   application-level [RetryPolicy] is strictly opt-in.
 */
data class NetworkConfig(
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    val enableLogging: Boolean = false,
    val userAgent: String = DEFAULT_USER_AGENT,
    val retryOnConnectionFailure: Boolean = true,
) {

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0, was $connectTimeoutMs" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be > 0, was $readTimeoutMs" }
        require(writeTimeoutMs > 0) { "writeTimeoutMs must be > 0, was $writeTimeoutMs" }
        require(callTimeoutMs > 0) { "callTimeoutMs must be > 0, was $callTimeoutMs" }
        require(userAgent.isNotBlank()) { "userAgent must not be blank: providers fingerprint it" }
    }

    /**
     * The call budget that is actually applied.
     *
     * OkHttp accepts a `callTimeout` smaller than the per-socket timeouts, but the result is a trap:
     * every request then dies on the overall budget and the carefully chosen read timeout for a
     * throttled endpoint never gets a chance to matter. Rather than silently ignoring the
     * caller's intent we *raise* the call budget to the smallest consistent value, so an explicit
     * per-request read timeout is always honoured. [create] documents this to the caller.
     */
    val effectiveCallTimeoutMs: Long
        get() = maxOf(callTimeoutMs, maxOf(connectTimeoutMs, maxOf(readTimeoutMs, writeTimeoutMs)))

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 15_000L
        const val DEFAULT_READ_TIMEOUT_MS: Long = 30_000L
        const val DEFAULT_WRITE_TIMEOUT_MS: Long = 30_000L
        const val DEFAULT_CALL_TIMEOUT_MS: Long = 60_000L

        /**
         * A browser-like UA.
         *
         * 115 validates this on several endpoints and on its media CDN (the reverse-engineering
         * manual records "老乡验证失败" otherwise), so the value is part of the integration contract
         * rather than a politeness header.
         */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36 NotDisk/0.1"

        /**
         * A profile for local tests: every budget short enough that a mistake fails fast.
         *
         * This exists so that tests do not hand-pick timeouts one by one and accidentally leave a
         * 60 s call budget in place, which is exactly how a "green" suite turns into a hung CI job
         * the first time a server stops answering.
         */
        fun forTesting(
            connectTimeoutMs: Long = 2_000L,
            readTimeoutMs: Long = 2_000L,
            writeTimeoutMs: Long = 2_000L,
            callTimeoutMs: Long = 5_000L,
        ): NetworkConfig = NetworkConfig(
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            writeTimeoutMs = writeTimeoutMs,
            callTimeoutMs = callTimeoutMs,
            enableLogging = false,
        )
    }
}
