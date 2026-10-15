package com.notdisk.feature.player

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.data.cache.FileMetadataCache
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthProvider
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.DefaultProviderRegistry
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.FileSystemProvider
import com.notdisk.core.provider.MediaProvider
import com.notdisk.core.provider.PreviewProvider
import com.notdisk.feature.player.testing.FakeFileMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for [PlayerViewModel].
 *
 * ### What these tests are actually protecting
 * Three things in the player can break in ways that are invisible in a screenshot and unpleasant in
 * production:
 *
 *  1. **The error a user reads.** "Retry" shown for an unrefreshable link is a button that can never
 *     work, and "expired" shown when the account needs re-authorising sends the user to the wrong
 *     screen. Each `CloudError` case therefore has its own assertion.
 *  2. **The refresh policy.** An uncapped refresh loop hammers a rate-limited vendor; a refresh that
 *     loses the position restarts a two-hour film from zero.
 *  3. **Silence about optional capabilities.** A provider with no progress endpoint answers
 *     `Unsupported` to every report. Treating that as an error would put a red banner on the screen
 *     of every 115 user, forever.
 *
 * ### No test in this class can hang
 * Every test runs inside `runTest`, which fails on a leaked coroutine and carries a real 60-second
 * timeout, and the ViewModel is driven on a [StandardTestDispatcher] whose virtual clock advances only
 * through [advanceUntilIdle]. The ViewModel issues no `delay` on the load path, and its one timed
 * behaviour (progress throttling) is a pure comparison against a recorded position that the tests set
 * explicitly — so no test waits for wall-clock time to pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val providerId = ProviderId("test-provider")
    private val accountId = AccountId("test-account")
    private val fileId = FileId("test-file")

    private lateinit var resolver: FakeMediaResolver

    @Before
    fun setUp() {
        // `viewModelScope` is bound to Dispatchers.Main, which does not exist on the JVM. Replacing it
        // with the test dispatcher is what lets `advanceUntilIdle()` drive the ViewModel's coroutines.
        Dispatchers.setMain(dispatcher)
        resolver = FakeMediaResolver()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ (a) error mapping

    @Test
    fun `unsupported maps to the not-playable-here message`() = runTest(dispatcher) {
        resolver.resolveResult = CloudResult.Failure(CloudError.Unsupported(message = "raw"))

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PlayerViewModel.UNSUPPORTED_MESSAGE, state.error)
        assertEquals(PlayerErrorKind.Plain, state.errorKind)
        assertFalse("a retry is offered for a transient problem", state.shouldOfferDownload)
    }

    @Test
    fun `not found maps to the deleted message`() = runTest(dispatcher) {
        resolver.resolveResult = CloudResult.Failure(CloudError.NotFound(message = "raw"))

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        assertEquals(PlayerViewModel.DELETED_MESSAGE, viewModel.state.value.error)
    }

    @Test
    fun `deleted maps to the deleted message`() = runTest(dispatcher) {
        resolver.resolveResult = CloudResult.Failure(CloudError.Deleted(message = "raw"))

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        assertEquals(PlayerViewModel.DELETED_MESSAGE, viewModel.state.value.error)
    }

    @Test
    fun `token expired maps to the re-authorise message and routes to the accounts screen`() =
        runTest(dispatcher) {
            resolver.resolveResult = CloudResult.Failure(CloudError.TokenExpired(message = "raw"))

            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(PlayerViewModel.REAUTH_MESSAGE, state.error)
            // The distinction matters: re-authorising is a different screen, not a retry here.
            assertEquals(PlayerErrorKind.Reauthorise, state.errorKind)
        }

    @Test
    fun `not authenticated maps to the same re-authorise message as token expired`() =
        runTest(dispatcher) {
            resolver.resolveResult = CloudResult.Failure(CloudError.NotAuthenticated(message = "raw"))

            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            assertEquals(PlayerViewModel.REAUTH_MESSAGE, viewModel.state.value.error)
        }

    @Test
    fun `an unrefreshable expired url maps to the download-instead message`() = runTest(dispatcher) {
        resolver.resolveResult = CloudResult.Failure(
            CloudError.ResourceUrlExpired(refreshable = false, message = "raw"),
        )

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PlayerViewModel.NOT_REFRESHABLE_MESSAGE, state.error)
        // The screen reads this to drop the retry button and show the download hint instead.
        assertTrue(state.shouldOfferDownload)
        assertEquals(PlayerErrorKind.DownloadInstead, state.errorKind)
    }

    @Test
    fun `a refreshable expired url keeps the provider's own message`() = runTest(dispatcher) {
        resolver.resolveResult = CloudResult.Failure(
            CloudError.ResourceUrlExpired(refreshable = true, message = "资源地址已过期"),
        )

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("资源地址已过期", state.error)
        // A refreshable expiry is exactly the case a retry *can* fix.
        assertFalse(state.shouldOfferDownload)
    }

    @Test
    fun `an unmapped error falls back to its user-facing message rather than a catch-all`() =
        runTest(dispatcher) {
            resolver.resolveResult = CloudResult.Failure(
                CloudError.RateLimited(message = "请求过于频繁，请稍后再试"),
            )

            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            assertEquals("请求过于频繁，请稍后再试", viewModel.state.value.error)
        }

    // ------------------------------------------------------------------ (b) refresh + position

    @Test
    fun `a refreshable playback failure triggers exactly one refresh and preserves the position`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            // The user watched 12 minutes before the URL died.
            viewModel.reportProgress(positionMs = 720_000L, durationMs = 3_600_000L)
            advanceUntilIdle()
            resolver.refreshResult = CloudResult.Success(
                FakeMediaResolver.defaultResource(url = "https://example.invalid/fresh.m3u8"),
            )

            viewModel.onPlaybackError(positionMs = 720_000L, durationMs = 3_600_000L)
            advanceUntilIdle()

            assertEquals("exactly one refresh was attempted", 1, resolver.refreshCalls.size)

            val state = viewModel.state.value
            assertNull("the failure was resolved", state.error)
            assertEquals(
                "playback resumes where the user was, not at zero",
                720_000L,
                state.startPositionMs,
            )
            assertEquals(
                "the fresh url is the one the screen will install",
                "https://example.invalid/fresh.m3u8",
                state.resource?.url,
            )
            assertEquals("one refresh is recorded", 1, state.refreshCount)
        }

    @Test
    fun `the source generation advances on refresh so the UI rebuilds the media source`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()
            val afterLoad = viewModel.state.value.sourceGeneration

            // A provider may hand back a byte-identical signed URL. Keying the UI's effect on the
            // generation rather than on the URL is what stops the failed source being reused.
            resolver.refreshResult = CloudResult.Success(FakeMediaResolver.defaultResource())
            viewModel.onPlaybackError(positionMs = 1_000L)
            advanceUntilIdle()

            assertTrue(
                "the generation must move even when the url is unchanged",
                viewModel.state.value.sourceGeneration > afterLoad,
            )
        }

    @Test
    fun `a playback failure on a non-refreshable resource offers a download and never refreshes`() =
        runTest(dispatcher) {
            // Simulates the provider's own capability refusal: no refresh token, not refreshable.
            resolver.resolveResult = CloudResult.Success(
                FakeMediaResolver.defaultResource(refreshable = false),
            )

            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()
            assertFalse("the screen must not promise a refresh it cannot do", viewModel.state.value.canRefresh)

            viewModel.onPlaybackError(positionMs = 5_000L)
            advanceUntilIdle()

            assertEquals("no refresh was attempted", 0, resolver.refreshCalls.size)
            val state = viewModel.state.value
            assertEquals(PlayerViewModel.NOT_REFRESHABLE_MESSAGE, state.error)
            assertTrue(state.shouldOfferDownload)
        }

    @Test
    fun `a failed refresh surfaces the error instead of retrying blindly`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        resolver.refreshResult = CloudResult.Failure(
            CloudError.ResourceUrlExpired(refreshable = false, message = "raw"),
        )
        viewModel.onPlaybackError(positionMs = 30_000L)
        advanceUntilIdle()

        assertEquals(1, resolver.refreshCalls.size)
        assertEquals(PlayerViewModel.NOT_REFRESHABLE_MESSAGE, viewModel.state.value.error)
        assertTrue(viewModel.state.value.shouldOfferDownload)
    }

    // ------------------------------------------------------------------ (c) the refresh cap

    @Test
    fun `the refresh cap is enforced and then surfaces an error`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        // The pathological case: every refresh "succeeds" with a URL that fails immediately. Without
        // a cap this loop would never end.
        repeat(PlayerViewModel.MAX_AUTO_REFRESHES + 3) {
            viewModel.onPlaybackError(positionMs = 1_000L * (it + 1))
            advanceUntilIdle()
        }

        assertEquals(
            "the cap bounds the number of refreshes exactly",
            PlayerViewModel.MAX_AUTO_REFRESHES,
            resolver.refreshCalls.size,
        )

        val state = viewModel.state.value
        assertEquals(PlayerViewModel.REFRESH_CAP_MESSAGE, state.error)
        assertEquals(PlayerErrorKind.DownloadInstead, state.errorKind)
        assertTrue(state.shouldOfferDownload)
        assertEquals(PlayerViewModel.MAX_AUTO_REFRESHES, state.refreshCount)
    }

    @Test
    fun `a fresh load resets the refresh budget`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()
        repeat(PlayerViewModel.MAX_AUTO_REFRESHES + 1) {
            viewModel.onPlaybackError(positionMs = 0L)
            advanceUntilIdle()
        }
        assertEquals(PlayerViewModel.MAX_AUTO_REFRESHES, resolver.refreshCalls.size)

        // Opening another file must not inherit the spent budget, or the next video would be
        // unplayable until the process restarted.
        viewModel.load(providerId, accountId, FileId("another-file"))
        advanceUntilIdle()
        viewModel.onPlaybackError(positionMs = 0L)
        advanceUntilIdle()

        assertEquals(
            "the new file gets its own budget",
            PlayerViewModel.MAX_AUTO_REFRESHES + 1,
            resolver.refreshCalls.size,
        )
    }

    @Test
    fun `a playback error before anything resolved does not refresh`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onPlaybackError(positionMs = 0L)
        advanceUntilIdle()

        assertEquals(0, resolver.refreshCalls.size)
        assertNull("no error is invented before a load was attempted", viewModel.state.value.error)
    }

    // ------------------------------------------------------------------ (d) progress reporting

    @Test
    fun `an unsupported progress report does not set an error`() = runTest(dispatcher) {
        // The default fake answers exactly what 115's provider answers: CloudError.Unsupported.
        resolver.progressResult = CloudResult.Failure(
            CloudError.Unsupported(message = "该网盘不支持记录播放进度"),
        )

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        // First report is past the throttle window measured from the load-time zero.
        viewModel.reportProgress(positionMs = 11_000L, durationMs = 3_600_000L)
        advanceUntilIdle()

        assertEquals(1, resolver.progressCalls.size)
        val state = viewModel.state.value
        assertNull("an optional capability must never look like a failure", state.error)
        assertNull(state.errorKind)
        assertFalse(state.hasError)
    }

    @Test
    fun `progress reports are throttled so the vendor is not polled once a second`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            // Ten consecutive one-second samples: the UI polls every second, the vendor hears once.
            (1..10).forEach { second ->
                viewModel.reportProgress(positionMs = second * 1_000L, durationMs = 3_600_000L)
            }
            advanceUntilIdle()

            assertEquals("only the first sample crossed the threshold", 1, resolver.progressCalls.size)

            viewModel.reportProgress(positionMs = 21_000L, durationMs = 3_600_000L)
            advanceUntilIdle()
            assertEquals("the next window reports again", 2, resolver.progressCalls.size)
        }

    @Test
    fun `the position is recorded even while the vendor report is throttled`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        viewModel.reportProgress(positionMs = 11_000L, durationMs = 3_600_000L)
        advanceUntilIdle()
        // A sample inside the throttle window: not sent, but still the position a refresh must resume
        // from. Dropping it would rewind the user by up to ten seconds on every refresh.
        viewModel.reportProgress(positionMs = 12_500L, durationMs = 3_600_000L)

        assertEquals(12_500L, viewModel.state.value.startPositionMs)
        assertEquals(1, resolver.progressCalls.size)
    }

    @Test
    fun `a negative position is ignored rather than reported`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        viewModel.reportProgress(positionMs = -1L, durationMs = null)
        advanceUntilIdle()

        assertEquals(0, resolver.progressCalls.size)
        assertEquals(0L, viewModel.state.value.startPositionMs)
    }

    // ------------------------------------------------------------------ rendition switching

    @Test
    fun `selecting a rendition re-resolves with the rendition id as the quality hint and resumes`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            viewModel.reportProgress(positionMs = 42_000L, durationMs = 3_600_000L)
            advanceUntilIdle()

            viewModel.selectRendition("720")
            advanceUntilIdle()

            val lastResolve = resolver.resolveCalls.last()
            assertEquals("the id is echoed to the resolver verbatim", "720", lastResolve.qualityHint)
            assertEquals("a quality switch is not a restart", 42_000L, viewModel.state.value.startPositionMs)
            assertEquals("720", viewModel.state.value.selectedRenditionId)
        }

    @Test
    fun `selecting the rendition already in effect does nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()
        viewModel.selectRendition("720")
        advanceUntilIdle()
        val callsAfterFirstSwitch = resolver.resolveCalls.size

        viewModel.selectRendition("720")
        advanceUntilIdle()

        assertEquals(
            "re-resolving the same quality would restart playback for no reason",
            callsAfterFirstSwitch,
            resolver.resolveCalls.size,
        )
    }

    @Test
    fun `an audio track the provider cannot deliver is a plain message, not a download hint`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()
            val urlBefore = viewModel.state.value.resource?.url

            // The fake's default is `CloudError.Unsupported`, the honest answer for a provider with
            // no separate audio playlist.
            viewModel.selectAudioTrack(MediaAudioTrack(id = "a1", label = "配音"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, resolver.audioTrackCalls.size)
            assertEquals("该网盘不支持独立音轨", state.error)
            // The video is still perfectly playable; only the track change failed.
            assertEquals(PlayerErrorKind.Plain, state.errorKind)
            assertFalse(state.shouldOfferDownload)
            assertEquals("the playing resource was left untouched", urlBefore, state.resource?.url)
        }

    @Test
    fun `a failed rendition switch keeps the provider's own reason rather than blaming the file`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()
            val playingUrl = viewModel.state.value.resource?.url

            resolver.resolveResult = CloudResult.Failure(
                CloudError.Unsupported(message = "该网盘不支持切换清晰度", capability = "media.renditions"),
            )
            viewModel.selectRendition("1080")
            advanceUntilIdle()

            val state = viewModel.state.value
            // The primary mapping would have said "该网盘不支持在线播放此文件", which is false: a video is
            // on screen and playing. The provider's own sentence names what actually failed.
            assertEquals("该网盘不支持切换清晰度", state.error)
            assertEquals(PlayerErrorKind.Plain, state.errorKind)
            assertEquals("the playing resource is untouched", playingUrl, state.resource?.url)
        }

    @Test
    fun `dismissing an error clears it without discarding the resource`() = runTest(dispatcher) {        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        viewModel.selectAudioTrack(MediaAudioTrack(id = "a1", label = "配音"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.hasError)

        viewModel.dismissError()

        val state = viewModel.state.value
        assertFalse(state.hasError)
        assertNull(state.errorKind)
        assertTrue("what was playing is still playing", state.canPlay)
    }

    // ------------------------------------------------------------------ resume position

    @Test
    fun `a vendor-remembered position is used as the starting point`() = runTest(dispatcher) {
        resolver.lastPositionResult = CloudResult.Success(630_000L)

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        assertEquals(
            "continue-watching must resume where the vendor says, not at zero",
            630_000L,
            viewModel.state.value.startPositionMs,
        )
    }

    @Test
    fun `a vendor with no progress endpoint starts from zero without an error`() = runTest(dispatcher) {
        // The documented answer for 115: CloudError.Unsupported. A convenience feature that is
        // unavailable is not a failure the user should ever see.
        resolver.lastPositionResult = CloudResult.Failure(
            CloudError.Unsupported(message = "该网盘不支持记录播放进度"),
        )

        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0L, state.startPositionMs)
        assertNull(state.error)
    }

    @Test
    fun `a vendor position of null or zero starts from zero`() = runTest(dispatcher) {
        resolver.lastPositionResult = CloudResult.Success(null)
        val viewModel = viewModel()
        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()
        assertEquals(0L, viewModel.state.value.startPositionMs)

        resolver.lastPositionResult = CloudResult.Success(0L)
        val second = viewModel()
        second.load(providerId, accountId, fileId)
        advanceUntilIdle()
        assertEquals(0L, second.state.value.startPositionMs)
    }

    // ------------------------------------------------------------------ title resolution

    @Test
    fun `the title comes from the file metadata`() = runTest(dispatcher) {
        val viewModel = viewModel(fileName = "假期录像.mp4")

        viewModel.load(providerId, accountId, fileId)
        advanceUntilIdle()

        assertEquals("假期录像.mp4", viewModel.state.value.title)
    }

    @Test
    fun `an unavailable title degrades to the file id instead of failing the load`() =
        runTest(dispatcher) {
            // No provider registered for this id, so `getFile` fails. Playback must still work: the
            // resource does not depend on the metadata call succeeding.
            resolver.resolveResult = CloudResult.Success(FakeMediaResolver.defaultResource())
            val viewModel = PlayerViewModel(
                resolver = resolver,
                files = repositoryFor(registry = null),
                logger = NoOpCloudLogger,
            )

            viewModel.load(providerId, accountId, fileId)
            advanceUntilIdle()

            assertNull("a missing title is not an error", viewModel.state.value.error)
            assertEquals("test-file", viewModel.state.value.title)
            assertEquals(MediaProtocol.HLS, viewModel.state.value.resource?.protocol)
        }

    // ------------------------------------------------------------------ helpers

    /** Builds a ViewModel whose file lookup is served by a registered test provider. */
    private fun TestScope.viewModel(fileName: String = "video.mp4"): PlayerViewModel =
        PlayerViewModel(
            resolver = resolver,
            files = repositoryFor(registry = registryWithFileNamed(fileName)),
            logger = NoOpCloudLogger,
        )

    /**
     * A real [CloudFileRepository] over an always-missing cache and a test registry.
     *
     * Constructing the production class (rather than a fake repository) is deliberate: the ViewModel's
     * only interaction with it is `getFile`, and using the real one means the test would also catch a
     * change in how the repository reports "no such file" — which is precisely the path that decides
     * whether a missing title degrades gracefully.
     */
    private fun repositoryFor(registry: DefaultProviderRegistry?): CloudFileRepository =
        CloudFileRepository(
            registry = registry ?: DefaultProviderRegistry(),
            cache = FileMetadataCache(dao = FakeFileMetadataDao(), logger = NoOpCloudLogger),
            logger = NoOpCloudLogger,
        )

    private fun registryWithFileNamed(name: String): DefaultProviderRegistry =
        DefaultProviderRegistry(listOf(StubFileProvider(providerId, name)))
}

/**
 * The smallest [CloudProvider] that can answer `getFile`.
 *
 * Only `files.getFile` is implemented with real behaviour; every other surface is unreachable from
 * the player (the player resolves media through [MediaResolver], not through a provider), and each
 * throws rather than returning a plausible-looking value. That turns "the player acquired provider
 * knowledge" from a code-review question into a failing test: if the ViewModel ever called a media or
 * auth surface directly, this provider would throw and the test would say so.
 */
private class StubFileProvider(
    override val id: ProviderId,
    private val fileName: String,
) : CloudProvider {

    override val displayName: String = "Stub ${id.value}"

    override fun capabilities(): CloudCapabilities {
        val base = CloudCapabilities(providerId = id)
        return base.copy(
            fileSystem = base.fileSystem.copy(getFile = CapabilitySupport.SUPPORTED),
        )
    }

    override val files: FileSystemProvider = object : FileSystemProvider {
        override suspend fun getFile(accountId: AccountId, fileId: FileId): CloudResult<FileItem> =
            CloudResult.Success(
                FileItem(
                    id = fileId,
                    providerId = id,
                    accountId = accountId,
                    name = fileName,
                    type = FileType.FILE,
                    mimeType = "video/mp4",
                    extension = "mp4",
                ),
            )

        override suspend fun listChildren(
            accountId: AccountId,
            request: ListChildrenRequest,
        ): CloudResult<FilePage> =
            throw UnsupportedOperationException("the player must not list folders")

        override suspend fun getRoot(accountId: AccountId): CloudResult<FileItem> =
            throw UnsupportedOperationException("the player must not read the root")

        override suspend fun getPath(
            accountId: AccountId,
            fileId: FileId,
        ): CloudResult<FilePath> =
            throw UnsupportedOperationException("the player must not resolve paths")

        override suspend fun search(
            accountId: AccountId,
            query: FileSearchQuery,
        ): CloudResult<FilePage> =
            throw UnsupportedOperationException("the player must not search")

        override suspend fun resolvePath(
            accountId: AccountId,
            path: String,
        ): CloudResult<FileId> =
            throw UnsupportedOperationException("the player must not resolve paths")
    }

    override val auth: AuthProvider
        get() = throw UnsupportedOperationException("the player must not touch credentials")

    override val operations: FileOperationProvider
        get() = throw UnsupportedOperationException("the player must not mutate files")

    override val media: MediaProvider
        get() = throw UnsupportedOperationException(
            "the player resolves media through MediaResolver, never through a provider",
        )

    override val preview: PreviewProvider
        get() = throw UnsupportedOperationException("the player must not preview")
}
