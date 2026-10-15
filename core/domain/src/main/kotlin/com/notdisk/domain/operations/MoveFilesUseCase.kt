package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.BatchOperationResult
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Moves a selection of items into a target folder.
 *
 * ### Why the selection is validated before anything is sent
 * Move is a **bulk mutation whose failure mode is visible as data loss**: if the user selected 40
 * items and one of them cannot be moved (permission, name collision, moderation), the vendor's
 * reaction ranges from "refuse the whole batch" to "apply 39 of them and report one error". The
 * domain layer cannot prevent the partial case — that is the vendor's semantics — but it can and
 * must prevent the cases that are pure local logic, so that a round trip is never spent on a
 * request that could not have succeeded. See [BatchSelection] for the full rationale; in short:
 * an empty selection is refused with
 * [com.notdisk.core.common.error.CloudError.InvalidArgument], and duplicate ids are removed
 * (order-preserving) because a repeat is a UI artefact whose only effect on the wire is a spurious
 * per-item failure — which would make a fully successful batch render as "部分失败" and train users
 * to ignore the one indicator that surfaces real damage.
 *
 * ### Why the destination is not checked against the selection
 * Whether a folder may be moved into its own descendant depends on the vendor's tree semantics and
 * on the item's type, and answering it authoritatively requires the ancestor chain — a `getPath`
 * per item, i.e. exactly the doomed round trips this module exists to avoid. The provider, which
 * already knows the tree, owns that check and reports it through
 * [com.notdisk.core.provider.BatchItemFailure] where the UI can render it per row.
 *
 * ### Retry safety
 * [com.notdisk.core.model.FileOperationCapabilities.idempotentWrites] is `false` for most
 * vendors, and move is the *benign* case where a retry is usually harmless (the file is already at
 * the destination). It is still not free: a retried move can clobber an item the user placed at the
 * destination in between. Callers must therefore reconcile before retrying rather than relying on
 * this use case to be safe to fire twice.
 */
class MoveFilesUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param fileIds the selection, in the order the caller wants outcomes reported.
     * @param targetParentId the destination folder.
     * @return per-item outcomes. A partially applied batch is a *successful* call whose
     *   [BatchOperationResult.failed] list is non-empty — callers must render that, not treat it
     *   as a blanket failure.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> {
        val selection = BatchSelection.of(fileIds)
        selection.refusalOrNull()?.let { return it }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.move,
                capability = CAPABILITY_MOVE,
                message = "该网盘不支持移动",
            ).flatMap {
                provider.operations.move(accountId, selection.fileIds, targetParentId)
            }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_MOVE: String = "operations.move"
    }
}
