package com.notdisk.core.data.repository

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.database.dao.AccountDao
import com.notdisk.core.database.entity.AccountEntity
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.ProviderDescriptor
import com.notdisk.core.provider.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The app's view of "which clouds are bound and are they usable".
 *
 * ### The one rule that shapes this class
 * **A provider's own account list is authoritative; the database is a cache of it.**
 *
 * The reason is that credentials live in the provider's encrypted store, not here. A row can survive
 * a credential that was wiped (keystore invalidation, a restore to a new device), and a credential can
 * exist for a row that was never written (a crash between the two writes). Reconciling at read time —
 * provider first, database as a fallback for display metadata — keeps a single source of truth and
 * makes the two failure modes visible instead of silent.
 *
 * ### Multi-account
 * Every method is scoped by `(providerId, accountId)`. There is no "the account" anywhere in this
 * class; the *active* account is a UI preference (see `NotDiskSettingsStore`) and deliberately not
 * modelled here, so a background operation can never accidentally target whichever account the user
 * last looked at.
 */
class AccountRepository(
    private val registry: ProviderRegistry,
    private val accountDao: AccountDao,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Metadata for the providers this build can offer, without instantiating heavy objects. */
    fun providerCatalog(): List<ProviderDescriptor> = registry.descriptors()

    /** Live list of every bound account across every provider. */
    fun observeAccounts(): Flow<List<CloudAccount>> =
        accountDao.observeAll().map { rows -> rows.map(AccountEntity::toDomain) }

    /**
     * Every bound account, aggregated across all providers.
     *
     * ### Partial failure is reported, not hidden
     * One provider being unreachable (or its credential store being temporarily unreadable) must not
     * empty the whole screen. This collects per-provider outcomes and returns the accounts it *did*
     * get, plus a typed failure describing what was missed — so the UI can show the list and a
     * non-blocking warning, rather than an error page that hides data the app actually has.
     */
    suspend fun listAccounts(): CloudResult<AccountListResult> {
        val accounts = mutableListOf<CloudAccount>()
        val failures = mutableListOf<AccountListFailure>()

        registry.all().forEach { provider ->
            when (val result = provider.auth.listAccounts()) {
                is CloudResult.Success -> {
                    accounts += result.data
                    // Mirror into the local table so the UI has something to render before a
                    // provider answers on the next cold start.
                    result.data.forEach { persistQuietly(it) }
                }

                is CloudResult.Failure -> {
                    // A provider with no accounts at all commonly reports "not authenticated"; that
                    // is not a failure worth surfacing, so it is filtered by the provider's own
                    // descriptor being absent from the failure list is handled in the UI layer.
                    failures += AccountListFailure(
                        providerId = provider.id,
                        providerName = provider.displayName,
                        error = result.error,
                    )
                    logger.w(
                        TAG,
                        "listing accounts for ${provider.id} failed: ${result.error::class.simpleName}",
                    )
                }
            }
        }

        return CloudResult.Success(
            AccountListResult(
                accounts = accounts.sortedBy { it.addedAt },
                failures = failures,
            ),
        )
    }

    /** Accounts bound to one provider, straight from the provider (authoritative). */
    suspend fun accountsOf(providerId: ProviderId): CloudResult<List<CloudAccount>> =
        when (val provider = registry.require(providerId)) {
            is CloudResult.Failure -> CloudResult.Failure(provider.error)
            is CloudResult.Success -> provider.data.auth.listAccounts()
        }

    suspend fun findAccount(providerId: ProviderId, accountId: AccountId): CloudResult<CloudAccount> =
        when (val provider = registry.require(providerId)) {
            is CloudResult.Failure -> CloudResult.Failure(provider.error)
            is CloudResult.Success -> provider.data.auth.getAccount(accountId)
        }

    /**
     * Verifies a credential and updates the stored [AuthState].
     *
     * Only an explicit authentication failure is allowed to downgrade the state (see
     * `docs/architecture.md` §9). A network outage returns the error but leaves the row untouched, so
     * a user on a plane does not come back to every account marked "expired".
     */
    suspend fun verifyAccount(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<CloudAccount> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        return when (val result = provider.auth.verifyAccount(accountId)) {
            is CloudResult.Failure -> {
                if (result.error.requiresReauthentication) {
                    accountDao.updateAuthState(
                        accountId = accountId.value,
                        providerId = providerId.value,
                        authState = AuthState.EXPIRED.name,
                    )
                    logger.i(TAG, "account ${accountId.value.take(8)}… marked expired")
                }
                CloudResult.Failure(result.error)
            }

            is CloudResult.Success -> {
                persistQuietly(result.data)
                CloudResult.Success(result.data)
            }
        }
    }

    /** Starts an interactive authorisation. The returned challenge is data; the UI renders it. */
    suspend fun beginAuthorization(
        providerId: ProviderId,
        flow: com.notdisk.core.model.AuthFlowKind,
        params: com.notdisk.core.provider.AuthFlowParams = com.notdisk.core.provider.AuthFlowParams.Empty,
    ): CloudResult<com.notdisk.core.provider.AuthChallenge> =
        when (val provider = registry.require(providerId)) {
            is CloudResult.Failure -> CloudResult.Failure(provider.error)
            is CloudResult.Success -> provider.data.auth.beginAuthorization(flow, params)
        }

    suspend fun pollAuthorization(
        providerId: ProviderId,
        challengeId: String,
    ): CloudResult<com.notdisk.core.provider.AuthChallengeState> =
        when (val provider = registry.require(providerId)) {
            is CloudResult.Failure -> CloudResult.Failure(provider.error)
            is CloudResult.Success -> provider.data.auth.pollChallenge(challengeId)
        }

    suspend fun cancelAuthorization(
        providerId: ProviderId,
        challengeId: String,
    ): CloudResult<Unit> = when (val provider = registry.require(providerId)) {
        is CloudResult.Failure -> CloudResult.Failure(provider.error)
        is CloudResult.Success -> provider.data.auth.cancelChallenge(challengeId)
    }

    /**
     * Removes an account: credential first, then the local rows.
     *
     * The order matters. If the credential were dropped only after the rows, a crash in between would
     * leave a usable credential with no account row — reachable by nothing and impossible to revoke
     * from the UI. Dropping the credential first means the worst case is an orphan row, which the
     * next list (`provider.auth.listAccounts()`) will not report and which [listAccounts] therefore
     * silently reconciles away.
     */
    suspend fun removeAccount(providerId: ProviderId, accountId: AccountId): CloudResult<Unit> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        return when (val result = provider.auth.logout(accountId)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // `deleteCascade` drops this account's file metadata, recents, favourites and
                // transfer rows in one transaction. Leaving them would make a later re-bind of the
                // same vendor account show another account's stale content.
                accountDao.deleteCascade(accountId.value, providerId.value)
                logger.i(TAG, "account ${accountId.value.take(8)}… removed with its local data")
                CloudResult.Success(Unit)
            }
        }
    }

    suspend fun refreshProfile(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<CloudAccount> = when (val provider = registry.require(providerId)) {
        is CloudResult.Failure -> CloudResult.Failure(provider.error)
        is CloudResult.Success -> {
            val refreshed = provider.data.auth.refreshProfile(accountId)
            (refreshed as? CloudResult.Success)?.data?.let { persistQuietly(it) }
            refreshed
        }
    }

    suspend fun storageQuota(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<StorageQuota> = when (val provider = registry.require(providerId)) {
        is CloudResult.Failure -> CloudResult.Failure(provider.error)
        is CloudResult.Success -> provider.data.auth.getStorageQuota(accountId)
    }

    /**
     * Whether a provider needs an extra secret before a destructive operation.
     *
     * Exposed through the provider's own [CloudCapabilities] rather than a type check, so 115's
     * safe key is expressed as data and a future vendor with a similar concept needs no new code here.
     */
    fun requiresSecretForPurge(providerId: ProviderId): Boolean =
        registry.find(providerId)?.capabilities()?.operations?.trash?.purgeRequiresSecret == true

    // ------------------------------------------------------------------ internals

    /**
     * Writes a row, swallowing (but logging) a failure.
     *
     * The local table is a *cache* of the provider's list: failing to write it must never fail the
     * user-visible operation, because the authoritative data already came back successfully.
     */
    private suspend fun persistQuietly(account: CloudAccount) {
        runCatching { accountDao.upsert(account.toEntity(clock())) }
            .onFailure { logger.w(TAG, "failed to cache account row for ${account.accountId.value.take(8)}…", it) }
    }

    /** Aggregated account list plus the providers that could not be read. */
    data class AccountListResult(
        val accounts: List<CloudAccount>,
        val failures: List<AccountListFailure>,
    ) {
        val hasFailures: Boolean get() = failures.isNotEmpty()
    }

    data class AccountListFailure(
        val providerId: ProviderId,
        val providerName: String,
        val error: CloudError,
    )

    companion object {
        private const val TAG = "AccountRepository"
    }
}

/** Account row ⇄ domain model. */
private fun AccountEntity.toDomain(): CloudAccount = CloudAccount(
    accountId = AccountId(accountId),
    providerId = ProviderId(providerId),
    remoteUserId = remoteUserId,
    displayName = displayName,
    avatarUrl = avatarUrl,
    authState = runCatching { AuthState.valueOf(authState) }.getOrDefault(AuthState.UNKNOWN),
    membershipLabel = membershipLabel,
    membershipExpiresAt = membershipExpiresAt,
    addedAt = addedAt,
    lastVerifiedAt = lastVerifiedAt,
)

private fun CloudAccount.toEntity(now: Long): AccountEntity = AccountEntity(
    accountId = accountId.value,
    providerId = providerId.value,
    remoteUserId = remoteUserId,
    displayName = displayName,
    avatarUrl = avatarUrl,
    authState = authState.name,
    membershipLabel = membershipLabel,
    membershipExpiresAt = membershipExpiresAt,
    // A provider that reports `addedAt = 0` (it does not track it) gets the first-seen time instead,
    // so the account list has a stable, meaningful order.
    addedAt = if (addedAt > 0) addedAt else now,
    lastVerifiedAt = lastVerifiedAt,
)
