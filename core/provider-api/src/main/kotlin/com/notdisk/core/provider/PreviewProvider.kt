package com.notdisk.core.provider

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ThumbnailRef

/**
 * The file metadata a caller already holds when asking for a preview.
 *
 * ### Why this type exists
 * Classification needs the file's name/extension. Some vendors embed the extension in the opaque
 * file id; 115 does not (its id carries `cid`/`fid`/`pickcode` only). Without a hint, a provider
 * would have to re-fetch the file's detail just to learn its extension — one extra round trip per
 * row while a list is rendering.
 *
 * Passing the hint the caller already has removes that cost and makes the "no network call when
 * `resolveAccess = false`" guarantee achievable for every provider, not just the ones whose ids are
 * self-describing.
 *
 * The hint is advisory: a provider that cannot use it (or that has a better source) is free to
 * ignore it. A provider must never *fail* because a hint was absent.
 */
data class PreviewHint(
    val name: String? = null,
    val extension: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
) {
    /** Lower-cased extension without a leading dot, derived from [name] when not given directly. */
    val normalisedExtension: String?
        get() = (extension ?: name?.substringAfterLast('.', missingDelimiterValue = ""))
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != name && it.length <= 10 }

    companion object {
        val Empty = PreviewHint()
    }
}

/**
 * Decides *how* a file should be shown, and resolves the access material for it.
 *
 * ### Contract
 *  - Returns `CloudError.Unsupported` only when the provider has **no** preview capability at all.
 *  - Returns a successful [PreviewResult] whose [PreviewResult.kind] is
 *    [PreviewKind.Unsupported] when preview exists in general but not for this file. The UI shows
 *    "cannot preview, download instead" in that case, which is a normal outcome rather than an
 *    error. Vendors differ here in a way users notice: 115 answers "暂不支持此文件格式预览" with an
 *    error code for formats it cannot render, and that must not surface as a failure dialog.
 *  - Never embeds a vendor preview URL in [PreviewKind]; access material goes in
 *    [PreviewResult.resource], and office viewers go in [PreviewKind.OfficeDocument] because those
 *    genuinely need a URL *and* a token together.
 *  - With `resolveAccess = false` the call must not touch the network.
 */
interface PreviewProvider {

    /**
     * Classifies a file and (when asked) resolves its access material.
     *
     * @param resolveAccess when `false`, the provider must classify using only [hint] and its own
     *   local knowledge, and must not make a network call. The UI uses this for list rendering; the
     *   preview screen calls again with `true`.
     * @param hint metadata the caller already has. Absent members simply reduce how much can be
     *   determined locally; they never cause a failure.
     */
    suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean = true,
        hint: PreviewHint = PreviewHint.Empty,
    ): CloudResult<PreviewResult>

    /**
     * Resolves a displayable thumbnail URL.
     *
     * Returns a successful payload with `resource = null` when the provider has no thumbnail for this
     * item (a normal outcome, e.g. a folder or an archive), and `CloudError.Unsupported` when it has
     * no thumbnail capability at all.
     */
    suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: ThumbnailRef? = null,
    ): CloudResult<PreviewResourceOrNull>
}

/**
 * Wrapper so "no thumbnail available" is distinguishable from a failure, without using `null`
 * as an error signal.
 */
data class PreviewResourceOrNull(
    val key: CloudFileKey,
    val resource: com.notdisk.core.model.PreviewResource?,
)
