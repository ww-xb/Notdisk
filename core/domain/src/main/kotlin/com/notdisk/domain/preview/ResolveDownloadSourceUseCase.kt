package com.notdisk.domain.preview

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.DownloadSource
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Resolves a fresh download location — the "download instead" affordance behind every failed
 * preview.
 *
 * ### Which affordance this powers, and why that matters for the gate
 * This is *not* the transfer path. Starting an actual download is
 * [com.notdisk.core.provider.FileOperationProvider.prepareDownload] followed by `download` with
 * a [com.notdisk.core.model.DownloadRequest], owned by the transfers layer. What this use case
 * is for is the small, immediate, user-facing case:
 *
 *  - a file the provider cannot preview ("无法预览，改为下载");
 *  - a "save a copy" action on a single file;
 *  - a share-sheet "open in another app", where the target app fetches the URL itself.
 *
 * The distinction drives the capability it is gated on. It is checked against
 * [com.notdisk.core.model.DownloadCapabilities.supported] — whether the provider can hand out a
 * downloadable location at all — and **not** against the transfer machinery, because a provider can
 * legitimately support handing a user a link while having no background-transfer implementation in
 * this build. Gating on the wrong one would hide a working feature.
 *
 * ### Why the returned URL is a secret that must not outlive its use
 * [DownloadSource.url] is a short-lived signed link — the model is explicit that it is
 * "a secret with a lifetime measured in minutes" and that it is deliberately absent from
 * [com.notdisk.core.model.TransferTask] so it can never be persisted by accident. This use case
 * makes no attempt to cache it, and callers must not either:
 *
 *  - **never** write it to a database, a saved-state bundle, or a log;
 *  - re-resolve before *each* use rather than holding one for a session —
 *    [DownloadSource.expiresAt] and [DownloadSource.isExpired] exist so that decision can be made
 *    from the vendor's own number instead of a guessed TTL;
 *  - prefer [DownloadSource.refreshable] over retrying blindly: a source that is not refreshable
 *    must be re-resolved by calling this use case again, which is a different (and cheaper) thing
 *    than retrying a failed byte transfer.
 *
 * Unlike the media path there is no separate `RefreshDownloadSourceUseCase`, because re-resolving a
 * download source *is* this call: `prepareDownload` is already the provider's "give me a fresh URL"
 * endpoint, so a second use case would be a rename rather than a distinction. The media path needs
 * [com.notdisk.domain.media.RefreshMediaResourceUseCase] because it must carry a quality hint and
 * classify non-refreshable expiry; neither applies here.
 *
 * ### Not validated beyond provider resolution
 * There is nothing to validate. The file id is opaque and non-blank by construction ([FileId]'s
 * `init` rejects blank values), and whether the file is downloadable is a *per-item* property the
 * caller reads from [com.notdisk.core.model.FileCapabilities.canDownload] on the
 * [com.notdisk.core.model.FileItem] it already holds — a `null` there means "not determined",
 * which the UI must treat as "hide the entry point" rather than "allowed". The provider is
 * responsible for failing with [com.notdisk.core.common.error.CloudError.NotFound] or
 * `PermissionDenied` for an item that turns out not to be downloadable.
 */
class ResolveDownloadSourceUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id — the URL is issued for that account's session.
     * @param fileId the file to download.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<DownloadSource> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.download.supported,
                capability = CAPABILITY_DOWNLOAD,
                message = "该网盘不支持下载",
            ).flatMap { provider.operations.prepareDownload(accountId, fileId) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_DOWNLOAD: String = "operations.download.supported"
    }
}
