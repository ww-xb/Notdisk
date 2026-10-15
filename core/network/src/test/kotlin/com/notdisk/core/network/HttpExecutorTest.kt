package com.notdisk.core.network

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.InMemoryCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.errorOrNull
import com.notdisk.core.common.result.getOrNull
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse

/**
 * Transport behaviour of [HttpExecutor], proven against a real socket.
 *
 * ### Why these are integration tests rather than mocks of OkHttp
 * The properties under test here *are* OkHttp's behaviours: which exception a read timeout produces,
 * whether a cancelled call surfaces as `IOException("Canceled")`, whether `Response` maps to the
 * right typed error. Mocking `Call` would replace exactly the code that can be wrong with a stub that
 * agrees with the test's assumptions. A local server is the only way to observe the real thing; the
 * alternative is finding out in production.
 *
 * ### How hanging is prevented
 *  - [NoHangMockWebServer] answers an unrouted request with an immediate `500` instead of blocking.
 *  - Every client is built from [NetworkConfig.forTesting], whose budgets are seconds, not minutes.
 *  - Every suspending test runs inside `withTimeout`, so a regression fails with a named
 *    `TimeoutCancellationException` instead of stalling the suite.
 */
class HttpExecutorTest {

    private lateinit var server: NoHangMockWebServer
    private lateinit var executor: HttpExecutor

    /** Short enough to keep the suite fast, long enough not to be flaky on a loaded machine. */
    private val testConfig = NetworkConfig.forTesting(
        connectTimeoutMs = 1_000L,
        readTimeoutMs = 1_000L,
        writeTimeoutMs = 1_000L,
        callTimeoutMs = 3_000L,
    )

    @BeforeTest
    fun setUp() {
        server = NoHangMockWebServer()
        server.start()
        executor = HttpExecutor(HttpClientFactory(testConfig).create())
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String = "/ok"): Request = Request.Builder().url(server.url(path)).build()

    // ------------------------------------------------------------------ happy path

    @Test
    fun `successful GET returns the body verbatim`(): Unit = runBlocking {
        server.enqueueJson("""{"state":true,"data":{"n":"报告.pdf"}}""")

        val result = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.get") }

        val body = assertIs<CloudResult.Success<String>>(result).data
        assertEquals("""{"state":true,"data":{"n":"报告.pdf"}}""", body)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `executeRaw exposes headers of a successful response`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Retry-After", "7")
                .setHeader("X-Trace", "abc")
                .setBody("payload"),
        )

        val result = withTimeout(TEST_TIMEOUT_MS) { executor.executeRaw(get(), "test.raw") }

        val response = assertIs<CloudResult.Success<Response>>(result).data
        assertEquals(200, response.code)
        assertEquals("abc", response.header("X-Trace"))
        // The body was drained before the response was handed over, so the connection is already
        // released: forgetting to close this response cannot leak a pooled socket. The body itself
        // stays single-read, exactly like any OkHttp body — a second read is empty, which is the
        // documented contract callers must respect.
        assertEquals("payload", response.body?.string())
        response.close()
    }

    @Test
    fun `body is empty rather than null for a 204`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.no-content") }

        assertEquals("", assertIs<CloudResult.Success<String>>(result).data)
    }

    // ------------------------------------------------------------------ status mapping

    @Test
    fun `500 maps to a retryable ServerError carrying the status`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.500") }
            .let { assertIs<CloudResult.Failure>(it).error }

        val serverError = assertIs<CloudError.ServerError>(error)
        assertEquals(500, serverError.httpStatus)
        assertTrue(serverError.isRetryable, "a 5xx must be retryable; the UI offers a retry for it")
    }

    @Test
    fun `429 maps to RateLimited and preserves Retry-After`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "42")
                .setBody("slow down"),
        )

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.429") }
            .let { assertIs<CloudResult.Failure>(it).error }

        val limited = assertIs<CloudError.RateLimited>(error)
        assertEquals(42L, limited.retryAfterSeconds)
        assertEquals(429, limited.httpStatus)
    }

    @Test
    fun `401 maps to a refreshable TokenExpired rather than an anonymous NotAuthenticated`(): Unit =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.401") }
                .let { assertIs<CloudResult.Failure>(it).error }

            // The distinction matters to the UI: a 401 means a credential exists and *failed*, which
            // is a different screen from "no account has ever been bound".
            val expired = assertIs<CloudError.TokenExpired>(error)
            assertTrue(expired.canRefresh)
            assertTrue(expired.requiresReauthentication)
        }

    @Test
    fun `404 is not retryable`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such file"))

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.404") }
            .let { assertIs<CloudResult.Failure>(it).error }

        assertIs<CloudError.NotFound>(error)
        assertFalse(error.isRetryable, "retrying a 404 wastes the user's time and can trip rate limits")
    }

    @Test
    fun `malformed response body of an error is truncated into vendorMessage`(): Unit = runBlocking {
        // A vendor HTML error page is the realistic shape here: it must not become a 40 KB error
        // value that travels through a StateFlow and into a crash report.
        val html = "<html><body>" + "x".repeat(5_000) + "</body></html>"
        server.enqueue(MockResponse().setResponseCode(502).setBody(html))

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.long-error") }
            .let { assertIs<CloudResult.Failure>(it).error }

        val vendorMessage = assertIs<CloudError.ServerError>(error).vendorMessage
        assertNotNull(vendorMessage)
        assertTrue(vendorMessage.length <= 200, "vendorMessage must be capped, was ${vendorMessage.length}")
    }

    // ------------------------------------------------------------------ timeouts

    @Test
    fun `read timeout maps to Timeout, not NetworkUnavailable`(): Unit = runBlocking {
        // Headers are delayed past the read timeout. This is the shape a black-holed or badly
        // throttled endpoint actually has, and telling the user "no network" for it would be a lie
        // that sends them to fix Wi-Fi.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("late")
                .setHeadersDelay(3, TimeUnit.SECONDS),
        )

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.timeout") }
            .let { assertIs<CloudResult.Failure>(it).error }

        val timeout = assertIs<CloudError.Timeout>(error)
        assertIs<SocketTimeoutException>(timeout.cause)
    }

    @Test
    fun `timeout while draining the body is also a Timeout`(): Unit = runBlocking {
        // Status line and headers arrive at once; the payload never follows. Without an explicit
        // catch on the body-read path this surfaces as a generic IOException and would be mislabelled
        // as NetworkUnavailable.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("payload")
                .setBodyDelay(3, TimeUnit.SECONDS),
        )

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.body-timeout") }
            .let { assertIs<CloudResult.Failure>(it).error }

        assertIs<CloudError.Timeout>(error)
    }

    // ------------------------------------------------------------------ DNS / transport

    @Test
    fun `unresolvable host maps to NetworkUnavailable`(): Unit = runBlocking {
        // `.invalid` is reserved by RFC 2606 and is guaranteed never to resolve, so this test cannot
        // start passing for the wrong reason on a network that hijacks NXDOMAIN.
        val request = Request.Builder().url("http://notdisk-nonexistent-host.invalid/ping").build()

        val error = withTimeout(TEST_TIMEOUT_MS) { executor.execute(request, "test.dns") }
            .let { assertIs<CloudResult.Failure>(it).error }

        val unavailable = assertIs<CloudError.NetworkUnavailable>(error)
        assertIs<java.net.UnknownHostException>(unavailable.cause)
        assertTrue(unavailable.isRetryable, "a DNS failure is often transient and must be retryable")
    }

    @Test
    fun `connection refused maps to NetworkUnavailable`(): Unit = runBlocking {
        // A server that was started and immediately closed: the port is (almost certainly) free, so
        // the connect fails with ECONNREFUSED — a transport failure that is not a timeout and not a
        // DNS problem.
        val closed = NoHangMockWebServer()
        closed.start()
        val url = closed.url("/gone")
        closed.shutdown()

        val error = withTimeout(TEST_TIMEOUT_MS) {
            executor.execute(Request.Builder().url(url).build(), "test.refused")
        }.let { assertIs<CloudResult.Failure>(it).error }

        assertIs<CloudError.NetworkUnavailable>(error)
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `cancelling the coroutine propagates CancellationException and never a Failure`(): Unit =
        runBlocking {
            // The response never completes on its own; only cancellation can end this call.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("never arrives")
                    .setHeadersDelay(30, TimeUnit.SECONDS),
            )

            val started = AtomicInteger(0)

            val job = launch(Dispatchers.IO) {
                started.incrementAndGet()
                // Deliberately NOT wrapped in runCatching: if the executor swallowed the
                // cancellation into CloudResult.Failure, this assignment would never happen and the
                // assertions below would fail on their own terms instead of silently passing.
                executor.execute(get("/slow"), "test.cancel")
                started.incrementAndGet()
            }

            // Wait until the request is genuinely in flight, so this tests cancellation of a live
            // call rather than cancellation of a coroutine that had not started.
            withTimeout(TEST_TIMEOUT_MS) {
                while (server.requestCount == 0) delay(10)
            }
            job.cancel()

            withContext(Dispatchers.IO) { job.join() }

            assertTrue(job.isCancelled, "the job must be cancelled")
            assertEquals(
                1,
                started.get(),
                "the second increment must never run: a cancelled call must not resume normally",
            )
        }

    @Test
    fun `cancellation surfaces as CancellationException when awaited`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("never arrives")
                .setHeadersDelay(30, TimeUnit.SECONDS),
        )

        val deferred = async(Dispatchers.IO) { executor.execute(get("/slow-await"), "test.cancel-await") }

        withTimeout(TEST_TIMEOUT_MS) {
            while (server.requestCount == 0) delay(10)
        }
        deferred.cancel()

        // `assertFailsWith` on the awaited deferred is the strongest available statement of the
        // contract: the caller sees a CancellationException, not a CloudResult.Failure value.
        assertFailsWith<CancellationException> { deferred.await() }
    }

    @Test
    fun `an uncancelled slow call still returns its body`(): Unit = runBlocking {
        // Guards the cancellation tests themselves: if the executor were simply broken for slow
        // responses, the two tests above would pass for the wrong reason. This one proves the call
        // completes when nobody cancels it.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("finally")
                .setHeadersDelay(200, TimeUnit.MILLISECONDS),
        )

        val result = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get("/slow-ok"), "test.slow-ok") }

        assertEquals("finally", result.getOrNull())
    }


    @Test
    fun `cancellation actually aborts the in-flight socket call`(): Unit = runBlocking {
        // A dedicated client whose read budget (60 s) is far longer than the observation window.
        // This is what makes the assertion meaningful: with the suite's normal 1 s read timeout the
        // call would end on its own, and the probe would pass even if cancellation did nothing.
        val client = HttpClientFactory(
            NetworkConfig.forTesting(
                connectTimeoutMs = 2_000L,
                readTimeoutMs = 60_000L,
                writeTimeoutMs = 2_000L,
                callTimeoutMs = 90_000L,
            ),
        ).create()
        val longExecutor = HttpExecutor(client)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("never arrives")
                .setHeadersDelay(30, TimeUnit.SECONDS),
        )

        val deferred = async(Dispatchers.IO) { longExecutor.execute(get("/inflight"), "test.inflight") }
        withTimeout(TEST_TIMEOUT_MS) {
            while (server.requestCount == 0) delay(10)
        }
        deferred.cancel()

        // The decisive assertion. Only a genuine abort of the socket can bring this to zero inside
        // the window, because the client itself would otherwise keep reading for 60 s. Measuring the
        // latency (rather than merely "eventually zero") is what stops a slow fix from passing: the
        // abort must be immediate, not "before the read timeout".
        val cancelledAt = System.nanoTime()
        var running = -1
        withTimeout(ABORT_WINDOW_MS) {
            while (true) {
                running = client.dispatcher.runningCallsCount()
                if (running == 0) break
                delay(5)
            }
        }
        val abortLatencyMs = (System.nanoTime() - cancelledAt) / 1_000_000
        assertEquals(0, running, "the cancelled call must stop occupying a socket")
        assertTrue(
            abortLatencyMs < 1_500L,
            "cancellation must abort the socket promptly, took ${abortLatencyMs}ms",
        )

        // And the coroutine itself must still terminate as a cancellation, not as a value.
        assertFailsWith<CancellationException> { deferred.await() }
    }

    // ------------------------------------------------------------------ retry

    @Test
    fun `execute performs exactly one attempt`(): Unit = runBlocking {
        // The safety property of the whole module: an un-retried mutation cannot create duplicates.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("should never be served"))

        val result = withTimeout(TEST_TIMEOUT_MS) { executor.execute(get(), "test.single") }

        assertIs<CloudResult.Failure>(result)
        assertEquals(1, server.requestCount, "execute() must not retry")
        assertEquals(1, server.unusedQueuedResponses(), "the second response must remain unserved")
    }

    @Test
    fun `executeWithRetry retries exactly maxAttempts times on 500`(): Unit = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }

        val policy = RetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 10L,
            maxBackoffMs = 20L,
            // Jitter off so the assertion is about attempt *count*, not timing.
            jitterRatio = 0.0,
        )

        val result = withTimeout(TEST_TIMEOUT_MS) {
            executor.executeWithRetry(get("/retry"), "test.retry", policy)
        }

        assertIs<CloudResult.Failure>(result)
        assertEquals(3, server.requestCount, "one initial attempt plus two retries")
        assertEquals(0, server.unusedQueuedResponses())
    }

    @Test
    fun `executeWithRetry stops immediately on a 404 and never consumes the retry`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        // Left in place on purpose: if the executor retried, this response would be consumed and the
        // count assertion below would fail with a much clearer signal than a timing check.
        server.enqueue(MockResponse().setResponseCode(200).setBody("a retry happened"))

        val policy = RetryPolicy(
            maxAttempts = 5,
            initialBackoffMs = 10L,
            maxBackoffMs = 20L,
            jitterRatio = 0.0,
        )

        val result = withTimeout(TEST_TIMEOUT_MS) {
            executor.executeWithRetry(get("/missing"), "test.no-retry-404", policy)
        }

        assertIs<CloudError.NotFound>(assertIs<CloudResult.Failure>(result).error)
        assertEquals(1, server.requestCount, "a 4xx is a decision, not a transient fault")
        assertEquals(1, server.unusedQueuedResponses())
    }

    @Test
    fun `executeWithRetry succeeds on the second attempt without consuming the third`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("recovered"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("too many"))

        val policy = RetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 10L,
            maxBackoffMs = 20L,
            jitterRatio = 0.0,
        )

        val result = withTimeout(TEST_TIMEOUT_MS) {
            executor.executeWithRetry(get("/recover"), "test.recover", policy)
        }

        assertEquals("recovered", result.getOrNull())
        assertEquals(2, server.requestCount)
        assertEquals(1, server.unusedQueuedResponses())
    }

    @Test
    fun `a retry never fires when the caller did not ask for one`(): Unit = runBlocking {
        // This is the duplicate-creating scenario the design exists to prevent: the caller models a
        // non-idempotent copy and deliberately passes no policy.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("second copy created"))

        val result = withTimeout(TEST_TIMEOUT_MS) {
            executor.executeWithRetry(get("/files/copy"), "test.copy")
        }

        assertIs<CloudResult.Failure>(result)
        assertEquals(1, server.requestCount, "the default policy is a single attempt")
    }

    // ------------------------------------------------------------------ retry policy arithmetic

    @Test
    fun `backoff grows exponentially, honours the cap and stays within the configured bounds`() {
        val policy = RetryPolicy(
            maxAttempts = 6,
            initialBackoffMs = 100L,
            maxBackoffMs = 800L,
            jitterRatio = 0.0,
        )

        assertEquals(100L, policy.backoffForAttempt(1))
        assertEquals(200L, policy.backoffForAttempt(2))
        assertEquals(400L, policy.backoffForAttempt(3))
        assertEquals(800L, policy.backoffForAttempt(4))
        // Saturated: a long outage must not end with a multi-minute sleep.
        assertEquals(800L, policy.backoffForAttempt(5))
        // A large attempt count must not shift past the width of a Long and produce a negative delay
        // (which would become a hot retry loop).
        assertTrue(policy.backoffForAttempt(100) >= 0L)
        assertEquals(800L, policy.backoffForAttempt(100))
    }

    @Test
    fun `jitter only removes time and never exceeds the configured backoff`() {
        val policy = RetryPolicy(
            maxAttempts = 4,
            initialBackoffMs = 1_000L,
            maxBackoffMs = 1_000L,
            jitterRatio = 0.25,
        )

        repeat(200) {
            val delay = policy.backoffForAttempt(1)
            assertTrue(delay in 750L..1_000L, "jittered delay out of range: $delay")
        }
    }

    @Test
    fun `a single-attempt policy never sleeps`() {
        assertEquals(0L, RetryPolicy.singleAttempt().backoffForAttempt(1))
        assertEquals(1, RetryPolicy.singleAttempt().maxAttempts)
    }

    @Test
    fun `impossible retry policies are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialBackoffMs = -1L) }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(initialBackoffMs = 1_000L, maxBackoffMs = 100L)
        }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(jitterRatio = 1.5) }
    }

    // ------------------------------------------------------------------ redaction

    @Test
    fun `no log line ever contains a query string, a cookie or a body`(): Unit = runBlocking {
        val logger = InMemoryCloudLogger()
        val loggingExecutor = HttpExecutor(
            HttpClientFactory(testConfig.copy(enableLogging = true), logger).create(),
            logger,
        )
        server.enqueueJson("""{"access_token":"super-secret-token"}""")

        // A signed URL: the credential material is in the query, which is precisely what must not be
        // logged. The Cookie header carries 115's session.
        val request = Request.Builder()
            .url(server.url("/files/download?sign=deadbeef&t=1234&k=SECRETKEY"))
            .header("Cookie", "UID=1_A1; CID=abcdef; SEID=verysecret")
            .build()

        withTimeout(TEST_TIMEOUT_MS) { loggingExecutor.execute(request, "test.redaction") }

        val logged = logger.snapshot().joinToString("\n") { "${it.tag} ${it.message}" }
        assertTrue(logged.isNotEmpty(), "the logging path must actually log something")
        assertFalse(logged.contains("deadbeef"), "query values must never be logged: $logged")
        assertFalse(logged.contains("SECRETKEY"), "signed-URL keys must never be logged: $logged")
        assertFalse(logged.contains("verysecret"), "cookies must never be logged: $logged")
        assertFalse(
            logged.contains("super-secret-token"),
            "response bodies must never be logged (BASIC only): $logged",
        )
        // The path itself is still useful for correlation and must survive redaction.
        assertTrue(logged.contains("/files/download"), "the path must remain correlatable: $logged")
    }

    // ------------------------------------------------------------------ JSON codec

    @Serializable
    private data class Envelope(val state: Boolean = false, val count: String? = null)

    @Test
    fun `malformed JSON maps to ParseError and never throws`() {
        val result = JsonCodec.decode<Envelope>("{ this is not json")

        val error = assertIs<CloudResult.Failure>(result).error
        assertIs<CloudError.ParseError>(error)
        assertNotNull(error.cause, "the decoder's own message is what makes a mapper fixable")
        assertFalse(error.isRetryable, "a malformed body will be malformed again on retry")
    }

    @Test
    fun `an empty body maps to ParseError`() {
        assertIs<CloudError.ParseError>(JsonCodec.decode<Envelope>("").errorOrNull())
    }

    @Test
    fun `decodeOrNull returns null instead of an error for a shape probe`() {
        assertNull(JsonCodec.decodeOrNull<Envelope>("not json at all"))
        assertNotNull(JsonCodec.decodeOrNull<Envelope>("""{"state":true}"""))
    }

    @Test
    fun `unknown keys are ignored so a vendor can add fields without breaking the app`() {
        val envelope = JsonCodec.decodeOrNull<Envelope>(
            """{"state":true,"count":"12","brand_new_field":{"nested":[1,2,3]}}""",
        )

        assertNotNull(envelope)
        assertTrue(envelope.state)
        assertEquals("12", envelope.count)
    }

    @Test
    fun `lenient parsing accepts the vendor's quoted and unquoted renderings`() {
        // 115 renders the same flag as a boolean on one endpoint and as 1/0 on another; both must
        // decode into the same value.
        assertEquals(true, JsonCodec.readBooleanFlag(tree("""{"state":true}"""), "state"))
        assertEquals(true, JsonCodec.readBooleanFlag(tree("""{"state":1}"""), "state"))
        assertEquals(true, JsonCodec.readBooleanFlag(tree("""{"state":"1"}"""), "state"))
        assertEquals(false, JsonCodec.readBooleanFlag(tree("""{"state":"false"}"""), "state"))
        // Absence is not falsity: a missing flag must not read as "the operation failed".
        assertNull(JsonCodec.readBooleanFlag(tree("{}"), "state"))
        assertNull(JsonCodec.readBooleanFlag(tree("""{"state":"maybe"}"""), "state"))
    }

    @Test
    fun `readLongOrNull accepts a quoted number and refuses to default to zero`() {
        assertEquals(42L, JsonCodec.readLongOrNull(tree("""{"count":42}"""), "count"))
        assertEquals(42L, JsonCodec.readLongOrNull(tree("""{"count":"42"}"""), "count"))
        assertNull(JsonCodec.readLongOrNull(tree("""{"count":""}"""), "count"))
        assertNull(JsonCodec.readLongOrNull(tree("{}"), "count"))
        assertNull(JsonCodec.readLongOrNull(tree("""{"count":"many"}"""), "count"))
    }

    @Test
    fun `explicit nulls are not encoded so a write cannot accidentally clear a field`() {
        val encoded = JsonCodec.encodeOrNull(Envelope(state = true, count = null))
        assertNotNull(encoded)
        assertFalse(encoded.contains("count"), "an absent field must stay absent: $encoded")
    }

    private fun tree(json: String) =
        assertNotNull(JsonCodec.parseTreeOrNull(json) as? JsonObject)
    private companion object {
        /**
         * An outer bound on every suspending test.
         *
         * [NoHangMockWebServer] already makes the *server* incapable of blocking, and the client's own
         * budgets are ~1 s; this is the third layer, and it is the one that produces a named failure
         * if either of the other two is ever changed in a way that reintroduces a wait.
         */
        const val TEST_TIMEOUT_MS = 20_000L

        /**
         * How long a cancelled call may keep a socket. Far below the probe client's 60 s read budget
         * and the server's 30 s header delay, so the assertion can only pass if the abort is real.
         */
        const val ABORT_WINDOW_MS = 3_000L
    }
}
