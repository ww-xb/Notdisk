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
 * Copies a selection of items into a target folder.
 *
 * ### Why this use case is the reason [BatchSelection] dedupes
 * Copy is the operation where a duplicated id causes **unrecoverable** damage rather than a
 * confusing report. Retrying a *move* is benign; retrying a *copy* creates a second file, and
 * sending the same id twice in one request does the same thing on vendors that iterate the list
 * without a de-duplicating set. The user selected one file and gets two — with the same name, so
 * neither the file list nor the user can tell which is which, and the surplus one counts against
 * their quota forever.
 *
 * That asymmetry is also why [com.notdisk.core.model.FileOperationCapabilities.idempotentWrites]
 * matters here more than anywhere else, and why it defaults to `false`. This use case does **not**
 * retry, ever: it makes exactly one provider call per invocation. Any retry policy belongs to the
 * transport or to an explicitly user-driven "retry" action, after the caller has re-read the
 * destination to see whether the first attempt actually landed.
 *
 * ### Progress and size
 * A copy duplicates bytes on the vendor's side. Some vendors process it asynchronously and return a
 * job id ([BatchOperationResult.vendorTaskId]) before the copy is visible at the destination. A
 * caller that refreshes the destination folder immediately and sees nothing new must not conclude
 * the copy failed — it should poll or wait until the vendor reports completion. This use case
 * forwards [BatchOperationResult.vendorTaskId] untouched precisely so that distinction survives.
 * No client-side progress is fabricated: where the vendor reports none, the UI shows an
 * indeterminate state rather than a fake percentage.
 */
class CopyFilesUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param fileIds the selection, in the order the caller wants outcomes reported.
     * @param targetParentId the destination folder.
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
                support = capabilities.operations.copy,
                capability = CAPABILITY_COPY,
                message = "该网盘不支持复制",
            ).flatMap {
                provider.operations.copy(accountId, selection.fileIds, targetParentId)
            }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_COPY: String = "operations.copy"
    }
}
