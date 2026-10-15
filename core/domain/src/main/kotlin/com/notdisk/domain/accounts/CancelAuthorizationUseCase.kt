package com.notdisk.domain.accounts

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Abandons an in-flight authorisation challenge and releases whatever the provider created for it.
 *
 * ### Why cancelling needs a use case of its own
 * The interesting failure mode is not "the user pressed Back" — it is that a started QR-code flow
 * has already asked the *vendor* for a token, and that token stays valid (and pollable) until it
 * expires. A client that simply stops polling leaves that dangling: on the vendor's side the
 * session is still "awaiting scan", and if the user later scans a stale QR code from a screenshot,
 * they can bind an account the app has already forgotten about.
 *
 * Routing cancellation through the provider keeps the vendor-side cleanup where it belongs. The
 * provider is required to make this idempotent, so the UI may call it from `onCleared()`,
 * `DisposableEffect` teardown, and an explicit "cancel" button without coordinating.
 *
 * Cancellation is deliberately *not* fatal: a failure to release server-side state must not block
 * the user from leaving the screen, so callers typically log and ignore the result. The result is
 * still propagated rather than swallowed here, because a provider that cannot reach the vendor may
 * be a signal worth showing.
 */
class CancelAuthorizationUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration that started the flow.
     * @param challengeId the challenge to abandon. Unknown/expired ids must succeed silently
     *   (provider contract: idempotent), so "cancel twice" is never an error the UI must handle.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        challengeId: String,
    ): CloudResult<Unit> =
        registry.withProvider(providerId) { provider -> provider.auth.cancelChallenge(challengeId) }
}
