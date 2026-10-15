package com.notdisk.domain.testing

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthSession
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.core.provider.AuthProvider

/**
 * Configurable [AuthProvider] fake.
 *
 * Every method has a **permissive default** and an override hook, so a test that is not about a
 * particular call does not have to configure anything, while a test that *is* about it can make the
 * call fail, throw, or return a specific value and then assert on the recorded call.
 *
 * ### The three failure modes are separate on purpose
 * [accountsFailure], [accountsThrow] and "returns an empty list" are three different provider
 * behaviours, and [com.notdisk.domain.accounts.ListAccountsUseCase] must handle all of them:
 *
 *  - **returns a failure** — the contract-abiding way to report a broken store;
 *  - **throws** — a contract *violation* that must still not take down the accounts screen, because
 *    the alternative is a user who cannot reach the screen that would let them remove the broken
 *    account;
 *  - **returns an empty list** — a provider with no accounts bound, which is a *success* and must
 *    not be reported as a failure.
 *
 * A fake with a single "fail" switch could not distinguish these, and a test using it would pass
 * whether or not the production code told them apart.
 */
class FakeAuthProvider(private val owner: FakeCloudProvider) : AuthProvider {

    var accounts: List<CloudAccount> = emptyList()
    var accountsFailure: com.notdisk.core.common.error.CloudError? = null
    var accountsThrow: Boolean = false

    var beginChallenge: AuthChallenge = AuthChallenge.QrCode(
        challengeId = "challenge-1",
        content = "https://example.invalid/qr",
        expiresAt = null,
    )
    var beginFailure: com.notdisk.core.common.error.CloudError? = null
    var beginResult: CloudResult<AuthChallenge>? = null

    var pollState: AuthChallengeState = AuthChallengeState.Pending()
    var pollFailure: com.notdisk.core.common.error.CloudError? = null

    var cancelFailure: com.notdisk.core.common.error.CloudError? = null

    var account: CloudAccount? = null
    var verifyResult: CloudResult<CloudAccount>? = null
    var refreshResult: CloudResult<CloudAccount>? = null
    var quotaFailure: com.notdisk.core.common.error.CloudError? = null
    var quota: StorageQuota = StorageQuota(
        totalBytes = 1_000_000L,
        usedBytes = 250_000L,
        remainingBytes = 750_000L,
    )
    var logoutResult: CloudResult<Unit>? = null
    var sessionFailure: com.notdisk.core.common.error.CloudError? = null

    override suspend fun beginAuthorization(
        flow: AuthFlowKind,
        params: AuthFlowParams,
    ): CloudResult<AuthChallenge> {
        owner.record(ProviderSurface.AUTH, "beginAuthorization", "flow" to flow, "params" to params)
        beginResult?.let { return it }
        beginFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(beginChallenge)
    }

    override suspend fun pollChallenge(challengeId: String): CloudResult<AuthChallengeState> {
        owner.record(ProviderSurface.AUTH, "pollChallenge", "challengeId" to challengeId)
        pollFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(pollState)
    }

    override suspend fun cancelChallenge(challengeId: String): CloudResult<Unit> {
        owner.record(ProviderSurface.AUTH, "cancelChallenge", "challengeId" to challengeId)
        cancelFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(Unit)
    }

    override suspend fun verifyAccount(accountId: AccountId): CloudResult<CloudAccount> {
        owner.record(ProviderSurface.AUTH, "verifyAccount", "accountId" to accountId)
        verifyResult?.let { return it }
        return CloudResult.Success(account ?: defaultAccount(accountId))
    }

    override suspend fun getAccount(accountId: AccountId): CloudResult<CloudAccount> {
        owner.record(ProviderSurface.AUTH, "getAccount", "accountId" to accountId)
        return CloudResult.Success(account ?: defaultAccount(accountId))
    }

    override suspend fun listAccounts(): CloudResult<List<CloudAccount>> {
        owner.record(ProviderSurface.AUTH, "listAccounts")
        // Throwing is the contract violation under test; it must happen *after* recording so the
        // call log still shows the provider was reached.
        if (accountsThrow) throw FakeProviderException("fake ${owner.id}: listAccounts threw")
        accountsFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(accounts)
    }

    override suspend fun refreshProfile(accountId: AccountId): CloudResult<CloudAccount> {
        owner.record(ProviderSurface.AUTH, "refreshProfile", "accountId" to accountId)
        refreshResult?.let { return it }
        return CloudResult.Success(account ?: defaultAccount(accountId))
    }

    override suspend fun getStorageQuota(accountId: AccountId): CloudResult<StorageQuota> {
        owner.record(ProviderSurface.AUTH, "getStorageQuota", "accountId" to accountId)
        quotaFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(quota)
    }

    override suspend fun logout(accountId: AccountId): CloudResult<Unit> {
        owner.record(ProviderSurface.AUTH, "logout", "accountId" to accountId)
        logoutResult?.let { return it }
        return CloudResult.Success(Unit)
    }

    override suspend fun session(accountId: AccountId): CloudResult<AuthSession> {
        owner.record(ProviderSurface.AUTH, "session", "accountId" to accountId)
        sessionFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(FakeAuthSession(owner.id, accountId))
    }

    private fun defaultAccount(accountId: AccountId) = CloudAccount(
        accountId = accountId,
        providerId = owner.id,
        remoteUserId = "remote-$accountId",
        displayName = "Account $accountId",
        authState = AuthState.AUTHORIZED,
    )
}

/** Minimal [AuthSession] used where a test needs one; carries no credential material. */
class FakeAuthSession(
    override val providerId: ProviderId,
    override val accountId: AccountId,
    override val expiresAt: Long? = null,
) : AuthSession
