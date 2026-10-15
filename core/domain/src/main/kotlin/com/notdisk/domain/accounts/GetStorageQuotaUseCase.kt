package com.notdisk.domain.accounts

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Reads an account's storage usage.
 *
 * ### Why the domain layer gates this instead of just calling it
 * Storage quota is the canonical "half-supported" vendor feature, and the failure mode is
 * user-visible in a specific way: the settings screen renders a progress bar. A provider that has
 * no quota endpoint at all must be distinguishable from one that has the endpoint but returned
 * nothing, because the first should render no bar at all and the second should render a bar in an
 * indeterminate state. [com.notdisk.core.model.AccountCapabilities.storageQuota] carries that
 * distinction, so the gate refuses the doomed request and the UI branches on the typed error.
 *
 * ### Partial data is normal — do not normalise it away
 * Every field of [StorageQuota] is nullable, and that is a deliberate part of the model rather
 * than sloppiness. Real vendors return:
 *
 *  - only a total and a used byte count (so `remainingBytes` must be *derived*, or left null if
 *    the total is itself unknown);
 *  - only pre-formatted labels ("1.2TB / 5TB") with no numbers;
 *  - a per-category breakdown whose fractions are vendor-reported and occasionally wrong (115
 *    even misspells the field upstream as `pecent` — the model normalises the name, not the value).
 *
 * This use case therefore passes the payload through untouched. **Deriving** values here would
 * hide which numbers the vendor actually asserted, and the UI needs that: a bar drawn from a
 * vendor-reported percentage and a bar drawn from `used/total` must be allowed to disagree, and
 * when they do, the vendor's own number is the one users see on the vendor's website. Callers that
 * want a convenience fraction compute it themselves (see `StorageQuota.usedFractionOrNull` in the
 * accounts feature) so the derivation is visible at the point of use.
 */
class GetStorageQuotaUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration the account belongs to.
     * @param accountId the local account id whose quota should be read.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
    ): CloudResult<StorageQuota> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.account.storageQuota,
                capability = CAPABILITY_STORAGE_QUOTA,
                message = "该网盘不提供容量信息",
            ).flatMap { provider.auth.getStorageQuota(accountId) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_STORAGE_QUOTA: String = "account.storageQuota"
    }
}
