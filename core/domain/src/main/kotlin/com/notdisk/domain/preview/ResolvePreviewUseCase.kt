package com.notdisk.domain.preview

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.PreviewHint
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Classifies a file and, when asked, resolves the access material needed to display it.
 *
 * ### Why the `PreviewHint` parameter is not optional convenience
 * [PreviewHint] is what makes **list rendering free of network calls**. Classifying a file needs its
 * name/extension, and some vendors embed the extension in the opaque file id while others (115:
 * `cid`/`fid`/`pickcode`, no extension) do not. Without a hint, a provider would have to re-fetch
 * file details just to learn the extension — one extra round trip per row, while a grid scrolls.
 *
 * Passing the metadata the caller already holds removes that cost, and the hint is *advisory* by
 * contract: a provider that cannot use it is free to ignore it, and a provider must never fail
 * because a hint was absent. That asymmetry is what allows a caller to pass
 * [PreviewHint.Empty] when it genuinely knows nothing (a deep link carrying only a file id) and
 * still get a correct — merely more expensive — answer.
 *
 * ### The two "no preview" outcomes, and why the caller must branch on them
 * This is the subtlest part of the preview contract, and getting it wrong is user-visible:
 *
 *  - **`CloudError.Unsupported`** means the *provider* has no preview capability at all. The UI
 *    should hide the preview affordance for that provider entirely, once, and never offer it again.
 *  - **`PreviewKind.Unsupported(reason)` inside a successful result** means the provider *can*
 *    preview, but not *this file* — a `.psd`, a `.7z`, a codec the vendor's transcoder rejects.
 *    This is a **normal outcome**: the UI shows "无法预览，改为下载" with the reason, which is a
 *    completely different screen from an error dialog.
 *
 * The model documents the distinction and even names the vendor behaviour that motivates it: 115
 * answers "暂不支持此文件格式预览" with an error code for formats it cannot render, and that must not
 * surface as a failure dialog. This use case therefore never rewrites a success into a failure; it
 * forwards both channels untouched so the caller can honour the distinction.
 *
 * ### Why `resolveAccess` is forwarded rather than always `true`
 * With `resolveAccess = false` the provider classifies from local knowledge only and must not touch
 * the network. That is the mode a file list uses; the preview screen calls again with `true` for the
 * one file the user opened. Exposing the flag here — instead of making callers choose between two
 * use cases — keeps the "cheap classification" and "real resolution" paths provably the same code
 * path in the provider, so they cannot disagree about what kind of file it is.
 *
 * ### Not gated on a capability
 * There is no single `preview` capability to check: the declaration is per *kind*
 * ([com.notdisk.core.model.PreviewCapabilities.image], `.text`, `.pdf`, `.officeDocument`, plus
 * `supportedExtensions`), and which kind applies depends on the file being previewed — which this
 * layer does not know without asking. A gate could only fire when *every* kind is unsupported, and
 * in that case the provider's own `CloudError.Unsupported` is already the accurate answer. The
 * per-kind declaration is left to the UI, which reads it through
 * [com.notdisk.domain.files.GetCapabilitiesUseCase] to decide which viewer to open.
 */
class ResolvePreviewUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id.
     * @param fileId the file to preview.
     * @param hint metadata the caller already has. Pass [PreviewHint.Empty] when nothing is known.
     * @param resolveAccess `false` forbids network access — see the class KDoc.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        hint: PreviewHint = PreviewHint.Empty,
        resolveAccess: Boolean = true,
    ): CloudResult<PreviewResult> =
        registry.withProvider(providerId) { provider ->
            provider.preview.preview(
                accountId = accountId,
                fileId = fileId,
                resolveAccess = resolveAccess,
                hint = hint,
            )
        }
}
