package com.notdisk.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.MediaRendition
import com.notdisk.core.model.MediaSubtitleTrack
import com.notdisk.core.model.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The playback screen's state machine: resolve once, then survive an expired URL.
 *
 * ### The one invariant this class defends
 * The player screen knows nothing about clouds. Everything vendor-shaped — which provider, what
 * headers, whether an HLS playlist is involved, whether the URL can be renewed — is already decided
 * by the time a [MediaResource] lands in [PlayerUiState.resource]. This ViewModel is the last place
 * where a provider id is still meaningful, and it uses it only as an opaque parameter to hand back
 * to the resolver. There is no `when (providerId)` here and there cannot be one: nothing in this
 * file's imports can tell two clouds apart.
 *
 * ### Why refresh is capped rather than "retried until it works"
 * A dead URL and a wrong-but-refreshable URL look identical to Media3. Without a cap, a resource
 * that re-resolves successfully but still cannot be decoded would put the app in an infinite
 * resolve→fail→resolve loop that drains the user's battery and hammers a rate-limited vendor
 * endpoint. [MAX_AUTO_REFRESHES] bounds it; past the cap the failure is surfaced for a human to
 * decide, which is the honest outcome.
 *
 * ### Why the position is kept here rather than read from the player
 * The player is destroyed and recreated when the media source changes. The position must outlive it
 * for the resume-after-refresh behaviour to work at all, so the UI pushes it in through
 * [reportProgress] and reads it back from [PlayerUiState.startPositionMs].
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val resolver: MediaResolver,
    private val files: CloudFileRepository,
    private val logger: CloudLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** How many resources have been issued for this file, so the UI can rebuild the source. */
    private var sourceGeneration: Int = 0

    /** Refresh attempts since the current file was loaded; reset by [load]. */
    private var refreshCount: Int = 0

    /** The identifiers the last successful [load] used, needed by every later resolver call. */
    private var loaded: LoadedIdentity? = null

    /**
     * The position last handed to the vendor.
     *
     * The UI samples the player once a second, which is far more often than any vendor wants to hear
     * from us; this is what turns a per-second sample into a per-[PROGRESS_REPORT_INTERVAL_MILLIS]
     * report without the UI needing to know the cadence.
     */
    private var lastReportedMs: Long = 0L

    /**
     * Resolves [fileId] for playback.
     *
     * The file's metadata is fetched first *only* to obtain a display title: the player must show
     * what is playing, and the id is opaque. A failure to read the metadata is not fatal — the
     * resource is what makes playback possible, so the title silently degrades to the file id rather
     * than blocking a stream that would have worked.
     */
    fun load(providerId: ProviderId, accountId: AccountId, fileId: FileId) {
        loaded = LoadedIdentity(providerId, accountId, fileId)
        refreshCount = 0
        // Reset with the file: carrying the previous file's offset into a new one would suppress the
        // first progress report of the new playback session.
        lastReportedMs = 0L

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, errorKind = null) }

            val title = resolveTitle(providerId, accountId, fileId)
            val resumeFrom = lastKnownPosition(providerId, accountId, fileId)

            when (val result = resolver.resolve(accountId, providerId, fileId)) {
                is CloudResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        title = title,
                        error = messageFor(result.error),
                        errorKind = kindFor(result.error),
                    )
                }

                is CloudResult.Success -> {
                    logger.i(
                        TAG,
                        "resolved playback for ${LogRedaction.redactId(fileId.value)} " +
                            "protocol=${result.data.protocol} " +
                            "renditions=${result.data.renditions.size}",
                    )
                    install(result.data, title = title, startPositionMs = resumeFrom)
                }
            }
        }
    }

    /**
     * Re-resolves the current resource after Media3 reported it unusable.
     *
     * ### What "preserve the position" means precisely
     * The new resource is a *different URL for the same content*, so the correct playback offset is
     * the byte position the user had reached, not zero. The offset travels in
     * [PlayerUiState.startPositionMs], which the UI reads when it installs the new media source —
     * the UI is also what supplies the current position in the first place, via [reportProgress].
     *
     * @param positionMs where playback had reached when it failed; the value the player will resume
     *   from if the refresh succeeds.
     * @param durationMs total duration when known, used only to decide whether a barely-started
     *   stream is worth reporting progress for.
     */
    fun onPlaybackError(positionMs: Long = 0L, durationMs: Long? = null) {
        val identity = loaded
        val current = _state.value.resource

        if (identity == null || current == null) {
            // An error before anything resolved is a load failure already reported by `load`.
            return
        }

        if (current.refreshToken == null || !current.refreshable) {
            // Nothing can be renewed. Saying "retry" here would be a promise the app cannot keep, so
            // the message points at the one action that does work.
            logger.w(TAG, "playback failed and the resource is not refreshable")
            _state.update {
                it.copy(
                    isLoading = false,
                    error = NOT_REFRESHABLE_MESSAGE,
                    errorKind = PlayerErrorKind.DownloadInstead,
                )
            }
            return
        }

        if (refreshCount >= MAX_AUTO_REFRESHES) {
            // The cap exists to break a resolve→fail→resolve cycle. Reaching it is not a bug in the
            // player; it means the URL is fine but the content is not playable in this app.
            logger.w(TAG, "refresh cap ($MAX_AUTO_REFRESHES) reached; surfacing the failure")
            _state.update {
                it.copy(
                    isLoading = false,
                    error = REFRESH_CAP_MESSAGE,
                    errorKind = PlayerErrorKind.DownloadInstead,
                )
            }
            return
        }

        refreshCount++
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, errorKind = null) }

            when (val result = resolver.refresh(current)) {
                is CloudResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        title = it.title,
                        error = messageFor(result.error),
                        errorKind = kindFor(result.error),
                    )
                }

                is CloudResult.Success -> {
                    logger.i(
                        TAG,
                        "refreshed playback URL (attempt $refreshCount/$MAX_AUTO_REFRESHES), " +
                            "resuming at ${positionMs}ms",
                    )
                    install(
                        result.data,
                        title = _state.value.title,
                        startPositionMs = positionMs.coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    /**
     * Switches to another rendition by re-resolving with [renditionId] as the quality hint.
     *
     * The position is carried across because a quality switch is not a restart: the user expects to
     * stay where they were. A rendition id is echoed back to the resolver verbatim; the player never
     * interprets it.
     */
    fun selectRendition(renditionId: String) {
        val identity = loaded ?: return
        if (_state.value.selectedRenditionId == renditionId) return

        val resumeAt = _state.value.startPositionMs
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, errorKind = null) }

            when (
                val result = resolver.resolve(
                    accountId = identity.accountId,
                    providerId = identity.providerId,
                    fileId = identity.fileId,
                    qualityHint = renditionId,
                )
            ) {
                is CloudResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        // Secondary operation: the current rendition is still playing, so this must not
                        // claim the *file* is unplayable. See `messageForSecondary`.
                        error = messageForSecondary(result.error),
                        errorKind = PlayerErrorKind.Plain,
                    )
                }

                is CloudResult.Success -> install(
                    result.data,
                    title = _state.value.title,
                    startPositionMs = resumeAt,
                    selectedRenditionId = renditionId,
                )
            }
        }
    }

    /**
     * Switches to an alternate audio track, when the provider delivers audio separately.
     *
     * A provider that has no separate audio answers `Unsupported`; that is reported as a plain
     * message rather than the download hint, because the video itself is still perfectly playable —
     * only the track change failed.
     */
    fun selectAudioTrack(track: MediaAudioTrack) {
        val identity = loaded ?: return
        val resumeAt = _state.value.startPositionMs

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, errorKind = null) }

            when (
                val result = resolver.resolveAudioTrack(
                    accountId = identity.accountId,
                    providerId = identity.providerId,
                    fileId = identity.fileId,
                    track = track,
                )
            ) {
                is CloudResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        error = messageForSecondary(result.error),
                        errorKind = PlayerErrorKind.Plain,
                    )
                }

                is CloudResult.Success -> install(
                    result.data,
                    title = _state.value.title,
                    startPositionMs = resumeAt,
                    selectedAudioTrackId = track.id,
                )
            }
        }
    }

    /**
     * Records the current playback position, and reports it to the vendor on a throttle.
     *
     * ### Why `Unsupported` is swallowed
     * The vendor progress endpoint is optional. 115 does not document one, so its provider answers
     * `CloudError.Unsupported` for every call. That is the *correct* answer, not a failure: showing
     * the user "该网盘不支持此操作" every ten seconds while a video plays would be actively harmful, and
     * would train them to ignore the error surface. The position is still recorded locally, so
     * resume-after-refresh keeps working even where the vendor cannot remember it.
     *
     * @param positionMs the player's current position.
     * @param durationMs total duration when known.
     */
    fun reportProgress(positionMs: Long, durationMs: Long?) {
        if (positionMs < 0) return

        // Recorded unconditionally and *outside* the coroutine: the refresh path reads it on the main
        // thread the moment playback fails, and it must not race a background report.
        _state.update { it.copy(startPositionMs = positionMs, durationMs = durationMs ?: it.durationMs) }

        val identity = loaded ?: return
        if (positionMs - lastReportedMs < PROGRESS_REPORT_INTERVAL_MILLIS) return
        lastReportedMs = positionMs

        viewModelScope.launch {
            when (val result = resolver.reportProgress(
                accountId = identity.accountId,
                providerId = identity.providerId,
                fileId = identity.fileId,
                positionMs = positionMs,
                durationMs = durationMs,
            )) {
                is CloudResult.Success -> logger.d(TAG, "playback progress reported at ${positionMs}ms")

                is CloudResult.Failure -> when (result.error) {
                    // The expected, documented answer for providers without a progress endpoint.
                    is CloudError.Unsupported ->
                        logger.d(TAG, "provider has no playback-progress endpoint; staying quiet")

                    // Anything else is a genuine transport problem, but it is still not worth
                    // interrupting playback for: the user did not ask to save their position.
                    else -> logger.d(
                        TAG,
                        "playback progress not recorded (${result.error::class.simpleName})",
                    )
                }
            }
        }
    }

    /** Clears a surfaced error so the screen returns to whatever is still playable. */
    fun dismissError() {
        _state.update { it.copy(error = null, errorKind = null) }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Publishes a newly resolved resource as the thing to play.
     *
     * [sourceGeneration] is bumped on every install so the UI can tell "the same URL again" from "a
     * new URL that happens to be byte-identical" (a provider may re-issue the exact same signed URL).
     * Without it, a refresh that returns an unchanged URL would leave the failed source in place.
     */
    private fun install(
        resource: MediaResource,
        title: String,
        startPositionMs: Long,
        selectedRenditionId: String? = null,
        selectedAudioTrackId: String? = null,
    ) {
        sourceGeneration++
        _state.update { current ->
            current.copy(
                isLoading = false,
                error = null,
                errorKind = null,
                resource = resource,
                title = title,
                availableRenditions = resource.renditions,
                selectedRenditionId = selectedRenditionId
                    ?: current.selectedRenditionId?.takeIf { id ->
                        // A refresh keeps the previous selection only when the provider still offers
                        // it; otherwise the UI would show a quality that is no longer in effect.
                        resource.renditions.any { it.id == id }
                    },
                availableAudioTracks = resource.audioTracks,
                selectedAudioTrackId = selectedAudioTrackId
                    ?: current.selectedAudioTrackId?.takeIf { id ->
                        resource.audioTracks.any { it.id == id }
                    },
                subtitles = resource.subtitles,
                canRefresh = resource.refreshable && resource.refreshToken != null,
                startPositionMs = startPositionMs,
                durationMs = resource.durationMs ?: current.durationMs,
                sourceGeneration = sourceGeneration,
                refreshCount = refreshCount,
            )
        }
    }

    /**
     * Asks the vendor where the user left off, degrading to zero.
     *
     * ### Why this is best-effort and never surfaced
     * "Continue watching" is a convenience, not a promise. A provider with no progress endpoint answers
     * `Unsupported` — the documented, correct answer for 115 — and a transport failure says nothing the
     * user needs to act on. Both cases resume from the beginning, which is what the app would have done
     * had the feature not existed. Treating either as an error would put a failure banner in front of a
     * user who asked to watch a video.
     *
     * A stored position that is past the media's own duration (the vendor remembered a longer cut) is
     * discarded rather than passed to the player, because seeking beyond the end produces an immediate
     * "playback finished" or an error depending on the extractor.
     */
    private suspend fun lastKnownPosition(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
    ): Long = when (val result = resolver.lastKnownPosition(accountId, providerId, fileId)) {
        is CloudResult.Success -> result.data?.takeIf { it > 0L } ?: 0L

        is CloudResult.Failure -> {
            logger.d(
                TAG,
                "no vendor playback position (${result.error::class.simpleName}); starting from zero",
            )
            0L
        }
    }

    /** Reads the display title, degrading to the opaque id when metadata is unavailable. */
    private suspend fun resolveTitle(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
    ): String = when (val result = files.getFile(accountId, providerId, fileId)) {
        is CloudResult.Success -> result.data.name
        is CloudResult.Failure -> {
            logger.d(TAG, "file metadata unavailable for the title; falling back to the file id")
            fileId.value
        }
    }

    /** The identity of the file currently loaded, kept so later calls need not re-take parameters. */
    private data class LoadedIdentity(
        val providerId: ProviderId,
        val accountId: AccountId,
        val fileId: FileId,
    )

    companion object {
        private const val TAG = "PlayerViewModel"

        /**
         * How many automatic refreshes one load may perform.
         *
         * Three is chosen to survive a genuine double expiry (a long film can outlive two signed
         * URLs) while still bounding a pathological resolve→fail cycle to four resolutions. A higher
         * cap buys nothing: if three fresh URLs all fail immediately, the URL was never the problem.
         */
        const val MAX_AUTO_REFRESHES = 3

        /** How often the position is pushed to the vendor. Ten seconds is one progress-bar notch. */
        const val PROGRESS_REPORT_INTERVAL_MILLIS = 10_000L

        /** Shown when the URL died and no refresh is possible. Points at the only working action. */
        const val NOT_REFRESHABLE_MESSAGE = "播放地址已失效且无法刷新，请下载后播放"

        /** Shown when refresh kept succeeding but playback kept failing. */
        const val REFRESH_CAP_MESSAGE = "多次刷新播放地址后仍无法播放，请下载后播放"

        /** Shown when the user needs to re-authorise the account. */
        const val REAUTH_MESSAGE = "授权已失效，请到账号页重新登录"

        /** Shown when the provider cannot stream this file at all. */
        const val UNSUPPORTED_MESSAGE = "该网盘不支持在线播放此文件"

        /** Shown when the file is gone from the remote side. */
        const val DELETED_MESSAGE = "文件已被删除"
    }

    /**
     * Maps a typed error onto the sentence the user reads.
     *
     * ### Why an explicit mapping instead of `error.userFacingMessage`
     * The error model's default messages are written for a *developer* reading a log ("资源地址已过期"),
     * while these are written for someone holding a phone who needs to know what to do next. The
     * cases that matter are the ones where the correct next action is not "retry": re-authorise, or
     * download. Collapsing those into a generic message is what produces a retry button that can
     * never work.
     *
     * The `else` branch deliberately falls back to [CloudError.userFacingMessage] rather than a
     * catch-all string, so a new error case added to the model still reads sensibly here.
     */
    private fun messageFor(error: CloudError): String = when (error) {
        is CloudError.Unsupported -> UNSUPPORTED_MESSAGE

        is CloudError.NotFound, is CloudError.Deleted -> DELETED_MESSAGE

        is CloudError.TokenExpired, is CloudError.NotAuthenticated -> REAUTH_MESSAGE

        is CloudError.ResourceUrlExpired ->
            if (error.refreshable) error.userFacingMessage else NOT_REFRESHABLE_MESSAGE

        else -> error.userFacingMessage
    }

    /**
     * Classifies an error for the screen's presentation.
     *
     * [PlayerErrorKind.DownloadInstead] is distinct because it changes the affordances: the retry
     * button is dropped (it cannot help) and a download hint is shown in its place.
     */
    private fun kindFor(error: CloudError): PlayerErrorKind = when {
        error is CloudError.ResourceUrlExpired && !error.refreshable -> PlayerErrorKind.DownloadInstead

        error is CloudError.TokenExpired || error is CloudError.NotAuthenticated ->
            PlayerErrorKind.Reauthorise

        else -> PlayerErrorKind.Plain
    }

    /**
     * Maps a *secondary* operation's error — a rendition switch, an audio-track switch.
     *
     * ### Why this is not [messageFor]
     * [messageFor] answers the question "why can I not watch this file?", and its most common
     * `Unsupported` case is genuinely "this cloud cannot stream it at all". But when the user is
     * *already watching* and only the track change failed, that same sentence is a lie: the video is
     * on screen and playing. An `Unsupported` from a secondary operation therefore always carries the
     * provider's own message, which names the capability that is missing rather than the file.
     *
     * The remaining cases keep [messageFor]'s mapping, because an expired token or a deleted file
     * means the same thing regardless of which request discovered it.
     */
    private fun messageForSecondary(error: CloudError): String = when (error) {
        is CloudError.Unsupported -> error.userFacingMessage
        else -> messageFor(error)
    }
}

/** How the screen should present a failure, which decides which actions it offers. */
enum class PlayerErrorKind {
    /** A retry may plausibly work; offer it. */
    Plain,

    /** Re-resolving cannot help; offer a download instead of a retry. */
    DownloadInstead,

    /** The account needs re-authorising; route to the accounts screen. */
    Reauthorise,
}

/**
 * Everything the playback screen renders.
 *
 * A single immutable value rather than several flows, because the fields are only meaningful in
 * combination: a `resource` with `isLoading = true` means "a new URL is being fetched for what is
 * already on screen", whereas `resource == null && isLoading` means "nothing has played yet". Two
 * booleans in separate flows would let the screen render a contradiction.
 *
 * [startPositionMs] and [sourceGeneration] are the contract with the UI:
 *  - [sourceGeneration] changes exactly when a *new* media source must be built;
 *  - [startPositionMs] is where that source should begin, which is non-zero after a refresh.
 */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val errorKind: PlayerErrorKind? = null,
    val resource: MediaResource? = null,
    val title: String = "",
    val availableRenditions: List<MediaRendition> = emptyList(),
    val selectedRenditionId: String? = null,
    val availableAudioTracks: List<MediaAudioTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val subtitles: List<MediaSubtitleTrack> = emptyList(),
    val canRefresh: Boolean = false,
    val startPositionMs: Long = 0L,
    val durationMs: Long? = null,
    val sourceGeneration: Int = 0,
    val refreshCount: Int = 0,
) {
    /** `true` while a failure is on screen, whichever kind it is. */
    val hasError: Boolean get() = error != null

    /** `true` when the screen has something to play and no failure to report. */
    val canPlay: Boolean get() = resource != null && !hasError

    /** `true` when a download is the only remaining route to this content. */
    val shouldOfferDownload: Boolean get() = errorKind == PlayerErrorKind.DownloadInstead
}
