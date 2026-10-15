package com.notdisk.core.network

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A [MockWebServer] that structurally **cannot** hang a test.
 *
 * ### Why the default server is dangerous here
 * `MockWebServer`'s default dispatcher is a `QueueDispatcher`, and when its queue is empty it does
 * not fail — it **blocks** until a response is enqueued or the server is shut down. In a suite whose
 * whole point is to prove that timeouts, cancellation and retries behave, that default is the worst
 * possible choice: a test that forgets one `enqueue` (or that consumes one response more than it
 * queued, which is exactly what a retry bug looks like) does not fail, it *hangs*. A hung test hides
 * the bug, stalls CI, and — because it holds a socket and a dispatcher thread — can make the
 * following tests fail for unrelated reasons.
 *
 * This helper therefore installs a dispatcher that answers an unrouted request with an immediate
 * `500`. The consequence is deliberate and useful: an unexpected extra request turns into a
 * fast, *typed* assertion failure (`ServerError`, or a request count that is one too high) instead of
 * a timeout that nobody can attribute to a cause.
 *
 * ### What the dispatcher does, in order
 *  1. Hand back the oldest explicitly queued response, if any.
 *  2. Otherwise answer `500` with a short body naming the path — no sleep, no wait, ever.
 *
 * The queue is exposed through [unusedQueuedResponses] so that "the code stopped retrying" can be
 * asserted positively: a leftover response proves that no further request consumed it, without the
 * test having to sleep and hope.
 *
 * Callers must use [enqueue], not `MockWebServer.enqueue`, which requires the queue dispatcher that
 * this class replaces.
 */
internal class NoHangMockWebServer {

    private val queued = ConcurrentLinkedQueue<MockResponse>()

    val server: MockWebServer = MockWebServer()

    /**
     * Starts the server with the non-blocking dispatcher installed.
     *
     * Port 0 lets the OS pick a free port, so tests can run in parallel and a leftover listener from a
     * previous failure cannot make the next run fail for the wrong reason.
     */
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                queued.poll()?.let { return it }
                // The fail-fast answer. It is a 500 on purpose: if a test accidentally triggers an
                // extra request, the bug surfaces as a server error in the assertion rather than as
                // an indefinite block.
                return MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"error":"no queued response for ${request.path}"}""")
            }
        }
        server.start()
    }

    /** Queues [response] to be served by the next request. */
    fun enqueue(response: MockResponse) {
        queued.add(response)
    }

    /** Convenience for the overwhelmingly common "200 with this body" case. */
    fun enqueueJson(body: String, code: Int = 200) {
        enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    /**
     * How many queued responses were never served.
     *
     * Used to prove a negative — "the client did not retry" — without sleeping: a non-zero value
     * after a call means the request that would have consumed it never happened.
     */
    fun unusedQueuedResponses(): Int = queued.size

    /** An absolute URL on this server; [path] must start with `/`. */
    fun url(path: String): HttpUrl = server.url(path)

    /** Requests received so far, as counted by the server itself. */
    val requestCount: Int get() = server.requestCount

    /**
     * Waits for one recorded request, or returns `null` after [timeoutMs].
     *
     * Returning `null` instead of blocking forever is what keeps a "the request never arrived"
     * failure inside the test's own timeout rather than in the harness.
     */
    fun takeRequest(timeoutMs: Long = TAKE_REQUEST_TIMEOUT_MS): RecordedRequest? =
        server.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)

    /** Releases the port. Safe to call twice and never blocks on an in-flight response. */
    fun shutdown() {
        runCatching { server.shutdown() }
    }

    private companion object {
        const val TAKE_REQUEST_TIMEOUT_MS = 5_000L
    }
}
