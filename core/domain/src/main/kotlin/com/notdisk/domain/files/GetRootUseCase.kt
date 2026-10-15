package com.notdisk.domain.files

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Fetches the account's root folder node.
 *
 * ### Why the root needs its own use case
 * "The root" is not a file that vendors agree on. For 115 it is a synthetic `cid = 0` node that
 * the `/files` endpoint never returns — the client has to materialise it, including a display
 * name ("根目录") and a [com.notdisk.core.model.FileCapabilities] set that reflects what the
 * account may do *at the root* (which differs from a normal folder: the root cannot be renamed or
 * moved, and a UI that offers those affordances on it is offering an error message).
 *
 * Every navigation surface starts here, so `getRoot` is the first remote call after a cold start
 * and the natural place for the app to notice that a session has died. Routing it through a use
 * case means the "which account's root?" question is answered at exactly one place, with an
 * explicit [AccountId] — never through ambient "current account" state, which is how a
 * two-account app ends up showing account A's files under account B's header.
 *
 * ### Not gated on a capability
 * There is no `CapabilitySupport` for "can this provider produce a root", and inventing one would
 * be dishonest: a provider that cannot answer `getRoot` cannot be browsed at all, so the honest
 * signal is the failure itself ([com.notdisk.core.common.error.CloudError.TokenExpired],
 * [com.notdisk.core.common.error.CloudError.NetworkUnavailable], …). The relevant browsing
 * capabilities (`fileSystem.listChildren`, `fileSystem.getFile`) are surfaced by
 * [GetCapabilitiesUseCase] so the UI can warn before the user binds a half-verified integration.
 */
class GetRootUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration to ask.
     * @param accountId the local account id whose root is wanted.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<FileItem> =
        registry.withProvider(providerId) { provider -> provider.files.getRoot(accountId) }
}
