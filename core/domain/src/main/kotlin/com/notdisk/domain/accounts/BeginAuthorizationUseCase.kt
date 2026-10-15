package com.notdisk.domain.accounts

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Starts an interactive authorisation flow and returns the challenge the UI must present.
 *
 * ### Why the domain layer mediates this at all
 * Because the *provider* decides which flows it can run, and the UI must not be the thing that
 * decides whether a "Scan QR code" button is legal. A build may ship a provider whose OAuth client
 * id has not been configured yet: [AuthFlowKind.OAUTH_REDIRECT] is then simply absent from
 * `capabilities().account.interactiveFlows`, and pressing the button must produce a typed refusal
 * rather than an HTTP 400 from a vendor that was never asked the right question.
 *
 * The flow therefore is:
 *  1. resolve the provider through the registry (unknown id → [CloudError.Unsupported]);
 *  2. gate on the *advertised* flow set (unadvertised flow → [CloudError.Unsupported] naming
 *     `account.interactiveFlows`, **without** contacting anyone);
 *  3. delegate to [com.notdisk.core.provider.AuthProvider.beginAuthorization], which returns
 *     *data* describing what to render.
 *
 * ### The invariant this protects
 * The domain layer never handles an `Intent`, a WebView redirect, or an Activity result. A
 * challenge is a value ([AuthChallenge]); presenting it is the platform layer's job. That is what
 * keeps this module free of Android and therefore unit-testable with no device.
 */
class BeginAuthorizationUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to authorise against.
     * @param flow which interactive flow to run. Must be advertised by the provider.
     * @param params flow-specific inputs (account/password for [AuthFlowKind.PASSWORD],
     *   client id/redirect for OAuth, a pasted token for [AuthFlowKind.MANUAL_CREDENTIAL]).
     *   Defaults to empty, which is correct for QR/device-code flows.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        flow: AuthFlowKind,
        params: AuthFlowParams = AuthFlowParams.Empty,
    ): CloudResult<AuthChallenge> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            // Gate *before* delegating: a provider that does not advertise this flow would have
            // to fail anyway, and asking it would produce a less specific error message.
            gate.requireFlag(
                available = flow in capabilities.account.interactiveFlows,
                capability = CAPABILITY_INTERACTIVE_FLOWS,
                message = "该网盘不支持此登录方式（$flow）",
            ).flatMap { provider.auth.beginAuthorization(flow, params) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_INTERACTIVE_FLOWS: String = "account.interactiveFlows"
    }
}
