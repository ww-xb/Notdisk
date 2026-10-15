package com.notdisk.core.provider

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.map
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.ProviderId

/**
 * The single lookup point for provider implementations.
 *
 * The domain layer asks the registry for a provider by id; it never constructs one and never
 * branches on a concrete type. Adding a vendor therefore means registering a new
 * [CloudProvider] — no `when (providerId)` anywhere in the app.
 *
 * ### Registration timing
 * Providers are registered during dependency-graph construction (a Hilt multibinding set in the
 * production app, a plain constructor list in tests). [register] exists for that bootstrap step;
 * afterwards the registry is read-only from the domain layer's point of view.
 */
interface ProviderRegistry {

    /** Every registered provider, in a stable order (registration order). */
    fun all(): List<CloudProvider>

    /** Metadata for every registered provider, without touching the implementations. */
    fun descriptors(): List<ProviderDescriptor>

    /** The provider with [id], or `null` when no such provider is registered. */
    fun find(id: ProviderId): CloudProvider?

    /**
     * The provider with [id], or `CloudError.Unsupported` when it is not registered.
     *
     * Use this in domain code so a missing/disabled provider degrades into a normal typed error
     * instead of a crash (e.g. an account row left behind after a provider was removed from a
     * build variant).
     */
    fun require(id: ProviderId): CloudResult<CloudProvider> =
        find(id)?.let { CloudResult.Success(it) }
            ?: CloudResult.Failure(
                CloudError.Unsupported(
                    message = "未注册的网盘 Provider: $id",
                    capability = "provider:$id",
                ),
            )

    /** Capabilities of the provider with [id], or a typed failure when it is not registered. */
    fun capabilitiesOf(id: ProviderId): CloudResult<CloudCapabilities> =
        require(id).map { provider -> provider.capabilities() }
}

/**
 * Lightweight, UI-facing description of a provider.
 *
 * The "add account" screen needs to render provider tiles and the flows each one offers. Doing
 * that through [ProviderDescriptor] means the UI can list providers without instantiating
 * anything heavy (a real provider may build HTTP clients on construction).
 */
data class ProviderDescriptor(
    val id: ProviderId,
    val displayName: String,
    /** Short line shown under the provider name. */
    val description: String,
    /** Interactive flows this provider can run right now. Empty = cannot be bound from the UI. */
    val availableAuthFlows: Set<com.notdisk.core.model.AuthFlowKind>,
    /**
     * Human-readable notes about configuration the build still needs before the provider can be
     * used (e.g. an OAuth client id). Shown verbatim in the UI so the limitation is visible
     * rather than hidden behind a failing button. Empty when nothing is required.
     */
    val configurationNotes: List<String> = emptyList(),
    /**
     * `true` for a provider that exists only for development.
     *
     * No such provider ships in this build — the offline demo provider was removed so that a build
     * cannot present fabricated accounts as real ones — but the flag stays meaningful for a future
     * developer-only integration, and the UI renders a badge when it is set.
     */
    val isDevelopmentOnly: Boolean = false,
)

/**
 * Default [ProviderRegistry] implementation.
 *
 * Deliberately a plain class (not an object): tests build their own instance containing only the
 * providers under test, so contract tests cannot accidentally pass because of a real provider.
 */
class DefaultProviderRegistry(
    providers: List<CloudProvider> = emptyList(),
) : ProviderRegistry {

    private val lock = Any()
    private val byId = LinkedHashMap<ProviderId, CloudProvider>()
    private val descriptors = LinkedHashMap<ProviderId, ProviderDescriptor>()

    init {
        providers.forEach { register(it) }
    }

    /**
     * Registers [provider]. Re-registering the same id replaces the previous entry, which is what
     * a test wants when it swaps in a double.
     */
    fun register(provider: CloudProvider, descriptor: ProviderDescriptor? = null) {
        synchronized(lock) {
            byId[provider.id] = provider
            descriptors[provider.id] = descriptor ?: descriptorFor(provider)
        }
    }

    /** Builds a default descriptor from the provider's own declaration. */
    private fun descriptorFor(provider: CloudProvider): ProviderDescriptor {
        val capabilities = provider.capabilities()
        return ProviderDescriptor(
            id = provider.id,
            displayName = provider.displayName,
            description = "",
            availableAuthFlows = capabilities.account.interactiveFlows,
            isDevelopmentOnly = false,
        )
    }

    override fun all(): List<CloudProvider> = synchronized(lock) { byId.values.toList() }

    override fun descriptors(): List<ProviderDescriptor> =
        synchronized(lock) { descriptors.values.toList() }

    override fun find(id: ProviderId): CloudProvider? = synchronized(lock) { byId[id] }
}
