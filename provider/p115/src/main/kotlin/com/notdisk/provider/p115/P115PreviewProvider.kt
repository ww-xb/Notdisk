package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.OfficeViewer
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResource
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.PreviewHint
import com.notdisk.core.provider.PreviewProvider
import com.notdisk.core.provider.PreviewResourceOrNull
import com.notdisk.provider.p115.api.P115Endpoints
import com.notdisk.provider.p115.api.P115Json
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.asLongOrNull
import com.notdisk.provider.p115.api.asNonBlankStringOrNull
import com.notdisk.provider.p115.api.intOf
import com.notdisk.provider.p115.api.longOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import com.notdisk.provider.p115.auth.P115Session
import java.util.Locale

/**
 * 115's preview surface.
 *
 * ### The two documented preview paths (§11)
 *
 * 1. **`POST /files/preview`** — the unified entry point. For documents it hands off to WPS cloud
 *    documents and returns `{ url, token, expire_at, refresh_before }`. §11.1 measured a rejection
 *    for an unsupported format: `{"state":false,"code":4011,"msg":"暂不支持此文件格式预览"}`.
 *    That rejection is a **normal outcome**, not a failure: the file simply has no online preview.
 *    It is translated into [PreviewKind.Unsupported] so the UI offers a download instead of an
 *    error dialog.
 *
 * 2. **`GET /files/image`** — for images it returns a `thumb.115.com` URL with several sizes plus a
 *    signed `source_url` for the original (§11.2).
 *
 * ### Classification is local first
 * The brief requires list rendering not to pay a network round trip per row, so
 * [preview] with `resolveAccess = false` classifies purely from the file's name and extension and
 * makes **no** request. That matters: a 200-row folder would otherwise issue 200 preview requests.
 *
 * ### Secrets
 * Every URL here (`thumb.115.com/...?s=&t=`, `source_url`, the WPS `url` + `token`) is a short-lived
 * bearer resource. None is persisted; each is resolved on demand and logged only through
 * [LogRedaction.redactUrl].
 */
internal class P115PreviewProvider(
    private val http: P115HttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<P115Session>,
    private val pickcodes: P115PickcodeResolver,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : PreviewProvider {

    override suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean,
        hint: PreviewHint,
    ): CloudResult<PreviewResult> {
        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )

        val key = CloudFileKey(P115ProviderId, accountId, fileId)

        // A folder has no previewable content. This is answered without a network call.
        if (ref.isFolder) {
            return CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = PreviewKind.Unsupported("文件夹不支持预览"),
                ),
            )
        }

        // Classification is driven by the caller's hint. 115's file id carries only
        // cid/fid/pickcode, so without a hint there is nothing to classify on locally — which is
        // exactly why the interface passes one.
        val extension = hint.normalisedExtension

        if (!resolveAccess) {
            return CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = classifyByExtension(extension),
                    mimeType = guessMimeType(extension),
                ),
            )
        }

        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        // `extension` is nullable ("the caller gave no hint"), so the branches that need a name
        // bind a non-null local rather than forcing with `!!` at each call site.
        val resolvedExtension = extension.orEmpty()
        return when {
            resolvedExtension in IMAGE_EXTENSIONS ->
                resolveImagePreview(accountId, fileId, session, key, resolvedExtension)

            resolvedExtension in WPS_EXTENSIONS ->
                resolveOfficePreview(accountId, fileId, session, key, resolvedExtension)

            else -> resolveGenericPreview(accountId, fileId, session, key, resolvedExtension)
        }
    }

    /**
     * §11.2: `GET /files/image?pickcode=<pc>&file_id=<fid>`.
     *
     * The response exposes several sizes (`_100`, `_480`, `_800`, `_1440`, `_0`). The *original*
     * (`_0`) is preferred for the image preview screen, and the 1440 variant for the thumbnail.
     */
    private suspend fun resolveImagePreview(
        accountId: AccountId,
        fileId: FileId,
        session: P115Session,
        key: CloudFileKey,
        extension: String,
    ): CloudResult<PreviewResult> {
        val ref = P115FileIdCodec.decode(fileId)!!
        val pickcode = when (val resolved = pickcodes.resolve(accountId, fileId, session)) {
            is CloudResult.Failure -> return CloudResult.Failure(resolved.error)
            is CloudResult.Success -> resolved.data
        }

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_IMAGE),
            query = buildMap {
                put("pickcode", pickcode)
                ref.fid?.let { put("file_id", it) }
            },
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.image")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val data = result.data.dataObject
                    ?: return CloudResult.Failure(
                        CloudError.ParseError(message = "115 图片预览接口未返回 data"),
                    )

                // §11.2: `url` (1440) and `origin_url` (_0) are the two useful entries; `all_url`
                // lists the rest. `origin_url` is preferred for the full-screen view.
                val originalUrl = data.stringOf("origin_url")
                val displayUrl = originalUrl ?: data.stringOf("url", "source_url")
                    ?: return CloudResult.Failure(
                        CloudError.Unsupported(
                            message = "115 未返回图片地址",
                            capability = "preview.image",
                        ),
                    )

                val thumbUrl = data.stringOf("url")
                val expiresAt = parseSignedUrlExpiry(displayUrl)

                logger.i(
                    TAG,
                    "image preview resolved for ${LogRedaction.redactId(fileId.value)}: " +
                        "origin=${originalUrl != null}",
                )

                CloudResult.Success(
                    PreviewResult(
                        key = key,
                        kind = PreviewKind.Image(
                            // The thumbnail is carried as a *descriptor*, never as the signed URL.
                            thumbnail = data.stringOf("file_sha1")
                                ?.let { ThumbnailRef(sizeHint = "1440", contentHash = it.lowercase(Locale.ROOT)) },
                            originalAvailable = originalUrl != null,
                        ),
                        mimeType = guessMimeType(extension),
                        resource = PreviewResource(
                            url = displayUrl,
                            headers = CDN_HEADERS,
                        ),
                        expiresAt = expiresAt,
                        refreshable = true,
                    ),
                ).also {
                    // The thumbnail URL is deliberately NOT returned here: it is resolved through
                    // `thumbnail()` so each signed URL has exactly one owner and one refresh path.
                    Unit
                }
            }
        }
    }

    /**
     * §11.1: `POST /files/preview` with `pickcode`.
     *
     * The WPS handoff is modelled as [PreviewKind.OfficeDocument] carrying the `url` + `token`
     * pair, because that is literally what the vendor's viewer requires: the manual notes the front
     * end loads `web-office-sdk-solution-*.umd.js` and opens the document with both values. Putting
     * them in a generic "resource" field would hide the fact that a token accompanies the URL.
     */
    private suspend fun resolveOfficePreview(
        accountId: AccountId,
        fileId: FileId,
        session: P115Session,
        key: CloudFileKey,
        extension: String,
    ): CloudResult<PreviewResult> {
        val pickcode = when (val resolved = pickcodes.resolve(accountId, fileId, session)) {
            is CloudResult.Failure -> return CloudResult.Failure(resolved.error)
            is CloudResult.Success -> resolved.data
        }

        val request = http.postForm(
            url = http.resolve(P115Endpoints.FILES_PREVIEW),
            form = mapOf("pickcode" to pickcode, "share_id" to ""),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.preview")) {
            is CloudResult.Failure -> {
                // §11.1's measured rejection is `code 4011`. The error table maps it to
                // `CloudError.Unsupported`, and here it becomes the *successful* classification
                // "this file has no online preview" — which is a different statement from "the
                // provider cannot preview", and the UI treats them differently.
                if (result.error is CloudError.Unsupported) {
                    CloudResult.Success(
                        PreviewResult(
                            key = key,
                            kind = PreviewKind.Unsupported(
                                "115 暂不支持此格式的在线预览，可下载后使用本地应用打开",
                            ),
                            mimeType = guessMimeType(extension),
                        ),
                    )
                } else {
                    CloudResult.Failure(result.error)
                }
            }

            is CloudResult.Success -> {
                // §11.1: the payload is under `data`.
                val data = result.data.dataObject
                    ?: return CloudResult.Failure(
                        CloudError.ParseError(message = "115 预览接口未返回 data"),
                    )

                val viewerUrl = data.stringOf("url")
                val token = data.stringOf("token")

                if (viewerUrl.isNullOrBlank()) {
                    return CloudResult.Success(
                        PreviewResult(
                            key = key,
                            kind = PreviewKind.Unsupported("115 未返回在线预览地址"),
                            mimeType = guessMimeType(extension),
                        ),
                    )
                }

                val expiresAt = data.longOf("expire_at")
                    ?.let { P115FileMapper.epochSecondsToMillis(it) }
                // §11.1: `refresh_before` is the vendor's own suggested refresh lead time.
                val refreshBefore = data.longOf("refresh_before")

                logger.i(
                    TAG,
                    "office preview resolved for ${LogRedaction.redactId(fileId.value)}, " +
                        "canEdit=${data.intOf("can_edit") == 1}",
                )

                CloudResult.Success(
                    PreviewResult(
                        key = key,
                        kind = PreviewKind.OfficeDocument(
                            viewer = OfficeViewer(
                                url = viewerUrl,
                                token = token,
                                expiresAt = expiresAt,
                                refreshBeforeSeconds = refreshBefore,
                                canEdit = data.intOf("can_edit") == 1,
                            ),
                        ),
                        mimeType = guessMimeType(extension),
                        // The viewer's own URL+token is the access material, so no separate
                        // `resource` is returned — that would duplicate the same secret in two
                        // places with two different lifetimes.
                        resource = null,
                        expiresAt = expiresAt,
                        refreshable = true,
                    ),
                )
            }
        }
    }

    /**
     * Text, PDF and everything else.
     *
     * §11.3 records that `.txt` also goes through `files/preview` (returning a WPS link), but notes
     * that a "轻量场景" can read the content through the download link instead. That is what happens
     * here for text: a plain [PreviewKind.Text] whose bytes come from the download endpoint, which
     * avoids pushing a small text file into a heavyweight office viewer. PDF goes through the same
     * WPS path because Android's own PdfRenderer needs a local file.
     */
    private suspend fun resolveGenericPreview(
        accountId: AccountId,
        fileId: FileId,
        session: P115Session,
        key: CloudFileKey,
        extension: String,
    ): CloudResult<PreviewResult> {
        // PDF and office formats share the WPS handoff.
        if (extension == "pdf" || extension in WPS_EXTENSIONS) {
            return resolveOfficePreview(accountId, fileId, session, key, extension)
        }

        if (extension in TEXT_EXTENSIONS) {
            // Text is read through the download endpoint so the content can be rendered inline.
            val operationRequest = http.get(
                url = http.resolve(P115Endpoints.FILES_DOWNLOAD),
                query = buildMap {
                    pickcodes.resolve(accountId, fileId, session)
                        .let { if (it is CloudResult.Success) put("pickcode", it.data) }
                    P115FileIdCodec.decode(fileId)?.fid?.let { put("file_id", it) }
                },
                cookies = session.credential.cookieHeader(),
            )

            return when (val result = http.execute(operationRequest, tag = "files.download.text")) {
                is CloudResult.Failure -> CloudResult.Failure(result.error)
                is CloudResult.Success -> {
                    val payload = result.data.dataObject ?: result.data.root
                    val url = payload.stringOf("file_url", "url")
                    if (url == null) {
                        CloudResult.Success(
                            PreviewResult(
                                key = key,
                                kind = PreviewKind.Unsupported("无法获取文本内容地址"),
                                mimeType = guessMimeType(extension),
                            ),
                        )
                    } else {
                        CloudResult.Success(
                            PreviewResult(
                                key = key,
                                kind = PreviewKind.Text(encoding = "UTF-8"),
                                mimeType = guessMimeType(extension),
                                resource = PreviewResource(url = url, headers = CDN_HEADERS),
                                expiresAt = parseSignedUrlExpiry(url),
                                refreshable = true,
                            ),
                        )
                    }
                }
            }
        }

        // Media is handled by the player, not the preview surface; the classification still has to
        // be truthful so the UI routes it correctly.
        if (extension in STREAMABLE_EXTENSIONS) {
            return CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = PreviewKind.Video,
                    mimeType = guessMimeType(extension),
                ),
            )
        }
        if (extension in AUDIO_EXTENSIONS) {
            return CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = PreviewKind.Audio,
                    mimeType = guessMimeType(extension),
                ),
            )
        }

        // Everything else: try the unified preview endpoint, which will answer with `4011` for
        // formats it cannot render. That answer becomes a normal `Unsupported` classification.
        return resolveOfficePreview(accountId, fileId, session, key, extension)
    }

    /**
     * §11.2: resolve a thumbnail URL for an image.
     *
     * "This item has no thumbnail" is expressed as a successful result with a `null` resource —
     * never as an error — because a folder full of archives would otherwise render as a wall of
     * failures.
     */
    override suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: ThumbnailRef?,
    ): CloudResult<PreviewResourceOrNull> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }
        val key = CloudFileKey(P115ProviderId, accountId, fileId)
        val vendorRef = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Success(PreviewResourceOrNull(key, null))

        // §11.2: only images have thumbnails. The caller's ThumbnailRef only appears on images in
        // practice, but the id itself cannot prove it, so the check is expressed through the
        // ref's presence rather than a name the provider does not have.
        val extension = if (ref?.contentHash != null) "jpg" else null
        if (extension !in IMAGE_EXTENSIONS) {
            return CloudResult.Success(PreviewResourceOrNull(key, null))
        }

        val pickcode = pickcodes.resolve(accountId, fileId, session).getOrNull()
        val query = buildMap {
            pickcode?.let { put("pickcode", it) }
            vendorRef.fid?.let { put("file_id", it) }
        }
        if (query.isEmpty()) {
            return CloudResult.Success(PreviewResourceOrNull(key, null))
        }

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_IMAGE),
            query = query,
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.image.thumb")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val data = result.data.dataObject
                // §11.2: `all_url` lists the available sizes; pick the requested one, else `url`.
                val requestedSize = ref?.sizeHint ?: DEFAULT_THUMBNAIL_SIZE
                val sized = data?.arrayOf("all_url")
                    ?.mapNotNull { it.asNonBlankStringOrNull() }
                    ?.firstOrNull { it.contains("_$requestedSize") }

                val url = sized ?: data?.stringOf("url")
                CloudResult.Success(
                    PreviewResourceOrNull(
                        key = key,
                        resource = url?.let { PreviewResource(url = it, headers = CDN_HEADERS) },
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Recovers the extension from the [FileId] when it is embedded, else `null`.
     *
     * The codec does not store the name (an id must not carry mutable metadata), so classification
     * inside this provider relies on the caller having supplied a `ThumbnailRef`/extension through
     * the file model. Where it is unavailable the result is `null`, which
     * [classifyByExtension] maps to "ask the server", never to a guess.
     */
    /** Local, no-network classification. `null` extension means "let the server decide". */
    private fun classifyByExtension(extension: String?): PreviewKind = when (extension) {
        null -> PreviewKind.ThumbnailOnly(thumbnail = null)
        in IMAGE_EXTENSIONS -> PreviewKind.Image()
        in STREAMABLE_EXTENSIONS -> PreviewKind.Video
        in AUDIO_EXTENSIONS -> PreviewKind.Audio
        "pdf" -> PreviewKind.Pdf
        in TEXT_EXTENSIONS -> PreviewKind.Text()
        in WPS_EXTENSIONS -> PreviewKind.ThumbnailOnly(thumbnail = null)
        else -> PreviewKind.Unsupported("该格式需要在线预览服务，正在解析")
    }

    /**
     * §7.1/§11.2: the `t` parameter on a signed CDN URL is its expiry (Unix seconds).
     * Returns `null` when absent instead of inventing a lifetime.
     */
    private fun parseSignedUrlExpiry(url: String?): Long? =
        url?.let { runCatching { it.substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("t=") }
                ?.removePrefix("t=")
                ?.toLongOrNull() }.getOrNull() }
            ?.let { P115FileMapper.epochSecondsToMillis(it) }

    private companion object {
        const val TAG = "P115Preview"

        /** §11.2: `url` defaults to the 1440px variant. */
        const val DEFAULT_THUMBNAIL_SIZE = "1440"

        /** §1.4 / §10.2: the CDN expects browser-like attribution headers. */
        val CDN_HEADERS = mapOf(
            "Referer" to "https://115.com/",
            "Origin" to "https://115.com",
            "User-Agent" to P115HttpClient.DEFAULT_USER_AGENT,
        )

        val IMAGE_EXTENSIONS = P115FileMapper.IMAGE_EXTENSIONS

        val STREAMABLE_EXTENSIONS = P115FileMapper.STREAMABLE_EXTENSIONS

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape",
        )

        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "log", "json", "xml", "csv", "ini", "conf", "yml", "yaml", "kt", "java",
        )

        /** §11.1: documents are handed to WPS cloud documents. */
        val WPS_EXTENSIONS = setOf(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "wps", "et", "dps", "txt", "pdf", "csv",
        )
    }
}
