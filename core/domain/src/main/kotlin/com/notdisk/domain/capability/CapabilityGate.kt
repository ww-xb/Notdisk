package com.notdisk.domain.capability

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.CapabilitySupport

/**
 * Turns a provider's *declared* capability into a `CloudResult<Unit>` guard.
 *
 * ### Why this type exists
 * Every mutating use case in this module has to answer two questions before it is allowed to
 * touch the network:
 *
 *  1. *Is this operation even possible on this provider?* Sending a request that the vendor is
 *     documented not to implement wastes a round trip, burns rate limit, and — worse — surfaces
 *     to the user as an opaque vendor error instead of the honest "该网盘不支持此操作"
 *     ([CloudError.Unsupported]).
 *  2. *Is the capability verified or merely declared?* [CapabilitySupport] has three states, and
 *     the difference between [CapabilitySupport.UNVERIFIED] and [CapabilitySupport.SUPPORTED] is
 *     exactly the difference between "we hope this works" and "we have seen it work".
 *
 * A `CapabilityGate` is the single, uniform way to ask those questions. It is deliberately a
 * *pure* value object with no provider reference, so a use case can gate on capabilities it read
 * once (`provider.capabilities()`), and so a test can assert the gate behaviour without standing
 * up a provider at all.
 *
 * ### What it is NOT
 * The gate is **not** a substitute for the provider's own honesty. Capabilities are hints, and a
 * declared-supported operation can still fail at runtime (permissions, server policy, file state).
 * The gate exists to reject the operations we *know* are impossible, never to mask real errors:
 * `require` returns the same [CloudResult.Failure] the provider would eventually have produced,
 * just earlier and more cheaply.
 *
 * ### The `UNVERIFIED` policy
 * [CapabilitySupport.UNVERIFIED] means "the vendor documents it but this build has not observed
 * it". Use cases must let those calls through ([require] does) because refusing them would make
 * a working-but-unverified endpoint unreachable forever. The UI is expected to render such entry
 * points disabled or with a warning (see [CapabilitySupport.toUiDescription]); the domain layer
 * only refuses what is [CapabilitySupport.UNSUPPORTED].
 */
class CapabilityGate private constructor() {

    /**
     * Rejects [support] when the provider genuinely does not implement the operation.
     *
     * @param support the declaration read off `CloudCapabilities`.
     * @param capability a stable, human-traceable capability path, e.g. `"operations.rename"`.
     *   It is propagated into [CloudError.Unsupported.capability] so that a failure can be traced
     *   back to the exact declaration that refused it (in logs and in bug reports) without
     *   guessing from the message.
     * @param message user-facing explanation. Defaults to a generic "not supported" string; use
     *   cases whose refusal has a *specific* remedy (e.g. trash purge needing a safe key) should
     *   override it so the UI can tell the user what to do instead.
     */
    fun require(
        support: CapabilitySupport,
        capability: String,
        message: String = DEFAULT_UNSUPPORTED_MESSAGE,
    ): CloudResult<Unit> = when (support) {
        CapabilitySupport.SUPPORTED, CapabilitySupport.UNVERIFIED -> Unit.asSuccessUnit()
        CapabilitySupport.UNSUPPORTED -> CloudResult.Failure(
            CloudError.Unsupported(message = message, capability = capability),
        )
    }

    /**
     * Rejects [support] unless it is *verified* supported.
     *
     * Use this for the rare operation where a half-working attempt is worse than a refusal — for
     * example a permanent delete, where a partially applied request cannot be undone. For
     * ordinary browsing/mutation, prefer [require], which does not lock out unverified endpoints.
     */
    fun requireVerified(
        support: CapabilitySupport,
        capability: String,
        message: String = DEFAULT_UNSUPPORTED_MESSAGE,
    ): CloudResult<Unit> = when (support) {
        CapabilitySupport.SUPPORTED -> Unit.asSuccessUnit()
        CapabilitySupport.UNVERIFIED, CapabilitySupport.UNSUPPORTED -> CloudResult.Failure(
            CloudError.Unsupported(message = message, capability = capability),
        )
    }

    /**
     * Rejects when [available] is `false`.
     *
     * For the plain `Boolean` flags on `CloudCapabilities` that are not [CapabilitySupport]
     * (`separateAudioTracks`, `urlExpires`, `requiresRequestHeaders`, …). Unlike those flags, a
     * boolean carries no "unverified" state, so the caller must have chosen a sensible default in
     * the model; this helper simply refuses `false`.
     */
    fun requireFlag(
        available: Boolean,
        capability: String,
        message: String = DEFAULT_UNSUPPORTED_MESSAGE,
    ): CloudResult<Unit> = if (available) {
        Unit.asSuccessUnit()
    } else {
        CloudResult.Failure(CloudError.Unsupported(message = message, capability = capability))
    }

    companion object {
        const val DEFAULT_UNSUPPORTED_MESSAGE: String = "该网盘不支持此操作"

        /** The shared, stateless gate instance. Gates hold no configuration, so one is enough. */
        val Default: CapabilityGate = CapabilityGate()

        /** Named accessor mirroring `CapabilityGate.Default` for call-site readability. */
        val instance: CapabilityGate get() = Default
    }
}

/** `CloudResult.Success(Unit)`, spelled once so the gate reads as a decision table. */
private fun Unit.asSuccessUnit(): CloudResult<Unit> = CloudResult.Success(Unit)

/**
 * `gate.require(...)` in extension form, for call sites that read better that way:
 * `caps.operations.rename.require("operations.rename")`.
 */
fun CapabilitySupport.requireSupported(
    capability: String,
    message: String = CapabilityGate.DEFAULT_UNSUPPORTED_MESSAGE,
    gate: CapabilityGate = CapabilityGate.Default,
): CloudResult<Unit> = gate.require(this, capability, message)

/**
 * UI-facing description of a declaration.
 *
 * ### Why the domain layer owns this string
 * The three-state capability model is the *only* place in the app that knows whether a feature
 * has actually been exercised against a live vendor API. If every screen invented its own
 * wording, users would see "supported" on one screen and "unknown" on another for the same
 * endpoint. Centralising the mapping keeps the honesty consistent, and keeps the states
 * (supported / unverified / unsupported) distinguishable in the UI rather than collapsing them
 * into a boolean.
 *
 * The strings are deliberately Chinese to match the rest of the user-facing surface
 * (see `CloudError.userFacingMessage`).
 */
fun CapabilitySupport.toUiDescription(): String = when (this) {
    CapabilitySupport.SUPPORTED -> "已支持"
    CapabilitySupport.UNVERIFIED -> "未验证，可能不可用"
    CapabilitySupport.UNSUPPORTED -> "不支持"
}

/**
 * `true` when the feature should be offered to the user at all.
 *
 * [CapabilitySupport.UNVERIFIED] features are *offerable but not promised*: hiding them would
 * make an unverified endpoint permanently unreachable, while promising them would lie. Callers
 * pair this with [toUiDescription] to render the caveat.
 */
val CapabilitySupport.isOfferableToUser: Boolean
    get() = this != CapabilitySupport.UNSUPPORTED
