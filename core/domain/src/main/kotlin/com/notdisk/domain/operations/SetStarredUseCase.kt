package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Stars or unstars a selection of items.
 *
 * ### Why this is gated on `delete`'s capability, which looks wrong and is not
 * [com.notdisk.core.model.FileOperationCapabilities] has no `star` member, and inventing one
 * would be a model change beyond this module's remit. The declaration that actually predicts
 * whether starring works is the *mutating* surface: a provider that reports
 * [com.notdisk.core.model.CapabilitySupport.UNSUPPORTED] for `delete` has no write surface at
 * all — it is a read-only mount (a shared folder, an archive, a browse-only integration). On such
 * a provider, `setStarred` returning [com.notdisk.core.common.error.CloudError.Unsupported]
 * would be the honest outcome anyway.
 *
 * This is the software-design cost of a capability model that is not exhaustive, and it is worth
 * naming rather than hiding: the gate here is a **proxy**, not a precise check. It is chosen
 * because the proxy is conservative in the right direction — it can only refuse a provider that
 * genuinely cannot mutate anything — and because the alternative (no gate at all, or a new model
 * field) is worse. When a `star` capability is added to `FileOperationCapabilities`, this constant
 * is the single line that changes.
 *
 * ### Why starring is a batch operation
 * Because it is the one mutation users perform on dozens of rows at once ("select all, star"), and
 * because it must be cheap: it is an *organisational* action, not a data one. Failures are reported
 * per item ([CloudResult] carries [Unit] here rather than a
 * [com.notdisk.core.provider.BatchOperationResult] because the provider contract models it that
 * way — a vendor either applies the whole star batch or refuses it), so a repeat of the same
 * selection is harmless and callers may retry freely after a timeout. That is rare enough in this
 * module to be worth stating explicitly: **this is the one mutation here that is safe to retry**,
 * because setting a flag to a value is genuinely idempotent.
 */
class SetStarredUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param fileIds the selection. Empty is refused locally.
     * @param starred the desired state — both directions go through this one call, so the UI cannot
     *   drift into "star" and "unstar" using different endpoints.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit> {
        val selection = BatchSelection.of(fileIds, fieldName = "选择")
        selection.refusalOrNull()?.let { return it }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.delete,
                capability = CAPABILITY_STAR,
                message = "该网盘不支持标记",
            ).flatMap {
                provider.operations.setStarred(accountId, selection.fileIds, starred)
            }
        }
    }

    companion object {
        /**
         * Capability path used for the gate.
         *
         * Names the *proxy* declaration deliberately: a reader who sees this constant in a failure
         * message should be able to tell immediately that the refusal was based on the mutating
         * surface as a whole, not on a dedicated starring declaration that does not exist.
         */
        const val CAPABILITY_STAR: String = "operations.delete(star-proxy)"
    }
}
