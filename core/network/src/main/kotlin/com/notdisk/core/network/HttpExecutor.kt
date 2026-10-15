package com.notdisk.core.network

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.map
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException as JavaCancellationException
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Runs one OkHttp [Request] as a suspend call and normalises *everything* that can go wrong into the
 * app's typed error model.
 *
 * ### Why this exists
 * OkHttp's API is synchronous, exception-based and speaks in `IOException`s. This app's contract
 * (`core:common`) is the opposite: suspend, and failures are values ([CloudResult.Failure] carrying a
 * [CloudError]). Without one adapter, every provider would write its own `try/catch` ladder, and the
 * subtle parts — cancellation, the retry boundary, which timeout maps to which error — would be
 * re-derived, slightly differently, dozens of times.
 *
 * ### The three rules this class enforces
 *  1. **Cancellation is never a value.** A cancelled coroutine must stay cancelled; swallowing it
 *     into `CloudResult.Failure` breaks structured concurrency and makes `viewModelScope`-owned work
 *     survive the screen that started it. See [isCancellationShaped].
 *  2. **A failure is typed, never a raw exception.** No caller of [execute] has to know what
 *     `SSLHandshakeException` is.
 *  3. **Retry is opt-in.** [execute] performs exactly one HTTP attempt. See [RetryPolicy].
 *
 * ### Threading
 * OkHttp's `execute()` blocks the calling thread, so it runs on [Dispatchers.IO]; everything before
 * and after it stays on the caller's dispatcher, which means the caller's context is what governs
 * where a decoded body is observed.
 *
 * @property client the transport. Sharing one client across accounts is intended (see
 *   [HttpClientFactory]); this class adds no per-account state.
 * @property logger optional diagnostics. It is fed only redacted URLs and status codes — never a
 *   header, never a body, never a query string.
 */
class HttpExecutor(
    private val client: OkHttpClient,
    private val logger: CloudLogger = NoOpCloudLogger,
) {

    /**
     * Executes [request] once and returns the response body as a string.
     *
     * ### Why the body is returned as a `String` rather than parsed here
     * The successful shape of a response is a *provider* concern. 115 wraps everything in a
     * `state`/`error`/`data` envelope, and a media manifest is not JSON at all, so a generic parser
     * would have to guess. Returning the raw body keeps this class reusable and keeps [JsonCodec]'s
     * `decode` as the single parsing step at the call site.
     *
     * Non-2xx responses are consumed and closed before the typed failure is returned, so a caller
     * that ignores the error cannot leak a connection back into the pool.
     *
     * @param request the fully built request. Building it is the caller's job because only the caller
     *   knows whether the operation is idempotent — which is also what decides whether it may be
     *   passed to [executeWithRetry].
     * @param tag a short, non-sensitive label for log correlation (for example `"files.list"`). Must
     *   not contain an account id or a query string; use [LogRedaction.redactId] if one is needed.
     */
    suspend fun execute(request: Request, tag: String = DEFAULT_TAG): CloudResult<String> =
        executeRaw(request, tag).map { response ->
            // The response is always rebuilt around a buffered body by `executeRaw` (see
            // `withBufferedBody`), so the body here is non-null in practice; `orEmpty()` covers the
            // status-only response OkHttp can theoretically hand back and keeps this a total
            // function rather than a place a `!!` could crash a caller that has already succeeded.
            // Reading it is a memory copy, so it cannot fail with an I/O error that would have to
            // become an error value after the response was consumed.
            response.body?.string().orEmpty()
        }

    /**
     * Executes [request] once and returns the raw [Response] for header inspection.
     *
     * ### Why this variant exists, and why it is a *buffered* response
     * Some endpoints answer 200 with no useful body and put everything in the headers (a `Location`
     * to a CDN edge, a `Retry-After`, an `ETag` for a range request). Callers that need those cannot
     * use [execute], which throws the headers away.
     *
     * The returned response is rebuilt around an already-drained body, so the **connection is
     * released before this function returns**. A caller that forgets to close the response therefore
     * leaks nothing, which matters because a leaked response holds a pooled socket and
     * `HttpExecutor` is used from long-lived background workers where such a leak is invisible until
     * the pool is exhausted.
     *
     * The body is **single-read**, exactly like any OkHttp body: `body?.string()` returns the payload
     * the first time and an empty string afterwards, because reading consumes the underlying buffer.
     * A caller that needs the payload more than once must retain the string rather than re-reading
     * the body.
     *
     * Non-2xx responses are *not* returned: they become typed failures, because a caller inspecting
     * headers of a 500 is almost always a bug.
     */
    suspend fun executeRaw(request: Request, tag: String = DEFAULT_TAG): CloudResult<Response> =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)

            logger.d(
                LOG_TAG,
                "$tag ${request.method} ${LogRedaction.redactUrl(request.url.toString())}",
            )

            val response = try {
                awaitCall(call)
            } catch (cancellation: CancellationException) {
                // Coroutine cancellation can surface directly when the dispatcher observes it.
                throw cancellation
            } catch (timeout: SocketTimeoutException) {
                logger.w(TAG_TIMEOUT, "$tag timed out", timeout)
                return@withContext CloudResult.Failure(CloudError.Timeout(cause = timeout))
            } catch (timeout: InterruptedIOException) {
                // OkHttp's own call/read timeout arrives as an InterruptedIOException whose message
                // is "timeout". It must not be confused with our cancellation, so the coroutine's
                // own state is consulted first — it is the only unambiguous signal available from
                // inside a catch block.
                if (!currentCoroutineContext().isActive || isCancellationShaped(timeout)) {
                    throw asCancellation(timeout)
                }
                logger.w(TAG_TIMEOUT, "$tag interrupted/timeout", timeout)
                return@withContext CloudResult.Failure(CloudError.Timeout(cause = timeout))
            } catch (dns: UnknownHostException) {
                logger.w(TAG_OFFLINE, "$tag dns failure", dns)
                return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = dns))
            } catch (tls: SSLHandshakeException) {
                // A TLS failure is not "the network is down": retrying will not help and the user
                // must not be told to check Wi-Fi. It is reported as a server-side problem so the
                // surfaced message stays truthful.
                logger.e(TAG_TLS, "$tag tls handshake failed", tls)
                return@withContext CloudResult.Failure(
                    CloudError.ServerError(
                        message = "安全连接失败",
                        vendorMessage = tls.message?.take(MAX_VENDOR_MESSAGE),
                        cause = tls,
                    ),
                )
            } catch (tls: SSLException) {
                logger.e(TAG_TLS, "$tag tls failure", tls)
                return@withContext CloudResult.Failure(
                    CloudError.ServerError(
                        message = "安全连接失败",
                        vendorMessage = tls.message?.take(MAX_VENDOR_MESSAGE),
                        cause = tls,
                    ),
                )
            } catch (io: IOException) {
                // A cancelled call is reported by OkHttp as a plain IOException whose message is
                // "Canceled"; the coroutine's own state is checked first so that our own abort is
                // never mistaken for a network fault.
                if (!currentCoroutineContext().isActive || isCancellationShaped(io)) {
                    throw asCancellation(io)
                }
                logger.w(TAG_OFFLINE, "$tag io failure", io)
                return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = io))
            } catch (unexpected: Throwable) {
                // Anything that is not an IOException (a bug in an interceptor, an
                // IllegalStateException from a closed client) still must not escape as a raw
                // throwable, but it must stay *visible* as Unknown rather than being disguised as a
                // network problem.
                if (unexpected is CancellationException) throw unexpected
                currentCoroutineContext().ensureActive()
                logger.e(TAG_UNEXPECTED, "$tag unexpected transport failure", unexpected)
                return@withContext CloudResult.Failure(
                    CloudError.Unknown(
                        message = unexpected.message?.take(MAX_VENDOR_MESSAGE) ?: "请求失败",
                        cause = unexpected,
                    ),
                )
            }

            response.use { res ->
                val bufferedBody = try {
                    res.body?.let { body -> body.string() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (timeout: SocketTimeoutException) {
                    // A timeout that fires while draining the body is the *common* case for a
                    // throttled or stalled endpoint: the status line and headers arrive immediately
                    // and the payload never follows. Reporting it as NetworkUnavailable would tell
                    // the user to check their connection when the server is the slow side, so it
                    // must be a Timeout here too — not only on the `execute()` path above.
                    logger.w(TAG_TIMEOUT, "$tag body read timed out", timeout)
                    return@withContext CloudResult.Failure(CloudError.Timeout(cause = timeout))
                } catch (io: IOException) {
                    if (!currentCoroutineContext().isActive || isCancellationShaped(io)) {
                        throw asCancellation(io)
                    }
                    logger.w(TAG_OFFLINE, "$tag body read failed", io)
                    return@withContext CloudResult.Failure(
                        CloudError.NetworkUnavailable(message = "读取响应失败", cause = io),
                    )
                }

                logger.d(LOG_TAG, "$tag -> HTTP ${res.code}")

                if (!res.isSuccessful) {
                    return@withContext CloudResult.Failure(
                        res.toCloudError(bufferedBody.orEmpty()),
                    )
                }

                CloudResult.Success(res.withBufferedBody(bufferedBody.orEmpty()))
            }
        }

    /**
     * Runs OkHttp's blocking `execute()` in a way that a coroutine cancellation can actually abort.
     *
     * ### Why the obvious implementations are wrong
     * `Call.execute()` blocks a thread on a socket read, and **coroutine cancellation is
     * cooperative** — it does not interrupt a thread by itself. Two plausible approaches both fail:
     *  - `withContext(Dispatchers.IO) { call.execute() }` — cancellation is only observed *after* the
     *    call returns, so a cancelled request keeps its socket (and the radio, and the server-side
     *    work) until the read timeout expires. Measured with a 60 s read budget, a cancelled call held
     *    the connection for the full 60 s.
     *  - `Job.invokeOnCompletion { call.cancel() }` — the *public* one-argument overload fires when
     *    the job reaches a terminal state, which is exactly too late: the blocking call must finish
     *    before the job can complete. (The overload that fires *on cancelling* is
     *    `@InternalCoroutinesApi`, so relying on it would make this transport depend on a coroutine
     *    internal.)
     * `Thread.interrupt()` is not sufficient either: a plain `Socket` read is not interruptible by
     * design, so the thread stays blocked regardless.
     *
     * ### What this does instead
     * [suspendCancellableCoroutine] hands out a continuation whose [invokeOnCancellation] handler is
     * invoked **immediately and on the cancelling thread** — that is a documented guarantee, and the
     * supported public way to release a resource that another thread is blocked on. The handler calls
     * [Call.cancel], which closes the socket and makes the blocked `execute()` throw at once; the
     * exception is then recognised as a cancellation and propagated (see [executeRaw]).
     *
     * No thread is leaked and no extra thread is created: the blocking call occupies one
     * `Dispatchers.IO` thread, exactly as it would without this helper.
     */
    private suspend fun awaitCall(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            // The socket read unblocks as soon as this closes the connection.
            call.cancel()
        }

        try {
            val response = call.execute()
            if (continuation.isActive) {
                // `resumeWith(Result…)` is the stable `Continuation` entry point. The
                // `CancellableContinuation`-specific `resume`/`tryResume` members are either
                // `@InternalCoroutinesApi` or need an `onCancellation` handler that does not apply
                // here, because cleanup is already handled by the `invokeOnCancellation` block above.
                continuation.resumeWith(Result.success(response))
            } else {
                // Cancelled while the call was completing: the response will never reach a caller, so
                // its body must be released here rather than leaked into the connection pool. This is
                // the path a cancelled request takes — `call.cancel()` makes `execute()` throw, and
                // the `isActive` guard keeps a late result from resuming a dead continuation.
                response.close()
            }
        } catch (throwable: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(throwable))
            }
        }
    }

    /**
     * Executes [request] with a bounded, *explicitly requested* retry.
     *
     * ### Why retrying is not the default, and why it must be the caller's decision
     * An automatic retry is only safe when the operation is **idempotent**: sending it twice must
     * have the same effect as sending it once. Cloud-drive mutations are frequently not idempotent,
     * and the failure mode is silent and expensive:
     *  - `files/copy` is *additive* — a retried copy creates a second copy, and the user sees
     *    duplicate files with no error anywhere;
     *  - `files/add` (create folder) retried creates `New folder (1)`, or fails with a confusing
     *    "already exists" if the vendor does de-duplicate names;
     *  - an upload or a chunked write is only safe to repeat at a known offset, which is a property of
     *    the *upload session*, not of the HTTP request;
     *  - a move is idempotent in effect but a retry after a partial success can address a path that
     *    no longer exists, turning a success into a spurious "file not found".
     * The transport genuinely cannot tell these apart — it sees a method and a URL. So it does not
     * guess: a caller that knows its operation is safe passes [retryPolicy], and a caller that is
     * unsure does not, which yields exactly one attempt and an honest error.
     *
     * ### What is retried
     * Only failures whose [CloudError.isRetryable] is true (timeout, transport failure, 5xx, 429) and
     * only while attempts remain. A 4xx such as 404 or 403 is a *decision* by the server and is
     * returned immediately: retrying it would waste the user's time and can trigger rate limiting.
     *
     * Backoff grows exponentially from [RetryPolicy.initialBackoffMs], is capped at
     * [RetryPolicy.maxBackoffMs], and — when [RetryPolicy.jitterRatio] is non-zero — is randomised
     * downwards by up to that ratio. The jitter matters when several accounts of the same provider
     * recover from the same outage: without it, every queued request fires in lockstep and re-creates
     * the overload it is waiting out. It is *downward* jitter, so the configured initial backoff
     * remains the upper bound and a test can still assert a deterministic ceiling.
     *
     * The delay itself is a real `delay`, so it is cancellable: cancelling the caller during backoff
     * throws instead of continuing to hammer the server.
     *
     * @param retryPolicy attempt count and backoff shape. Defaults to a single attempt with no delay,
     *   which makes the parameter's presence the whole safety mechanism.
     */
    suspend fun executeWithRetry(
        request: Request,
        tag: String = DEFAULT_TAG,
        retryPolicy: RetryPolicy = RetryPolicy.singleAttempt(),
    ): CloudResult<String> {
        var attempt = 0
        var lastFailure: CloudResult.Failure? = null

        while (attempt < retryPolicy.maxAttempts) {
            currentCoroutineContext().ensureActive()
            attempt++

            when (val result = execute(request, tag)) {
                is CloudResult.Success -> return result

                is CloudResult.Failure -> {
                    lastFailure = result
                    val isLastAttempt = attempt >= retryPolicy.maxAttempts
                    if (isLastAttempt || !result.error.isRetryable) {
                        logger.d(
                            LOG_TAG,
                            "$tag giving up after $attempt attempt(s): ${result.error.javaClass.simpleName}",
                        )
                        return result
                    }

                    val backoff = retryPolicy.backoffForAttempt(attempt)
                    logger.d(LOG_TAG, "$tag retrying in ${backoff}ms (attempt $attempt)")
                    delay(backoff)
                }
            }
        }

        // Unreachable while `maxAttempts >= 1`, which `RetryPolicy` enforces. Written as an explicit
        // value rather than `error(...)` so a future change to the loop cannot turn a transport
        // problem into a crash.
        return lastFailure ?: CloudResult.Failure(
            CloudError.Unknown(message = "请求未执行"),
        )
    }

    /**
     * Executes [request] with retry and returns the raw response, for idempotent calls that need
     * headers (a range probe, a signed-URL HEAD). Same rules as [executeWithRetry].
     */
    suspend fun executeRawWithRetry(
        request: Request,
        tag: String = DEFAULT_TAG,
        retryPolicy: RetryPolicy = RetryPolicy.singleAttempt(),
    ): CloudResult<Response> {
        var attempt = 0
        var lastFailure: CloudResult.Failure? = null

        while (attempt < retryPolicy.maxAttempts) {
            currentCoroutineContext().ensureActive()
            attempt++

            when (val result = executeRaw(request, tag)) {
                is CloudResult.Success -> return result

                is CloudResult.Failure -> {
                    lastFailure = result
                    if (attempt >= retryPolicy.maxAttempts || !result.error.isRetryable) return result
                    delay(retryPolicy.backoffForAttempt(attempt))
                }
            }
        }

        return lastFailure ?: CloudResult.Failure(CloudError.Unknown(message = "请求未执行"))
    }

    private companion object {
        const val LOG_TAG = "CloudHttpExec"
        const val TAG_TIMEOUT = "CloudHttpTimeout"
        const val TAG_OFFLINE = "CloudHttpOffline"
        const val TAG_TLS = "CloudHttpTls"
        const val TAG_UNEXPECTED = "CloudHttpUnexpected"
        const val DEFAULT_TAG = "http"

        /** Caps a vendor message so an error cannot become a payload of its own. */
        const val MAX_VENDOR_MESSAGE = 200
    }
}

/**
 * How many times, and how patiently, an *idempotent* request may be repeated.
 *
 * ### Why this is a data class with a validating constructor
 * A retry policy is the kind of value that is wrong in a way nobody notices: `maxAttempts = 10` with
 * a one-second backoff looks harmless and turns one failed listing into a ten-second hang during
 * which the UI has nothing to show. Making the bounds explicit and validated means an accidental
 * combination fails at construction, in a test, rather than in a user's hands.
 *
 * @property maxAttempts total attempts, **including the first**. `1` means "no retry at all", which
 *   is the default everywhere in this app, because an un-retried failure is honest and a retried
 *   mutation may create duplicates.
 * @property initialBackoffMs delay before the second attempt. Ignored when [maxAttempts] is 1.
 * @property maxBackoffMs ceiling for the exponential growth, so a long outage does not end with a
 *   multi-minute sleep between attempts.
 * @property jitterRatio fraction of the computed delay that may be randomly removed, in `0.0..1.0`.
 *   Defaults to a small value rather than `0.0`: several accounts recovering together would
 *   otherwise retry in lockstep and re-create the overload. Set to `0.0` in tests that assert exact
 *   timing.
 */
data class RetryPolicy(
    val maxAttempts: Int = 1,
    val initialBackoffMs: Long = 500L,
    val maxBackoffMs: Long = 8_000L,
    val jitterRatio: Double = 0.2,
) {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        require(initialBackoffMs >= 0) { "initialBackoffMs must be >= 0, was $initialBackoffMs" }
        require(maxBackoffMs >= initialBackoffMs) {
            "maxBackoffMs ($maxBackoffMs) must be >= initialBackoffMs ($initialBackoffMs)"
        }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0.0..1.0, was $jitterRatio" }
    }

    /**
     * The delay before attempt number [attempt] + 1, where [attempt] is the 1-based index of the
     * attempt that just failed.
     *
     * Doubling starts from [initialBackoffMs] and saturates at [maxBackoffMs] *before* jitter is
     * applied, so the returned value never exceeds [maxBackoffMs].
     */
    fun backoffForAttempt(attempt: Int): Long {
        if (maxAttempts <= 1) return 0L
        // Shift by (attempt - 1) is the textbook exponential backoff, but a large attempt count
        // would shift past Long's width and produce a negative delay (an immediate hot retry loop).
        // Capping the shift keeps the arithmetic total.
        val exponent = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val growth = 1L shl exponent
        val raw = if (initialBackoffMs == 0L || growth > maxBackoffMs / initialBackoffMs.coerceAtLeast(1L)) {
            maxBackoffMs
        } else {
            min(initialBackoffMs * growth, maxBackoffMs)
        }
        if (jitterRatio <= 0.0) return raw
        val removable = (raw * jitterRatio).toLong()
        if (removable <= 0L) return raw
        // Downward jitter only: the configured backoff stays an upper bound, which is what makes it
        // testable and keeps a retry from ever sleeping longer than the caller asked for.
        val removed = ThreadLocalRandom.current().nextLong(0L, removable + 1L)
        return (raw - removed).coerceAtLeast(0L)
    }

    companion object {
        /** A shift beyond this cannot be represented and is meaningless anyway once capped. */
        private const val MAX_SHIFT = 30

        /** One attempt, no retry. The default, deliberately. */
        fun singleAttempt(): RetryPolicy = RetryPolicy(maxAttempts = 1)

        /**
         * A conservative policy for reads: three attempts with a short, jittered backoff.
         *
         * Only ever pass this for operations that are idempotent by construction — a listing, a
         * metadata fetch, a download URL resolution.
         */
        fun forIdempotentReads(): RetryPolicy = RetryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 500L,
            maxBackoffMs = 4_000L,
            jitterRatio = 0.2,
        )
    }
}

/**
 * True when [throwable] is OkHttp's way of reporting a cancelled call rather than a real I/O fault.
 *
 * ### Why a string check is the only option, and why it is not as fragile as it looks
 * OkHttp does not expose a public "was this cancelled" flag on the exception it throws from
 * `Call.execute()`. When a call is cancelled (by `Call.cancel()` *or* by an interrupt), the
 * `RealCall`/`RetryAndFollowUpInterceptor` path throws `IOException("Canceled")` — that exact literal
 * is the contract, and it is asserted by OkHttp's own tests. `RetryAndFollowUpInterceptor` also
 * wraps it as `"canceled due to …"` when a route is being retried, and an interrupt produces
 * `InterruptedIOException("canceled")`.
 *
 * The check is deliberately **narrow**: `"Canceled"`/`"canceled"` as a whole word, plus the
 * `InterruptedIOException` case, and it never matches a generic IOException with an unrelated
 * message. A false negative (we treat a cancellation as a network error) leaves a stale result
 * flowing into a cancelled screen; a false *positive* would turn a genuine, reportable network
 * failure into a silently swallowed non-result — the worse of the two — so the pattern is anchored
 * rather than a loose `contains("cancel")` (which would also match a server message such as
 * "operation cancelled by peer").
 */
internal fun isCancellationShaped(throwable: Throwable): Boolean {
    if (throwable is CancellationException) return true
    if (throwable is JavaCancellationException) return true
    if (throwable.cause?.let { it !== throwable && isCancellationShaped(it) } == true) return true

    val message = throwable.message ?: return false
    if (message == "Canceled" || message == "canceled") return true
    // `RetryAndFollowUpInterceptor` prefixes the reason: "canceled due to ...".
    if (message.startsWith("canceled due to", ignoreCase = true)) return true
    // An interrupted blocking read: okio/OkHttp report this as an interrupted I/O.
    if (throwable is InterruptedIOException && message.contains("cancel", ignoreCase = true)) return true
    return false
}

/**
 * Converts a cancellation-shaped [throwable] into the coroutine [CancellationException] that the
 * caller's structured concurrency expects.
 *
 * `kotlinx.coroutines.CancellationException` is a typealias of `java.util.concurrent.CancellationException`
 * on the JVM, so this is a *rethrow of the cancellation* rather than a wrapping: a `catch
 * (e: CancellationException)` in any caller — including `withContext`, `runTest` and
 * `supervisorScope` — recognises it and lets it propagate.
 */
internal fun asCancellation(throwable: Throwable): CancellationException =
    if (throwable is CancellationException) {
        throwable
    } else {
        CancellationException(throwable.message ?: "call canceled").also { it.initCause(throwable) }
    }

/**
 * Maps an unsuccessful HTTP response onto the closest typed [CloudError].
 *
 * ### Why the mapping lives outside [HttpExecutor]
 * Status handling is where providers differ: 115 answers HTTP 200 with an `errno` in the body for
 * most business errors, so its own layer must get a chance to reinterpret the response *before* a
 * status code is turned into an error. Keeping this a pure function of `(code, body, headers)` lets
 * the generic path use it and lets a provider override or reuse it without inheriting the executor's
 * coroutine machinery.
 *
 * ### Why each branch
 *  - **429** → [CloudError.RateLimited], carrying `Retry-After`. Telling a user "server error" for
 *    rate limiting makes them retry harder, which is the exact opposite of what is needed.
 *  - **401** → [CloudError.TokenExpired] (`canRefresh = true`) rather than [CloudError.NotAuthenticated]:
 *    a 401 means we *sent* something and it was rejected, i.e. a stored credential exists and may be
 *    refreshable. [CloudError.NotAuthenticated] is reserved for "no account is bound", which the
 *    provider decides — the transport cannot see the difference.
 *  - **403** → [CloudError.PermissionDenied]; expired signed URLs are a provider-level refinement.
 *  - **404/410** → [CloudError.NotFound] / [CloudError.Deleted]; 410 is explicitly "gone", which lets
 *    the UI offer "remove from cache" instead of "retry".
 *  - **400/422** → [CloudError.InvalidArgument], because a retry cannot fix a malformed request.
 *  - **5xx** → [CloudError.ServerError], retryable.
 *  - anything else → [CloudError.Unknown] with the status preserved. Never [CloudError.ServerError]
 *    by default: an unrecognised status is an unrecognised *fact*, and `Unknown` keeps it honest.
 *
 * The body is used only for a short, redacted diagnostic (`vendorMessage`). It is truncated because
 * it can be an entire HTML error page, and passing it through unchanged would put a signed URL or a
 * session echo into an error that travels into the UI and crash reports.
 */
fun Response.toCloudError(body: String): CloudError {
    // A short, single-line excerpt: the reason phrase is often the only machine-readable part, and
    // an HTML error page must not become a 40 KB error value.
    val excerpt = body.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(MAX_ERROR_EXCERPT)
        ?.takeIf { it.isNotEmpty() }

    return when (code) {
        429 -> CloudError.RateLimited(
            retryAfterSeconds = header("Retry-After")?.trim()?.toLongOrNull(),
            vendorMessage = excerpt ?: message.ifBlank { null },
        )

        401 -> CloudError.TokenExpired(
            canRefresh = true,
            vendorMessage = excerpt ?: message.ifBlank { null },
        )

        403 -> CloudError.PermissionDenied(
            vendorMessage = excerpt ?: message.ifBlank { null },
        )

        404 -> CloudError.NotFound(vendorMessage = excerpt ?: message.ifBlank { null })

        410 -> CloudError.Deleted(vendorMessage = excerpt ?: message.ifBlank { null })

        400, 422 -> CloudError.InvalidArgument(
            message = "请求参数不合法",
            vendorMessage = excerpt ?: message.ifBlank { null },
        )

        408 -> CloudError.Timeout()

        in 500..599 -> CloudError.ServerError(
            httpStatus = code,
            vendorMessage = excerpt ?: message.ifBlank { null },
        )

        else -> CloudError.Unknown(
            message = "HTTP $code",
            httpStatus = code,
            vendorMessage = excerpt ?: message.ifBlank { null },
        )
    }
}

/**
 * Rebuilds [this] response around an already-read body so the returned object owns no socket.
 *
 * Keeping the original status, headers and message is what makes the buffered response usable for
 * header inspection; only the body source changes, from a stream to an in-memory buffer. The
 * original `Content-Type` is preserved, so a caller that branches on `application/json` versus
 * `application/octet-stream` still sees the server's answer rather than a synthesised one.
 */
internal fun Response.withBufferedBody(body: String): Response = newBuilder()
    .body(body.toResponseBody(contentTypeOrNull()))
    .build()

/**
 * The media type of the response body, or `null` when the server sent none.
 *
 * Recovered from the `Content-Type` header rather than from `ResponseBody.contentType()` because the
 * buffered path reads the body *before* rebuilding the response, and the body object of the original
 * response is already consumed at that point.
 */
private fun Response.contentTypeOrNull(): MediaType? =
    header("Content-Type")?.let { it.toMediaTypeOrNull() }

/** The excerpt cap for `vendorMessage`. See [toCloudError]. */
private const val MAX_ERROR_EXCERPT = 200
