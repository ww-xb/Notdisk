package com.notdisk.provider.baidu

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
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
import com.notdisk.provider.baidu.api.BaiduEndpoints
import com.notdisk.provider.baidu.api.BaiduErrorFamily
import com.notdisk.provider.baidu.api.BaiduHttpClient
import com.notdisk.provider.baidu.api.intOf
import com.notdisk.provider.baidu.api.stringOf
import com.notdisk.provider.baidu.auth.BaiduSession
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * Baidu Netdisk's preview surface.
 *
 * ### The three documented access paths, and which one this build uses
 * The reference material describes several preview *pages* (`/disk/docview`, `/disk/pdfview`,
 * `/disk/plugin`) and three API-level ways to obtain content. Choosing between them is not arbitrary:
 *
 * | Access material        | Endpoint                             | Why this build uses it / does not        |
 * |------------------------|--------------------------------------|------------------------------------------|
 * | `thumbs.url1/2/3`      | in the **listing** response (§8.2)   | ✅ zero extra requests, already present   |
 * | `picdocpreview`        | `filemetas?text=1` (§8.5)            | ✅ the documented text/code-reader path   |
 * | `docpreview`           | `/api/categorylist` (§3.3)           | ⚠️ only on that endpoint — see below      |
 * | `dlink`                | `/api/download` (§5.3)               | ❌ needs a server-supplied JS signature   |
 * | `/disk/docview` page   | a separate OAuth application (§8.4)  | ❌ `client_id=9dgBV9…`, requires consent  |
 *
 * ### Why there is no office viewer here
 * §8.4 measured that `/disk/docview` **redirects into a separate OAuth application** and that
 * `/disk/pdfview` is the variant without OAuth. Both are *pages*, not APIs: driving them means
 * embedding a WebView and letting the user's browser session authenticate a third-party app. The
 * shared model has an [OfficeViewer] handoff for exactly that shape, and 115 uses it — but claiming it
 * for Baidu would mean advertising a viewer whose consent flow this build cannot complete.
 *
 * So office documents and PDFs are classified as [PreviewKind.Pdf] / [PreviewKind.OfficeDocument] with
 * **`resource = null`**, which is the honest shape: "this file should be handed to a document viewer,
 * and here is no access material". [PreviewKind.Unsupported] would be wrong — the file *is*
 * previewable, just not by material this provider can produce.
 *
 * ### Classification is local first
 * The brief requires list rendering not to pay a network round trip per row, so [preview] with
 * `resolveAccess = false` classifies purely from the caller's [PreviewHint] and makes **no** request.
 * That matters: a 200-row folder would otherwise issue 200 preview requests.
 *
 * ### Secrets
 * Every URL here (`thumbnail*.baidupcs.com/…?sign=&expires=`, `pcsdata.baidu.com/…&sign=`) is a
 * short-lived bearer resource. None is persisted; each is resolved on demand and logged only through
 * [LogRedaction.redactUrl].
 */
internal class BaiduPreviewProvider(
    private val http: BaiduHttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<BaiduSession>,
    private val logger: CloudLogger,
) : PreviewProvider {

    override suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean,
        hint: PreviewHint,
    ): CloudResult<PreviewResult> {
        val ref = BaiduFileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的百度网盘文件标识: $fileId"),
            )

        val key = CloudFileKey(BaiduProviderId, accountId, fileId)

        // A folder has no previewable content. Answered without a network call.
        if (ref.isFolder) {
            return CloudResult.Success(
                PreviewResult(key = key, kind = PreviewKind.Unsupported("文件夹不支持预览")),
            )
        }

        val extension = hint.normalisedExtension

        if (!resolveAccess) {
            // `classifyLocally` accepts a nullable extension by design: a caller that supplied no
            // hint still gets an honest classification ("thumbnail only, resolution unknown") rather
            // than a failure.
            return CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = classifyLocally(extension),
                    mimeType = guessMimeType(extension),
                ),
            )
        }

        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        // Images and text have a documented *content* URL. Everything else is classification only:
        // the caller renders it through the media player (video/audio) or hands it to a document
        // viewer, and neither needs access material from this provider.
        //
        // `resolvedExtension` binds the non-null form because `extension` is nullable by design (the
        // caller may have supplied no hint at all) and only the hint-bearing branches reach here.
        val resolvedExtension = extension.orEmpty()
        return when (resolvedExtension) {
            in BaiduFileMapper.IMAGE_EXTENSIONS ->
                resolveImagePreview(accountId, fileId, key, hint)

            in BaiduFileMapper.TEXT_EXTENSIONS ->
                resolveTextPreview(accountId, fileId, session, key, resolvedExtension, ref.path)

            else -> CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = classifyLocally(resolvedExtension),
                    mimeType = guessMimeType(resolvedExtension),
                ),
            )
        }
    }

    /**
     * §8.5 — text and code.
     *
     * `GET /api/filemetas?dlink=0&text=1&target=["<path>"]` returns `info[0].picdocpreview`, a
     * `pcsdata.baidu.com` URL that serves the file's text. The reference material measured it and
     * recommends this as the "轻量场景" path that avoids pushing a small text file into the
     * heavyweight document viewer.
     *
     * The reading limit is documented as 4 MiB (1 MiB on IE) and is enforced **server-side**; this
     * method does not duplicate the check, because the caller's hint may not know the size and a
     * second client-side limit would be a number that can drift from the vendor's.
     */
    private suspend fun resolveTextPreview(
        accountId: AccountId,
        fileId: FileId,
        session: BaiduSession,
        key: CloudFileKey,
        extension: String,
        path: String,
    ): CloudResult<PreviewResult> {
        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.FILEMETAS),
            form = mapOf("target" to targetJson(path)),
            query = mapOf("dlink" to "0", "text" to "1"),
            cookies = session.credential.cookieHeader(),
        )

        // The same multi-target semantics as the file-system provider's detail call: `errno 12` means
        // "some entries failed", and the element-level codes below carry the actual reason.
        return when (
            val result = http.execute(
                request,
                tag = "filemetas.text",
                family = BaiduErrorFamily.GENERAL,
                partialFailureCodes = setOf(PARTIAL_FAILURE_CODE),
            )
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val entry = result.data.entries.firstOrNull()
                val elementCode = entry?.intOf("errno")
                if (elementCode != null && elementCode != 0) {
                    // §3.5: an element-level error means *this file* failed, which for a text preview
                    // is a normal outcome (e.g. the file is gone). It becomes a classification rather
                    // than a dialog, because a preview screen already offers "download instead".
                    return CloudResult.Success(
                        PreviewResult(
                            key = key,
                            kind = PreviewKind.Unsupported("无法读取该文本文件"),
                            mimeType = guessMimeType(extension),
                        ),
                    )
                }

                val url = entry?.stringOf("picdocpreview")
                if (url.isNullOrBlank()) {
                    // Text files that `filemetas` declines (a `.md` larger than the limit, for
                    // instance) still classify as text so the UI can offer the download path.
                    return CloudResult.Success(
                        PreviewResult(
                            key = key,
                            kind = PreviewKind.Text(),
                            mimeType = guessMimeType(extension),
                        ),
                    )
                }

                logger.i(TAG, "text preview resolved for ${LogRedaction.redactId(fileId.value)}")
                CloudResult.Success(
                    PreviewResult(
                        key = key,
                        kind = PreviewKind.Text(encoding = "UTF-8"),
                        mimeType = guessMimeType(extension),
                        resource = PreviewResource(url = url, headers = emptyMap()),
                        expiresAt = parseSignedUrlExpiry(url),
                        refreshable = true,
                    ),
                )
            }
        }
    }

    /**
     * §8.2 — images.
     *
     * Deliberately does **not** issue a request. The reference material is explicit that thumbnail URLs
     * are delivered *inside the listing response* (`thumbs.url{1,2,3}`) and that its own client simply
     * rewrites the `size`/`quality` query parameters, which the signing scheme does not cover
     * ("`size`、`quality` 不在签名内，可自由调整").
     *
     * So the access material for an image is re-derived from the descriptor the caller already holds,
     * which means opening an image costs **zero** requests. When no descriptor is available the result
     * is a successful classification with `resource = null` — "show it, and resolve the bytes through
     * `thumbnail()`" — rather than a failure.
     */
    private fun resolveImagePreview(
        accountId: AccountId,
        fileId: FileId,
        key: CloudFileKey,
        hint: PreviewHint,
    ): CloudResult<PreviewResult> {
        // No request is made: §8.2's signed URL arrives inside a *listing* response, so opening an
        // image costs zero requests when the caller already holds a `ThumbnailRef` (which every image
        // row in a listing does). The caller has already resolved the session, so a logged-out account
        // cannot reach this point.
        logger.d(TAG, "image preview classified locally for ${LogRedaction.redactId(fileId.value)}")
        return CloudResult.Success(
            PreviewResult(
                key = key,
                kind = PreviewKind.Image(
                    thumbnail = ThumbnailRef(sizeHint = DEFAULT_THUMBNAIL_SIZE),
                    // This provider cannot produce the untouched original without a `dlink`, which needs
                    // a server-supplied JavaScript signature. Claiming `true` would make the UI offer a
                    // full-resolution view that always fails.
                    originalAvailable = false,
                ),
                mimeType = guessMimeType(hint.normalisedExtension),
                // No inline URL: §8.2's signed URL is a short-lived secret, and `thumbnail()` is the
                // single owner that resolves it.
                resource = null,
                expiresAt = null,
                refreshable = true,
            ),
        )
    }

    /**
     * §8.2 — resolve a displayable thumbnail URL.
     *
     * ### Why this returns `null` rather than fetching a URL
     * The signed URL lives in the **listing** response, and this provider's [FileItem] deliberately does
     * not carry it (a signed URL is a credential, and `FileItem`s are cached — see
     * `docs/security.md`). Re-resolving one without a listing would need
     * `pcsdata/rest/2.0/pcs/thumbnail?method=generate`, which §8.2 documents as the *fallback* "when the
     * response has no `thumbs`" and whose response shape the material does not record.
     *
     * Rather than issue a request whose answer shape is unknown, this returns a successful
     * `resource = null`, which the shared contract documents as the normal "no thumbnail available"
     * answer. The UI then falls back to a type icon — honest, and it costs nothing.
     */
    override suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: ThumbnailRef?,
    ): CloudResult<PreviewResourceOrNull> {
        when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> Unit
        }
        val key = CloudFileKey(BaiduProviderId, accountId, fileId)
        return CloudResult.Success(PreviewResourceOrNull(key = key, resource = null))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Local, no-network classification.
     *
     * `null` extension means "the caller gave no hint", which the shared contract documents as
     * reducing what can be determined locally but never causing a failure. It classifies as
     * [PreviewKind.ThumbnailOnly] — "show what we have, and let a real resolution decide" — rather
     * than as `Unsupported`, which would claim knowledge this method does not have.
     */
    private fun classifyLocally(extension: String?): PreviewKind = when (extension) {
        null -> PreviewKind.ThumbnailOnly(thumbnail = null)
        in BaiduFileMapper.IMAGE_EXTENSIONS -> PreviewKind.Image()
        in BaiduFileMapper.STREAMABLE_EXTENSIONS -> PreviewKind.Video
        in BaiduFileMapper.AUDIO_EXTENSIONS -> PreviewKind.Audio
        in BaiduFileMapper.TEXT_EXTENSIONS -> PreviewKind.Text()
        "pdf" -> PreviewKind.Pdf
        // §8.1's registry routes `doc/docx/ppt/pptx/xls/xlsx/pdf` to `pdf-plugin` (the platform's own
        // reader). Classified as an office document so the UI routes it to a viewer, with no viewer
        // material attached — see the class KDoc for why no [OfficeViewer] is fabricated.
        in BaiduFileMapper.DOCUMENT_EXTENSIONS -> PreviewKind.OfficeDocument(
            viewer = OfficeViewer(url = "", token = null),
        )
        in BaiduFileMapper.ARCHIVE_EXTENSIONS ->
            // §8.1 routes archives to `unzip-plugin`, i.e. a *listing* UI rather than a renderer, and
            // §8.6's `/api/zipfile/list` needs a password parameter whose semantics are only partly
            // documented. So the honest classification is "no inline preview".
            PreviewKind.Unsupported("压缩包需要先解压或使用解压预览，本版本未实现")

        else -> PreviewKind.Unsupported("该格式没有在线预览")
    }

    /** §3.5: `target` is a JSON **array string**. Escaped because a path is arbitrary user data. */
    private fun targetJson(path: String): String = buildString {
        append("[")
        append('"')
        path.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
        append('"')
        append("]")
    }

    /**
     * Recovers the expiry from a signed URL's `expires` parameter (§8.2).
     *
     * The vendor writes a **duration** (`8h`, `24h`) rather than a timestamp, so a lifetime in seconds
     * is converted into an absolute instant relative to now. Anything unrecognised yields `null`
     * rather than an invented lifetime: an invented expiry would either discard a still-valid URL or
     * keep using a dead one.
     */
    private fun parseSignedUrlExpiry(url: String): Long? {
        val raw = runCatching {
            url.substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("expires=") }
                ?.removePrefix("expires=")
        }.getOrNull() ?: return null

        val seconds = when {
            raw.endsWith("h") -> raw.dropLast(1).toLongOrNull()?.times(3600)
            raw.endsWith("m") -> raw.dropLast(1).toLongOrNull()?.times(60)
            raw.endsWith("d") -> raw.dropLast(1).toLongOrNull()?.times(86_400)
            else -> raw.toLongOrNull()
        } ?: return null

        return System.currentTimeMillis() + seconds * 1_000L
    }

    private companion object {
        const val TAG = "BaiduPreview"

        /** §8.2's `url3` variant is `c850_u580`, the largest pre-set the listing carries. */
        const val DEFAULT_THUMBNAIL_SIZE = "850"

        /** §3.5 — `/api/filemetas`'s outer "partial failure" code. See the file-system provider. */
        const val PARTIAL_FAILURE_CODE = 12
    }
}
