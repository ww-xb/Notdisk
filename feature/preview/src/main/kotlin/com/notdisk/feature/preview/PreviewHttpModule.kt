package com.notdisk.feature.preview

import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.network.HttpClientFactory
import com.notdisk.core.network.NetworkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Provides the HTTP client the previewer uses to read a text body.
 *
 * ### Why this module exists at all
 * `core:network` builds the shared [OkHttpClient] through [HttpClientFactory] but deliberately exposes
 * no Hilt binding for it: only providers and the transfer manager need one, and they construct theirs
 * through the factory with the configuration their surface requires. The previewer is the first
 * non-provider consumer that needs a *bare* client — it has no cookies, no account and no vendor
 * session, and it must not inherit one, because a signed preview URL is self-authorising and the
 * request must carry exactly the headers the response's `PreviewResource` specifies.
 *
 * ### Why it is the network module's factory rather than `OkHttpClient()`
 * Using the factory keeps the app's shared timeouts, connection pool policy and logging posture in one
 * place. A bare constructor here would give the previewer a second, differently-configured HTTP stack
 * whose timeouts no one would think to change when the shared ones are tuned.
 *
 * `enableLogging = false` is deliberate double cover: the URLs this client fetches are signed, and an
 * OkHttp logging interceptor prints full URLs including query strings. The configured logger in this
 * app redacts, but the safest setting is the one that never produces the line at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreviewHttpModule {

    @Provides
    @Singleton
    fun providePreviewOkHttpClient(logger: CloudLogger): OkHttpClient =
        HttpClientFactory(
            config = NetworkConfig(
                // Signed preview URLs are short-lived, so a long timeout buys nothing and only
                // delays the error the user needs to see. The body read is additionally bounded by
                // PreviewViewModel's byte cap, which is what actually protects against a huge file.
                connectTimeoutMs = 15_000L,
                readTimeoutMs = 30_000L,
                writeTimeoutMs = 30_000L,
                callTimeoutMs = 60_000L,
                enableLogging = false,
            ),
            logger = logger,
        ).create()
}
