package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.core.provider.ShareResult
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.invalidArgument
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Creates or revokes a public share link for a selection of items.
 *
 * ### Why this is the use case most likely to refuse, and why that is correct
 * Sharing is the classic *unverified* vendor feature. On 115 it is documented in the vendor's own
 * front-end bundle (`POST /usershare/share` with `share_opt=1|0`) but has never been exercised in
 * this build, so the provider declares it
 * [com.notdisk.core.model.CapabilitySupport.UNVERIFIED] — not `SUPPORTED`, and not `UNSUPPORTED`
 * either.
 *
 * The gate therefore *lets the call through*, because refusing `UNVERIFIED` would make a
 * documented endpoint permanently unreachable. What it must not do is imply the result is reliable.
 * A caller that wants to be honest with the user reads
 * [com.notdisk.core.model.FileOperationCapabilities.share] through
 * [com.notdisk.domain.files.GetCapabilitiesUseCase] and renders the entry point with the caveat
 * from `CapabilitySupport.toUiDescription()` ("未验证，可能不可用") rather than as a promise. The
 * split is deliberate: the *domain* layer decides what is impossible, the *UI* decides how to
 * hedge what is uncertain.
 *
 * ### Why both create and revoke live here
 * Because the vendor implements them as one endpoint with a flag (`share_opt`), so splitting them
 * into two use cases would put two callers in a position to disagree about the flag's meaning. One
 * `enable: Boolean` parameter makes "the same endpoint, two directions" explicit and makes it
 * impossible to revoke by calling a create path with an empty password.
 *
 * ### Validation
 * The selection is validated like every other batch ([BatchSelection]): empty is refused, duplicates
 * are removed order-preservingly. There is one exception to the general "validate before gating"
 * order, and it is deliberate: an *empty password* is **not** rejected. Some vendors use an empty
 * password to mean "no password" and others to mean "generate one"; the vendor is the authority, and
 * [ShareResult.password] reports back what it actually chose. Guessing here would either strip a
 * password the user wanted or invent one they did not ask for.
 *
 * ### The returned URL is a secret with a lifetime
 * [ShareResult.shareUrl] is a bearer credential: anyone holding it can read the files. It must not
 * be logged verbatim, written to crash reports, or persisted in a share history table without the
 * user's explicit intent. [ShareResult.expiresAt] is forwarded so the UI can show the link's
 * lifetime instead of presenting a permanent-looking URL that dies in an hour.
 */
class CreateShareUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to operate on.
     * @param accountId the local account id.
     * @param fileIds the selection to share, in the order the caller wants outcomes reported.
     * @param enable `true` creates the link, `false` revokes it.
     * @param password an optional extraction code. Never logged, never persisted.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileIds: List<FileId>,
        enable: Boolean = true,
        password: String? = null,
    ): CloudResult<ShareResult> {
        val selection = BatchSelection.of(fileIds, fieldName = "分享选择")
        selection.refusalOrNull()?.let { return it }

        // A NUL byte in a password cannot round-trip through any vendor's form encoding, and the
        // failure it produces is a malformed request rather than a clean refusal.
        if (password != null && password.contains('\u0000')) {
            return invalidArgument("提取码", "包含非法字符")
        }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.share,
                capability = CAPABILITY_SHARE,
                message = "该网盘不支持分享",
            ).flatMap {
                provider.operations.share(
                    accountId = accountId,
                    fileIds = selection.fileIds,
                    enable = enable,
                    password = password,
                )
            }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_SHARE: String = "operations.share"
    }
}
