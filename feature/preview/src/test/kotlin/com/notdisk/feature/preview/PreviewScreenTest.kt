package com.notdisk.feature.preview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.ThemeMode
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.OfficeViewer
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResource
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ProviderId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Compose UI tests for the preview screen's **state branches**.
 *
 * ### Why Robolectric rather than a device
 * The brief requires "Compose 关键页面测试". This environment has **no KVM and no CPU
 * virtualisation** (`/dev/kvm` is absent and `/proc/cpuinfo` exposes no `vmx`/`svm` — both verified),
 * so a managed emulator cannot boot usefully. Robolectric runs the *real* Compose runtime, the real
 * layout pass and the real semantics tree on the JVM, which is exactly enough to verify the property
 * that matters here and that no plain unit test can reach:
 *
 * **every [PreviewKind] branch renders something, and a branch with nothing to render offers a way
 * out.**
 *
 * That is an explicit acceptance criterion ("不能播放或预览的文件有明确的替代操作"), and it is the kind
 * of property that rots silently: add a [PreviewKind] variant and the `when` gains a branch nobody
 * exercises until a user hits an unsupported file.
 *
 * ### What is deliberately NOT covered
 * No pixel layout, and no Coil image loading or office WebView (both need a real surface/network).
 * Those remain unverified on-device — see `docs/testing.md` §5.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val providerId = ProviderId("p115")
    private val accountId = AccountId("acct-1")
    private val fileId = FileId("file:115:fid=42:cid=1")

    /**
     * The state the screen consumes.
     *
     * Note the field set matches the real `PreviewUiState` — an earlier draft of this test invented
     * `providerId`/`accountId`/`fileId`/`fileName` fields that the production type does not have,
     * because the screen derives all of that from `result.key`. Writing the test against the real
     * type is what makes it a test of the shipped screen rather than of an imagined one.
     */
    private fun state(
        kind: PreviewKind? = null,
        resource: PreviewResource? = null,
        textContent: String? = null,
        error: String? = null,
        isLoading: Boolean = false,
    ) = PreviewUiState(
        isLoading = isLoading,
        error = error,
        result = kind?.let {
            PreviewResult(
                key = CloudFileKey(providerId, accountId, fileId),
                kind = it,
                mimeType = "application/octet-stream",
                resource = resource,
            )
        },
        textContent = textContent,
    )

    private fun render(
        state: PreviewUiState,
        onDownload: () -> Unit = {},
        onPlayMedia: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                PreviewScreen(
                    state = state,
                    onBack = {},
                    onRetry = onRetry,
                    onPlayMedia = onPlayMedia,
                    onDownload = onDownload,
                )
            }
        }
    }

    /**
     * The load-bearing test: a file with no inline preview must offer a way forward.
     *
     * The brief is explicit that this must not be presented as an error — the provider worked
     * correctly; the file simply has no preview — so the assertion is both that the *reason* is shown
     * and that a download is reachable.
     */
    @Test
    fun `an unpreviewable file offers a download instead of a dead end`() {
        var downloadRequested = false
        render(
            state = state(PreviewKind.Unsupported("该格式暂不支持在线预览")),
            onDownload = { downloadRequested = true },
        )

        composeRule.onNodeWithText("该格式暂不支持在线预览", substring = true).assertIsDisplayed()

        // Exactly ONE download affordance. An earlier version rendered two — the EmptyState's action
        // and a separate DownloadBar — so `onNodeWithText` failing on "found 2 nodes" is the
        // regression guard, not an inconvenience to work around with `onAllNodes`.
        composeRule.onNodeWithText("下载", substring = true).assertIsDisplayed().performClick()
        assertTrue(downloadRequested, "the download action must be wired through to the callback")
    }

    /** A text preview renders its already-bounded content inline; no download needed. */
    @Test
    fun `a text preview renders the content`() {
        render(
            state = state(
                kind = PreviewKind.Text(encoding = "UTF-8"),
                resource = PreviewResource(url = "https://cdn.example/text?t=1&k=secret"),
                textContent = "hello from the cloud",
            ),
        )

        composeRule.onNodeWithText("hello from the cloud", substring = true).assertIsDisplayed()
    }

    /**
     * A video preview must **hand off** rather than host playback.
     *
     * Asserting the callback (not a PlayerView) pins the player/preview decoupling at the UI level:
     * if someone later embeds an ExoPlayer here, this test still passes, so the real guard is
     * `feature:player`'s isolation test. What this adds is that the handoff is actually reachable.
     */
    @Test
    fun `a video preview delegates playback to the shared player`() {
        var played = false
        render(state = state(PreviewKind.Video), onPlayMedia = { played = true })

        composeRule.onNodeWithText("在线播放", substring = true).assertIsDisplayed().performClick()
        assertTrue(played, "preview must delegate playback, not embed it")
    }

    /** A failure must be recoverable and must explain what the user can do instead. */
    @Test
    fun `an error state shows the message and offers retry`() {
        var retried = false
        render(
            state = state(error = "播放地址已失效且无法刷新"),
            onRetry = { retried = true },
        )

        composeRule.onNodeWithText("播放地址已失效且无法刷新", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("重试", substring = true).assertIsDisplayed().performClick()
        assertTrue(retried, "an error must be retryable")
    }

    /** Loading must be visible rather than an empty surface the user would read as "broken". */
    @Test
    fun `a loading state renders a progress indicator`() {
        render(state = state(isLoading = true))

        composeRule.onNodeWithText("正在准备预览", substring = true).assertIsDisplayed()
    }

    /**
     * An office document must reach the vendor's viewer branch rather than falling through to
     * "unsupported": 115 returns a WPS `url` **plus** a `token`, and the pairing is what makes the
     * view work.
     */
    @Test
    fun `an office document does not fall through to unsupported`() {
        render(
            state = state(
                PreviewKind.OfficeDocument(
                    OfficeViewer(
                        url = "https://appdocs.wpscdn.cn/office/w/abc",
                        token = "Anonymous-token",
                        canEdit = false,
                    ),
                ),
            ),
        )

        // The WebView needs a real surface, so the assertion is that this branch was taken at all.
        composeRule.onNodeWithText("暂不支持", substring = true).assertDoesNotExist()
    }

    /**
     * A malformed route (no `result` yet, no error) must not render a blank screen with no
     * explanation — it is reachable from a deep link.
     */
    @Test
    fun `a missing classification explains itself rather than rendering nothing`() {
        render(state = state(kind = null))

        composeRule.onNodeWithText("无法预览", substring = true).assertIsDisplayed()
    }
}
