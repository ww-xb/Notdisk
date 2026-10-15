package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthSession
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CategoryUsage
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.AuthProvider
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.AuthChallenge
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline [AuthProvider] used by the Mock cloud.
 *
 * ### What it is for
 *  - letting the whole app (accounts UI, repository, ViewModels, player) run with no network;
 *  - exercising the *shape* of a real interactive flow: begin → challenge → poll → success, with a
 *    cancellable, expiring challenge rather than an instant "logged in" boolean.
 *
 * ### What it is NOT
 * It never claims to be a real login. The account it creates is visibly labelled as a Mock account
 * and the provider is registered with `isDevelopmentOnly = true`, so the UI can badge it.
 */
internal class MockAuthProvider(
    private val store: MockAccountStore,
    /**
     * Number of [pollChallenge] calls before the QR flow reports success. `0` makes the very first
     * poll succeed, which is what most tests want; the default mimics a user taking a moment.
     */
    private val pollsUntilSuccess: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthProvider {

    private data class Challenge(
        val id: String,
        val flow: AuthFlowKind,
        val createdAt: Long,
        val expiresAt: Long,
        val polls: AtomicInteger = AtomicInteger(0),
        @Volatile var cancelled: Boolean = false,
    )

    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val mutex = Mutex()

    override suspend fun beginAuthorization(
        flow: AuthFlowKind,
        params: AuthFlowParams,
    ): CloudResult<AuthChallenge> {
        if (flow !in MOCK_AUTH_FLOWS) {
            return unsupported(
                capability = "auth.flow.$flow",
                message = "Mock Provider 不支持 $flow 授权方式",
            )
        }
        val id = "mock-challenge-${UUID.randomUUID()}"
        val now = clock()
        val challenge = Challenge(
            id = id,
            flow = flow,
            createdAt = now,
            // A short, explicit validity window so the accounts UI exercises the "expired
            // challenge, start again" path rather than assuming a challenge lives forever.
            expiresAt = now + CHALLENGE_TTL_MS,
        )
        challenges[id] = challenge

        return when (flow) {
            AuthFlowKind.QR_CODE -> CloudResult.Success(
                AuthChallenge.QrCode(
                    challengeId = id,
                    content = "mock://qr/$id",
                    expiresAt = challenge.expiresAt,
                ),
            )

            AuthFlowKind.MANUAL_CREDENTIAL -> CloudResult.Success(
                // The Mock flow "completes" as soon as it starts, so the UI can bind an account
                // without any user interaction. It still goes through the challenge API so the
                // same UI code path is exercised as for a real provider.
                AuthChallenge.Completed(
                    challengeId = id,
                    accountId = createAccount(params.manualCredential ?: "Mock 用户").accountId,
                ),
            )

            else -> unsupported("auth.flow.$flow", "Mock Provider 不支持 $flow 授权方式")
        }
    }

    override suspend fun pollChallenge(challengeId: String): CloudResult<AuthChallengeState> {
        // An unknown id is reported as a *terminal state*, not as an error. The caller is a polling
        // loop that only stops on a terminal state; handing it a NotFound failure would either be
        // treated as "keep polling" (an infinite loop) or force every call site to special-case it.
        val challenge = challenges[challengeId]
            ?: return CloudResult.Success(
                AuthChallengeState.Failed("授权会话已结束，请重新发起", retryable = true),
            )

        if (challenge.cancelled) {
            return CloudResult.Success(AuthChallengeState.Failed("授权已取消", retryable = true))
        }
        if (clock() >= challenge.expiresAt) {
            challenges.remove(challengeId)
            return CloudResult.Success(AuthChallengeState.Failed("二维码已过期", retryable = true))
        }

        val attempt = challenge.polls.incrementAndGet()
        if (attempt <= pollsUntilSuccess) {
            return CloudResult.Success(
                when (attempt) {
                    1 -> AuthChallengeState.Pending("等待扫码")
                    else -> AuthChallengeState.Scanned
                },
            )
        }

        // A QR challenge that reaches this point represents "the user scanned and confirmed".
        val account = createAccount("Mock 用户")
        challenges.remove(challengeId)
        return CloudResult.Success(AuthChallengeState.Succeeded(account.accountId))
    }

    override suspend fun cancelChallenge(challengeId: String): CloudResult<Unit> {
        challenges[challengeId]?.cancelled = true
        challenges.remove(challengeId)
        return CloudResult.Success(Unit)
    }

    override suspend fun verifyAccount(accountId: AccountId): CloudResult<CloudAccount> {
        val record = store.get(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "账号不存在"))

        return when (record.authState) {
            AuthState.EXPIRED, AuthState.REVOKED -> CloudResult.Failure(
                CloudError.TokenExpired(
                    message = "Mock 账号授权已失效，请重新登录",
                    canRefresh = false,
                    vendorCode = "MOCK_EXPIRED",
                ),
            )

            else -> {
                val updated = store.update(accountId) {
                    it.lastVerifiedAt = clock()
                    it.authState = AuthState.AUTHORIZED
                }!!
                CloudResult.Success(updated.toAccount())
            }
        }
    }

    override suspend fun getAccount(accountId: AccountId): CloudResult<CloudAccount> {
        val record = store.get(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "账号不存在"))
        return CloudResult.Success(record.toAccount())
    }

    override suspend fun listAccounts(): CloudResult<List<CloudAccount>> =
        CloudResult.Success(store.all().map { it.toAccount() })

    override suspend fun refreshProfile(accountId: AccountId): CloudResult<CloudAccount> {
        val record = store.get(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "账号不存在"))
        // Offline provider: the profile is local, so a "refresh" is a verification.
        return verifyAccount(record.accountId)
    }

    override suspend fun getStorageQuota(accountId: AccountId): CloudResult<StorageQuota> {
        if (store.get(accountId) == null) {
            return CloudResult.Failure(CloudError.NotAuthenticated(message = "账号不存在"))
        }
        // Deterministic numbers derived from the tree so tests can assert on them.
        val used = MockTree.all.sumOf { it.size ?: 0L }
        val total = 2L * 1024 * 1024 * 1024 * 1024 // 2 TiB
        return CloudResult.Success(
            StorageQuota(
                totalBytes = total,
                usedBytes = used,
                remainingBytes = total - used,
                totalLabel = "2.00TB",
                usedLabel = "${used / (1024 * 1024)}MB",
                remainingLabel = "${(total - used) / (1024 * 1024 * 1024)}GB",
                categoryUsage = MockTree.all
                    .filter { !it.isFolder }
                    .mapNotNull { node -> categoryOf(node.extension)?.let { it to node } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, nodes) ->
                        CategoryUsage(
                            itemCount = nodes.size.toLong(),
                            bytes = nodes.sumOf { it.size ?: 0L },
                        )
                    },
            ),
        )
    }

    override suspend fun logout(accountId: AccountId): CloudResult<Unit> {
        // Mirrors the real contract: the local credential is dropped even if the "remote" part
        // would have failed.
        store.remove(accountId)
        return CloudResult.Success(Unit)
    }

    override suspend fun session(accountId: AccountId): CloudResult<AuthSession> {
        val record = store.get(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "账号不存在"))
        if (record.authState == AuthState.EXPIRED || record.authState == AuthState.REVOKED) {
            return CloudResult.Failure(
                CloudError.TokenExpired(message = "Mock 账号授权已失效", canRefresh = false),
            )
        }
        return CloudResult.Success(
            MockAuthSession(
                providerId = MOCK_PROVIDER_ID,
                accountId = accountId,
                expiresAt = record.lastVerifiedAt?.plus(SESSION_TTL_MS),
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun createAccount(userName: String): MockAccountStore.Record = mutex.withLock {
        val accountId = AccountId("mock-account-${UUID.randomUUID()}")
        val record = MockAccountStore.Record(
            accountId = accountId,
            userId = "mock-user-${store.all().size + 1}",
            userName = userName,
            authState = AuthState.AUTHORIZED,
            membershipLabel = "Mock 会员",
            addedAt = clock(),
            lastVerifiedAt = clock(),
            sessionToken = UUID.randomUUID().toString(),
        )
        store.insert(record)
        record
    }

    private fun MockAccountStore.Record.toAccount(): CloudAccount = CloudAccount(
        accountId = accountId,
        providerId = MOCK_PROVIDER_ID,
        remoteUserId = userId,
        displayName = userName,
        avatarUrl = null,
        authState = authState,
        membershipLabel = membershipLabel,
        membershipExpiresAt = null,
        addedAt = addedAt,
        lastVerifiedAt = lastVerifiedAt,
    )

    /**
     * Simulates the vendor side taking its time, so the UI's polling loop is genuinely exercised
     * rather than resolving synchronously.
     */
    suspend fun simulateNetworkDelay(ms: Long = 40) = delay(ms)

    private companion object {
        const val CHALLENGE_TTL_MS = 120_000L
        const val SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000L
    }
}

/** Counts the mock file categories; kept next to the auth provider because it feeds the quota. */
internal fun MockNode.category(): FileCategory? = categoryOf(extension)
