package com.notdisk.domain

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.errorOrNull
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.DefaultProviderRegistry
import com.notdisk.domain.testing.FakeCloudProvider

/**
 * Shared fixtures for the domain test suite.
 *
 * ### Why the ids are constants rather than freshly generated
 * A test that asserts "the failure names the provider that failed" has to know the id. Using
 * `ProviderId("alpha")`/`ProviderId("beta")` throughout makes every assertion readable and makes a
 * failure message self-explanatory ("alpha" is obviously the first fake), which matters because
 * these tests are read far more often than they are written.
 */
object Fixtures {

    val ALPHA: ProviderId = ProviderId("alpha")
    val BETA: ProviderId = ProviderId("beta")
    val GAMMA: ProviderId = ProviderId("gamma")

    val ACCOUNT: AccountId = AccountId("account-1")
    val OTHER_ACCOUNT: AccountId = AccountId("account-2")

    /** An account bound to [ALPHA], for `ListAccountsUseCase` fixtures. */
    fun account(
        providerId: ProviderId,
        accountId: AccountId = ACCOUNT,
        displayName: String = "账户 $accountId",
        addedAt: Long = 1_000L,
        authState: AuthState = AuthState.AUTHORIZED,
    ): CloudAccount = CloudAccount(
        accountId = accountId,
        providerId = providerId,
        remoteUserId = "remote-$accountId",
        displayName = displayName,
        authState = authState,
        addedAt = addedAt,
    )
}

/** Builds a registry from fakes, keeping the `DefaultProviderRegistry` path under test. */
fun registryOf(vararg providers: FakeCloudProvider): DefaultProviderRegistry =
    DefaultProviderRegistry(providers.toList())

/** Asserts a failure of an exact type and returns it, cast, for further field assertions. */
inline fun <reified E : CloudError> CloudResult<*>.failureOfType(): E {
    val error = errorOrNull() ?: error("expected a Failure but got Success: $this")
    if (error !is E) {
        error("expected ${E::class.simpleName} but got ${error::class.simpleName}: $error")
    }
    return error
}

/**
 * Unwraps a success or fails the test with the error, keeping assertions short.
 *
 * ### Why this branches on the sealed type instead of going through [getOrNull]
 * Because `getOrNull()` returns `null` for a `Failure` **and** for a `Success(null)`, and those are
 * different outcomes. Several use cases in this module deliberately return a nullable payload where
 * `null` is a *value* rather than an error — `GetPlaybackProgressUseCase` returns
 * `CloudResult<Long?>` where a success carrying `null` means "the provider answered and has no
 * recorded position", which is emphatically not a failure.
 *
 * A helper written as `getOrNull() ?: error(...)` would turn that legitimate success into a test
 * failure, and would push callers towards asserting on the raw type. Matching on the sealed
 * interface keeps the helper usable for every payload shape this module produces.
 */
fun <T> CloudResult<T>.requireSuccess(): T = when (this) {
    is CloudResult.Success -> data
    is CloudResult.Failure -> error("expected Success but got $error")
}
