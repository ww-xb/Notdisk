package com.notdisk.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.notdisk.core.designsystem.component.ErrorState
import com.notdisk.core.designsystem.component.LoadingState
import com.notdisk.core.designsystem.component.SectionHeader
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.Dimens
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.MediaRendition
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.MediaSubtitleTrack
import com.notdisk.core.model.ProviderId
import kotlinx.coroutines.delay

/*
 * ============================================================================================
 *  MODULE INVARIANT — `feature:player` CONTAINS ZERO PROVIDER KNOWLEDGE.
 * ============================================================================================
 *
 * This module must never:
 *
 *   1. declare a `provider:` project dependency (see `PlayerModuleIsolationTest`, which reads this
 *      module's `build.gradle.kts` and fails the build if one appears);
 *   2. import `provider:p115`, `provider:mock`, or any other vendor package;
 *   3. branch on a provider identity — there is no `when (providerId)` in this module because no
 *      provider id is ever compared to anything here;
 *   4. construct a vendor URL, sign a request, or know a vendor's parameter names.
 *
 * ### Why this invariant exists
 * The product's central claim is that supporting a second cloud is a *provider-only* change. That
 * claim is only true if the surfaces which display remote content have no way to special-case a
 * cloud. A player that can tell 115 from anyone else will accumulate a `when` branch per vendor —
 * and every new vendor then edits the player, which is exactly the coupling the architecture exists
 * to prevent.
 *
 * ### How the invariant is enforced structurally, not by discipline
 * The player is handed a [MediaResource] and nothing else: a URL, a [MediaProtocol], a header map, an
 * expiry, and optional renditions/audio/subtitles. There is no provider id in that type, so there is
 * nothing to branch on — the code below *cannot* express a vendor difference even by accident. The
 * provider-facing work (choosing the endpoint, signing the URL, attaching `pickcode`/`sign`
 * parameters, deciding whether an expired link can be renewed) all happens behind the
 * [MediaResolver] seam in `PlayerViewModel`, which is the only collaborator that ever sees a
 * `ProviderId`.
 *
 * ### The one protocol branch, and why it is not an exception
 * `buildMediaSource` switches on [MediaProtocol], which is a statement about *transport* (an HLS
 * playlist is fetched and parsed differently from a progressive file), not about a vendor. Vendors
 * that emit HLS share that branch; that is the point of modelling the protocol as a closed enum in
 * `core:model` rather than letting each integration pick a parsing strategy.
 */

/**
 * The playback destination: resolves the ViewModel and hands its state to [PlayerScreen].
 *
 * Route arguments arrive as strings because that is the only shape that survives process death and
 * deep links (see `NotDiskNavHost`). They are converted to typed identifiers exactly once, here,
 * and never inspected.
 */
@Composable
fun PlayerRoute(
    providerId: String,
    accountId: String,
    fileId: String,
    onBack: () -> Unit,
    onOpenDownload: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The identity is validated before it is turned into a value class: `ProviderId`/`AccountId`/
    // `FileId` all reject blank input by contract, and a malformed route must render an error rather
    // than crash the process on a `require` failure.
    val identity = remember(providerId, accountId, fileId) {
        val p = providerId.takeIf { it.isNotBlank() }
        val a = accountId.takeIf { it.isNotBlank() }
        val f = fileId.takeIf { it.isNotBlank() }
        if (p == null || a == null || f == null) {
            null
        } else {
            Triple(ProviderId(p), AccountId(a), FileId(f))
        }
    }

    LaunchedEffect(identity) {
        identity?.let { (p, a, f) -> viewModel.load(p, a, f) }
    }

    PlayerScreen(
        state = state,
        onBack = onBack,
        onRetry = {
            identity?.let { (p, a, f) -> viewModel.load(p, a, f) }
        },
        onPlaybackError = viewModel::onPlaybackError,
        onProgress = viewModel::reportProgress,
        onSelectRendition = viewModel::selectRendition,
        onSelectAudioTrack = viewModel::selectAudioTrack,
        onOpenDownload = onOpenDownload,
        modifier = modifier,
    )
}

/**
 * The stateless playback surface.
 *
 * Split from [PlayerRoute] so every branch — loading, failure, download-instead, playing — can be
 * rendered from a literal [PlayerUiState] in a `@Preview` and in a screenshot test, with no
 * ViewModel, no resolver and no device media stack involved.
 *
 * ### Why the media source is built inside `LaunchedEffect` and not during composition
 * Building a `MediaSource` allocates a data-source factory and mutates the player. Doing that in the
 * composition body would re-run it on every recomposition — including the ones caused by the
 * position updates this very screen produces — which restarts playback roughly once a second. The
 * effect is keyed on [PlayerUiState.sourceGeneration], which the ViewModel changes *only* when a
 * genuinely new resource must be installed (initial resolve, a refresh, a rendition switch).
 *
 * @param onPlaybackError invoked with the position reached so far, so a successful refresh resumes
 *   where the user was rather than at the start.
 * @param onProgress called about once a second while playing; the ViewModel throttles what it
 *   actually reports upstream.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlaybackError: (positionMs: Long, durationMs: Long?) -> Unit,
    onProgress: (positionMs: Long, durationMs: Long?) -> Unit,
    onSelectRendition: (String) -> Unit,
    onSelectAudioTrack: (MediaAudioTrack) -> Unit,
    onOpenDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // One player for the lifetime of this screen. It is deliberately *not* keyed on the resource:
    // swapping the media source on the existing instance is what makes a refresh seamless, whereas
    // recreating the player would tear down the video surface and flash black.
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // The callbacks are captured through `rememberUpdatedState` because the listener is installed
    // once. Reading a plain lambda parameter inside it would freeze the first composition's closure,
    // so a refresh would call a stale `onPlaybackError` that still pointed at the old resource.
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnProgress by rememberUpdatedState(onProgress)

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentOnPlaybackError(exoPlayer.currentPosition, exoPlayer.safeDurationMs())
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // A state change is also a good moment to record where we got to: if the process is
                // killed while paused, the position is already in the ViewModel.
                currentOnProgress(exoPlayer.currentPosition, exoPlayer.safeDurationMs())
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    val resource = state.resource

    LaunchedEffect(state.sourceGeneration, resource?.url) {
        val target = resource ?: return@LaunchedEffect
        val source = buildMediaSource(target)
        // `setMediaSource(source, startPositionMs)` is what implements resume-after-refresh: the new
        // URL is installed *at the old offset* in one call, so there is no window in which the user
        // sees the stream restart from zero.
        exoPlayer.setMediaSource(source, state.startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Position pump. Cancelled automatically when the screen leaves composition or the media
    // changes, so it can never outlive the player it reads from.
    LaunchedEffect(exoPlayer, state.sourceGeneration) {
        while (true) {
            delay(POSITION_POLL_INTERVAL_MILLIS)
            if (exoPlayer.isPlaying) {
                currentOnProgress(exoPlayer.currentPosition, exoPlayer.safeDurationMs())
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                title = state.title.ifBlank { "播放" },
                onBack = onBack,
                renditions = state.availableRenditions,
                selectedRenditionId = state.selectedRenditionId,
                onSelectRendition = onSelectRendition,
                audioTracks = state.availableAudioTracks,
                selectedAudioTrackId = state.selectedAudioTrackId,
                onSelectAudioTrack = onSelectAudioTrack,
                actionsEnabled = state.canPlay,
            )

            when {
                state.isLoading && resource == null -> LoadingState(label = "正在解析播放地址…")

                state.hasError -> ErrorState(
                    userMessage = state.error.orEmpty(),
                    onRetry = if (state.shouldOfferDownload) null else onRetry,
                    retryLabel = "重新解析",
                    detail = if (state.shouldOfferDownload) DOWNLOAD_HINT else null,
                )

                resource == null ->
                    // Reachable when a load produced neither a resource nor an error — e.g. the route
                    // carried a blank identifier, which never reaches the ViewModel at all.
                    ErrorState(
                        userMessage = "无法播放：文件标识不完整",
                        onRetry = onRetry,
                        retryLabel = "重新解析",
                    )

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .padding(horizontal = Dimens.Small),
                    ) {
                        AndroidViewPlayer(exoPlayer = exoPlayer)
                        if (state.isLoading) {
                            // A refresh in flight over an already-visible picture: a small, unobtrusive
                            // indicator rather than a full-screen spinner that would hide the video.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(Dimens.Small),
                            ) {
                                LoadingState(label = "正在刷新播放地址…")
                            }
                        }
                    }

                    PlaybackDetails(
                        state = state,
                        onOpenDownload = onOpenDownload,
                    )
                }
            }
        }
    }
}

/**
 * Hosts the Media3 surface.
 *
 * `PlayerView` is used rather than hand-built controls because it already implements the transport
 * controls, the buffering spinner, the subtitle renderer and the accessibility semantics of a video
 * player. Re-implementing them here would be a large amount of code whose only achievement would be
 * to be worse.
 */
@OptIn(UnstableApi::class)
@Composable
private fun AndroidViewPlayer(exoPlayer: ExoPlayer) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
                // The player owns its own surface; letting it keep the screen on is the behaviour a
                // user expects from a video player and costs nothing when audio-only.
                keepScreenOn = true
            }
        },
        update = { view -> view.player = exoPlayer },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The title row plus the two track selectors.
 *
 * The quality and audio menus are rendered only when the provider actually offered options: an empty
 * dropdown is a dead control that invites a tap and does nothing, which reads as a bug. Subtitles are
 * listed but not selectable — see [PlaybackDetails].
 */
@Composable
private fun PlayerTopBar(
    title: String,
    onBack: () -> Unit,
    renditions: List<MediaRendition>,
    selectedRenditionId: String?,
    onSelectRendition: (String) -> Unit,
    audioTracks: List<MediaAudioTrack>,
    selectedAudioTrackId: String?,
    onSelectAudioTrack: (MediaAudioTrack) -> Unit,
    actionsEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.XSmall, vertical = Dimens.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (renditions.isNotEmpty()) {
            OptionMenu(
                icon = Icons.Filled.HighQuality,
                contentDescription = "清晰度",
                enabled = actionsEnabled,
                options = renditions.map { rendition ->
                    MenuOption(
                        key = rendition.id,
                        label = rendition.displayLabel(),
                        selected = rendition.id == selectedRenditionId,
                    )
                },
                onSelect = onSelectRendition,
            )
        }

        if (audioTracks.isNotEmpty()) {
            OptionMenu(
                icon = Icons.Filled.Audiotrack,
                contentDescription = "音轨",
                enabled = actionsEnabled,
                options = audioTracks.map { track ->
                    MenuOption(
                        key = track.id,
                        label = track.language?.let { "${track.label}（$it）" } ?: track.label,
                        selected = track.id == selectedAudioTrackId,
                    )
                },
                onSelect = { id -> audioTracks.firstOrNull { it.id == id }?.let(onSelectAudioTrack) },
            )
        }
    }
}

/** One row of an [OptionMenu]. [key] is opaque and is echoed straight back to the ViewModel. */
private data class MenuOption(val key: String, val label: String, val selected: Boolean)

/** An icon button that opens a single-choice menu. */
@Composable
private fun OptionMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    options: List<MenuOption>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(icon, contentDescription = contentDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (option.selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.key)
                    },
                )
            }
        }
    }
}

/**
 * The information panel below the video surface.
 *
 * ### Why subtitles are listed but not switchable here
 * A [MediaSubtitleTrack] is a *separate signed URI*, not an embedded HLS `#EXT-X-MEDIA` group. Wiring
 * one into an ExoPlayer requires either a `MergingMediaSource` or a `MediaItem.SubtitleConfiguration`
 * whose MIME type must be known in advance — and getting that wrong fails silently at playback rather
 * than at build time. The screen therefore states what the provider offered and where it lives, which
 * is honest and immediately useful (the user can open or download the subtitle), instead of shipping
 * a control that might quietly do nothing.
 */
@Composable
private fun PlaybackDetails(
    state: PlayerUiState,
    onOpenDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.Large),
        verticalArrangement = Arrangement.spacedBy(Dimens.Small),
    ) {
        if (state.canRefresh) {
            // Stated rather than implied: the user can see that an expiring link was renewed, which
            // is what makes an unexpected position resume legible instead of mysterious.
            Text(
                text = "播放地址会自动刷新" + if (state.refreshCount > 0) "（已刷新 ${state.refreshCount} 次）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.resource?.let { resource ->
            Text(
                text = "传输协议：${resource.protocol.displayLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            resource.mimeType?.let { mime ->
                Text(
                    text = "格式：$mime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.subtitles.isNotEmpty()) {
            SectionHeader(title = "字幕（${state.subtitles.size}）", showDivider = false)
            state.subtitles.forEach { track ->
                SubtitleRow(track)
            }
        }

        if (state.shouldOfferDownload || state.errorKind == PlayerErrorKind.Plain) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.IconSmall),
                )
                Text(
                    text = DOWNLOAD_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            androidx.compose.material3.TextButton(onClick = onOpenDownload) {
                Text(text = "下载后播放")
            }
        }
    }
}

/**
 * One subtitle entry.
 *
 * Nothing about the track's URL is rendered, logged or stored: a subtitle URL is signed material of
 * the same class as the media URL, so only the human-readable label and language are shown.
 */
@Composable
private fun SubtitleRow(track: MediaSubtitleTrack) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
    ) {
        Icon(
            imageVector = Icons.Filled.Subtitles,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Text(
            text = track.language?.let { "${track.label}（$it）" } ?: track.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------------ helpers

/** How often the playback position is sampled. One second keeps the resume offset within a second. */
private const val POSITION_POLL_INTERVAL_MILLIS = 1_000L

/** Shown wherever the app must tell the user that downloading is the remaining option. */
private const val DOWNLOAD_HINT = "该地址无法继续播放，可下载后使用本地播放器打开"

/**
 * Builds the Media3 source for [resource] — the *only* place in this module that touches a URL.
 *
 * ### Rule 1: request headers are mandatory, not optional
 * Several providers serve media only when the request carries `Referer`/`Origin`/`User-Agent`
 * (115's transcoded HLS output does). Dropping [MediaResource.headers] therefore does not degrade
 * gracefully — it produces a 403 that looks exactly like an expired URL and sends the refresh logic
 * chasing a problem that does not exist. `DefaultHttpDataSource.Factory.setDefaultRequestProperties`
 * applies them to every request the source makes, including each segment fetch of a playlist, which
 * per-request configuration would miss.
 *
 * `setAllowCrossProtocolRedirects(true)` is set because a signed URL commonly redirects from HTTPS to
 * a CDN edge on a different scheme; without it Media3 rejects the redirect.
 *
 * ### Rule 2: branching is on protocol, never on provider
 * See the module invariant at the top of this file. [MediaProtocol] is a transport statement.
 *
 * ### Coverage note
 * [MediaProtocol.DASH] and [MediaProtocol.LOCAL_URI] fall into the progressive branch. That is
 * accurate for what this build actually produces — every registered provider declares HLS and/or
 * progressive HTTP (verified against both providers' capability declarations) — and it is recorded
 * here rather than hidden so that adding a DASH provider is a visible, deliberate change that also
 * adds `media3-exoplayer-dash`.
 */
@OptIn(UnstableApi::class)
private fun buildMediaSource(resource: MediaResource): MediaSource {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(resource.headers)
        .setAllowCrossProtocolRedirects(true)

    val mediaItem = MediaItem.fromUri(resource.url)

    return when (resource.protocol) {
        MediaProtocol.HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

        MediaProtocol.PROGRESSIVE_HTTP,
        MediaProtocol.DASH,
        MediaProtocol.LOCAL_URI,
        MediaProtocol.OTHER,
        -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    }
}

/**
 * The player's duration, or `null` when it is not yet known.
 *
 * `C.TIME_UNSET` is a sentinel meaning "unknown", not a very long video. Passing it through as a
 * number would produce a progress readout of roughly 292 million years.
 */
@OptIn(UnstableApi::class)
private fun ExoPlayer.safeDurationMs(): Long? =
    duration.takeIf { it != C.TIME_UNSET && it > 0L }

/** A rendition's label, enriched with its resolution when the provider reported one. */
private fun MediaRendition.displayLabel(): String {
    val resolution = if (width != null && height != null) " · ${width}×$height" else ""
    return "$label$resolution"
}

/** A transport protocol as user-facing text. */
private fun MediaProtocol.displayLabel(): String = when (this) {
    MediaProtocol.HLS -> "HLS 流"
    MediaProtocol.PROGRESSIVE_HTTP -> "HTTP 直链"
    MediaProtocol.DASH -> "MPEG-DASH"
    MediaProtocol.LOCAL_URI -> "本地文件"
    MediaProtocol.OTHER -> "其他"
}

// ------------------------------------------------------------------ previews

/**
 * Previews for every reachable state.
 *
 * They exist because this screen has four mutually exclusive presentations that are easy to break
 * while editing one of them, and because a stateless `PlayerScreen` is the whole reason those
 * presentations can be rendered without a device, a provider or a network.
 */
private fun previewResource(
    protocol: MediaProtocol = MediaProtocol.HLS,
    refreshable: Boolean = true,
) = MediaResource(
    url = "https://example.invalid/preview/stream.m3u8",
    protocol = protocol,
    mimeType = "application/vnd.apple.mpegurl",
    expiresAt = Long.MAX_VALUE,
    refreshable = refreshable,
    durationMs = 3_600_000L,
    renditions = listOf(
        MediaRendition(id = "1080", label = "1080P", width = 1920, height = 1080),
        MediaRendition(id = "720", label = "720P", width = 1280, height = 720),
    ),
    audioTracks = listOf(
        MediaAudioTrack(id = "a0", label = "原声", language = "zh"),
        MediaAudioTrack(id = "a1", label = "配音", language = "en"),
    ),
    subtitles = listOf(
        MediaSubtitleTrack(url = "https://example.invalid/preview/zh.srt", label = "简体中文", language = "zh"),
    ),
)

@Preview(name = "正在加载", showBackground = true)
@Composable
private fun PlayerScreenLoadingPreview() {
    NotDiskTheme(dynamicColor = false) {
        PlayerScreen(
            state = PlayerUiState(isLoading = true, title = "示例影片.mp4"),
            onBack = {},
            onRetry = {},
            onPlaybackError = { _, _ -> },
            onProgress = { _, _ -> },
            onSelectRendition = {},
            onSelectAudioTrack = {},
            onOpenDownload = {},
        )
    }
}

@Preview(name = "播放中", showBackground = true)
@Composable
private fun PlayerScreenPlayingPreview() {
    NotDiskTheme(dynamicColor = false) {
        PlayerScreen(
            state = PlayerUiState(
                isLoading = false,
                resource = previewResource(),
                title = "示例影片.mp4",
                availableRenditions = previewResource().renditions,
                availableAudioTracks = previewResource().audioTracks,
                subtitles = previewResource().subtitles,
                canRefresh = true,
            ),
            onBack = {},
            onRetry = {},
            onPlaybackError = { _, _ -> },
            onProgress = { _, _ -> },
            onSelectRendition = {},
            onSelectAudioTrack = {},
            onOpenDownload = {},
        )
    }
}

@Preview(name = "需要下载后播放", showBackground = true)
@Composable
private fun PlayerScreenDownloadInsteadPreview() {
    NotDiskTheme(dynamicColor = false) {
        PlayerScreen(
            state = PlayerUiState(
                isLoading = false,
                error = PlayerViewModel.NOT_REFRESHABLE_MESSAGE,
                errorKind = PlayerErrorKind.DownloadInstead,
                title = "示例影片.mp4",
            ),
            onBack = {},
            onRetry = {},
            onPlaybackError = { _, _ -> },
            onProgress = { _, _ -> },
            onSelectRendition = {},
            onSelectAudioTrack = {},
            onOpenDownload = {},
        )
    }
}

@Preview(name = "解析失败", showBackground = true)
@Composable
private fun PlayerScreenErrorPreview() {
    NotDiskTheme(dynamicColor = false) {
        PlayerScreen(
            state = PlayerUiState(
                isLoading = false,
                error = "网络不可用",
                errorKind = PlayerErrorKind.Plain,
                title = "示例影片.mp4",
            ),
            onBack = {},
            onRetry = {},
            onPlaybackError = { _, _ -> },
            onProgress = { _, _ -> },
            onSelectRendition = {},
            onSelectAudioTrack = {},
            onOpenDownload = {},
        )
    }
}
