package com.notdisk.domain.accounts

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Polls an in-flight interactive authorisation challenge.
 *
 * ### Why polling is a use case rather than a `while` loop in the ViewModel
 * The loop itself is trivial; what is not trivial is *where the state lives*. The challenge id is
 * issued by the provider and the provider is the only thing that knows whether the user has
 * scanned the QR code, so the poll must be routed back through the registry by
 * ([ProviderId], challengeId) — the same way it was started. Putting the loop in the UI is how
 * apps end up with a poll that survives configuration changes, leaks a coroutine on every
 * recomposition, and keeps hammering a vendor after the user navigated away.
 *
 * Keeping [invoke] a *single* poll (not a loop) is deliberate: the caller owns the cadence, and
 * that is what makes the flow testable with `runTest`/`advanceTimeBy` instead of real delay. The
 * accounts feature schedules the cadence; this use case guarantees that one call means one
 * provider call and that the state is returned verbatim.
 *
 * ### Invariants
 *  - The provider contract requires `pollChallenge` to degrade to [AuthChallengeState.Failed]
 *    rather than throwing when the challenge expired, so a long-abandoned QR code surfaces as a
 *    normal terminal state, not a crash. This use case passes that through unchanged; it never
 *    rewrites a `Failed` into a `Pending`, because "keep waiting" after the vendor said no is how
 *    a spinner becomes a hang.
 *  - Provider absence is a typed [com.notdisk.core.common.error.CloudError.Unsupported], not an
 *    exception (e.g. the app was restarted and the provider removed from the build).
 */
class PollAuthorizationUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration that issued [challengeId].
     * @param challengeId the id carried by the [com.notdisk.core.provider.AuthChallenge] that
     *   [BeginAuthorizationUseCase] returned.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        challengeId: String,
    ): CloudResult<AuthChallengeState> =
        registry.withProvider(providerId) { provider -> provider.auth.pollChallenge(challengeId) }
}
