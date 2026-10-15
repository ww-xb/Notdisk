package com.notdisk.core.network

import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.logging.NoOpCloudLogger
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Builds the one shared [OkHttpClient] every provider talks through.
 *
 * ### Why this class exists at all
 * OkHttp's own guidance is "share one client"; the reason is that a client owns a connection pool
 * and a dispatcher, so a second client means a second pool, a second set of threads, and a second
 * place where timeouts can silently disagree with the first. Meanwhile the providers in this app
 * have genuinely different needs (a QR endpoint that throttles to ~30 s per answer, a CDN that
 * wants a browser `User-Agent`, a test that wants a 250 ms read timeout). This factory resolves that
 * tension: [create] is the production path and is cheap to call repeatedly because it is built from
 * an immutable, already-validated [NetworkConfig]; [newBuilder] is the escape hatch for a provider
 * or a test that must deviate, and it starts from the same correct defaults so a deviation is
 * always a *visible* one-line change.
 *
 * ### Interceptor order is part of the contract
 * [UserAgentInterceptor] is installed *before* the logging interceptor so that a log line shows the
 * `User-Agent` that is actually on the wire. Reversing them is a classic debugging trap: the log
 * then prints a request that no server ever saw.
 *
 * ### BODY logging is banned, not merely discouraged
 * `HttpLoggingInterceptor.Level.BODY` writes the request body, the response body, **and** every
 * header of both. In this app that is credential material by construction, not by accident:
 *  - 115 authenticates with a `Cookie:` header built from the account's `UID`/`CID`/`SEID`/`KID`
 *    session fields, so anything that logs headers logs a working session;
 *  - 115 returns fresh session material in the **response body** (that is precisely why this client
 *    refuses to use a cookie jar — see below), so `BODY` would print credentials that never appear
 *    in a header at all;
 *  - download and stream URLs carry `sign`/`t`/`k` query parameters, and their bodies are the user's
 *    files.
 * Logcat is readable by other processes on a compromised or rooted device and is routinely captured
 * by bug-report tooling, so once a token lands there it must be treated as disclosed. `BASIC` emits
 * exactly one line per exchange — `--> POST /files/copy` / `<-- 200 OK` — which is enough to
 * correlate a failure with a request without ever carrying a value. Providers that need deeper
 * diagnostics use [CloudLogger] with [LogRedaction], which is designed to be safe by construction.
 *
 * ### Why there is deliberately NO cookie jar
 * The obvious design is `OkHttpClient.Builder().cookieJar(CookieJar())`, and for most APIs it is the
 * right one. It is wrong here:
 *  - **115 does not send the session in `Set-Cookie`.** Its login flow returns the session fields
 *    (`UID`, `CID`, `SEID`, `KID`) inside the JSON response body, and the client is expected to send
 *    them back as a hand-assembled `Cookie` header. A cookie jar would therefore not populate in the
 *    first place; worse, it would look like it was handling session state while the real credential
 *    lived somewhere else entirely.
 *  - **One jar cannot serve multiple accounts.** A jar is keyed by host and holds exactly one cookie
 *    per `(domain, path, name)`. This app supports several accounts of the *same* provider
 *    simultaneously, so a shared jar would let the most recent login silently overwrite the previous
 *    account's session and cause cross-account reads — the single worst failure mode a cloud-drive
 *    aggregator can have.
 *  - **Persistence and clearing would become invisible.** A jar persists by default
 *    (`JavaNetCookieJar`/`CookieManager` back onto disk), so "log out" would depend on a code path
 *    that no provider controls. Instead, credentials live in the provider's own encrypted store and
 *    are attached per request, which makes both logout and account switching explicit and testable.
 *
 * The cost of this decision is that a provider *must* attach its own `Cookie` header; that is
 * already how the 115 transport works, and it is documented again on [NetworkConfig].
 *
 * @property config the effective transport settings. Immutable, so a factory instance is safe to
 *   share across threads and accounts.
 * @property logger receives this module's own diagnostics (never headers, bodies or query strings).
 */
class HttpClientFactory(
    val config: NetworkConfig = NetworkConfig(),
    private val logger: CloudLogger = NoOpCloudLogger,
) {

    /**
     * A ready-to-use client.
     *
     * The returned client is *not* cached here: OkHttp clients are immutable, cheap to build and
     * designed to be shared, so the decision of "one client for the whole app" belongs to the DI
     * graph that owns the process lifetime, not to a factory that may be constructed per test.
     * Caching inside the factory would quietly turn a test's short-timeout client into the process
     * singleton.
     */
    fun create(): OkHttpClient = newBuilder().build()

    /**
     * The same configuration as a mutable builder, for callers that must deviate.
     *
     * Typical legitimate uses: a media player that needs a longer read timeout because it streams
     * byte ranges, a test that needs a 250 ms read timeout, or a provider that must add an
     * authentication interceptor. Every one of those shows up as an explicit call at the use site
     * rather than as a change to global defaults that would affect every other account.
     */
    fun newBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(config.effectiveCallTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(config.retryOnConnectionFailure)
            // Redirects are followed: providers hand out short-lived signed download URLs that
            // legitimately 302 to a CDN edge, and refusing to follow would surface as a bogus
            // "server error" for a normal case.
            .followRedirects(true)
            .followSslRedirects(true)

        // See the class KDoc: an explicit, empty jar documents the decision in code as well as in
        // prose, and makes it impossible for a later change to enable cookie persistence by
        // accident.
        builder.cookieJar(NO_COOKIES)

        if (config.userAgent.isNotBlank()) {
            builder.addInterceptor(UserAgentInterceptor(config.userAgent))
        }

        if (config.enableLogging) {
            builder.addInterceptor(createRedactingLoggingInterceptor(logger))
        }

        return builder
    }

    /**
     * Builds the only logging interceptor this app is allowed to install.
     *
     * Exposed so that a provider which builds its *own* client (for example a media downloader with
     * different timeouts) cannot end up with an unredacted logger just because it did not reuse
     * [create]. The level is hard-coded to [HttpLoggingInterceptor.Level.BASIC] rather than being a
     * parameter: a parameter is an invitation, and the class KDoc explains why `BODY` is never
     * acceptable here.
     *
     * Redaction is belt-and-braces. `BASIC` already logs no headers and no bodies, but the request
     * line of a redirect or a signed-URL fetch can still carry a query string, so the header names
     * that matter are registered as redacted anyway — if someone later changes the level, the
     * credential headers stay masked.
     */
    fun createRedactingLoggingInterceptor(logger: CloudLogger): Interceptor {
        val logging = HttpLoggingInterceptor { line ->
            // The interceptor's own output is untrusted text from OkHttp: it is passed through
            // redaction one more time so that a URL embedded in a line cannot leak its query.
            logger.d(LOG_TAG, LogRedaction.redactUrl(line))
        }
        logging.level = HttpLoggingInterceptor.Level.BASIC
        REDACTED_HEADERS.forEach(logging::redactHeader)
        return logging
    }

    companion object {
        private const val LOG_TAG = "CloudHttp"

        /**
         * Headers that must never appear in a log line, even if the logging level is ever raised.
         * `Authorization`/`Cookie` are the two that carry 115's session; `Set-Cookie` is present
         * because a future provider may use a conventional session, and `Proxy-Authorization`
         * because a corporate proxy is still a credential.
         */
        private val REDACTED_HEADERS = listOf(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "Proxy-Authorization",
        )

        /**
         * The explicit "no cookie jar" jar.
         *
         * `CookieJar.NO_COOKIES` is a real, empty implementation rather than `null`, so that
         * `client.cookieJar` reads back as an intentional empty jar and a future reader can tell the
         * difference between "we decided not to store cookies" and "nobody has configured this yet".
         */
        val NO_COOKIES: CookieJar = CookieJar.NO_COOKIES
    }
}

/**
 * Stamps a caller-supplied `User-Agent` onto every outgoing request.
 *
 * ### Why an interceptor and not per-request headers
 * The `User-Agent` is a *device fingerprint* for the providers this app integrates: 115 validates it
 * on several API endpoints and on its media CDN, and a request without it can be rejected outright.
 * Setting it once at the transport layer means a new endpoint added later cannot forget it, and it
 * also overrides any default OkHttp would otherwise send (`okhttp/4.12.0`), which is itself a
 * fingerprint that identifies this app as an unknown client.
 *
 * The header is *replaced*, not appended: `addHeader` would send two `User-Agent` values and let the
 * server pick, which is a source of non-reproducible failures.
 *
 * @property userAgent the exact header value. Validated non-blank by [NetworkConfig].
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // A caller that deliberately sets its own UA (for example a per-account browser profile)
        // wins; otherwise the configured one is applied. This keeps the interceptor from being a
        // silent source of "why is my custom header ignored?".
        if (request.header("User-Agent") != null) return chain.proceed(request)

        val stamped = request.newBuilder()
            .header("User-Agent", userAgent)
            .build()
        return chain.proceed(stamped)
    }

    /**
     * Exposed for tests and for diagnostics. It is intentionally a plain value, never a header map,
     * so this class cannot be used to smuggle credentials into a log line.
     */
    override fun toString(): String = "UserAgentInterceptor(userAgent=$userAgent)"
}
