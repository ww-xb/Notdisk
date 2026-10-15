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
 * Permanently deletes entries from the recycle bin. **There is no undo.**
 *
 * ### Why the safe key is a per-call parameter and never stored
 * 115 requires the account's **safe key** (`rb_pass`) for `POST /rb/clean`, and
 * [com.notdisk.core.model.TrashCapabilities.purgeRequiresSecret] declares that. The parameter
 * exists on this call for exactly one reason: a secret the user types for a specific, visible,
 * irreversible action should live for the duration of that action and no longer. Making it a
 * field on a use case instance, a parameter object a caller might retain, or a value in a
 * ViewModel would create a place for it to be cached, logged, or written to a crash report.
 * Passing it through a `suspend fun` argument makes its lifetime the call's lifetime, which is the
 * shortest lifetime the type system can express here.
 *
 * The domain layer does **not** persist it, does not read it from storage, and does not offer an
 * overload without it. Callers that need one must obtain it from the user each time — which is also
 * the honest UX, because purging a recycle bin is not something to make frictionless.
 *
 * ### Why `safeKey` is passed through even when the capability does not require it
 * A provider whose `purgeRequiresSecret` is `false` still receives `safeKey` (possibly `null`) and
 * is expected to ignore it. Stripping it here would mean the domain layer deciding which providers
 * want a secret — a vendor-shaped decision this module exists to avoid. Deciding *whether* to ask
 * the user is the UI's job, and it makes that decision from
 * [com.notdisk.core.model.TrashCapabilities.purgeRequiresSecret] read via
 * [com.notdisk.domain.files.GetCapabilitiesUseCase]. A user who has set a recycle-bin password
 * sees a prompt; one who has not is not asked for a secret they do not have.
 *
 * ### Gating and refusal
 * Gated on `operations.trash.purge`. When the capability is `UNSUPPORTED` the refusal is issued
 * **before** the network, so a mis-tapped destructive button cannot reach a vendor. That ordering is
 * the entire point: a doomed *read* wastes a round trip, whereas a doomed *purge* either does
 * nothing (best case) or is interpreted by a vendor as its own irreversible operation.
 */
class PurgeFromTrashUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param trashIds the selection, addressed by *trash* id.
     * @param safeKey the account's recycle-bin secret, when the user has one. `null` means "not
     *   provided"; the provider reports
     *   [com.notdisk.core.common.error.CloudError.AdditionalVerificationRequired] if it needed
     *   one. Never logged, never persisted.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        trashIds: List<String>,
        safeKey: String? = null,
    ): CloudResult<BatchOperationResult> {
        validateTrashSelection(trashIds)?.let { return it }
        val selection = dedupeTrashSelection(trashIds)

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.trash.purge,
                capability = CAPABILITY_TRASH_PURGE,
                message = "该网盘不支持永久删除回收站内容",
            ).flatMap { provider.operations.purgeFromTrash(accountId, selection, safeKey) }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_TRASH_PURGE: String = "operations.trash.purge"
    }
}
