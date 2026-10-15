package com.notdisk.domain.internal

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.ProviderRegistry

/**
 * Provider-resolution plumbing shared by every use case in this module.
 *
 * ### Why this exists rather than `when (providerId)`
 * The single most important structural rule of `core:domain` is that **no use case may branch on
 * a concrete vendor**. Branching is how a "unified" cloud-drive client rots: one `if (id == P115)`
 * becomes a dozen, the vendor's quirks leak into every screen, and adding provider number three
 * means editing forty files. Everything vendor-specific therefore goes through exactly one door —
 * [ProviderRegistry] — and this file makes that door convenient enough that nobody is tempted to
 * go around it:
 *
 *  - the registry itself already degrades a missing provider into a typed
 *    [CloudError.Unsupported] rather than a crash (so an account row left behind after a provider
 *    was compiled out of a build variant still renders);
 *  - [withProvider] threads that failure through without a `when` at each of the ~35 call sites;
 *  - [capabilitiesOfOrNull] lets a use case read a declaration without materialising a provider
 *    call, which is what makes "reject before the network" possible.
 *
 * Vendor differences are expressed as *capability* differences, read from
 * [CloudCapabilities] — never as identity comparisons.
 */

/**
 * Runs [block] with the provider registered under [providerId].
 *
 * Short-circuits with the registry's own typed failure (an [CloudError.Unsupported] naming the
 * missing provider id) when the provider is not registered, so an unregistered provider is a
 * normal, renderable error rather than an exception.
 */
internal inline fun <T> ProviderRegistry.withProvider(
    providerId: ProviderId,
    block: (CloudProvider) -> CloudResult<T>,
): CloudResult<T> = when (val resolved = require(providerId)) {
    is CloudResult.Success -> block(resolved.data)
    is CloudResult.Failure -> resolved
}

/**
 * Resolves the provider **and** reads its capability declaration in one step.
 *
 * Capability checks and provider resolution always happen together in the mutating use cases
 * ("can this provider do it, and if so, who do I ask?"), and doing them separately invites the
 * bug where a use case resolves the provider, forgets the gate, and sends a request the vendor
 * does not implement. Fusing them makes the guarded form the path of least resistance.
 *
 * [CloudProvider.capabilities] is contracted to be cheap and side-effect free, so reading it on
 * every call is intentional and safe.
 */
internal inline fun <T> ProviderRegistry.withProviderAndCapabilities(
    providerId: ProviderId,
    block: (CloudProvider, CloudCapabilities) -> CloudResult<T>,
): CloudResult<T> = withProvider(providerId) { provider -> block(provider, provider.capabilities()) }

/**
 * Reads the capability declaration for [providerId], or `null` when the provider is absent.
 *
 * Callers that must *report* the absence use [withProvider] instead; callers that only want to
 * avoid an obviously doomed request treat `null` as "unknown, let the provider decide".
 */
internal fun ProviderRegistry.capabilitiesOrNull(providerId: ProviderId): CloudCapabilities? =
    find(providerId)?.capabilities()

/**
 * Wraps a per-item [CloudFileKey] as a batch failure entry.
 *
 * Batch operations report per-item outcomes rather than a blanket boolean, because a vendor can
 * legitimately apply 3 of 5 items (name collision, permission, moderation). When the *domain*
 * layer refuses an item before it reaches the provider, that refusal is reported through the same
 * channel so the UI has one shape to render.
 */
internal fun CloudFileKey.asBatchFailure(error: CloudError) =
    com.notdisk.core.provider.BatchItemFailure(key = this, error = error)

/** Fully-qualified key for one file, so batch results are safe to persist and to log. */
internal fun keyOf(providerId: ProviderId, accountId: AccountId, fileId: FileId): CloudFileKey =
    CloudFileKey(providerId = providerId, accountId = accountId, fileId = fileId)

/** `CloudResult.Success(Unit)`, so validation helpers return a uniform type. */
internal fun unitSuccess(): CloudResult<Unit> = CloudResult.Success(Unit)

/** Type alias kept short: validation helpers all return `CloudResult<Unit>`. */
internal typealias Validation = CloudResult<Unit>

/**
 * Builds an [CloudError.InvalidArgument] carrying the *offending field name*.
 *
 * The field name matters: these errors surface directly in toasts next to the input the user just
 * typed, and "参数不合法" without a field sends the user hunting. [vendorCode] stays `null`
 * because nothing left the device — this is a local refusal, and marking it as such keeps
 * post-mortems from chasing a request that was never sent.
 */
internal fun invalidArgument(field: String, reason: String): CloudResult<Nothing> =
    CloudResult.Failure(
        CloudError.InvalidArgument(message = "$field $reason"),
    )
