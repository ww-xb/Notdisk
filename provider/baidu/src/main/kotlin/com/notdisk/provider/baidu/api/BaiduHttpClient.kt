package com.notdisk.provider.baidu.api

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody

/**
 * Low-level HTTP transport for the Baidu Netdisk API.
 *
 * ### Responsibilities, and the deliberate absence of others
 * This class knows how to *send* a request and *normalize the envelope*. It knows nothing about
 * files, media or accounts. That split matters because three of this API's quirks are global rather
 * than per-endpoint, and keeping them in one small, tested class is what keeps the provider classes
 * readable:
 *
 *  1. **Every request carries the same five global parameters.** The vendor's own client installs
 *     them once (`Bi.install(si["a"], {clienttype:"0", app_id:250528, web:1})`, §0.1) and its request
 *     interceptor adds `dp-logid` to *every* call. Omitting any of them produces authentication or
 *     routing failures that look like permission problems.
 *  2. **Success is strictly `errno === 0`.** The vendor's axios instance sets
 *     `validateStatus: () => true`, i.e. **HTTP status is not a failure signal at all** — a 200 can
 *     carry a business failure and a 400 can carry a structured one (§7.3's `errno 31023` arrives
 *     with HTTP 400). So the envelope is parsed regardless of status.
 *  3. **The credential is a cookie, not a bearer header.** The caller passes the `Cookie` value per
 *     request, because two accounts of the same provider must be able to issue requests
 *     concurrently without sharing mutable state.
 *
 * ### What it does NOT do
 *  - It does not hold credentials (see point 3).
 *  - It does not retry. Retry decisions depend on the *operation*: a retried `filemanager` copy is
 *    not the same as a retried listing, so they belong to the caller.
 */
internal class BaiduHttpClient(
    private val logger: CloudLogger,
    /** Overridable for tests, which need to point at a local server. Keyed by host constant. */
    private val baseOverride: Map<String, String> = emptyMap(),
    okHttpClient: OkHttpClient? = null,
    /** Injected so `dp-logid` is deterministic in tests. */
    private val logIdFactory: () -> String = ::defaultDpLogId,
) {

    /**
     * A single shared client.
     *
     * `followRedirects` is on because `dlink`s are documented to redirect server-side (§5.3's
     * "dlink → 真实文件主机的 302 跳转行为未经实测" — the transfer is expected to be handled by the
     * HTTP stack rather than by this client).
     */
    val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Resolves a full endpoint URL against the configured host overrides.
     *
     * ### Why prefix replacement rather than a plain map lookup
     * [BaiduEndpoints] stores *complete* URLs (they are built from [BaiduHosts] at class-init time).
     * A plain `baseOverride[url]` lookup therefore never matches, because the map is keyed by
     * **host**, not by the full URL — a mistake that silently sends test traffic to the real service
     * instead of the local server.
     */
    private fun resolveHost(url: String): String {
        baseOverride[url]?.let { return it }
        for ((from, to) in baseOverride) {
            if (url.startsWith(from)) {
                return to + url.removePrefix(from)
            }
        }
        return url
    }

    /** Exposes host resolution so providers can log/report the effective base. */
    fun resolve(url: String): String = resolveHost(url)

    // ------------------------------------------------------------------ request builders

    /**
     * A GET request with the global parameters merged in.
     *
     * `bdstoken` is **not** added automatically: it is a write-operation credential (§13.2 item 6),
     * and sending it on a read the vendor does not expect it on would widen the credential's
     * exposure surface for no benefit. Write callers pass it explicitly via [BaiduGlobalParams].
     */
    fun get(
        url: String,
        query: Map<String, String?> = emptyMap(),
        cookies: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        globalParams: Boolean = true,
    ): Request {
        val parsed = url.toHttpUrlOrNull()
            ?: error("Invalid Baidu endpoint URL: $url")
        val builder = parsed.newBuilder()
        if (globalParams) {
            defaults().forEach { (key, value) -> builder.addQueryParameter(key, value) }
        }
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }

        return baseRequest(builder.build(), cookies, extraHeaders)
            .get()
            .build()
    }

    /**
     * A POST request with a URL-encoded body — the content type the vendor's axios instance defaults
     * to for writes (§0.1).
     */
    fun postForm(
        url: String,
        form: Map<String, String?>,
        query: Map<String, String?> = emptyMap(),
        cookies: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        globalParams: Boolean = true,
    ): Request {
        val parsed = url.toHttpUrlOrNull()
            ?: error("Invalid Baidu endpoint URL: $url")
        val builder = parsed.newBuilder()
        if (globalParams) {
            defaults().forEach { (key, value) -> builder.addQueryParameter(key, value) }
        }
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }

        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> if (value != null) add(key, value) }
        }.build()

        return baseRequest(builder.build(), cookies, extraHeaders)
            .post(body)
            .build()
    }

    /**
     * The five parameters the vendor installs globally (§0.1).
     *
     * `dp-logid` is regenerated per request — the vendor's interceptor does exactly that
     * (`t["dp-logid"] = getDpLogId()`), and reusing one across requests would make its own risk
     * control see a single "request" repeated.
     */
    private fun defaults(): Map<String, String> = mapOf(
        "clienttype" to CLIENT_TYPE,
        "app_id" to APP_ID,
        "web" to "1",
        "channel" to CHANNEL,
        "dp-logid" to logIdFactory(),
    )

    /**
     * The headers that make a request look like the vendor's own client.
     *
     * These are not decoration: the reference material records a `Referer`-validated request path
     * and that the download endpoint's reconstructed headers are
     * `Referer: https://pan.baidu.com/disk/main` plus `X-Requested-With` (§5.3's validation note).
     * The vendor's axios instance sets `X-Requested-With` on every request by default (§0.1).
     */
    private fun baseRequest(
        url: HttpUrl,
        cookies: String?,
        extraHeaders: Map<String, String>,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("Referer", "https://pan.baidu.com/disk/main")
        .header("Origin", "https://pan.baidu.com")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Accept", "application/json, text/plain, */*")
        .header("User-Agent", DEFAULT_USER_AGENT)
        .apply {
            if (!cookies.isNullOrBlank()) header("Cookie", cookies)
            extraHeaders.forEach { (name, value) -> header(name, value) }
        }

    // ------------------------------------------------------------------ execution

    /**
     * Executes [request] and normalizes the response envelope.
     *
     * All transport failures become typed [CloudError]s here so no provider method has to reason
     * about `IOException` subtypes. Cancellation is preserved: OkHttp reports a cancelled call as an
     * `IOException("Canceled")`, and swallowing that into a `Failure` would break structured
     * concurrency in a way that is very hard to debug.
     */
    suspend fun execute(
        request: Request,
        tag: String,
        family: BaiduErrorFamily = BaiduErrorFamily.GENERAL,
        partialFailureCodes: Set<Int> = emptySet(),
    ): CloudResult<BaiduEnvelope> = withContext(Dispatchers.IO) {
        logger.d(TAG, "$tag ${request.method} ${LogRedaction.redactUrl(request.url.toString())}")

        val response = try {
            client.newCall(request).execute()
        } catch (timeout: SocketTimeoutException) {
            logger.w(TAG, "$tag timeout", timeout)
            return@withContext CloudResult.Failure(CloudError.Timeout(cause = timeout))
        } catch (dns: UnknownHostException) {
            logger.w(TAG, "$tag dns failure", dns)
            return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = dns))
        } catch (io: IOException) {
            if (io.message?.contains("Canceled", ignoreCase = true) == true ||
                io.message?.contains("cancel", ignoreCase = true) == true
            ) {
                throw kotlinx.coroutines.CancellationException(io.message)
            }
            logger.w(TAG, "$tag io failure", io)
            return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = io))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            logger.e(TAG, "$tag unexpected transport failure", t)
            return@withContext CloudResult.Failure(
                CloudError.Unknown(message = t.message ?: "请求失败", cause = t),
            )
        }

        response.use { res ->
            val body = try {
                res.body?.string().orEmpty()
            } catch (io: IOException) {
                return@withContext CloudResult.Failure(
                    CloudError.NetworkUnavailable(message = "读取响应失败", cause = io),
                )
            }

            logger.d(TAG, "$tag -> HTTP ${res.code}, ${body.length} bytes")
            BaiduJson.parseEnvelope(body, res.code, family, partialFailureCodes)
        }
    }

    /**
     * Executes a request whose response is the HLS playlist text rather than an `errno` envelope.
     *
     * `/api/streaming`'s **second** phase is documented to answer `HTTP 200` with
     * `Content-Type: application/x-mpegURL` and an `#EXTM3U` body (§7.3). It is still necessary to
     * detect the *first* phase's JSON (`errno 133`), because the same URL returns either depending on
     * whether `adToken` was supplied — so this method checks the content type rather than assuming.
     */
    suspend fun executeForText(
        request: Request,
        tag: String,
        family: BaiduErrorFamily = BaiduErrorFamily.MEDIA,
    ): CloudResult<String> = withContext(Dispatchers.IO) {
        logger.d(TAG, "$tag ${request.method} ${LogRedaction.redactUrl(request.url.toString())}")

        val response = try {
            client.newCall(request).execute()
        } catch (timeout: SocketTimeoutException) {
            return@withContext CloudResult.Failure(CloudError.Timeout(cause = timeout))
        } catch (dns: UnknownHostException) {
            return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = dns))
        } catch (io: IOException) {
            if (io.message?.contains("Canceled", ignoreCase = true) == true ||
                io.message?.contains("cancel", ignoreCase = true) == true
            ) {
                throw kotlinx.coroutines.CancellationException(io.message)
            }
            return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = io))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return@withContext CloudResult.Failure(
                CloudError.Unknown(message = t.message ?: "请求失败", cause = t),
            )
        }

        response.use { res ->
            val body = try {
                res.body?.string().orEmpty()
            } catch (io: IOException) {
                return@withContext CloudResult.Failure(
                    CloudError.NetworkUnavailable(message = "读取响应失败", cause = io),
                )
            }

            val contentType = res.header("Content-Type").orEmpty()
            val looksLikePlaylist = body.trimStart().startsWith("#EXTM3U") ||
                contentType.contains("mpegurl", ignoreCase = true)

            if (looksLikePlaylist) {
                logger.i(TAG, "$tag -> HTTP ${res.code}, HLS playlist (${body.length} bytes)")
                CloudResult.Success(body)
            } else {
                // The JSON first phase (or an error). Normalize it so the caller gets a typed reason
                // instead of an unparseable "playlist".
                BaiduJson.parseEnvelope(body, res.code, family).let { envelope ->
                    when (envelope) {
                        is CloudResult.Success -> CloudResult.Failure(
                            CloudError.ParseError(
                                message = "百度网盘未返回 HLS 清单（Content-Type: $contentType）",
                            ),
                        )

                        is CloudResult.Failure -> CloudResult.Failure(envelope.error)
                    }
                }
            }
        }
    }

    /**
     * Executes a request and returns the **business outcome without classifying it**.
     *
     * This exists for exactly one caller: `/api/streaming`, whose phase one answers `errno 133` and
     * carries an `adToken` that phase two needs. That is a *documented handshake*, not a failure, and
     * the token lives in the error payload — so classifying it into a [CloudError] would either lose
     * the token or force vendor fields into the shared error type.
     *
     * Only *transport* failures become [CloudResult.Failure] here. A non-zero business `errno` is
     * reported through [BaiduRawResponse.code] so the caller can decide what it means. Every other
     * caller must use [execute], which applies the project-wide error mapping.
     */
    suspend fun executeRawEnvelope(
        request: Request,
        tag: String,
    ): CloudResult<BaiduRawResponse> = withContext(Dispatchers.IO) {
        logger.d(TAG, "$tag ${request.method} ${LogRedaction.redactUrl(request.url.toString())}")

        val response = try {
            client.newCall(request).execute()
        } catch (timeout: SocketTimeoutException) {
            return@withContext CloudResult.Failure(CloudError.Timeout(cause = timeout))
        } catch (dns: UnknownHostException) {
            return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = dns))
        } catch (io: IOException) {
            if (io.message?.contains("Canceled", ignoreCase = true) == true ||
                io.message?.contains("cancel", ignoreCase = true) == true
            ) {
                throw kotlinx.coroutines.CancellationException(io.message)
            }
            return@withContext CloudResult.Failure(CloudError.NetworkUnavailable(cause = io))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return@withContext CloudResult.Failure(
                CloudError.Unknown(message = t.message ?: "请求失败", cause = t),
            )
        }

        response.use { res ->
            val body = try {
                res.body?.string().orEmpty()
            } catch (io: IOException) {
                return@withContext CloudResult.Failure(
                    CloudError.NetworkUnavailable(message = "读取响应失败", cause = io),
                )
            }

            // A playlist answer has no envelope at all. Both shapes are returned so the caller can
            // tell "phase two succeeded" from "phase one gated" without re-issuing anything.
            val root = try {
                BaiduJson.lenient.parseToJsonElement(body) as? JsonObject
            } catch (_: Throwable) {
                null
            }

            CloudResult.Success(
                BaiduRawResponse(
                    root = root,
                    body = body,
                    code = root?.get("errno").asIntOrNull() ?: root?.get("error_code").asIntOrNull(),
                    httpStatus = res.code,
                    contentType = res.header("Content-Type").orEmpty(),
                    requestUrl = request.url.toString(),
                ),
            )
        }
    }

    /** Fetches a body verbatim — used for byte-range probes and non-JSON preview resources. */
    suspend fun executeRaw(request: Request): CloudResult<ResponseBody> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext CloudResult.Failure(
                    CloudError.ServerError(
                        message = "HTTP ${response.code}",
                        httpStatus = response.code,
                    ),
                )
            }
            val body = response.body
            if (body == null) {
                response.close()
                CloudResult.Failure(CloudError.ParseError(message = "响应没有 body"))
            } else {
                CloudResult.Success(body)
            }
        } catch (timeout: SocketTimeoutException) {
            CloudResult.Failure(CloudError.Timeout(cause = timeout))
        } catch (io: IOException) {
            CloudResult.Failure(CloudError.NetworkUnavailable(cause = io))
        }
    }

    companion object {
        private const val TAG = "BaiduHttp"

        /**
         * The personal-edition `app_id` (§1.2). It is the value used 224 times in the reference
         * capture and the only one this client needs; the enterprise (`24029990`) and team-space
         * (`118511220`) variants are different product lines and **mixing them returns permission
         * errors** (§13.2 item 5).
         */
        const val APP_ID = "250528"

        /** The web client sends `0` (§1.2). AI endpoints use `21`, which this build does not call. */
        const val CLIENT_TYPE = "0"

        /** The web channel identifier (§1.2). */
        const val CHANNEL = "chunlei"

        /**
         * The vendor validates a browser-like UA on several endpoints and on its CDN, and the
         * reference material notes a custom UA acts as a device fingerprint (§5.3's rebuilt headers).
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36 NotDisk/0.2"

        /** `<13-digit epoch millis><8-digit random>`, matching §1.2's documented shape. */
        fun defaultDpLogId(): String =
            System.currentTimeMillis().toString() +
                Random.nextInt(10_000_000, 100_000_000).toString()
    }
}

/**
 * A response whose business outcome has deliberately **not** been classified.
 *
 * See [BaiduHttpClient.executeRawEnvelope] for the one case that needs this: `/api/streaming`, where a
 * non-zero `errno` is a documented handshake step carrying data rather than a failure. It exists as a
 * narrow escape hatch so that no vendor field ever has to be added to the shared [CloudError] type.
 */
internal data class BaiduRawResponse(
    /** The parsed envelope, or `null` when the body was not JSON (e.g. an HLS playlist). */
    val root: JsonObject?,
    /** The raw body, so a non-JSON answer (a playlist) stays usable. */
    val body: String,
    /** The business code, or `null` when the body carried none. */
    val code: Int?,
    val httpStatus: Int,
    val contentType: String,
    /** The effective request URL, which for `/api/streaming` **is** the playable location. */
    val requestUrl: String,
) {
    /** True when the body is an HLS playlist rather than an envelope. */
    val isPlaylist: Boolean
        get() = root == null &&
            (body.trimStart().startsWith("#EXTM3U") || contentType.contains("mpegurl", ignoreCase = true))
}

/**
 * The parameters a *write* request must carry in addition to the globals.
 *
 * Held as a value object rather than as a bare string so a call site cannot forget it: the vendor
 * documents `bdstoken` as mandatory for every mutating endpoint (§13.2 item 6), and the failure mode
 * of omitting it is `errno 2`/`-6`, which reads like a permission problem rather than a missing
 * parameter.
 */
internal data class BaiduGlobalParams(
    /**
     * The CSRF token from `/api/gettemplatevariable?fields=["bdstoken"]` (§2.4). A 32-hex string.
     * It is session material but not a bearer credential on its own: it is only meaningful alongside
     * the session cookie, which is why it is passed separately from the cookie header.
     */
    val bdstoken: String,
) {
    /** Renders the query parameters the vendor appends to every write. */
    fun asQuery(): Map<String, String?> = mapOf("bdstoken" to bdstoken)

    /** Redacted by design: interpolating a CSRF token into a log line must not leak it. */
    override fun toString(): String = "BaiduGlobalParams(bdstoken=***)"
}
