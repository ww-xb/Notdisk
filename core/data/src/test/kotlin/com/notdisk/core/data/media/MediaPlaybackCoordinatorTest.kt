package com.notdisk.core.data.media

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.MediaResource
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.DefaultProviderRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MediaPlaybackCoordinator].
 *
 * These exist because the coordinator is the **only** thing standing between the player and provider
 * knowledge, and because its two hardest properties are invisible in normal use:
 *
 *  1. a refresh must actually re-ask the provider (a "refresh" served from cache would leave the
 *     player holding the very URL that just failed);
 *  2. a resource that the provider cannot refresh must be reported as *non-refreshable*, so the UI can
 *     offer a download instead of a retry button that can never succeed.
 *
 * Both are asserted against a hand-written fake provider rather than a mock, so the tests exercise the
 * real `ProviderRegistry` path — including the `Unsupported` error for an unregistered provider.
 */
class MediaPlaybackCoordinatorTest {

    private val accountId = AccountId("acct-1")
    private val fileId = FileId("file-1")


    private fun resource(
        url: String = "https://cdn.example/video.m3u8?t=1",
        expiresAt: Long? = 10_000_000L,
        refreshable: Boolean = true,
        quality: String? = "1080p",
        key: CloudFileKey = CloudFileKey(FakeProviders.ID, accountId, fileId),
    ) = MediaResource(
        url = url,
        protocol = MediaProtocol.HLS,
        expiresAt = expiresAt,
        refreshable = refreshable,
        refreshToken = if (refreshable) {
            com.notdisk.core.model.MediaRefreshToken(key = key, qualityHint = quality)
        } else {
            null
        },
    )

    private fun coordinator(
        media: FakeMedia,
        now: () -> Long = { 0L },
    ): MediaPlaybackCoordinator {
        val provider = FakeProviders.withMedia(media)
        val registry = DefaultProviderRegistry(listOf(provider))
        return MediaPlaybackCoordinator(registry = registry, logger = NoOpCloudLogger, clock = now)
    }

    // ------------------------------------------------------------------ resolve

    @Test
    fun `resolve asks the provider and returns its resource`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource()) })
        val coordinator = coordinator(media)

        val result = coordinator.resolve(accountId, FakeProviders.ID, fileId)

        assertNotNull(result.getOrNull())
        assertEquals("https://cdn.example/video.m3u8?t=1", result.getOrNull()?.url)
        assertEquals(1, media.resolveCalls)
    }

    @Test
    fun `an unknown provider is a typed Unsupported rather than a crash`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource()) })
        val coordinator = coordinator(media)

        val result = coordinator.resolve(
            accountId,
            com.notdisk.core.model.ProviderId("not-registered"),
            fileId,
        )

        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.Unsupported)
        assertEquals(0, media.resolveCalls, "an unregistered provider must not be contacted")
    }

    @Test
    fun `a second resolve within the validity window is served from cache`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource(expiresAt = 10_000_000L)) })
        val coordinator = coordinator(media, now = { 1_000L })

        coordinator.resolve(accountId, FakeProviders.ID, fileId)
        coordinator.resolve(accountId, FakeProviders.ID, fileId)

        assertEquals(1, media.resolveCalls, "a still-valid resource must not be re-fetched")
    }

    @Test
    fun `forceRefresh re-asks the provider even when the cache is valid`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource(expiresAt = 10_000_000L)) })
        val coordinator = coordinator(media, now = { 1_000L })

        coordinator.resolve(accountId, FakeProviders.ID, fileId)
        coordinator.resolve(accountId, FakeProviders.ID, fileId, forceRefresh = true)

        assertEquals(2, media.resolveCalls)
    }

    @Test
    fun `a different quality hint is not served from the cache`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource(quality = "1080p")) })
        val coordinator = coordinator(media, now = { 1_000L })

        coordinator.resolve(accountId, FakeProviders.ID, fileId, qualityHint = "1080p")
        coordinator.resolve(accountId, FakeProviders.ID, fileId, qualityHint = "720p")

        assertEquals(2, media.resolveCalls, "switching quality must take effect, not hit the cache")
        assertEquals("720p", media.lastQualityHint)
    }

    // ------------------------------------------------------------------ proactive refresh

    @Test
    fun `a resource inside the refresh skew is refreshed proactively`() = runTest {
        val expiry = 1_000_000L
        val media = FakeMedia({ CloudResult.Success(resource(expiresAt = expiry)) })

        // `now` sits inside REFRESH_SKEW_MILLIS of the expiry, so the coordinator must consider the
        // resource as needing a refresh rather than letting playback run into a dead URL.
        val coordinator = coordinator(
            media,
            now = { expiry - MediaPlaybackCoordinator.REFRESH_SKEW_MILLIS / 2 },
        )

        coordinator.resolve(accountId, FakeProviders.ID, fileId)
        // The second call must NOT be served from the cache: the cached entry is inside the skew.
        coordinator.resolve(accountId, FakeProviders.ID, fileId)

        assertEquals(
            2,
            media.resolveCalls,
            "a resource within the skew must be treated as stale so playback never hits a dead URL",
        )
    }

    @Test
    fun `shouldRefreshProactively respects the injected clock`() {
        val expiry = 500_000L
        val coordinator = coordinator(
            FakeMedia({ CloudResult.Success(resource()) }),
            now = { 0L },
        )
        val res = resource(expiresAt = expiry)

        assertTrue(!coordinator.shouldRefreshProactively(res, nowMillis = 0L))
        assertTrue(
            coordinator.shouldRefreshProactively(
                res,
                nowMillis = expiry - MediaPlaybackCoordinator.REFRESH_SKEW_MILLIS + 1,
            ),
        )
        assertTrue(coordinator.shouldRefreshProactively(res, nowMillis = expiry + 1))
    }

    // ------------------------------------------------------------------ refresh

    @Test
    fun `refresh re-asks the provider rather than returning the failed url`() = runTest {
        var callIndex = 0
        val media = FakeMedia({
            callIndex++
            CloudResult.Success(resource(url = "https://cdn.example/video.m3u8?t=$callIndex"))
        })
        val coordinator = coordinator(media, now = { 1_000L })

        val first = coordinator.resolve(accountId, FakeProviders.ID, fileId).getOrNull()!!
        val refreshed = coordinator.refresh(first).getOrNull()

        assertNotNull(refreshed)
        assertEquals(
            "https://cdn.example/video.m3u8?t=2",
            refreshed.url,
            "a refresh must produce a genuinely new URL, never the one that just failed",
        )
        assertEquals(2, media.resolveCalls)
    }

    @Test
    fun `a resource without a refresh token reports a non refreshable expiry`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource()) })
        val coordinator = coordinator(media)

        val result = coordinator.refresh(resource(refreshable = false))

        assertTrue(result is CloudResult.Failure)
        val error = (result as CloudResult.Failure).error
        assertTrue(error is CloudError.ResourceUrlExpired, "got $error")
        assertTrue(
            !error.refreshable,
            "the UI needs refreshable=false to offer a download instead of an impossible retry",
        )
        assertEquals(0, media.resolveCalls, "an unrefreshable resource must not be probed")
    }

    @Test
    fun `a provider that cannot refresh is not probed`() = runTest {
        val media = FakeMedia { CloudResult.Success(resource()) }
            .apply { urlRefresh = CapabilitySupport.UNSUPPORTED }
        val coordinator = coordinator(media)

        val result = coordinator.refresh(resource())

        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.ResourceUrlExpired)
        assertTrue(
            !(result as CloudResult.Failure).error.let { (it as CloudError.ResourceUrlExpired).refreshable },
        )
        assertEquals(
            0,
            media.resolveCalls,
            "a provider declaring UNSUPPORTED refresh must not be asked: the answer is known",
        )
    }

    @Test
    fun `a refresh preserves the quality hint from the original resource`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource()) })
        val coordinator = coordinator(media, now = { 1_000L })

        val original = resource(quality = "4k")
        coordinator.refresh(original)

        assertEquals("4k", media.lastQualityHint, "a refresh must keep the user's chosen quality")
    }

    // ------------------------------------------------------------------ auth propagation

    @Test
    fun `an expired token during resolve is reported as TokenExpired`() = runTest {
        val media = FakeMedia({
            CloudResult.Failure(
                CloudError.TokenExpired(message = "授权已失效", canRefresh = false),
            )
        })
        val coordinator = coordinator(media)

        val result = coordinator.resolve(accountId, FakeProviders.ID, fileId)

        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.TokenExpired)
    }

    @Test
    fun `a failed resolve does not leave a stale resource cached`() = runTest {
        var shouldFail = false
        val media = FakeMedia({
            if (shouldFail) {
                CloudResult.Failure(CloudError.NotFound(message = "文件已删除"))
            } else {
                CloudResult.Success(resource(expiresAt = 10_000_000L))
            }
        })
        val coordinator = coordinator(media, now = { 1_000L })

        coordinator.resolve(accountId, FakeProviders.ID, fileId)
        shouldFail = true
        val failed = coordinator.resolve(accountId, FakeProviders.ID, fileId, forceRefresh = true)
        assertTrue(failed is CloudResult.Failure)

        // A third call must go back to the provider (the previous failure invalidated the cache),
        // rather than serving the URL the provider just refused to re-issue.
        shouldFail = false
        coordinator.resolve(accountId, FakeProviders.ID, fileId)
        assertEquals(3, media.resolveCalls)
    }

    // ------------------------------------------------------------------ progress

    @Test
    fun `progress reporting passes through and does not fake success`() = runTest {
        // 115 has no documented progress endpoint, so the fake mirrors that: `Unsupported`.
        val provider = FakeProviders.withMedia(
            FakeMedia({ CloudResult.Success(resource()) }),
            reportProgress = { _, _ ->
                CloudResult.Failure(
                    CloudError.Unsupported(
                        message = "未实现播放进度上报",
                        capability = "media.playbackProgress",
                    ),
                )
            },
        )
        val coordinator = MediaPlaybackCoordinator(
            registry = DefaultProviderRegistry(listOf(provider)),
            logger = NoOpCloudLogger,
        )

        val result = coordinator.reportProgress(accountId, FakeProviders.ID, fileId, 1_000, 2_000)

        assertTrue(
            result is CloudResult.Failure,
            "the coordinator must not turn a provider's Unsupported into an apparent success",
        )
    }

    // ------------------------------------------------------------------ audio tracks

    @Test
    fun `audio track resolution is delegated to the provider`() = runTest {
        var requestedTrack: MediaAudioTrack? = null
        val provider = FakeProviders.withMedia(
            FakeMedia({ CloudResult.Success(resource()) }),
            resolveAudio = { track ->
                requestedTrack = track
                CloudResult.Success(
                    MediaResource(
                        url = "https://cdn.example/audio/1.m3u8",
                        protocol = MediaProtocol.HLS,
                    ),
                )
            },
        )
        val coordinator = MediaPlaybackCoordinator(
            registry = DefaultProviderRegistry(listOf(provider)),
            logger = NoOpCloudLogger,
        )

        val track = MediaAudioTrack(id = "1", label = "5.1 环绕声")
        val result = coordinator.resolveAudioTrack(accountId, FakeProviders.ID, fileId, track)

        assertNotNull(result.getOrNull())
        assertEquals(track, requestedTrack, "the exact track must reach the provider")
    }

    // ------------------------------------------------------------------ invalidation

    @Test
    fun `invalidating an account drops its cached resolutions`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource(expiresAt = 10_000_000L)) })
        val coordinator = coordinator(media, now = { 1_000L })

        coordinator.resolve(accountId, FakeProviders.ID, fileId)
        coordinator.invalidateAccount(accountId)
        coordinator.resolve(accountId, FakeProviders.ID, fileId)

        assertEquals(
            2,
            media.resolveCalls,
            "a logout must not leave a playable URL cached for the removed account",
        )
    }

    @Test
    fun `refreshTokenFor carries only stable identifiers`() = runTest {
        val media = FakeMedia({ CloudResult.Success(resource()) })
        val coordinator = coordinator(media)

        val token = coordinator
            .refreshTokenFor(accountId, FakeProviders.ID, fileId, resource())
            .getOrNull()

        assertNotNull(token)
        assertEquals(CloudFileKey(FakeProviders.ID, accountId, fileId), token.key)
        // A refresh token is retained for a whole playback session, so it must never contain the URL.
        assertTrue(!token.toString().contains("http"))
        assertNull(token.key.fileId.value.takeIf { it.contains("cdn.example") })
    }
}

/**
 * Produces a [CloudProvider] with a fake media surface.
 *
 * Hand-written rather than mocked so that the real `DefaultProviderRegistry` and the real
 * `capabilities()` lookup are exercised — a mock of the registry would let a bug in the registration
 * path pass unnoticed, and the "unregistered provider" behaviour is one of the things under test.
 */
private object FakeProviders {

    val ID = com.notdisk.core.model.ProviderId("fake")

    fun withMedia(
        media: FakeMediaSurface,
        reportProgress: suspend (Long, Long?) -> CloudResult<Unit> = { _, _ ->
            CloudResult.Failure(
                CloudError.Unsupported(message = "not implemented", capability = "media.playbackProgress"),
            )
        },
        resolveAudio: suspend (MediaAudioTrack) -> CloudResult<MediaResource> = {
            CloudResult.Failure(CloudError.Unsupported(message = "no audio tracks"))
        },
    ): CloudProvider = FakeCloudProvider(media, reportProgress, resolveAudio)

}

/**
 * The media behaviour each test configures.
 *
 * An interface rather than a mock so the real `DefaultProviderRegistry` and the real
 * `capabilities()` lookup are exercised: mocking the registry would let a bug in the
 * registration path pass unnoticed, and "an unregistered provider is a typed `Unsupported`"
 * is one of the behaviours under test.
 */
private interface FakeMediaSurface {
    fun onResolve(): CloudResult<MediaResource>

    val urlRefresh: CapabilitySupport

    /** The most recent `qualityHint` the provider was asked for. */
    val lastQualityHint: String?

    /** How many times `resolveMedia` was called — the core assertion in most of these tests. */
    val resolveCalls: Int

    /** Records the hint; called by the provider shim below. */
    fun recordQualityHint(hint: String?)
}
/** Minimal provider whose only real behaviour is its media surface. */
private class FakeCloudProvider(
    /** Named `mediaSurface` so it does not shadow the `CloudProvider.media` member it feeds. */
    private val mediaSurface: FakeMediaSurface,
    private val reportProgress: suspend (Long, Long?) -> CloudResult<Unit>,
    private val resolveAudio: suspend (MediaAudioTrack) -> CloudResult<MediaResource>,
) : CloudProvider {

    override val id = FakeProviders.ID
    override val displayName = "Fake"

    override val auth: com.notdisk.core.provider.AuthProvider = UnsupportedAuth
    override val files: com.notdisk.core.provider.FileSystemProvider = UnsupportedFiles
    override val operations: com.notdisk.core.provider.FileOperationProvider = UnsupportedOperations
    override val preview: com.notdisk.core.provider.PreviewProvider = UnsupportedPreview

    override val media: com.notdisk.core.provider.MediaProvider =
        object : com.notdisk.core.provider.MediaProvider {
            override val providerId = FakeProviders.ID

            override suspend fun resolveMedia(
                accountId: AccountId,
                fileId: FileId,
                qualityHint: String?,
            ): CloudResult<MediaResource> {
                mediaSurface.recordQualityHint(qualityHint)
                return mediaSurface.onResolve()
            }

            override suspend fun resolveAudioTrack(
                accountId: AccountId,
                fileId: FileId,
                track: MediaAudioTrack,
            ): CloudResult<MediaResource> = resolveAudio(track)

            override suspend fun reportPlaybackProgress(
                accountId: AccountId,
                fileId: FileId,
                positionMs: Long,
                durationMs: Long?,
            ): CloudResult<Unit> = reportProgress(positionMs, durationMs)

            override suspend fun getPlaybackProgress(
                accountId: AccountId,
                fileId: FileId,
            ): CloudResult<Long?> = CloudResult.Failure(
                CloudError.Unsupported(message = "not implemented"),
            )
        }

    override fun capabilities(): CloudCapabilities = CloudCapabilities(
        providerId = FakeProviders.ID,
        media = com.notdisk.core.model.MediaCapabilities(
            videoStreaming = CapabilitySupport.SUPPORTED,
            protocols = setOf(MediaProtocol.HLS),
            urlRefresh = mediaSurface.urlRefresh,
        ),
    )
}

/**
 * The three sub-surfaces this test does not use.
 *
 * Declared once, here, rather than mocked: an unimplemented surface must fail *closed* with a typed
 * error, which is also what the real providers do for capabilities they do not offer.
 */
private fun unsupported(name: String): CloudResult<Nothing> = CloudResult.Failure(
    CloudError.Unsupported(message = "$name is not part of this fake", capability = name),
)

private object UnsupportedAuth : com.notdisk.core.provider.AuthProvider {
    override suspend fun beginAuthorization(
        flow: com.notdisk.core.model.AuthFlowKind,
        params: com.notdisk.core.provider.AuthFlowParams,
    ) = unsupported("auth.begin")

    override suspend fun pollChallenge(challengeId: String) = unsupported("auth.poll")
    override suspend fun cancelChallenge(challengeId: String) = unsupported("auth.cancel")
    override suspend fun verifyAccount(accountId: AccountId) = unsupported("auth.verify")
    override suspend fun getAccount(accountId: AccountId) = unsupported("auth.get")
    override suspend fun listAccounts() = CloudResult.Success(emptyList<com.notdisk.core.model.CloudAccount>())
    override suspend fun refreshProfile(accountId: AccountId) = unsupported("auth.refreshProfile")
    override suspend fun getStorageQuota(accountId: AccountId) = unsupported("auth.quota")
    override suspend fun logout(accountId: AccountId) = unsupported("auth.logout")
    override suspend fun session(accountId: AccountId) = unsupported("auth.session")
}

private object UnsupportedFiles : com.notdisk.core.provider.FileSystemProvider {
    override suspend fun getRoot(accountId: AccountId) = unsupported("files.getRoot")
    override suspend fun listChildren(
        accountId: AccountId,
        request: com.notdisk.core.model.ListChildrenRequest,
    ) = unsupported("files.listChildren")

    override suspend fun getFile(accountId: AccountId, fileId: FileId) = unsupported("files.getFile")
    override suspend fun getPath(accountId: AccountId, fileId: FileId) = unsupported("files.getPath")
    override suspend fun search(
        accountId: AccountId,
        query: com.notdisk.core.model.FileSearchQuery,
    ) = unsupported("files.search")

    override suspend fun resolvePath(accountId: AccountId, path: String) = unsupported("files.resolvePath")
}

private object UnsupportedOperations : com.notdisk.core.provider.FileOperationProvider {
    override suspend fun createFolder(accountId: AccountId, parentId: FileId, name: String) =
        unsupported("ops.createFolder")

    override suspend fun rename(accountId: AccountId, fileId: FileId, newName: String) =
        unsupported("ops.rename")

    override suspend fun move(accountId: AccountId, fileIds: List<FileId>, targetParentId: FileId) =
        unsupported("ops.move")

    override suspend fun copy(accountId: AccountId, fileIds: List<FileId>, targetParentId: FileId) =
        unsupported("ops.copy")

    override suspend fun delete(accountId: AccountId, fileIds: List<FileId>, permanent: Boolean) =
        unsupported("ops.delete")

    override suspend fun upload(
        accountId: AccountId,
        request: com.notdisk.core.model.UploadRequest,
    ) = unsupported("ops.upload")

    override suspend fun prepareDownload(accountId: AccountId, fileId: FileId) =
        unsupported("ops.prepareDownload")

    override suspend fun download(
        accountId: AccountId,
        request: com.notdisk.core.model.DownloadRequest,
        source: com.notdisk.core.provider.DownloadSource,
    ) = unsupported("ops.download")

    override fun observeTransfer(transferId: String) =
        kotlinx.coroutines.flow.flowOf(com.notdisk.core.model.TransferProgress(0, null, com.notdisk.core.model.TransferStatus.FAILED))

    override suspend fun cancelTransfer(transferId: String) = unsupported("ops.cancelTransfer")
    override suspend fun retryTransfer(transferId: String) = unsupported("ops.retryTransfer")

    override suspend fun share(accountId: AccountId, fileIds: List<FileId>, enable: Boolean, password: String?) =
        unsupported("ops.share")

    override suspend fun setStarred(accountId: AccountId, fileIds: List<FileId>, starred: Boolean) =
        unsupported("ops.setStarred")

    override suspend fun listTrash(accountId: AccountId, cursor: String?, pageSize: Int) =
        unsupported("ops.listTrash")

    override suspend fun restoreFromTrash(accountId: AccountId, trashIds: List<String>) =
        unsupported("ops.restoreFromTrash")

    override suspend fun purgeFromTrash(accountId: AccountId, trashIds: List<String>, safeKey: String?) =
        unsupported("ops.purgeFromTrash")
}

private object UnsupportedPreview : com.notdisk.core.provider.PreviewProvider {
    override suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean,
        hint: com.notdisk.core.provider.PreviewHint,
    ) = unsupported("preview.preview")

    override suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: com.notdisk.core.model.ThumbnailRef?,
    ) = unsupported("preview.thumbnail")
}

/**
 * The media behaviour each test configures.
 *
 * Hand-written rather than mocked so that the real `DefaultProviderRegistry` and the real
 * `capabilities()` lookup are exercised — mocking the registry would let a bug in the registration
 * path pass unnoticed, and "an unregistered provider is a typed `Unsupported`" is one of the
 * behaviours under test.
 */
private class FakeMedia(
    private val resolveResult: () -> CloudResult<MediaResource>,
) : FakeMediaSurface {

    /**
     * Declared as a mutable property rather than a constructor parameter.
     *
     * Every call site passes the resolve lambda positionally, so the lambda must come first; a
     * `CapabilitySupport` parameter after it could then only be set by name, which Kotlin does not
     * allow for a `var` in the primary constructor. Tests override it with
     * `FakeMedia { … }.apply { urlRefresh = … }`.
     */
    override var urlRefresh: CapabilitySupport = CapabilitySupport.SUPPORTED

    override var resolveCalls: Int = 0
        private set

    override var lastQualityHint: String? = null

    override fun onResolve(): CloudResult<MediaResource> {
        resolveCalls++
        return resolveResult()
    }

    override fun recordQualityHint(hint: String?) {
        lastQualityHint = hint
    }
}
