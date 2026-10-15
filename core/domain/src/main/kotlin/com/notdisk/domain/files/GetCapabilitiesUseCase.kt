package com.notdisk.domain.files

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Reads a provider's honest capability declaration.
 *
 * ### Why this is the most important use case in the module
 * Every other use case here exists to *perform* something; this one exists to let the app decide
 * what it is allowed to offer in the first place. It is the concrete answer to the design rule
 * "if you feel the need to special-case a vendor, express it as a capability check" — the app never
 * asks *which* provider it is talking to, only *what that provider can do*. Add a vendor, register
 * it, and every screen reconfigures itself with no edits.
 *
 * ### Three states, and why collapsing them would be a lie
 * [com.notdisk.core.model.CapabilitySupport] distinguishes:
 *
 *  - `SUPPORTED` — verified against the live API. Offer it, promise it.
 *  - `UNVERIFIED` — the vendor documents it, this build has not observed it. The UI must be
 *    conservative: hide the entry point or show it disabled *with an explanation*. This state is
 *    the whole reason the type is an enum and not a `Boolean`; [CloudCapabilities] exists so that
 *    "we think this works" is representable without being asserted.
 *  - `UNSUPPORTED` — do not offer it at all.
 *
 * A client that treats `UNVERIFIED` as `SUPPORTED` ships a button that fails on some accounts and
 * not others, which is the hardest kind of bug for a user to report. A client that treats it as
 * `UNSUPPORTED` permanently locks out a feature that may already work. This use case hands the
 * caller the three-state value untouched so that decision can be made honestly at the point where
 * the UI knows how to phrase it — see `CapabilitySupport.toUiDescription()` in
 * [com.notdisk.domain.capability] for the shared wording.
 *
 * ### Cheap by contract
 * [com.notdisk.core.provider.CloudProvider.capabilities] is required to be side-effect free and
 * to make no network call, because it is read on every composition that decides which affordances
 * to render. This use case therefore performs no caching of its own: the provider owns that, and a
 * second cache layer here would only create a way for the two to disagree after an app upgrade
 * changes a declaration.
 *
 * ### Unknown provider
 * An unregistered [ProviderId] yields [com.notdisk.core.common.error.CloudError.Unsupported]
 * naming the id, never a crash. That case is real: an account row persisted by a build that shipped
 * a provider the current variant does not include must render as "该网盘不可用", not take down the
 * accounts screen.
 */
class GetCapabilitiesUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration whose declaration is wanted.
     */
    operator fun invoke(providerId: ProviderId): CloudResult<CloudCapabilities> =
        registry.withProvider(providerId) { provider -> CloudResult.Success(provider.capabilities()) }
}
