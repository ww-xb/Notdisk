package com.notdisk.provider.baidu.di

import android.content.Context
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.provider.CloudProvider
import com.notdisk.provider.baidu.BaiduCloudProvider
import com.notdisk.provider.baidu.BaiduConfiguration
import com.notdisk.provider.baidu.auth.BaiduAccountMetadataStore
import com.notdisk.provider.baidu.auth.BaiduCredentialStore
import com.notdisk.provider.baidu.auth.BaiduEncryptedCredentialStore
import com.notdisk.provider.baidu.auth.InMemoryBaiduAccountMetadataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Wires the Baidu Netdisk integration.
 *
 * ### Configuration is injected, not hardcoded
 * [BaiduConfiguration] carries no credentials, and that is a factual difference from 115 rather than
 * an omission: the one flow this provider offers — a pasted browser session cookie — needs nothing
 * from the project. Every request identifies itself with the **web client's own public** `app_id`
 * (`250528`), which is not a secret and is not issued per developer.
 *
 * The consequence is visible in the UI: Baidu appears in the accounts screen with exactly one
 * available flow, and that flow works without any developer credential.
 *
 * ### Why the `app_id` is not a configuration field
 * The enterprise (`24029990`) and team-space (`118511220`) product lines have different endpoints,
 * different response shapes and different error tables, and §13.2 item 5 records that **mixing them
 * returns permission errors**. Exposing an `appId` string here would invite exactly that mix-up, so a
 * future enterprise integration is a new provider rather than a new field.
 */
@Module
@InstallIn(SingletonComponent::class)
object BaiduProviderModule {

    /**
     * The account-metadata store.
     *
     * Kept in memory for this build, matching the 115 integration. It holds **no credentials**
     * (display name, membership label, last verification time), so the security requirement is met
     * either way — but a process restart loses the local mirror of the account list, which the users of
     * this build should know about. Moving it to a Room-backed implementation is a wiring change, not
     * an architecture change, because the interface already exists.
     */
    @Provides
    @Singleton
    fun provideAccountMetadataStore(): BaiduAccountMetadataStore = InMemoryBaiduAccountMetadataStore()

    /**
     * The credential store.
     *
     * **Encrypted**, backed by the Android Keystore. This is the one piece that must never be swapped
     * for something simpler: a Baidu credential's `BDUSS` is the account's primary login token and is
     * long-lived, so anyone holding it can read and modify the user's files. See `docs/security.md`.
     */
    @Provides
    @Singleton
    fun provideCredentialStore(
        @ApplicationContext context: Context,
        logger: CloudLogger,
    ): BaiduCredentialStore = BaiduEncryptedCredentialStore(context, logger)

    /**
     * The provider itself.
     *
     * Registered as a `Set<CloudProvider>` element, which is the mechanism that keeps
     * `ProviderRegistryModule` free of any provider-specific import. Adding a cloud is adding a module
     * like this one.
     */
    @Provides
    @Singleton
    @IntoSet
    fun provideBaiduProvider(
        logger: CloudLogger,
        credentials: BaiduCredentialStore,
        accounts: BaiduAccountMetadataStore,
    ): CloudProvider = BaiduCloudProvider(
        configuration = BaiduConfiguration(),
        logger = logger,
        credentialStore = credentials,
        accountMetadataStore = accounts,
    )
}
