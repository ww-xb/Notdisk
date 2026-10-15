package com.notdisk.domain.accounts

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Confirms that a bound account's credential still works, refreshing it silently when possible.
 *
 * ### Why this exists separately from [RefreshAccountProfileUseCase]
 * These two calls look similar and are routinely conflated, at real cost:
 *
 *  - *verify* answers "may I make a request right now?" — it may contact the vendor, and its job
 *    is to produce an honest [com.notdisk.core.model.AuthState] and a renewed credential;
 *  - *refresh profile* answers "has this account's display data changed?" — it is cosmetic.
 *
 * A client that only refreshes the profile will happily show "年费VIP" for an account whose token
 * died a week ago. A client that only verifies never notices the user upgraded their membership.
 * Keeping them as two use cases means the accounts screen can do the cheap, essential one on
 * every resume and the cosmetic one on a long interval.
 *
 * ### What the caller must do with the result
 * [invoke] returns the *refreshed* [CloudAccount] with a corrected
 * [com.notdisk.core.model.AuthState]. Callers must persist it — the provider is required to
 * have updated its own store, but the account list the UI is holding is now stale. In particular:
 *
 *  - [com.notdisk.core.common.error.CloudError.TokenExpired] means the user must re-authorise;
 *    the UI should route to the add-account flow for this specific [AccountId] rather than
 *    deleting the account (the credential is dead, the account's remote files are not);
 *  - [com.notdisk.core.common.error.CloudError.NotAuthenticated] means nothing is stored at
 *    all — an account row that outlived its credential, which the UI may offer to remove;
 *  - [com.notdisk.core.common.error.CloudError.NetworkUnavailable] is *not* an authorisation
 *    problem and must not flip the badge to "expired": the previous state stands, unverified.
 *    Providers express that by returning `AuthState.UNKNOWN`, which this use case passes through
 *    untouched so the distinction survives to the UI.
 */
class VerifyAccountUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration the account belongs to.
     * @param accountId the **local** account id (never the vendor's user id), so that
     *   re-authorisation of the same vendor account keeps its local identity and its caches.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<CloudAccount> =
        registry.withProvider(providerId) { provider -> provider.auth.verifyAccount(accountId) }
}
