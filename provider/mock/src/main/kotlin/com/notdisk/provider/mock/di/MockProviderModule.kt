package com.notdisk.provider.mock.di

import com.notdisk.core.provider.CloudProvider
import com.notdisk.provider.mock.MockCloudProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Contributes the offline provider to the registry.
 *
 * ### Why the Mock provider is in the production graph
 * It is not a test double here — it is a **real, first-class provider** that happens to have no
 * network. Shipping it means:
 *  - the whole application (accounts, browsing, playback, preview, transfers) can be exercised on a
 *    device with no account and no connectivity, which is how the UI is developed and demonstrated;
 *  - it is a second independent implementation of the same contract, so the shared contract suite
 *    has something to compare the real provider against.
 *
 * It is registered with `isDevelopmentOnly = true` (derived from its id in
 * `ProviderRegistryModule`), so the UI can badge it and a release build can exclude this module
 * without touching any other code. That exclusion path is the reason the registration lives in a
 * module rather than in `app`.
 */
@Module
@InstallIn(SingletonComponent::class)
object MockProviderModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideMockProvider(): CloudProvider = MockCloudProvider(seedDemoAccount = true)
}
