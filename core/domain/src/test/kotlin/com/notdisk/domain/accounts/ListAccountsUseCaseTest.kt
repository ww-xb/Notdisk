package com.notdisk.domain.accounts

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.DefaultProviderRegistry
import com.notdisk.domain.Fixtures
import com.notdisk.domain.failureOfType
import com.notdisk.domain.registryOf
import com.notdisk.domain.requireSuccess
import com.notdisk.domain.testing.FakeCloudProvider
import com.notdisk.domain.testing.ProviderSurface
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [com.notdisk.core.provider.ProviderRegistry.require] and [ListAccountsUseCase].
 *
 * ### What these tests are really protecting
 * The registry is the app's only vendor-neutral door. Two properties matter and neither is
 * obvious from the code:
 *
 *  1. **Requiring an absent provider is a normal, typed answer.** Accounts persist across app
 *     upgrades, and a build variant can drop a provider that a previous build shipped. The accounts
 *     screen must render that row as "该网盘不可用", not crash.
 *  2. **ListAccounts must survive a broken provider.** It is the only call in the app that touches
 *     every provider at once, on the very screen the user needs in order to *remove* a broken
 *     account. If a failure propagates, the user is locked out of the fix.
 */
class ListAccountsUseCaseTest {

    // ---------------------------------------------------------------------------------------------
    // ProviderRegistry.require
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `require returns Unsupported naming the id for an unknown provider`() = runTest {
        val registry = registryOf(FakeCloudProvider(Fixtures.ALPHA))

        val result = registry.require(ProviderId("does-not-exist"))

        assertTrue(result.isFailure(), "an unregistered provider must not resolve")
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(
            "provider:does-not-exist",
            error.capability,
            "the refusal must name the missing provider so the failure is traceable",
        )
    }

    @Test
    fun `require resolves a registered provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val registry = registryOf(alpha)

        val resolved = registry.require(Fixtures.ALPHA).requireSuccess()

        assertEquals(Fixtures.ALPHA, resolved.id)
    }

    @Test
    fun `find returns null rather than failing for an unknown provider`() = runTest {
        val registry = registryOf(FakeCloudProvider(Fixtures.ALPHA))

        assertEquals(null, registry.find(ProviderId("missing")))
    }

    @Test
    fun `capabilitiesOf forwards the providers own declaration`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val registry = registryOf(alpha)

        val caps = registry.capabilitiesOf(Fixtures.ALPHA).requireSuccess()

        assertEquals(Fixtures.ALPHA, caps.providerId)
        assertTrue(caps.fileSystem.listChildren.isSupported)
    }

    // ---------------------------------------------------------------------------------------------
    // ListAccountsUseCase — the happy path
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `aggregates accounts across every registered provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val beta = FakeCloudProvider(Fixtures.BETA)
        alpha.authFake.accounts = listOf(
            Fixtures.account(Fixtures.ALPHA, Fixtures.ACCOUNT, addedAt = 1_000L),
        )
        beta.authFake.accounts = listOf(
            Fixtures.account(Fixtures.BETA, Fixtures.OTHER_ACCOUNT, addedAt = 2_000L),
        )
        val useCase = ListAccountsUseCase(registryOf(alpha, beta))

        val snapshot = useCase().requireSuccess()

        assertEquals(2, snapshot.accounts.size)
        assertTrue(snapshot.isComplete, "no provider failed, so the snapshot is complete")
        assertEquals(2, snapshot.providersQueried)
        // Both providers were consulted exactly once.
        assertEquals(1, alpha.callsOn(ProviderSurface.AUTH).size)
        assertEquals(1, beta.callsOn(ProviderSurface.AUTH).size)
    }

    @Test
    fun `orders accounts by addedAt then display name for a stable list`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.accounts = listOf(
            Fixtures.account(Fixtures.ALPHA, Fixtures.ACCOUNT, displayName = "z", addedAt = 5_000L),
            Fixtures.account(Fixtures.ALPHA, Fixtures.OTHER_ACCOUNT, displayName = "a", addedAt = 1_000L),
        )
        val useCase = ListAccountsUseCase(registryOf(alpha))

        val snapshot = useCase().requireSuccess()

        assertEquals(
            listOf(1_000L, 5_000L),
            snapshot.accounts.map { it.addedAt },
            "earlier-added accounts must come first so rows do not shuffle between refreshes",
        )
    }

    @Test
    fun `an empty provider list is a complete and empty snapshot, not an error`() = runTest {
        val useCase = ListAccountsUseCase(registryOf())

        val snapshot = useCase().requireSuccess()

        assertTrue(snapshot.accounts.isEmpty())
        assertTrue(snapshot.isComplete)
        assertTrue(snapshot.isEmptyAndComplete, "this is the honest empty state the UI shows")
        assertEquals(0, snapshot.providersQueried)
    }

    // ---------------------------------------------------------------------------------------------
    // ListAccountsUseCase — per-provider failure isolation (the required test)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a provider returning a failure does not fail the whole list`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val beta = FakeCloudProvider(Fixtures.BETA)
        val gamma = FakeCloudProvider(Fixtures.GAMMA)

        alpha.authFake.accounts = listOf(Fixtures.account(Fixtures.ALPHA, Fixtures.ACCOUNT))
        beta.authFake.accountsFailure = CloudError.LocalIoError(message = "credential store corrupt")
        gamma.authFake.accounts = listOf(
            Fixtures.account(Fixtures.GAMMA, Fixtures.OTHER_ACCOUNT),
        )

        val useCase = ListAccountsUseCase(registryOf(alpha, beta, gamma))

        val result = useCase()

        assertTrue(result.isSuccess(), "one broken provider must not fail the whole call")
        val snapshot = result.requireSuccess()
        assertEquals(
            listOf(Fixtures.ALPHA, Fixtures.GAMMA),
            snapshot.accounts.map { it.providerId },
            "the healthy providers' accounts must still be returned",
        )
        assertEquals(1, snapshot.failures.size)
        assertEquals(Fixtures.BETA, snapshot.failures.single().providerId)
        assertTrue(snapshot.failures.single().error is CloudError.LocalIoError)
        assertTrue(
            !snapshot.isComplete,
            "the UI must be able to tell this list apart from a genuinely empty one",
        )
    }

    @Test
    fun `a provider that throws is isolated just like one that returns a failure`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val beta = FakeCloudProvider(Fixtures.BETA)
        alpha.authFake.accounts = listOf(Fixtures.account(Fixtures.ALPHA, Fixtures.ACCOUNT))
        // beta violates the no-throw contract — the accounts screen must still work, because it is
        // the only place the user can go to remove the broken account.
        beta.authFake.accountsThrow = true

        val useCase = ListAccountsUseCase(registryOf(alpha, beta))

        val result = useCase()

        assertTrue(result.isSuccess(), "a thrown provider bug must not take down the accounts list")
        val snapshot = result.requireSuccess()
        assertEquals(listOf(Fixtures.ALPHA), snapshot.accounts.map { it.providerId })
        assertEquals(Fixtures.BETA, snapshot.failures.single().providerId)
        assertNotNull(
            (snapshot.failures.single().error as? CloudError.Unknown)?.cause,
            "the thrown cause must be preserved so the bug is diagnosable",
        )
    }

    @Test
    fun `every failing provider is reported, not just the first`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val beta = FakeCloudProvider(Fixtures.BETA)
        alpha.authFake.accountsFailure = CloudError.NetworkUnavailable()
        beta.authFake.accountsFailure = CloudError.TokenExpired()

        val snapshot = ListAccountsUseCase(registryOf(alpha, beta))().requireSuccess()

        assertEquals(
            listOf(Fixtures.ALPHA, Fixtures.BETA),
            snapshot.failures.map { it.providerId },
            "failures are collected in registration order so the UI reports all of them",
        )
    }

    @Test
    fun `a provider with no accounts is a success and is not reported as a failure`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.accounts = emptyList()

        val snapshot = ListAccountsUseCase(registryOf(alpha))().requireSuccess()

        assertTrue(snapshot.accounts.isEmpty())
        assertTrue(
            snapshot.isComplete,
            "'no accounts bound' must not be conflated with 'the provider is broken'",
        )
        assertTrue(snapshot.failures.isEmpty())
    }

    @Test
    fun `propagates the refreshed auth state without rewriting it`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.accounts = listOf(
            Fixtures.account(Fixtures.ALPHA, authState = AuthState.EXPIRED),
        )

        val snapshot = ListAccountsUseCase(registryOf(alpha))().requireSuccess()

        assertEquals(
            AuthState.EXPIRED,
            snapshot.accounts.single().authState,
            "the use case must not silently upgrade an expired state to authorized",
        )
    }

    @Test
    fun `reads the registry lazily so a provider registered later is still listed`() = runTest {
        // Guards against an implementation that snapshots `registry.all()` at construction.
        val registry = DefaultProviderRegistry()
        val useCase = ListAccountsUseCase(registry)

        assertTrue(useCase().requireSuccess().accounts.isEmpty())

        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.accounts = listOf(Fixtures.account(Fixtures.ALPHA))
        registry.register(alpha)

        assertEquals(1, useCase().requireSuccess().accounts.size)
    }
}
