package com.notdisk.core.data.di

import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.DefaultProviderRegistry
import com.notdisk.core.provider.ProviderDescriptor
import com.notdisk.core.provider.ProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Assembles the provider registry from a Hilt multibinding set.
 *
 * ### Why this shape, and why it is the architecture's keystone
 * Each provider module contributes a `@Binds @IntoSet CloudProvider` entry in its own DI module. This
 * class never names a provider, so:
 *
 *  - **adding a cloud means adding a module**, not editing this one;
 *  - there is no `when (providerId)` anywhere in the app — the registry is the only lookup path;
 *  - a build variant can drop a provider (e.g. an offline-only build) by excluding its DI module, and
 *    nothing here changes.
 *
 * If this file ever needs to import a specific provider to make something work, that is the signal
 * that a responsibility has leaked into the wrong layer.
 *
 * ### Descriptor overrides
 * [descriptorOverrides] exists because a provider cannot always describe itself accurately from
 * inside its own module: whether OAuth is available depends on configuration the app owns, and whether
 * a provider is "development only" is a build-flavour decision. Overrides are keyed by `ProviderId`
 * so they stay declarative.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderRegistryModule {

    @Provides
    @Singleton
    fun provideProviderRegistry(
        providers: Set<@JvmSuppressWildcards CloudProvider>,
        logger: CloudLogger,
    ): ProviderRegistry {
        // A LinkedHashSet from Hilt has no guaranteed order, so the registry is built from a
        // deterministically sorted list. The UI renders providers in registration order, and an
        // arbitrary order that changes between runs would make screenshots and tests unstable.
        val ordered = providers.sortedBy { it.id.value }

        val registry = DefaultProviderRegistry()
        ordered.forEach { provider ->
            registry.register(provider, descriptorFor(provider))
            logger.i(
                TAG,
                "registered provider '${provider.id}' (${provider.displayName})",
            )
        }

        if (ordered.isEmpty()) {
            // Not fatal: an offline-only build legitimately has none, and the UI renders an empty
            // state. But it is worth a warning, because it is usually a missing DI module.
            logger.w(TAG, "no cloud providers are registered; the accounts screen will be empty")
        }

        return registry
    }

    private const val TAG = "ProviderRegistry"

    /**
     * Builds the UI-facing descriptor from the provider's own declaration.
     *
     * Everything comes from `capabilities()`, which is required to be cheap and side-effect free, so
     * this is safe to call during dependency-graph construction.
     */
    private fun descriptorFor(provider: CloudProvider): ProviderDescriptor {
        val capabilities = provider.capabilities()
        return ProviderDescriptor(
            id = provider.id,
            displayName = provider.displayName,
            description = describe(provider),
            availableAuthFlows = capabilities.account.interactiveFlows,
            configurationNotes = emptyList(),
            isDevelopmentOnly = isDevelopmentProvider(provider),
        )
    }

    /**
     * A one-line summary for the provider tile.
     *
     * Derived from declared capabilities rather than hardcoded, so a provider that gains upload support
     * updates its own description with no change here.
     */
    private fun describe(provider: CloudProvider): String {
        val capabilities = provider.capabilities()
        val fileSystem = capabilities.fileSystem

        val parts = buildList {
            if (fileSystem.listChildren.isSupported) add("文件浏览")
            if (fileSystem.search.isSupported) add("搜索")
            if (capabilities.media.videoStreaming.isSupported) add("在线播放")
            if (capabilities.preview.image.isSupported) add("图片预览")
            if (capabilities.operations.upload.supported.isSupported) add("上传")
        }

        return if (parts.isEmpty()) {
            "尚未验证任何功能"
        } else {
            parts.joinToString(" · ")
        }
    }

    /**
     * Whether a provider exists only for development.
     *
     * Detected by its declared id rather than by a type check, so this module still never imports a
     * concrete provider. The convention is documented here and in `docs/provider-contract.md`: a
     * development-only provider uses the `mock` id.
     */
    private fun isDevelopmentProvider(provider: CloudProvider): Boolean =
        provider.id.value == DEVELOPMENT_PROVIDER_ID

    private const val DEVELOPMENT_PROVIDER_ID = "mock"
}
