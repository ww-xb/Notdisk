package com.notdisk.domain.accounts

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Unbinds an account: invalidates the credential on the vendor side where possible, then drops it
 * locally.
 *
 * ### Why this use case deliberately does *not* gate on a capability
 * Every other use case in this module checks a [com.notdisk.core.model.CapabilitySupport]
 * before delegating, so this one needs to justify being the exception.
 *
 * [com.notdisk.core.model.AccountCapabilities.logout] declares whether the provider can revoke
 * the credential **on the vendor's side**. It says nothing about the local removal, which is the
 * part the user actually asked for when they tapped "退出登录". Gating on that declaration would
 * have the domain layer refuse the call for a provider with
 * [com.notdisk.core.model.CapabilitySupport.UNSUPPORTED] logout — and refusing means the local
 * credential is *never dropped*, leaving a live token on the device whose owner believes they have
 * signed out. That is a security defect dressed up as a capability check.
 *
 * The provider contract already resolves this correctly: `logout` **must succeed even when the
 * remote call fails**, because it removes the local credential unconditionally. The right domain
 * behaviour is therefore to always delegate, and to let the provider decide internally whether
 * there is a remote endpoint worth calling. This is the case where the honest answer is "the
 * capability flag is informational, not a gate" — and the flag remains useful to the *UI*, which
 * uses it to word the confirmation dialog ("also sign out on the vendor's website" vs. "this only
 * clears the local authorisation").
 *
 * ### Why callers should not delete cached data first
 * The remote files outlive the binding. Download records, playback progress and the local cache
 * index are keyed by [com.notdisk.core.model.CloudFileKey] and stay meaningful if the user
 * re-binds the same vendor account — [AccountId] is generated locally and preserved across
 * re-authorisation precisely so that works. Wiping them eagerly turns a re-login into a cold
 * start; the data layer owns that decision, not this use case.
 *
 * ### Failure semantics
 * A returned failure means the **local** removal failed, which is the only failure the user can do
 * anything about. Callers should surface it and offer a retry rather than pretending the sign-out
 * succeeded; a stale row in the accounts list is exactly the bug this contract prevents.
 */
class LogoutAccountUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration the account belongs to.
     * @param accountId the local account id to unbind.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<Unit> =
        registry.withProvider(providerId) { provider -> provider.auth.logout(accountId) }
}
