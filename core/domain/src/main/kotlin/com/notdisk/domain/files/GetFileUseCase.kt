package com.notdisk.domain.files

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Reads the authoritative metadata for one file or folder.
 *
 * ### Why a "refresh one row" call deserves its own use case
 * The list screen renders from whatever the page endpoint returned, and that snapshot goes stale
 * the moment the user does anything: a rename from another device, a background sync, a share that
 * a colleague just revoked. Every affordance that *acts* on a row (rename, move, share) needs the
 * current state of that row first, because the interesting fields are the per-item
 * [com.notdisk.core.model.FileCapabilities] — "may I rename this?" is a property of the item,
 * not of the provider, and a shared/read-only item in an otherwise writable folder is exactly the
 * case where the list snapshot is wrong.
 *
 * Routing that through a use case keeps the `(providerId, accountId, fileId)` triple explicit.
 * [FileId] is deliberately **not** globally unique — it is opaque and provider-scoped — so a bare
 * file id that reaches the wrong provider either fails confusingly or, worse, addresses a
 * different file. Requiring all three at one call site makes that class of bug impossible.
 *
 * ### When to call it
 * On selection (opening a details sheet, starting a rename, entering the preview screen) — not
 * while scrolling. The list endpoint already returns everything needed to *render* a row; this is
 * for the moment the app is about to *change* something.
 */
class GetFileUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration that issued [fileId].
     * @param accountId the local account id — never ambient state, because two accounts of the same
     *   provider can hold files whose opaque ids collide.
     * @param fileId the opaque, provider-scoped handle.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<FileItem> =
        registry.withProvider(providerId) { provider -> provider.files.getFile(accountId, fileId) }
}
