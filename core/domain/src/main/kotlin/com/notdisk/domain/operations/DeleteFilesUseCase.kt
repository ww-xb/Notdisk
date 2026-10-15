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
 * Deletes a selection of items, optionally permanently.
 *
 * ### The two-tier capability gate, and why the second tier is not paranoia
 * Deleting has two very different meanings behind one verb, and the app must never guess which one
 * the user is getting:
 *
 *  - `permanent = false` asks the vendor to move items to its recycle bin. This is recoverable and
 *    is what the normal "delete" affordance does. It requires
 *    [com.notdisk.core.model.FileOperationCapabilities.delete].
 *  - `permanent = true` destroys data with no undo. On every real vendor it is a *different*
 *    endpoint with a *different* authorisation requirement: 115 requires the account's **safe key**
 *    (`rb_pass`) for `POST /rb/clean`, and the trash surface declares that through
 *    [com.notdisk.core.model.TrashCapabilities.purge] and
 *    [com.notdisk.core.model.TrashCapabilities.purgeRequiresSecret].
 *
 * So a permanent delete is gated on the *trash purge* capability, not on `delete`. Gating it on
 * `delete` would let a request through that the vendor is guaranteed to refuse, and — worse — on a
 * vendor whose recycle bin is not exposed at all, "permanent delete" via the normal delete endpoint
 * may be exactly what happens. Falling through to a destructive operation because a capability was
 * not consulted is the single worst failure this module can prevent, so the check is explicit and
 * the failure is typed.
 *
 * ### Why the safe key is not a parameter here
 * Because it must never be stored, and a use case parameter is the first step towards one being
 * cached in a ViewModel. Permanent deletion from the *trash* (where a safe key is meaningful) lives
 * in [PurgeFromTrashUseCase], which takes it explicitly per call. This use case deliberately has no
 * safe-key parameter: a caller that wants `permanent = true` on a live file is asking the provider
 * to perform a purge, and the provider will report
 * [com.notdisk.core.common.error.CloudError.AdditionalVerificationRequired] when it needs a
 * secret the caller did not supply. That routes the user through the trash flow, where the secret
 * is collected for a specific, visible action.
 *
 * ### Reconciling a delete that timed out
 * Deletion is not idempotent in the sense that matters here: a retry after a timeout either
 * succeeds again (harmless, the item is gone) or deletes a *different* item that the user recreated
 * at the same path in the meantime. When [com.notdisk.core.common.error.CloudError.Timeout]
 * comes back, callers must re-read the parent folder to see whether the items are still there
 * before deciding, never blindly retry.
 */
class DeleteFilesUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param fileIds the selection, in the order the caller wants outcomes reported.
     * @param permanent `true` destroys the items irrecoverably. Requires the trash-purge
     *   capability; defaults to `false` so an accidental omission is the *safe* direction.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileIds: List<FileId>,
        permanent: Boolean = false,
    ): CloudResult<BatchOperationResult> {
        val selection = BatchSelection.of(fileIds)
        selection.refusalOrNull()?.let { return it }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            val gateResult = if (permanent) {
                // Permanent deletion is a purge, wherever it is invoked from. See the class KDoc.
                gate.require(
                    support = capabilities.operations.trash.purge,
                    capability = CAPABILITY_PURGE,
                    message = "该网盘不支持永久删除",
                )
            } else {
                gate.require(
                    support = capabilities.operations.delete,
                    capability = CAPABILITY_DELETE,
                    message = "该网盘不支持删除",
                )
            }

            gateResult.flatMap {
                provider.operations.delete(accountId, selection.fileIds, permanent)
            }
        }
    }

    companion object {
        /** Stable capability path for recycle-bin deletion. */
        const val CAPABILITY_DELETE: String = "operations.delete"

        /** Stable capability path for irreversible deletion. */
        const val CAPABILITY_PURGE: String = "operations.trash.purge"
    }
}
