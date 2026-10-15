package com.notdisk.provider.p115.api

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/**
 * The hosts this integration talks to.
 *
 * Transcribed from `API_REFERENCE.md` §1.1, which lists 13 hosts and then notes that an app only
 * needs eight of them. Only the hosts that this build actually calls are declared here — adding an
 * unused host would make the dependency surface look larger than it is.
 */
internal object P115Hosts {
    /** Main file API: listings, operations, download links, video metadata, preview, trash. */
    const val WEB_API = "https://webapi.115.com"

    /** Authentication: token exchange, login, logout, safe-key status. */
    const val PASSPORT_API = "https://passportapi.115.com"

    /** QR-code login: token issuance and status polling. */
    const val QRCODE_API = "https://qrcodeapi.115.com"

    /** Session validation (`?ct=ajax&ac=nav`). */
    const val MY = "https://my.115.com"

    /** Playback progress / repeat detection. */
    const val APS = "https://aps.115.com"
}

/**
 * Low-level HTTP transport for the 115 API.
 *
 * ### Responsibilities (and the deliberate absence of others)
 * This class knows how to *send* a request and *normalise the envelope*. It knows nothing about
 * files, media or accounts. That split matters because the envelope rules (§1.3) and the credential
 * rules (§2.1) are the two places where 115 differs most from a conventional REST API, and keeping
 * them in one small, tested class is what keeps the provider classes readable.
 *
 * ### What it does NOT do
 *  - It does not hold credentials. The caller passes the cookie header per request, because two
 *    accounts of the same provider must be able to issue requests concurrently without sharing
 *    mutable state.
 *  - It does not retry. Retry decisions depend on the *operation* (a retried copy is not the same
 *    as a retried listing), so they belong to the caller.
 */
internal class P115HttpClient(
    private val logger: CloudLogger,
    /** Overridable for tests, which need to point at a local server. */
    private val baseOverride: Map<String, String> = emptyMap(),
    okHttpClient: OkHttpClient? = null,
) {

    /**
     * A single shared client.
     *
     * Timeouts are split deliberately: 115's QR status endpoint is documented as being throttled to
     * roughly one effective response per 30 seconds (§2.1 Step 2), so a short read timeout there
     * would produce spurious failures, while file listings should fail fast.
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
     * [P115Endpoints] stores *complete* URLs (they are built from the host constants at class-init
     * time). A plain `baseOverride[url]` lookup therefore never matches, because the map is keyed by
     * **host**, not by the full URL — a mistake that silently sends test traffic to the real service
     * instead of the local server.
     *
     * Matching on the host prefix and substituting it handles both shapes: an override keyed by
     * `https://webapi.115.com` rewrites `https://webapi.115.com/files?...` correctly, and an
     * override that is already a full URL is used verbatim.
     */
    private fun host(url: String): String {
        baseOverride[url]?.let { return it }
        for ((from, to) in baseOverride) {
            if (url.startsWith(from)) {
                return to + url.removePrefix(from)
            }
        }
        return url
    }

    // ------------------------------------------------------------------ request builders

    /**
     * A GET request.
     *
     * The headers mirror §1.4. `Referer`/`Origin` are not decoration: the vendor validates them for
     * some endpoints (the manual records a "老乡验证失败" error otherwise), and the media CDN
     * requires them on every segment fetch.
     */
    fun get(
        url: String,
        query: Map<String, String?> = emptyMap(),
        cookies: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val parsed = url.toHttpUrlOrNull()
            ?: error("Invalid 115 endpoint URL: $url")
        val builder = parsed.newBuilder()
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }

        return baseRequest(builder.build(), cookies, extraHeaders)
            .get()
            .build()
    }

    /** A POST request with a URL-encoded body — the only content type 115 accepts for writes. */
    fun postForm(
        url: String,
        form: Map<String, String?>,
        cookies: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> if (value != null) add(key, value) }
        }.build()

        return baseRequest(url.toHttpUrlOrNull() ?: error("Invalid 115 endpoint URL: $url"), cookies, extraHeaders)
            .post(body)
            .build()
    }

    /**
     * A POST request whose body is a repeated-key form.
     *
     * This exists because of the single most error-prone detail of the whole API (§5.3, §5.4, §8.2,
     * §8.3 and §15.2 item 3): 115's batch endpoints require **indexed** keys — `fid[0]=…&fid[1]=…`
     * — and reject `file_ids=`, `file_id=` and `ids=` with `errno 1001`. A `Map<String, String>`
     * cannot express that safely (a duplicate key would be silently overwritten), so the builder
     * takes an ordered list of pairs.
     */
    fun postIndexedForm(
        url: String,
        pairs: List<Pair<String, String>>,
        cookies: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val body = FormBody.Builder().apply {
            pairs.forEach { (key, value) -> add(key, value) }
        }.build()

        return baseRequest(url.toHttpUrlOrNull() ?: error("Invalid 115 endpoint URL: $url"), cookies, extraHeaders)
            .post(body)
            .build()
    }

    private fun baseRequest(
        url: HttpUrl,
        cookies: String?,
        extraHeaders: Map<String, String>,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("Referer", "https://115.com/")
        .header("Origin", "https://115.com")
        .header("Accept", "application/json, text/plain, */*")
        .header("User-Agent", DEFAULT_USER_AGENT)
        .apply {
            if (!cookies.isNullOrBlank()) header("Cookie", cookies)
            extraHeaders.forEach { (name, value) -> header(name, value) }
        }

    // ------------------------------------------------------------------ execution

    /**
     * Executes [request] and normalises the response envelope.
     *
     * All transport failures are converted to typed [CloudError]s here so that no provider method
     * ever has to reason about `IOException` subtypes. Cancellation is preserved: [withContext] on
     * [Dispatchers.IO] propagates `CancellationException` and OkHttp's synchronous call is
     * interrupted through the coroutine's cancellation of the blocking call.
     */
    suspend fun execute(request: Request, tag: String): CloudResult<P115Envelope> =
        withContext(Dispatchers.IO) {
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
                // A cancelled coroutine surfaces here as an IOException on some OkHttp versions;
                // if the coroutine is genuinely cancelled we must not swallow it into a Failure.
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
                P115Json.parseEnvelope(body, res.code)
            }
        }

    /** Fetches a body verbatim — used for the QR PNG fallback and for direct-link HEAD probes. */
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

    /** Resolves a caller-supplied host alias such as [P115Hosts.WEB_API] to its effective base. */
    fun resolve(host: String): String = host(host)

    companion object {
        private const val TAG = "P115Http"

        /**
         * 115 validates a browser-like UA on several endpoints and on its CDN. The manual notes
         * (§1.2) that a custom UA is used as a device fingerprint, so this is not merely cosmetic;
         * it is also what distinguishes this app's traffic from an unrecognised client.
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36 NotDisk/0.1"
    }
}
