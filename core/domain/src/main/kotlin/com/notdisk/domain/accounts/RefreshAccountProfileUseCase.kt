package com.notdisk.domain.accounts

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Re-fetches the vendor-side profile (display name, avatar, membership tier, expiry).
 *
 * ### Why this is distinct from verifying the credential
 * See [VerifyAccountUseCase] for the full rationale. In short: this call is *cosmetic and
 * expensive*, and treating it as a health check is how an app greets a returning user with a
 * dead session while showing a freshly-fetched VIP badge.
 *
 * ### The signed-URL trap this use case exists to keep out of persistence
 * [CloudAccount.avatarUrl] is documented as "a non-signed, stable URL; signed avatars must be
 * `null`". Vendors routinely return avatar URLs carrying an expiring signature. Because this use
 * case is the only way an avatar enters the app, the rule is enforced at exactly one place in the
 * codebase — the provider — and the accounts UI can cache whatever it receives without leaking a
 * credential-bearing URL into Room or into a log. Callers must therefore treat the returned
 * [CloudAccount] as the authoritative, *stable* profile, and re-resolve anything signed on demand.
 *
 * ### Error handling
 * A profile refresh failing is never a reason to log the user out. Callers should keep the
 * previous profile on failure and retry later; the interesting cases are
 * [com.notdisk.core.common.error.CloudError.TokenExpired] (surface "please re-login") and
 * [com.notdisk.core.common.error.CloudError.RateLimited] (back off, do not retry in a loop —
 * vendors rate-limit this endpoint aggressively because every client polls it).
 */
class RefreshAccountProfileUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration the account belongs to.
     * @param accountId the local account id whose profile should be re-fetched and persisted.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<CloudAccount> =
        registry.withProvider(providerId) { provider -> provider.auth.refreshProfile(accountId) }
}
