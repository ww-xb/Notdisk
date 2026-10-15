package com.notdisk.domain.media

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.domain.Fixtures
import com.notdisk.domain.failureOfType
import com.notdisk.domain.registryOf
import com.notdisk.domain.requireSuccess
import com.notdisk.domain.testing.CapabilityFixtures
import com.notdisk.domain.testing.FakeCloudProvider
import com.notdisk.domain.testing.ProviderSurface
import com.notdisk.domain.testing.tokenFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the media use cases, with the **refresh classification** as the centrepiece.
 *
 * ### Why `refreshable` gets the most attention
 * When a signed playback URL expires there are two worlds: *expired but recoverable*, where the
 * player can refresh transparently and the user sees a brief rebuffer, and *expired and stuck*,
 * where retrying is futile and the user needs a different remedy. A player that cannot tell them
 * apart either gives up on a recoverable stream ("playback randomly stops") or retries a dead one
 * forever (a spinner that never ends, draining battery). The classification lives in
 * [CloudError.ResourceUrlExpired.refreshable], and [RefreshMediaResourceUseCase] is the only place
 * positioned to set it correctly — so these tests pin both directions.
 */
class MediaUseCasesTest {

    private val fileId = FileId("video-1")

    // ---------------------------------------------------------------------------------------------
    // ResolveMediaResourceUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolveMediaResource returns the provider's resource`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val resource = ResolveMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        ).requireSuccess()

        assertEquals(MediaProtocol.HLS, resource.protocol)
        assertTrue(resource.url.isNotBlank())
        assertEquals(
            mapOf("Referer" to "https://vendor.invalid/"),
            resource.headers,
            "vendors that validate Referer must get their headers through untouched",
        )
    }

    @Test
    fun `resolveMediaResource forwards the quality hint verbatim`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        ResolveMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            qualityHint = "1080p",
        ).requireSuccess()

        assertEquals(
            "1080p",
            alpha.callsOn(ProviderSurface.MEDIA).single()["qualityHint"],
            "the hint is opaque to the domain layer and must round-trip unmodified",
        )
    }

    @Test
    fun `resolveMediaResource refuses before the network when the provider cannot stream at all`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            media = caps.media.copy(
                videoStreaming = CapabilitySupport.UNSUPPORTED,
                audioStreaming = CapabilitySupport.UNSUPPORTED,
            ),
        )

        val result = ResolveMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(ResolveMediaResourceUseCase.CAPABILITY_STREAMING, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `resolveMediaResource lets one supported media kind through`() = runTest {
        // The gate only fires when *neither* kind is possible: whether this particular file is
        // video or audio is unknowable without a metadata round trip, so the provider decides.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            media = caps.media.copy(
                videoStreaming = CapabilitySupport.UNSUPPORTED,
                audioStreaming = CapabilitySupport.SUPPORTED,
            ),
        )

        val result = ResolveMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isSuccess())
        assertEquals(1, alpha.totalCalls())
    }

    @Test
    fun `resolveMediaResource forwards an Unsupported for this specific item`() = runTest {
        // A provider that can stream in general but not *this* item (still transcoding) reports it
        // as a failure, and the domain layer must not rewrite it.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.mediaFake.resolveMediaFailure = CloudError.Unsupported(capability = "media.item")

        val result = ResolveMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals("media.item", error.capability)
    }

    @Test
    fun `resolveMediaResource on an unknown provider is Unsupported`() = runTest {
        val result = ResolveMediaResourceUseCase(registryOf())(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unsupported>()
    }

    // ---------------------------------------------------------------------------------------------
    // ResolveAudioTrackUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolveAudioTrack forwards the track id, not an index`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val track = MediaAudioTrack(id = "track-2", label = "国语", language = "zh")

        val resource = ResolveAudioTrackUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            track,
        ).requireSuccess()

        assertTrue(resource.url.contains("track-2"))
        assertEquals("track-2", alpha.callsOn(ProviderSurface.MEDIA).single()["trackId"])
    }

    @Test
    fun `resolveAudioTrack refuses before the network when tracks are not separated`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            media = caps.media.copy(separateAudioTracks = CapabilitySupport.UNSUPPORTED),
        )

        val result = ResolveAudioTrackUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            MediaAudioTrack(id = "1", label = "音轨 1"),
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(ResolveAudioTrackUseCase.CAPABILITY_SEPARATE_AUDIO, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    // ---------------------------------------------------------------------------------------------
    // Playback progress
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `getPlaybackProgress distinguishes 'no position' from 'not supported'`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.mediaFake.playbackProgressMs = null

        val progress = GetPlaybackProgressUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        ).requireSuccess()

        assertNull(
            progress,
            "a success carrying null means the provider answered and has no position for this file",
        )
    }

    @Test
    fun `getPlaybackProgress returns the vendor's position when it has one`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.mediaFake.playbackProgressMs = 93_000L

        val progress = GetPlaybackProgressUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        ).requireSuccess()

        assertEquals(93_000L, progress)
    }

    @Test
    fun `getPlaybackProgress is a typed failure when the provider has no such feature`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            media = caps.media.copy(playbackProgress = CapabilitySupport.UNSUPPORTED),
        )

        val result = GetPlaybackProgressUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure(), "'we cannot ask' must not look like 'there is nothing'")
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(GetPlaybackProgressUseCase.CAPABILITY_PLAYBACK_PROGRESS, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `reportPlaybackProgress forwards the position and duration`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        ReportPlaybackProgressUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            positionMs = 12_345L,
            durationMs = 600_000L,
        ).requireSuccess()

        val call = alpha.callsOn(ProviderSurface.MEDIA).single()
        assertEquals(12_345L, call["positionMs"])
        assertEquals(600_000L, call["durationMs"])
    }

    @Test
    fun `reportPlaybackProgress rejects a negative position without calling the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = ReportPlaybackProgressUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            positionMs = -1L,
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `reportPlaybackProgress rejects a non-positive duration but allows null`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = ReportPlaybackProgressUseCase(registryOf(alpha))

        assertTrue(
            useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, fileId, 0L, durationMs = 0L).isFailure(),
        )
        assertTrue(
            useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, fileId, 0L, durationMs = null).isSuccess(),
            "a live stream genuinely has no duration",
        )
    }

    @Test
    fun `reportPlaybackProgress never fakes success when the provider refuses`() = runTest {
        // The player wants to swallow this; the use case must not do it for them.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.mediaFake.reportProgressFailure = CloudError.Unsupported(capability = "media.progress")

        val result = ReportPlaybackProgressUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            positionMs = 1_000L,
        )

        assertTrue(result.isFailure(), "claiming synced progress that was not saved is a lie")
        result.failureOfType<CloudError.Unsupported>()
    }

    // ---------------------------------------------------------------------------------------------
    // RefreshMediaResourceUseCase — the recoverable / stuck distinction
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `refresh re-resolves with the token's quality hint and yields a new url`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = RefreshMediaResourceUseCase(registryOf(alpha))
        val token = tokenFor(Fixtures.ALPHA, Fixtures.ACCOUNT, "video-1", qualityHint = "720p")

        val resource = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, token).requireSuccess()

        assertEquals("720p", alpha.callsOn(ProviderSurface.MEDIA).single()["qualityHint"])
        assertTrue(resource.url.isNotBlank())
        assertTrue(resource.refreshable)
    }

    @Test
    fun `refresh reports non-refreshable expiry when the provider cannot refresh`() = runTest {
        // THE required case: the UI must be able to tell "expired but recoverable" from
        // "expired and stuck", and this is where that distinction is produced.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            media = caps.media.copy(urlRefresh = CapabilitySupport.UNSUPPORTED),
        )
        val useCase = RefreshMediaResourceUseCase(registryOf(alpha))

        val result = useCase(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.ALPHA, Fixtures.ACCOUNT, "video-1"),
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.ResourceUrlExpired>()
        assertTrue(
            !error.refreshable,
            "refreshable=false is the load-bearing part: the UI must stop offering retry",
        )
        assertTrue(
            alpha.wasNeverCalled(),
            "a provider that declares no refresh must not receive a refresh request",
        )
    }

    @Test
    fun `refresh succeeds when the provider declares refresh supported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = RefreshMediaResourceUseCase(registryOf(alpha))

        val resource = useCase(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.ALPHA, Fixtures.ACCOUNT, "video-1"),
        ).requireSuccess()

        assertEquals(1, alpha.totalCalls())
        assertTrue(resource.url.isNotBlank())
    }

    @Test
    fun `refresh allows an UNVERIFIED refresh declaration through to the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            media = caps.media.copy(urlRefresh = CapabilitySupport.UNVERIFIED),
        )

        val result = RefreshMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.ALPHA, Fixtures.ACCOUNT, "video-1"),
        )

        assertTrue(result.isSuccess(), "UNVERIFIED means unobserved, not impossible")
        assertEquals(1, alpha.totalCalls())
    }

    @Test
    fun `refresh rejects a token belonging to another account`() = runTest {
        // A cross-account refresh would issue a request against the wrong credential — and where
        // file ids collide across accounts, stream the wrong file.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = RefreshMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.ALPHA, Fixtures.OTHER_ACCOUNT, "video-1"),
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(error.message.contains(Fixtures.ACCOUNT.value))
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `refresh rejects a token belonging to another provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = RefreshMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.BETA, Fixtures.ACCOUNT, "video-1"),
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `refresh propagates a provider failure rather than reporting expiry`() = runTest {
        // A deleted file is NotFound, not "expired": conflating them would send the user to a
        // refresh loop for a file that no longer exists.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.mediaFake.resolveMediaFailure = CloudError.NotFound()

        val result = RefreshMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.ALPHA, Fixtures.ACCOUNT, "video-1"),
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.NotFound>()
    }

    @Test
    fun `refresh on an unknown provider is Unsupported`() = runTest {
        val result = RefreshMediaResourceUseCase(registryOf())(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            tokenFor(Fixtures.ALPHA, Fixtures.ACCOUNT, "video-1"),
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unsupported>()
    }

    // ---------------------------------------------------------------------------------------------
    // Expiry introspection the coordinator relies on
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resource expiry is evaluated against an injected clock`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.mediaFake.expiresAt = 5_000L

        val resource = ResolveMediaResourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        ).requireSuccess()

        assertTrue(!resource.isExpired(nowMillis = 4_000L))
        assertTrue(
            resource.isExpired(skewMillis = 2_000L, nowMillis = 4_000L),
            "a positive skew lets the coordinator refresh proactively, before mid-stream expiry",
        )
        assertTrue(resource.isExpired(nowMillis = 5_000L))
    }
}
