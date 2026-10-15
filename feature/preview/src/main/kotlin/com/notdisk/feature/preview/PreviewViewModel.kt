package com.notdisk.feature.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.PreviewHint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The preview screen's state machine: classify a file once, then resolve only the access material
 * the chosen branch actually needs.
 *
 * ### Why the classification and the content are separate steps
 * `PreviewResult` answers *how* a file should be shown and carries the (signed, short-lived) way to
 * fetch it. Those are different lifetimes: the classification is stable for the session, the URL
 * expires in minutes. Holding them in one state object with one `isLoading` flag would make "the
 * classification succeeded but the text body failed to download" indistinguishable from "we do not
 * know what this file is" — and the UI needs to say different things in those two cases.
 *
 * Hence [PreviewUiState.error] describes a *classification* failure, while
 * [PreviewUiState.textError] describes a *content* failure that leaves the classification valid.
 *
 * ### Why no provider is consulted here
 * There is no `when (providerId)` in this file and there cannot be one: every vendor difference
 * arrives already folded into a [PreviewKind] by `CloudFileRepository.preview`, which routes through
 * the provider registry. This class only knows the closed set of kinds.
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val files: CloudFileRepository,
    private val httpClient: OkHttpClient,
    private val logger: CloudLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    /**
     * Classifies [fileId] and resolves whatever access material its kind needs.
     *
     * @param hint metadata the caller already has (name, extension, MIME type, size). Passing it is
     *   not an optimisation but a correctness requirement for some providers: 115's file id encodes
     *   only `cid`/`fid`/`pickcode`, so without a hint the provider cannot classify without an extra
     *   round trip it does not always need.
     */
    fun load(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        hint: PreviewHint = PreviewHint.Empty,
    ) {
        // Recorded before the request so `retry()` works even when the *first* attempt failed — which
        // is the only situation in which the retry action is reachable at all.
        lastIdentity = Identity(providerId, accountId, fileId, hint)

        viewModelScope.launch {
            _state.value = PreviewUiState(isLoading = true)

            when (
                val result = files.preview(
                    accountId = accountId,
                    providerId = providerId,
                    fileId = fileId,
                    resolveAccess = true,
                    hint = hint,
                )
            ) {
                is CloudResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = messageFor(result.error), errorKind = result.error)
                }

                is CloudResult.Success -> {
                    val preview = result.data
                    logger.i(
                        TAG,
                        "preview classified as ${preview.kind::class.simpleName} " +
                            "for ${LogRedaction.redactId(fileId.value)}",
                    )
                    _state.update { it.copy(result = preview) }

                    when (val kind = preview.kind) {
                        is PreviewKind.Text -> loadTextBody(preview, kind, accountId, providerId, fileId)

                        // Both branches render one image; the image branch additionally knows an
                        // original may exist. Resolving a thumbnail for either keeps the screen's
                        // data flow identical, and the UI decides what to offer.
                        is PreviewKind.Image,
                        is PreviewKind.ThumbnailOnly,
                        -> loadThumbnail(preview, accountId, providerId, fileId)

                        // Video/audio hand off to the player, PDF/Office/Unsupported render from the
                        // classification alone. Nothing further to fetch.
                        else -> _state.update { it.copy(isLoading = false) }
                    }
                }
            }
        }
    }

    /** Re-runs [load] for the identity it was last given. Backs the error state's retry action. */
    fun retry() {
        val identity = lastIdentity ?: return
        load(identity.providerId, identity.accountId, identity.fileId, identity.hint)
    }

    // ------------------------------------------------------------------ internals

    /**
     * Fetches the text body with a hard byte ceiling.
     *
     * ### Why the read is capped rather than trusted
     * A `.log` or `.json` in a cloud drive can be hundreds of megabytes, and the provider is under no
     * obligation to report a size. Reading it into a `String` to display the first screenful would
     * allocate the whole file, which on a mid-range device is an OOM. The cap therefore implements the
     * contract [PreviewKind.Text.inlineLimitChars] describes, and the truncation is *reported* to the
     * user rather than hidden — a silently shortened document is worse than a labelled one.
     *
     * ### Secrets
     * `PreviewResource.url` is signed and short-lived. It is never logged, never stored in the state
     * object, and never persisted; the log line records only the byte count. `LogRedaction.redactUrl`
     * is used for the one diagnostic that names the host at all.
     */
    private suspend fun loadTextBody(
        preview: PreviewResult,
        kind: PreviewKind.Text,
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ) {
        val resource = preview.resource
        if (resource == null) {
            // The provider classified the file as text but returned no way to read it. That is a
            // legitimate answer (some vendors only hand out a URL on request), and it leaves the
            // download action as the remaining route — not an error page.
            _state.update {
                it.copy(
                    isLoading = false,
                    textError = "该网盘未提供文本内容地址，可下载后查看",
                )
            }
            return
        }

        val outcome = withContext(Dispatchers.IO) {
            fetchText(resource.url, resource.headers, kind.encoding)
        }

        _state.update { current ->
            when (outcome) {
                is TextOutcome.Failure -> current.copy(
                    isLoading = false,
                    textError = outcome.message,
                )

                is TextOutcome.Success -> current.copy(
                    isLoading = false,
                    textContent = outcome.text,
                    textTruncated = outcome.truncated,
                    textBytesRead = outcome.bytes,
                )
            }
        }

        logger.d(
            TAG,
            "text preview fetched for ${LogRedaction.redactId(fileId.value)} from " +
                "${LogRedaction.redactUrl(resource.url)}",
        )
    }

    /**
     * Performs the bounded GET.
     *
     * Runs on [Dispatchers.IO] and inside `use`, so both the socket and the response body are closed
     * on every path — including the exception path, which is the one that leaks a connection when it
     * is forgotten.
     */
    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        encoding: String?,
    ): TextOutcome = try {
        val request = Request.Builder()
            .url(url)
            .apply {
                // Headers are mandatory, not advisory: providers that require `Referer`/`Origin` for
                // media require them for text resources too, and omitting them turns a readable file
                // into a 403 that looks like a missing file.
                headers.forEach { (name, value) -> header(name, value) }
            }
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                TextOutcome.Failure(
                    message = "读取文本失败（HTTP ${response.code}），可下载后查看",
                )
            } else {
                val body = response.body
                if (body == null) {
                    TextOutcome.Failure(message = "该网盘返回了空内容")
                } else {
                    val bytes = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
                    var truncated = false
                    var total = 0L

                    body.byteStream().use { input ->
                        val buffer = ByteArray(READ_CHUNK_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break

                            val remaining = MAX_TEXT_BYTES - total
                            if (read >= remaining) {
                                // Write only what fits, then stop: reading the rest would transfer
                                // bytes the screen can never show.
                                bytes.write(buffer, 0, remaining.toInt())
                                total += remaining
                                truncated = true
                                break
                            }

                            bytes.write(buffer, 0, read)
                            total += read
                        }
                    }

                    TextOutcome.Success(
                        text = bytes.toByteArray().toString(charsetFor(encoding)),
                        truncated = truncated,
                        bytes = total,
                    )
                }
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        // Structured concurrency must keep working: a cancelled screen must not look like an I/O
        // failure to its own state machine.
        throw cancellation
    } catch (io: java.io.IOException) {
        TextOutcome.Failure(message = "读取文本失败：${io.javaClass.simpleName}，可下载后查看")
    } catch (t: Throwable) {
        TextOutcome.Failure(message = "读取文本失败：${t.javaClass.simpleName}")
    }

    /**
     * Resolves the image to display.
     *
     * Precedence is deliberate: the provider-supplied **original** (`preview.resource`) wins over a
     * thumbnail when both exist, because the user opened a preview to *look at the picture* — the
     * settings screen's "prefer original" preference governs the file list, not an explicit preview.
     * The thumbnail is the fallback for providers that only render small derivatives, which is also
     * the whole content of the [PreviewKind.ThumbnailOnly] branch.
     */
    private suspend fun loadThumbnail(
        preview: PreviewResult,
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ) {
        val original = preview.resource
        if (original != null) {
            _state.update { it.copy(isLoading = false, thumbnailUrl = original.url) }
            return
        }

        val ref = when (val kind = preview.kind) {
            is PreviewKind.Image -> kind.thumbnail
            is PreviewKind.ThumbnailOnly -> kind.thumbnail
            else -> null
        }

        when (val result = files.thumbnail(accountId, providerId, fileId, ref)) {
            is CloudResult.Failure -> _state.update {
                // A thumbnail failure is not a preview failure: the kind is still known, so the
                // screen shows its classification and offers download instead of an error page.
                it.copy(isLoading = false, thumbnailUrl = null, textError = null)
            }

            is CloudResult.Success -> _state.update {
                // `resource == null` is the documented "this item has no thumbnail" answer, which is
                // a normal outcome rather than a failure.
                it.copy(isLoading = false, thumbnailUrl = result.data.resource?.url)
            }
        }
    }

    /**
     * The identity of the last [load].
     *
     * Held so [retry] does not need the caller to repeat route arguments, and so a retry after a
     * configuration change targets the same file. No eager work happens in `init`: the screen decides
     * when loading starts, which avoids a wasted request when the user backs out immediately.
     */
    private var lastIdentity: Identity? = null

    private data class Identity(
        val providerId: ProviderId,
        val accountId: AccountId,
        val fileId: FileId,
        val hint: PreviewHint,
    )

    /** The bounded-read outcome, kept private: it is an implementation detail of [fetchText]. */
    private sealed interface TextOutcome {
        data class Success(val text: String, val truncated: Boolean, val bytes: Long) : TextOutcome
        data class Failure(val message: String) : TextOutcome
    }

    companion object {
        private const val TAG = "PreviewViewModel"

        /**
         * 256 KB, matching [PreviewKind.Text.inlineLimitChars]'s default.
         *
         * Chosen as the point where a monospaced viewer stops being useful: 256 KB is roughly 4,000
         * lines, far more than anyone scrolls, while still fitting comfortably in a `String` on a
         * low-end device.
         */
        const val MAX_TEXT_BYTES = 256L * 1024L

        private const val INITIAL_BUFFER_BYTES = 32 * 1024
        private const val READ_CHUNK_BYTES = 16 * 1024

        /** Shown when the provider classified the file but returned no way to read its text. */
        const val NO_TEXT_RESOURCE_MESSAGE = "该网盘未提供文本内容地址，可下载后查看"

        /**
         * Resolves the declared encoding, degrading to UTF-8.
         *
         * A vendor may report an encoding this JVM does not know, and `Charset.forName` throws for an
         * unknown or illegal name. UTF-8 is the safe fallback: it never throws on decode (invalid
         * sequences become replacement characters) and is what every provider actually sends.
         */
        private fun charsetFor(encoding: String?): Charset =
            if (encoding.isNullOrBlank()) {
                Charsets.UTF_8
            } else {
                runCatching { Charset.forName(encoding) }.getOrDefault(Charsets.UTF_8)
            }

        /**
         * Maps a classification failure to what the user reads.
         *
         * Note that most preview failures are *not* errors at all: a vendor that cannot render a
         * format answers with a successful [PreviewKind.Unsupported], which this method never sees.
         * By the time an error reaches here it is a transport, auth or existence problem, so the
         * mapping stays short and the fallback is the error's own message rather than a catch-all.
         */
        internal fun messageFor(error: CloudError): String = when (error) {
            is CloudError.Unsupported -> "该网盘不支持在线预览"

            is CloudError.NotFound, is CloudError.Deleted -> "文件已被删除"

            is CloudError.TokenExpired, is CloudError.NotAuthenticated ->
                "授权已失效，请到账号页重新登录"

            else -> error.userFacingMessage
        }
    }
}

/**
 * Everything the preview screen renders.
 *
 * ### Why there are two error fields
 * [error] means "the app could not work out how to show this file" and replaces the whole screen.
 * [textError] means "we know this is a text file but could not read its body" and is shown *inside*
 * the text branch, where the download action still applies. Collapsing them would force the screen to
 * choose between hiding a valid classification and pretending a failed download succeeded.
 */
data class PreviewUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val errorKind: CloudError? = null,
    val result: PreviewResult? = null,
    /** Resolved URL for the image branches. Signed; never logged or persisted. */
    val thumbnailUrl: String? = null,
    /** Decoded body for the text branch, already bounded by the ViewModel's byte cap. */
    val textContent: String? = null,
    val textTruncated: Boolean = false,
    val textBytesRead: Long = 0L,
    /** A content-level failure inside an otherwise valid classification. */
    val textError: String? = null,
) {
    /** The classification, or `null` until one arrives. */
    val kind: PreviewKind? get() = result?.kind

    val hasError: Boolean get() = error != null

    /** `true` when the user must be offered a download because nothing can be rendered inline. */
    val offersDownload: Boolean
        get() = error != null || kind is PreviewKind.Unsupported || kind is PreviewKind.ThumbnailOnly
}
