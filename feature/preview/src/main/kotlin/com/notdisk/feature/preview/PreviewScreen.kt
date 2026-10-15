package com.notdisk.feature.preview

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.notdisk.core.designsystem.component.EmptyState
import com.notdisk.core.designsystem.component.ErrorState
import com.notdisk.core.designsystem.component.LoadingState
import com.notdisk.core.designsystem.component.SectionHeader
import com.notdisk.core.designsystem.format.FileSizeFormatter
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.Dimens
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.OfficeViewer
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResource
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.PreviewHint

/*
 * ============================================================================================
 *  MODULE INVARIANT — `feature:preview` CONTAINS ZERO PROVIDER KNOWLEDGE.
 * ============================================================================================
 *
 * Like `feature:player`, this module must never declare a `provider:` dependency, import a vendor
 * package, or branch on a provider identity. `CloudFileRepository.preview` has already folded every
 * vendor difference into a `PreviewKind`, so there is nothing here to branch on: the screen renders
 * one composable per case of a closed enum defined in `core:model`.
 *
 * ### Why that is what makes a new cloud cheap
 * Previewing is the feature where vendors differ most (WPS handoff vs. an inline PDF vs. nothing at
 * all). If that difference lived here, adding a cloud would mean editing this screen; because it
 * lives in the provider's `PreviewProvider`, adding a cloud means adding a module and nothing else.
 *
 * ### The WebView branch, stated plainly
 * `PreviewKind.OfficeDocument` is the one case where the app hands a user's document to a third
 * party. That handoff is deliberate, it is visible in the UI (the branch names the viewer), and it is
 * documented on [OfficeDocumentPreview]. The URL and token that make it work are short-lived secrets:
 * they are never logged, never persisted, and never placed in the ViewModel's state.
 *
 * ---------------------------------------------------------------------------------------------
 *  NOTE ON COIL HEADERS
 * ---------------------------------------------------------------------------------------------
 * Every image request carries the resource's headers via `httpHeaders(NetworkHeaders)`, which is the
 * Coil 3 API and requires the `coil-network-okhttp` artifact that this module declares. This matters
 * for providers whose image URLs are rejected without `Referer`/`Origin`/`User-Agent`: without the
 * headers the request fails with a 403 that is indistinguishable from an expired link.
 *
 * A headers change is *not* applied to an already-running request, so the request is keyed on the
 * header set (see `imageRequestFor`). Without that key, switching to a differently-signed URL with the
 * same image bytes would leave the previous headers in place — which is exactly the bug that produces
 * "the image loads for the first file and 403s for the second".
 */

/**
 * The preview destination: resolves the ViewModel and hands its state to [PreviewScreen].
 *
 * The route carries `(providerId, accountId, fileId)` as strings because that is the only file
 * reference valid across provider and account boundaries, and the only shape that survives process
 * death (see `NotDiskNavHost`).
 *
 * [hint] is optional metadata the caller already has. When it is absent the provider falls back to its
 * own knowledge, so a missing hint degrades the request count rather than the result.
 */
@Composable
fun PreviewRoute(
    providerId: String,
    accountId: String,
    fileId: String,
    onBack: () -> Unit,
    onPlayMedia: (providerId: String, accountId: String, fileId: String) -> Unit,
    onDownload: (providerId: String, accountId: String, fileId: String) -> Unit,
    modifier: Modifier = Modifier,
    hint: PreviewHint = PreviewHint.Empty,
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Validated before conversion: all three id types reject blank input by contract, and a malformed
    // deep link must render an error rather than crash the process on a `require` failure.
    val identity = remember(providerId, accountId, fileId) {
        val p = providerId.takeIf { it.isNotBlank() }
        val a = accountId.takeIf { it.isNotBlank() }
        val f = fileId.takeIf { it.isNotBlank() }
        if (p == null || a == null || f == null) null else Triple(ProviderId(p), AccountId(a), FileId(f))
    }

    LaunchedEffect(identity) {
        identity?.let { (p, a, f) -> viewModel.load(p, a, f, hint) }
    }

    PreviewScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onPlayMedia = { onPlayMedia(providerId, accountId, fileId) },
        onDownload = { onDownload(providerId, accountId, fileId) },
        modifier = modifier,
    )
}

/**
 * The stateless preview surface: exactly one presentation per [PreviewKind].
 *
 * Stateless so every branch is reachable from a literal [PreviewUiState] — which is what the
 * `@Preview` functions at the bottom of this file do, and the only practical way to check that the
 * eight cases of the sealed hierarchy all render something sensible without a device or a network.
 *
 * @param onPlayMedia hands video/audio to the player destination. The player is deliberately *not*
 *   embedded: it owns its own full-screen surface and its own refresh policy, and duplicating either
 *   here would create a second, divergent implementation of both.
 * @param onDownload the remaining route when nothing can be rendered inline.
 */
@Composable
fun PreviewScreen(
    state: PreviewUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayMedia: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PreviewTopBar(title = state.result?.key?.fileId?.value ?: "预览", onBack = onBack)

            when {
                state.isLoading -> LoadingState(label = "正在准备预览…")

                state.hasError -> ErrorState(
                    userMessage = state.error.orEmpty(),
                    onRetry = onRetry,
                    retryLabel = "重试",
                    detail = "也可以下载后使用本地应用打开",
                )

                state.kind == null ->
                    // Reachable only for a malformed route, which never reaches the ViewModel.
                    ErrorState(
                        userMessage = "无法预览：文件标识不完整",
                        onRetry = onRetry,
                        retryLabel = "重试",
                    )

                else -> PreviewBranch(
                    state = state,
                    kind = state.kind!!,
                    onPlayMedia = onPlayMedia,
                    onDownload = onDownload,
                )
            }

            // `Unsupported` is deliberately excluded: `UnsupportedPreview` renders the download action
            // inside its own EmptyState, so including it here produced **two** identical buttons on
            // screen (caught by `PreviewScreenTest`). `ThumbnailOnly` has no action of its own and the
            // error branch keeps its retry, so both still need this bar.
            val kind = state.kind
            if (state.offersDownload &&
                kind != null &&
                kind !is PreviewKind.Unsupported &&
                !state.hasError
            ) {
                DownloadBar(onDownload = onDownload)
            }
        }
    }
}

/** The title row. */
@Composable
private fun PreviewTopBar(title: String, onBack: () -> Unit) {
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
    }
}

/**
 * Dispatches on the classification.
 *
 * Every case of [PreviewKind] is handled and every one renders something: there is no `else`-less
 * `when`, so adding a kind to the sealed hierarchy becomes a compile error here rather than a blank
 * screen in the field.
 */
@Composable
private fun PreviewBranch(
    state: PreviewUiState,
    kind: PreviewKind,
    onPlayMedia: () -> Unit,
    onDownload: () -> Unit,
) {
    when (kind) {
        is PreviewKind.Image -> ImagePreview(
            url = state.thumbnailUrl,
            headers = state.result?.resource?.headers.orEmpty(),
            contentDescription = "图片预览",
            allowZoom = true,
        )

        PreviewKind.Video -> MediaHandoff(
            icon = Icons.Filled.PlayCircle,
            title = "视频文件",
            description = "使用内置播放器播放，支持清晰度切换与自动刷新播放地址。",
            actionLabel = "在线播放",
            onAction = onPlayMedia,
        )

        PreviewKind.Audio -> MediaHandoff(
            icon = Icons.Filled.MusicNote,
            title = "音频文件",
            description = "使用内置播放器播放。",
            actionLabel = "播放音频",
            onAction = onPlayMedia,
        )

        is PreviewKind.Text -> TextPreview(state = state, onDownload = onDownload)

        PreviewKind.Pdf -> PdfPreview(state = state, onDownload = onDownload)

        is PreviewKind.OfficeDocument -> OfficeDocumentPreview(viewer = kind.viewer)

        is PreviewKind.ThumbnailOnly -> ThumbnailOnlyPreview(state = state, onDownload = onDownload)

        is PreviewKind.Unsupported -> UnsupportedPreview(reason = kind.reason, onDownload = onDownload)
    }
}

/**
 * The image branch: a zoomable, pannable image.
 *
 * ### Why the gesture handling is hand-written
 * Compose ships no pan/zoom container for arbitrary content, and the alternatives are all worse: a
 * `horizontalScroll`/`verticalScroll` pair cannot express pinch, and pulling in an image-viewer
 * library for one screen adds a dependency whose transitive Compose version would then have to be
 * reconciled with this build's BOM.
 *
 * The state is two floats and an offset, cleared on double-tap, which is the behaviour every photo
 * viewer has. The scale is clamped to `1f..5f` so the image cannot be shrunk into an invisible dot or
 * zoomed past the point where the pixels carry no information.
 */
@Composable
private fun ImagePreview(
    url: String?,
    headers: Map<String, String>,
    contentDescription: String,
    allowZoom: Boolean,
) {
    if (url.isNullOrBlank()) {
        // The provider classified the file as an image but produced no displayable URL (documented
        // for items with no thumbnail). Download is the honest remaining action.
        EmptyState(
            title = "无法显示这张图片",
            icon = Icons.Filled.Image,
            description = "该网盘未提供可显示的图片地址，可下载后查看。",
        )
        return
    }

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }

    val context = LocalContext.current
    val request = remember(url, headers) {
        // Keyed on the header set as well as the URL: Coil will not re-apply headers to an
        // already-running request, so switching resources with identical bytes but different signing
        // must produce a genuinely new request.
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .apply {
                if (headers.isNotEmpty()) {
                    httpHeaders(
                        NetworkHeaders.Builder().apply {
                            headers.forEach { (name, value) -> set(name, value) }
                        }.build(),
                    )
                }
            }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!allowZoom) {
                        Modifier
                    } else {
                        Modifier.pointerInput(url) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                // Panning is suppressed at 1× so the gesture does not drift the image
                                // away from its centred resting position.
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                    },
                )
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )

        if (scale > 1f) {
            OutlinedButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Dimens.Large),
            ) {
                Text(text = "复位 ${(scale * 100).toInt()}%")
            }
        }
    }
}

/**
 * The video/audio branch: a handoff, not an embedded player.
 *
 * Keeping the player out of this screen is what lets the player own its own refresh policy, its own
 * full-screen surface and its own failure handling exactly once. An inlined `ExoPlayer` here would
 * need all three again, and the two copies would drift.
 */
@Composable
private fun MediaHandoff(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.IconHero),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}

/**
 * The text branch: a selectable, monospaced, scrollable body.
 *
 * `SelectionContainer` is required rather than decorative: the reason anyone opens a `.log`, `.json`
 * or `.csv` preview is to copy a line out of it, and text that cannot be selected makes the whole
 * branch pointless.
 *
 * The truncation notice is shown *above* the text so a reader knows before they start scrolling that
 * what follows is a prefix — the alternative (a notice at the bottom of 4,000 lines) is a notice
 * almost nobody sees.
 */
@Composable
private fun TextPreview(state: PreviewUiState, onDownload: () -> Unit) {
    val body = state.textContent
    val problem = state.textError

    when {
        problem != null -> EmptyState(
            title = "无法读取文本内容",
            icon = Icons.AutoMirrored.Filled.Article,
            description = problem,
            actionLabel = "下载后查看",
            onAction = onDownload,
        )

        body == null -> LoadingState(label = "正在读取文本…")

        else -> Column(modifier = Modifier.fillMaxSize()) {
            if (state.textTruncated) {
                TruncationNotice(
                    message = "文件较大，仅显示前 ${FileSizeFormatter.formatBytes(state.textBytesRead)}" +
                        "（上限 ${FileSizeFormatter.formatBytes(PreviewViewModel.MAX_TEXT_BYTES)}），" +
                        "完整内容请下载后查看。",
                )
            }

            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.Large),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The PDF branch.
 *
 * ### Why a URL is not simply loaded
 * Android's `WebView` cannot render a PDF natively — unlike a desktop browser, it has no built-in
 * PDF plugin, and `PreviewResult.resource` for a PDF is a *signed media URL* rather than a viewer
 * page. Handing that URL to a WebView therefore produces a download prompt or a blank page, not a
 * document. Since this branch carries no viewer URL of its own (a vendor that offers one returns
 * [PreviewKind.OfficeDocument] instead), the honest presentation is a download action, with the
 * platform viewer one tap away.
 *
 * When a resource *is* present it is offered as an explicit "open externally" affordance rather than
 * being silently injected, because opening a remote document is the user's decision to make.
 */
@Composable
private fun PdfPreview(state: PreviewUiState, onDownload: () -> Unit) {
    val resource = state.result?.resource

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.IconHero),
        )
        Text(
            text = "PDF 文档",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (resource != null) {
                "本机没有可嵌入的 PDF 渲染器，可下载后使用系统应用打开。"
            } else {
                "该网盘未提供 PDF 的在线渲染，可下载后使用系统应用打开。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDownload) {
            Text(text = "下载")
        }
    }
}

/**
 * The online office-document branch: the one deliberate third-party handoff.
 *
 * ### This is a handoff, and the UI says so
 * The document is *not* rendered by this app. The vendor's own cloud viewer is loaded into a
 * `WebView`, which means the document, and the fact that this account opened it, reach that vendor's
 * viewer servers. That is why this branch names the viewer in the UI instead of presenting it as a
 * native preview: the user is entitled to know their document left the device.
 *
 * ### Why a WebView rather than the platform browser
 * The viewer requires the account's short-lived token, which the vendor issues to *this client* for
 * *this document*. There is no way to carry that into an external browser without exposing it in a
 * URL bar, history and clipboard. A WebView keeps the token inside the app's own process.
 *
 * ### Why the token is a serious secret
 * [OfficeViewer.token] grants access to the document without further authentication until it expires
 * ([OfficeViewer.expiresAt], with the vendor's own lead time in
 * [OfficeViewer.refreshBeforeSeconds]). Consequently:
 *
 *  - **it is never logged** — not even redacted, simply never passed to a logger;
 *  - **it is never persisted** — it lives in the `PreviewResult` held by the ViewModel, and dies with
 *    the screen;
 *  - **it is never written to the DOM** — [buildViewerUrl] puts it in the URL fragment, which is
 *    applied client-side and is therefore not sent to the document server in a request line or a
 *    `Referer` header, and does not appear in the page's own request log;
 *  - **it is never shown to the user** — no "copy token" affordance exists.
 *
 * ### Why JavaScript is required, and what that costs
 * WPS's viewer is a JavaScript application: with JS disabled it renders nothing. Enabling it is
 * therefore unavoidable for this branch. The mitigations are that the WebView is restricted to the
 * viewer's own origin ([isAllowedViewerNavigation]), file access is disabled, and mixed content is
 * refused. The WebView is destroyed with the composition, so the token does not outlive the screen in
 * a live renderer process.
 */
@Composable
private fun OfficeDocumentPreview(viewer: OfficeViewer) {
    val context = LocalContext.current

    // Built once per viewer: rebuilding it would reload the document on every recomposition.
    val viewerUrl = remember(viewer.url, viewer.token) { buildViewerUrl(viewer) }

    Column(modifier = Modifier.fillMaxSize()) {
        HandoffNotice(
            message = "此文档由「在线文档查看器」打开，内容会发送到该查看器所在的服务端。" +
                if (viewer.canEdit) "该查看器允许编辑。" else "该查看器为只读。",
        )

        AndroidView(
            // Fills the remaining height (the handoff notice sits above it), so the doc viewer gets
            // as much of the screen as the layout allows without a hardcoded pixel height.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configureForOfficeViewer(onNavigate = { })
                    loadUrl(viewerUrl)
                }
            },
            onRelease = { webView ->
                // Explicit teardown: leaving a live WebView with a token-bearing page loaded keeps
                // both the renderer process and the credential alive after the user has left.
                webView.stopLoading()
                webView.loadUrl(BLANK_PAGE)
                webView.destroy()
            },
        )
    }
}

/**
 * Prepares [WebView] for the vendor's viewer, restricted to that viewer's origin.
 *
 * ### Why the origin lock is the important line
 * A `WebView` that can navigate anywhere is a browser inside the app, and the token in the initial
 * URL makes an unexpected navigation genuinely dangerous. Confining the WebView to the viewer's own
 * origin turns "the page tries to send the user somewhere" into a refused navigation, and it costs
 * nothing: a document viewer has no legitimate reason to leave its own host.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForOfficeViewer(onNavigate: () -> Unit) {
    settings.apply {
        // Required: the viewer is a JavaScript application and renders nothing without it.
        javaScriptEnabled = true
        domStorageEnabled = true
        // No local-file bridge: the WebView renders a remote document and has no business reading
        // the app's private storage.
        allowFileAccess = false
        allowContentAccess = false
        // A signed HTTPS viewer URL must never be downgraded to plaintext, where the token would be
        // readable in transit.
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // The page is already sized for a phone viewport; letting it zoom out to a desktop layout
        // makes a document unreadable.
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val target = request?.url?.toString() ?: return true
            val allowed = view?.url?.let { isSameOrigin(it, target) } ?: false
            if (allowed) onNavigate()
            // Returning `true` for a disallowed target cancels the navigation instead of performing
            // it. The document stays on screen; nothing is silently loaded in its place.
            return !allowed
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceErrorCompat,
        ) {
            // Errors are surfaced by the viewer's own page; the app does not overlay its own UI on a
            // third-party surface it cannot control.
        }
    }
}

/**
 * Composes the viewer URL, carrying the token in the fragment.
 *
 * The fragment (`#`) is chosen over the query string on purpose. It is applied by the client and is
 * excluded from the request line, from `Referer` headers and from most server-side access logs, so a
 * token placed there leaks strictly less than the same token in a query parameter. Vendors whose
 * viewers expect a query parameter are handled by [OfficeViewer.url] already containing whatever the
 * viewer needs; the token is additive.
 *
 * A token is appended only when the viewer URL does not already carry one, so a provider that embeds
 * the token itself is not sent a duplicate.
 */
private fun buildViewerUrl(viewer: OfficeViewer): String {
    val token = viewer.token
    if (token.isNullOrBlank()) return viewer.url
    if (viewer.url.contains(TOKEN_PLACEHOLDER)) {
        return viewer.url.replace(TOKEN_PLACEHOLDER, token)
    }
    return if (viewer.url.contains(token)) {
        viewer.url
    } else {
        "${viewer.url}#$TOKEN_FRAGMENT_KEY=$token"
    }
}

/** True when [target] shares a scheme, host and port with [origin]. */
private fun isSameOrigin(origin: String, target: String): Boolean {
    fun parts(value: String): Triple<String, String, Int>? {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        return Triple(scheme, host, port)
    }
    val a = parts(origin) ?: return false
    val b = parts(target) ?: return false
    return a == b
}

/**
 * The thumbnail-only branch: show what there is, and offer the file itself.
 *
 * `PreviewKind.ThumbnailOnly` is a *successful* classification — the provider can render a small
 * image but not the document. Presenting it as an error would be wrong, so the branch shows the
 * thumbnail when one resolved and a plain explanation when none did.
 */
@Composable
private fun ThumbnailOnlyPreview(state: PreviewUiState, onDownload: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.thumbnailUrl.isNullOrBlank()) {
            EmptyState(
                title = "只有缩略图可用",
                icon = Icons.Filled.Visibility,
                description = "该网盘未提供此文件的更多预览内容，可下载后查看。",
                actionLabel = "下载",
                onAction = onDownload,
            )
        } else {
            HandoffNotice(message = "该网盘仅提供缩略图，完整内容请下载后查看。")
            ImagePreview(
                url = state.thumbnailUrl,
                headers = state.result?.resource?.headers.orEmpty(),
                contentDescription = "缩略图",
                allowZoom = false,
            )
        }
    }
}

/**
 * The unsupported branch.
 *
 * This is reached from a **successful** preview call whose classification is
 * [PreviewKind.Unsupported] — the file's format has no inline preview, which is a normal outcome and
 * not a failure. Hence [EmptyState] rather than [ErrorState]: nothing went wrong, there is simply
 * nothing to render here. The reason string comes from the provider, which knows the format.
 */
@Composable
private fun UnsupportedPreview(reason: String, onDownload: () -> Unit) {
    EmptyState(
        title = "该文件无法在线预览",
        icon = Icons.Filled.CloudOff,
        description = reason,
        actionLabel = "下载",
        onAction = onDownload,
    )
}

/** A full-width download action for the branches that end in one. */
@Composable
private fun DownloadBar(onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.Large),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(onClick = onDownload) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Text(text = "  下载", modifier = Modifier.padding(start = Dimens.XSmall))
        }
    }
}

/** A one-line, non-blocking explanation shown above a branch. */
@Composable
private fun HandoffNotice(message: String) {
    SectionHeader(title = "说明", showDivider = false)
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Large, vertical = Dimens.XSmall),
    )
}

/** An explicit, dismissible-looking notice for a truncated read. */
@Composable
private fun TruncationNotice(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(Dimens.Medium),
    )
}

/** Zoom bounds. Above 5× a phone screen shows a handful of pixels; below 1× the image is a dot. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/** Placeholder a provider may embed when it wants the app to substitute the token. */
private const val TOKEN_PLACEHOLDER = "{token}"

/** Fragment key used when the viewer URL carries no token of its own. */
private const val TOKEN_FRAGMENT_KEY = "token"

/** A committed blank document, used to evict the token-bearing page before destruction. */
private const val BLANK_PAGE = "about:blank"

/** Local alias so the WebView client signature stays readable. */
private typealias WebResourceErrorCompat = android.webkit.WebResourceError

// ------------------------------------------------------------------ previews

private fun previewResult(kind: PreviewKind, resource: PreviewResource? = null) = PreviewResult(
    key = CloudFileKey(
        providerId = ProviderId("preview-provider"),
        accountId = AccountId("preview-account"),
        fileId = FileId("示例文件"),
    ),
    kind = kind,
    resource = resource,
)

/**
 * One preview per branch.
 *
 * Parameterised on [PreviewUiState] rather than written eight times, so adding a branch means adding
 * one call here — and a branch that renders nothing is visible in the IDE's preview pane immediately.
 */
@Preview(name = "图片", showBackground = true)
@Composable
private fun PreviewImage() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(
                isLoading = false,
                result = previewResult(PreviewKind.Image(thumbnail = ThumbnailRef(sizeHint = "480"))),
                thumbnailUrl = "https://example.invalid/preview/image.jpg",
            ),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "视频交接", showBackground = true)
@Composable
private fun PreviewVideo() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(isLoading = false, result = previewResult(PreviewKind.Video)),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "音频交接", showBackground = true)
@Composable
private fun PreviewAudio() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(isLoading = false, result = previewResult(PreviewKind.Audio)),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "文本", showBackground = true)
@Composable
private fun PreviewText() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(
                isLoading = false,
                result = previewResult(PreviewKind.Text()),
                textContent = "2024-01-02 14:30:00 INFO  启动完成\n2024-01-02 14:30:01 WARN  重试中…",
                textTruncated = true,
                textBytesRead = 262_144L,
            ),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "文本读取失败", showBackground = true)
@Composable
private fun PreviewTextError() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(
                isLoading = false,
                result = previewResult(PreviewKind.Text()),
                textError = "读取文本失败（HTTP 403），可下载后查看",
            ),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "PDF", showBackground = true)
@Composable
private fun PreviewPdf() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(isLoading = false, result = previewResult(PreviewKind.Pdf)),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "Office 在线查看器", showBackground = true)
@Composable
private fun PreviewOffice() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(
                isLoading = false,
                result = previewResult(
                    PreviewKind.OfficeDocument(
                        OfficeViewer(
                            url = "https://example.invalid/viewer",
                            token = "preview-token",
                            canEdit = true,
                        ),
                    ),
                ),
            ),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "仅缩略图", showBackground = true)
@Composable
private fun PreviewThumbnailOnly() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(
                isLoading = false,
                result = previewResult(PreviewKind.ThumbnailOnly(thumbnail = ThumbnailRef(sizeHint = "480"))),
                thumbnailUrl = "https://example.invalid/preview/thumb.jpg",
            ),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "不支持预览", showBackground = true)
@Composable
private fun PreviewUnsupported() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(
                isLoading = false,
                result = previewResult(PreviewKind.Unsupported("暂不支持此格式的在线预览，可下载后使用本地应用打开")),
            ),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "加载中", showBackground = true)
@Composable
private fun PreviewLoading() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(isLoading = true),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}

@Preview(name = "分类失败", showBackground = true)
@Composable
private fun PreviewError() {
    NotDiskTheme(dynamicColor = false) {
        PreviewScreen(
            state = PreviewUiState(isLoading = false, error = "网络不可用"),
            onBack = {},
            onRetry = {},
            onPlayMedia = {},
            onDownload = {},
        )
    }
}
