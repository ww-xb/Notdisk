package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Renames one file or folder.
 *
 * ### Why the validation is shared with folder creation
 * [CreateFolderUseCase.validateFolderName] is reused verbatim rather than re-implemented. The two
 * operations accept the same input from the same dialog, so a name that is legal to create but
 * illegal to rename to is a trap: the user creates `a/b` under a vendor that tolerates it, then
 * cannot fix the typo. Sharing the validator makes "any name the app will create, the app can also
 * rename to" a structural property instead of a coincidence.
 *
 * ### Why this is not a batch operation
 * Vendors implement rename as a batch endpoint *because* their UI renames many files at once (115's
 * working endpoint is `POST /files/batch_rename`, taking `files_new_name[<id>]`). The unified
 * contract exposes it per item anyway, for a reason that shows up in the UI: renaming is a
 * single-item interaction with a text field attached to one row, and a per-item result lets the app
 * keep that row's optimistic state scoped to the row that actually failed. A batch signature here
 * would force every caller to build a one-element list and unpack a one-element result, and would
 * make it easy to accidentally send a rename for rows the user never edited.
 *
 * ### The capability gate
 * `operations.rename` is `UNSUPPORTED` for read-only surfaces (a shared folder mounted from another
 * drive, an archive, a build with only browse wired up). Refusing locally means the user sees
 * "该网盘不支持重命名" instead of an opaque vendor error after a round trip — and, more importantly,
 * the row's optimistic UI never enters a "renaming…" state that can only fail.
 *
 * ### Note on the returned item
 * The provider returns the **re-fetched** [FileItem], not the input with a new name patched in.
 * Vendors may adjust the name (de-duplication suffixes, forbidden-character substitution), so the
 * returned value is the only authoritative statement about what the item is now called. Callers
 * must replace the cached row with it rather than assuming their input took effect.
 */
class RenameUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the item.
     * @param accountId the local account id.
     * @param fileId the item to rename.
     * @param newName the desired name, as typed by the user.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        newName: String,
    ): CloudResult<FileItem> {
        CreateFolderUseCase.validateFolderName(newName)?.let { refusal -> return refusal }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.rename,
                capability = CAPABILITY_RENAME,
                message = "该网盘不支持重命名",
            ).flatMap { provider.operations.rename(accountId, fileId, newName) }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_RENAME: String = "operations.rename"
    }
}
