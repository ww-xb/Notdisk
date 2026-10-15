package com.notdisk.feature.player

import com.notdisk.core.data.media.MediaPlaybackCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the player's [MediaResolver] seam to the shared [MediaPlaybackCoordinator].
 *
 * ### Why this lives in `feature:player` and not in `core:data`
 * The coordinator is what `core:data` owns and exports; the *narrow interface* is a player concern,
 * born of the player's need to be unit-testable and to state a minimal dependency. Putting the
 * adapter next to its only consumer keeps `core:data` from growing an interface whose sole purpose
 * is to serve one screen, and it keeps the seam visible to whoever edits the player next.
 *
 * The provider binding is deliberately `@Singleton` and takes the coordinator as a parameter: Hilt
 * hands over the same singleton instance the rest of the app uses, so the player shares the
 * coordinator's resolution cache and its per-file refresh lock. Constructing a private coordinator
 * here would silently give the player its own cache and break the "one refresh in flight per file"
 * guarantee.
 *
 * This module knows exactly one provider-facing type — the coordinator — and imports no vendor
 * module. See the module invariant at the top of `PlayerScreen.kt`.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaResolverModule {

    @Provides
    @Singleton
    fun provideMediaResolver(coordinator: MediaPlaybackCoordinator): MediaResolver =
        CoordinatorMediaResolver(coordinator)
}
