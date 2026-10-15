package com.notdisk.provider.p115.di

import android.content.Context
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.provider.CloudProvider
import com.notdisk.provider.p115.P115CloudProvider
import com.notdisk.provider.p115.P115Configuration
import com.notdisk.provider.p115.auth.P115EncryptedCredentialStore
import com.notdisk.provider.p115.auth.InMemoryP115AccountMetadataStore
import com.notdisk.provider.p115.auth.P115AccountMetadataStore
import com.notdisk.provider.p115.auth.P115CredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Wires the 115 integration.
 *
 * ### Configuration is injected, not hardcoded
 * [P115Configuration] is provided with **no** OAuth client id. That is the honest state: this project
 * has not been issued one, and the brief explicitly forbids fabricating credentials. The consequence
 * is deliberate and visible rather than hidden:
 *
 *  - `capabilities().account.interactiveFlows` contains only `QR_CODE`;
 *  - the accounts UI therefore offers only the QR flow for 115, and it *works* without any developer
 *    credential (see `API_REFERENCE.md` §2.1);
 *  - `P115Configuration.notes` carries a Chinese explanation the UI can display, so the missing
 *    capability is explained instead of silently absent.
 *
 * When a real `app_id` becomes available, the only change needed is the value passed here.
 */
@Module
@InstallIn(SingletonComponent::class)
object P115ProviderModule {

    /**
     * The account-metadata store.
     *
     * Kept in memory for this build. It holds **no credentials** (display name, VIP tier, last
     * verification time), so the security requirement is met either way — but a process restart loses
     * the local mirror of the account list, which the users of this build should know about. Moving it
     * to the Room-backed implementation is a wiring change, not an architecture change, because the
     * interface already exists.
     */
    @Provides
    @Singleton
    fun provideAccountMetadataStore(): P115AccountMetadataStore = InMemoryP115AccountMetadataStore()

    /**
     * The credential store.
     *
     * **Encrypted**, backed by the Android Keystore. This is the one piece that must never be swapped
     * for something simpler: a 115 credential contains a 120-hex long-lived `SEID`
     * (`API_REFERENCE.md` §2.1 Step 4) that grants full account access. See `docs/security.md`.
     */
    @Provides
    @Singleton
    fun provideCredentialStore(
        @ApplicationContext context: Context,
        logger: CloudLogger,
    ): P115CredentialStore = P115EncryptedCredentialStore(context, logger)

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
    fun provideP115Provider(
        logger: CloudLogger,
        credentials: P115CredentialStore,
        accounts: P115AccountMetadataStore,
    ): CloudProvider = P115CloudProvider(
        configuration = P115Configuration(),
        logger = logger,
        credentialStore = credentials,
        accountMetadataStore = accounts,
    )

    /** Surfaced for the settings screen so the OAuth gap is explainable in the UI. */
    @Provides
    @Singleton
    fun provideP115Configuration(): P115Configuration = P115Configuration()
}
