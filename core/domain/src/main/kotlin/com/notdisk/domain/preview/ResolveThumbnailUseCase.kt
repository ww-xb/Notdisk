package com.notdisk.domain.preview

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.PreviewResourceOrNull
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Resolves a displayable thumbnail URL for one item.
 *
 * ### Why the payload distinguishes "no thumbnail" from "failed"
 * [PreviewResourceOrNull.resource] is nullable *inside a successful result*, and that shape is
 * deliberate rather than sloppy modelling. Three outcomes are all normal and must be told apart:
 *
 *  1. **success with a URL** — render it;
 *  2. **success with `null`** — the provider can make thumbnails in general but has none for *this*
 *     item. This is the common case for exactly the items a file list is full of: folders,
 *     archives, and any format the vendor's thumbnailer does not handle. The UI draws a type icon
 *     and moves on, with **no error state anywhere**;
 *  3. **`CloudError.Unsupported`** — the provider has no thumbnail capability at all. The UI should
 *     stop asking for this provider, once, rather than issuing one doomed request per row.
 *
 * Collapsing (2) into `null`-as-failure or into an error is the single most common way a file list
 * ends up littered with broken-image placeholders for every folder in it. This use case therefore
 * does not touch the payload: [PreviewResourceOrNull] is forwarded exactly as the provider produced
 * it, `null` resource and all.
 *
 * ### Why the capability gate fires here but not in [ResolvePreviewUseCase]
 * Because `thumbnail` *is* a single, precise declaration
 * ([com.notdisk.core.model.PreviewCapabilities.thumbnail]) that partitions the input exactly —
 * unlike preview, which is split across per-kind flags whose applicability depends on the file. A
 * provider reporting [com.notdisk.core.model.CapabilitySupport.UNSUPPORTED] here will never
 * produce a usable thumbnail for any file, so the gate can refuse cheaply and accurately, and
 * outcome (3) above becomes a *local* decision that costs no round trip. A grid that scrolls
 * through hundreds of rows benefits directly: with an unsupported provider, the app issues zero
 * thumbnail requests instead of hundreds.
 *
 * ### Why the [ThumbnailRef] is passed back rather than recovered
 * [com.notdisk.core.model.FileItem.thumbnail] carries a ref — not a URL — precisely because
 * thumbnail URLs are short-lived and signed, and caching one in a `FileItem` that a ViewModel holds
 * would both break and leak a credential-bearing URL into persistence. The ref carries the stable
 * facts the provider groups thumbnails by (`sizeHint`, `contentHash`), so passing it back lets the
 * provider serve a cached rendition instead of re-rendering. Callers that have a [FileItem] should
 * always forward its ref; `null` is for callers that only hold a file id, and costs the provider a
 * lookup it would otherwise skip.
 */
class ResolveThumbnailUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id.
     * @param fileId the item whose thumbnail is wanted.
     * @param ref the descriptor from [com.notdisk.core.model.FileItem.thumbnail] when the caller
     *   has one, otherwise `null`.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        ref: ThumbnailRef? = null,
    ): CloudResult<PreviewResourceOrNull> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.preview.thumbnail,
                capability = CAPABILITY_THUMBNAIL,
                message = "该网盘不支持缩略图",
            ).flatMap { provider.preview.thumbnail(accountId, fileId, ref) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_THUMBNAIL: String = "preview.thumbnail"
    }
}
