package com.notdisk.domain.accounts

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.domain.Fixtures
import com.notdisk.domain.failureOfType
import com.notdisk.domain.registryOf
import com.notdisk.domain.requireSuccess
import com.notdisk.domain.testing.CapabilityFixtures
import com.notdisk.domain.testing.FakeCloudProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the account use cases other than [ListAccountsUseCase].
 *
 * ### The three properties every one of these tests checks
 *  1. **The right provider is reached**, with the caller's arguments intact — a use case that
 *     dropped `params`, or defaulted `accountId`, would otherwise compile and pass a laxer test.
 *  2. **An absent provider is a typed failure**, never an exception.
 *  3. **A declared-unsupported capability is refused without a provider call**, which is the
 *     module's central "never send a doomed request" property.
 */
class AccountUseCasesTest {

    // ---------------------------------------------------------------------------------------------
    // BeginAuthorizationUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `beginAuthorization delegates the flow and params verbatim`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val params = AuthFlowParams(account = "user", password = "secret")
        val useCase = BeginAuthorizationUseCase(registryOf(alpha))

        val challenge = useCase(Fixtures.ALPHA, AuthFlowKind.PASSWORD, params).requireSuccess()

        assertTrue(challenge is AuthChallenge.QrCode)
        val call = alpha.callsOn(com.notdisk.domain.testing.ProviderSurface.AUTH).single()
        assertEquals("beginAuthorization", call.method)
        assertEquals(AuthFlowKind.PASSWORD, call["flow"])
        assertEquals(params, call["params"], "flow params must reach the provider unchanged")
    }

    @Test
    fun `beginAuthorization refuses an unadvertised flow without contacting the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        // The provider advertises only QR; asking for OAuth must be refused locally.
        alpha.capabilityDeclaration = CapabilityFixtures.allSupported(Fixtures.ALPHA).let { caps ->
            caps.copy(
                account = caps.account.copy(interactiveFlows = setOf(AuthFlowKind.QR_CODE)),
            )
        }
        val useCase = BeginAuthorizationUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, AuthFlowKind.OAUTH_REDIRECT)

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(BeginAuthorizationUseCase.CAPABILITY_INTERACTIVE_FLOWS, error.capability)
        assertTrue(
            alpha.wasNeverCalled(),
            "an unadvertised flow must not produce a vendor request",
        )
    }

    @Test
    fun `beginAuthorization on an unknown provider fails with Unsupported`() = runTest {
        val useCase = BeginAuthorizationUseCase(registryOf())

        val result = useCase(Fixtures.ALPHA, AuthFlowKind.QR_CODE)

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unsupported>()
    }

    // ---------------------------------------------------------------------------------------------
    // PollAuthorizationUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `pollAuthorization forwards the challenge id and returns the state verbatim`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.pollState = AuthChallengeState.Scanned
        val useCase = PollAuthorizationUseCase(registryOf(alpha))

        val state = useCase(Fixtures.ALPHA, "challenge-42").requireSuccess()

        assertEquals(AuthChallengeState.Scanned, state)
        assertEquals(
            "challenge-42",
            alpha.callsOn(com.notdisk.domain.testing.ProviderSurface.AUTH).single()["challengeId"],
        )
    }

    @Test
    fun `pollAuthorization passes a terminal Failed through instead of rewriting it`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.pollState = AuthChallengeState.Failed(message = "二维码已过期")
        val useCase = PollAuthorizationUseCase(registryOf(alpha))

        val state = useCase(Fixtures.ALPHA, "challenge-1").requireSuccess()

        assertTrue(
            state is AuthChallengeState.Failed,
            "a vendor 'no' must never be rewritten into 'keep waiting'",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // CancelAuthorizationUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `cancelAuthorization is idempotent from the caller's point of view`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = CancelAuthorizationUseCase(registryOf(alpha))

        assertTrue(useCase(Fixtures.ALPHA, "c1").isSuccess())
        assertTrue(useCase(Fixtures.ALPHA, "c1").isSuccess())
        assertEquals(2, alpha.callsOn(com.notdisk.domain.testing.ProviderSurface.AUTH).size)
    }

    // ---------------------------------------------------------------------------------------------
    // VerifyAccountUseCase / RefreshAccountProfileUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `verifyAccount forwards the account id and preserves the returned auth state`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.verifyResult = com.notdisk.core.common.result.CloudResult.Success(
            Fixtures.account(Fixtures.ALPHA, authState = AuthState.EXPIRED),
        )
        val useCase = VerifyAccountUseCase(registryOf(alpha))

        val account = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT).requireSuccess()

        assertEquals(AuthState.EXPIRED, account.authState)
        assertEquals(Fixtures.ACCOUNT, account.accountId)
    }

    @Test
    fun `verifyAccount surfaces TokenExpired so the UI can route to re-authorisation`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.verifyResult = com.notdisk.core.common.result.CloudResult.Failure(
            CloudError.TokenExpired(canRefresh = false),
        )
        val useCase = VerifyAccountUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.TokenExpired>()
        assertTrue(!error.canRefresh)
    }

    @Test
    fun `refreshProfile is a distinct call from verifyAccount`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.account = Fixtures.account(Fixtures.ALPHA, displayName = "新昵称")

        val account = RefreshAccountProfileUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
        ).requireSuccess()

        assertEquals("新昵称", account.displayName)
        assertEquals(
            "refreshProfile",
            alpha.callsOn(com.notdisk.domain.testing.ProviderSurface.AUTH).single().method,
            "refreshing must not be implemented by calling verifyAccount",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // LogoutAccountUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `logout delegates even when the provider declares no remote logout`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            account = caps.account.copy(logout = CapabilitySupport.UNSUPPORTED),
        )
        val useCase = LogoutAccountUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(
            result.isSuccess(),
            "refusing here would leave a live credential on the device after the user signed out",
        )
        assertEquals(
            "logout",
            alpha.callsOn(com.notdisk.domain.testing.ProviderSurface.AUTH).single().method,
        )
    }

    @Test
    fun `logout propagates a local removal failure so the user can retry`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.authFake.logoutResult = com.notdisk.core.common.result.CloudResult.Failure(
            CloudError.LocalIoError(message = "could not clear credential"),
        )

        val result = LogoutAccountUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.LocalIoError>()
    }

    // ---------------------------------------------------------------------------------------------
    // GetStorageQuotaUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `storage quota passes partial vendor data through untouched`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        // A vendor that reports only labels and no numbers is a real case; deriving numbers here
        // would hide which values the vendor actually asserted.
        alpha.authFake.quota = StorageQuota(
            totalBytes = null,
            usedBytes = null,
            remainingBytes = null,
            totalLabel = "5TB",
            usedLabel = "1.2TB",
        )
        val useCase = GetStorageQuotaUseCase(registryOf(alpha))

        val quota = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT).requireSuccess()

        assertNull(quota.totalBytes)
        assertNull(quota.usedBytes)
        assertEquals("5TB", quota.totalLabel)
        assertEquals("1.2TB", quota.usedLabel)
    }

    @Test
    fun `storage quota refuses before the network when the provider has no quota endpoint`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            account = caps.account.copy(storageQuota = CapabilitySupport.UNSUPPORTED),
        )
        val useCase = GetStorageQuotaUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(GetStorageQuotaUseCase.CAPABILITY_STORAGE_QUOTA, error.capability)
        assertTrue(alpha.wasNeverCalled(), "the quota request must never leave the device")
    }

    @Test
    fun `storage quota allows an UNVERIFIED declaration through to the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            account = caps.account.copy(storageQuota = CapabilitySupport.UNVERIFIED),
        )
        val useCase = GetStorageQuotaUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(
            result.isSuccess(),
            "UNVERIFIED means 'not yet observed', not 'impossible' — refusing it would lock the " +
                "feature out permanently",
        )
        assertEquals(1, alpha.totalCalls())
    }
}
