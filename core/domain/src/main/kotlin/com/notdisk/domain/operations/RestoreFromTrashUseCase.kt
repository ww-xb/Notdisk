package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.BatchOperationResult
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Restores entries from the recycle bin.
 *
 * ### Why this takes trash ids rather than [com.notdisk.core.model.FileId]
 * Because they are different identifiers, and the difference is load-bearing.
 * [com.notdisk.core.provider.TrashItem.trashId] is the vendor's *trash* handle — 115's `rid` —
 * which is not the file id the entry had while it was alive, and in general is not even derivable
 * from it. A restore request built from file ids is therefore not "slightly wrong": on the vendors
 * that bother to reject it, it fails with an opaque error; on those that do not, it is a no-op that
 * reports success, and the UI shows "已恢复" while the file stays in the bin. Keeping the parameter
 * type `List<String>` makes the correct call the only one that compiles.
 *
 * ### The partial-restore case is the interesting one
 * Restoring a batch can fail per item for reasons that are *not* transient and that the user must
 * see: the original parent folder may be gone (deleted, or itself in the trash), a name collision
 * may exist at the destination, or the entry may be past the vendor's retention window. Each of
 * those needs a different remedy, so [BatchOperationResult.failed] is forwarded intact rather than
 * collapsed into a success/failure. In particular a caller must **not** treat a partial restore as
 * an error and retry the whole batch: the entries that already came back would be restored twice
 * where the vendor tolerates it, and the retry would produce the same per-item failures again.
 *
 * ### Capability
 * Gated on `operations.trash.restore`. A provider with a listable but non-restorable bin exists in
 * practice (read-only archive exports), and on such a provider the honest message is "该网盘不支持恢复"
 * rather than an empty success.
 */
class RestoreFromTrashUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param trashIds the selection, addressed by *trash* id. Order is preserved in the result.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult> {
        validateTrashSelection(trashIds)?.let { return it }
        val selection = dedupeTrashSelection(trashIds)

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.trash.restore,
                capability = CAPABILITY_TRASH_RESTORE,
                message = "该网盘不支持从回收站恢复",
            ).flatMap { provider.operations.restoreFromTrash(accountId, selection) }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_TRASH_RESTORE: String = "operations.trash.restore"
    }
}
