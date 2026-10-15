package com.notdisk.feature.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.ThemeMode
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.ProviderDescriptor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for the accounts screen.
 *
 * ### What these cover that a ViewModel test cannot
 * The accounts screen is where the brief's "登录和授权状态明确" acceptance criterion lives, and the
 * parts that can silently regress are visual/structural rather than logical:
 *
 *  - an **expired** account must be visually distinguishable from a working one, or the user has no
 *    way to know they need to re-authorise;
 *  - the **add-account** section must offer only the flows a provider actually advertises, or the UI
 *    shows a button that cannot work (the brief's §18 requires that a missing capability must not
 *    merely be disabled — it must not be presented as achievable);
 *  - an in-flight QR challenge must show *what is happening* (including the poll hint) rather than a
 *    generic spinner indistinguishable from a hang.
 *
 * Robolectric, because this environment has no KVM and therefore cannot boot an emulator (verified:
 * `/dev/kvm` absent, no `vmx`/`svm` on the CPU). Robolectric runs the real Compose runtime, layout and
 * semantics tree on the JVM, which is enough for structural assertions of exactly this kind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccountsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val providerId = ProviderId("p115")

    private fun account(
        name: String,
        state: AuthState,
        id: String = name,
    ) = CloudAccount(
        accountId = AccountId(id),
        providerId = providerId,
        remoteUserId = "20856775",
        displayName = name,
        avatarUrl = null,
        authState = state,
        membershipLabel = "年费VIP",
        membershipExpiresAt = 1_982_993_972_000,
        addedAt = 1_791_956_000_000,
        lastVerifiedAt = 1_791_956_000_000,
    )

    private fun descriptor(
        id: String = "p115",
        name: String = "115 网盘",
        flows: Set<AuthFlowKind> = setOf(AuthFlowKind.QR_CODE),
        notes: List<String> = emptyList(),
    ) = ProviderDescriptor(
        id = ProviderId(id),
        displayName = name,
        description = "文件浏览 · 搜索",
        availableAuthFlows = flows,
        configurationNotes = notes,
    )

    private fun render(
        state: AccountsUiState,
        onBeginAuthorization: (String, AuthFlowKind) -> Unit = { _, _ -> },
        onVerify: (String, String) -> Unit = { _, _ -> },
        onRemove: (String, String) -> Unit = { _, _ -> },
        onCancelAuthorization: () -> Unit = {},
    ) {
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                AccountsScreen(
                    state = state,
                    onAccountSelected = { _, _ -> },
                    onVerify = onVerify,
                    onRemove = onRemove,
                    onBeginAuthorization = onBeginAuthorization,
                    onCancelAuthorization = onCancelAuthorization,
                    onDismissBanner = {},
                    onReload = {},
                )
            }
        }
    }

    /** An empty state must say what to do next, not show a blank list. */
    @Test
    fun `no accounts shows an actionable empty state`() {
        render(AccountsUiState(isLoading = false, providers = listOf(descriptor())))

        composeRule.onNodeWithText("添加账号", substring = true).assertIsDisplayed()
    }

    /** A bound account renders its name, provider and membership. */
    @Test
    fun `a bound account renders its identity`() {
        render(
            AccountsUiState(
                isLoading = false,
                providers = listOf(descriptor()),
                accounts = listOf(account("wwxb", AuthState.AUTHORIZED)),
            ),
        )

        composeRule.onNodeWithText("wwxb", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("年费VIP", substring = true).assertIsDisplayed()
    }

    /**
     * An expired account must be *visibly* different and must be verifiable again.
     *
     * This is the brief's §18 requirement "授权失效后能够提示用户恢复访问": if the expired state were
     * indistinguishable, the user would have no route back to a working session.
     */
    @Test
    fun `an expired account is labelled and can be re-verified`() {
        var verified: Pair<String, String>? = null
        render(
            state = AccountsUiState(
                isLoading = false,
                providers = listOf(descriptor()),
                accounts = listOf(account("wwxb", AuthState.EXPIRED)),
            ),
            onVerify = { p, a -> verified = p to a },
        )

        // The badge text for an expired session must appear (the exact wording is the screen's, so a
        // substring that any reasonable wording contains is used).
        // `assertIsDisplayed` is deliberately not used for the badge: Robolectric's default surface is
        // narrower than a phone, so a three-button row overflows and Compose reports the overflowing
        // nodes as not displayed. Existence plus the click below is what this test can actually
        // establish; on-device visibility is covered by docs/testing.md's manual checklist.
        // The wording comes from `AccountAuthStatus.label` in the design system, so the badge and any
        // future reuse of the chip cannot drift apart.
        //
        // `useUnmergedTree` because `CloudAccountChip` is a single accessible row: Compose merges its
        // children's semantics into the parent, so the badge's own text is only visible in the
        // unmerged tree. (Compose's own error message suggests exactly this, which is a good reminder
        // that a merged tree is the *accessibility* view, not the composition view.)
        composeRule.onNodeWithText("已过期", substring = true, useUnmergedTree = true).assertExists()

        composeRule.onNodeWithText("验证", substring = false).assertExists().performClick()
        assertEquals("p115" to "wwxb", verified, "verification must carry the account's real ids")
    }

    /**
     * The add-account section must offer exactly the advertised flows.
     *
     * A provider that advertises only QR must not present a password button: the brief forbids
     * presenting a capability that does not exist, and a button that always fails is worse than no
     * button because it makes the app look broken.
     */
    @Test
    fun `only advertised auth flows are offered`() {
        val started = mutableListOf<AuthFlowKind>()
        render(
            state = AccountsUiState(
                isLoading = false,
                providers = listOf(descriptor(flows = setOf(AuthFlowKind.QR_CODE))),
                accounts = emptyList(),
            ),
            onBeginAuthorization = { _, flow -> started += flow },
        )

        composeRule.onNodeWithText("扫码登录", substring = true).assertIsDisplayed().performClick()
        assertEquals(listOf(AuthFlowKind.QR_CODE), started)

        // The flows this provider does not advertise must be absent entirely.
        composeRule.onNodeWithText("账号密码", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("OAuth", substring = true).assertDoesNotExist()
    }

    /** A configuration gap must be explained rather than hidden behind a failing button. */
    @Test
    fun `configuration notes are surfaced to the user`() {
        render(
            AccountsUiState(
                isLoading = false,
                providers = listOf(
                    descriptor(
                        flows = setOf(AuthFlowKind.QR_CODE),
                        notes = listOf("OAuth 登录未启用：需要 115 开发者 app_id 与回调地址。"),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("app_id", substring = true).assertIsDisplayed()
    }

    /**
     * An in-flight QR challenge must show the payload and the poll hint, and be cancellable.
     *
     * The brief requires that authorisation state be explicit. A challenge that rendered only a
     * spinner would be indistinguishable from a hang, and — importantly — would leave the user with no
     * way to see that they are expected to scan something.
     */
    @Test
    fun `a qr challenge shows its payload, hint and a cancel action`() {
        var cancelled = false
        render(
            state = AccountsUiState(
                isLoading = false,
                providers = listOf(descriptor()),
                authorization = AuthorizationState.Waiting(
                    AuthChallenge.QrCode(
                        challengeId = "c1",
                        content = "https://115.com/scan/dg-abcdef123456",
                        expiresAt = System.currentTimeMillis() + 120_000,
                        imageUrl = null,
                    ),
                ),
                pollHint = "请使用 115 App 扫码",
            ),
            onCancelAuthorization = { cancelled = true },
        )

        composeRule.onNodeWithText("请使用 115 App 扫码", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("取消", substring = true).assertIsDisplayed().performClick()
        assertTrue(cancelled, "an in-flight challenge must be cancellable")
    }

    /** A warning banner must be dismissible so it cannot block the list forever. */
    @Test
    fun `a banner is rendered and dismissible`() {
        var dismissed = false
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                AccountsScreen(
                    state = AccountsUiState(
                        isLoading = false,
                        providers = listOf(descriptor()),
                        accounts = listOf(account("wwxb", AuthState.AUTHORIZED)),
                        banner = Banner(message = "115 网盘：网络不可用", isWarning = true),
                    ),
                    onAccountSelected = { _, _ -> },
                    onVerify = { _, _ -> },
                    onRemove = { _, _ -> },
                    onBeginAuthorization = { _, _ -> },
                    onCancelAuthorization = {},
                    onDismissBanner = { dismissed = true },
                    onReload = {},
                )
            }
        }

        composeRule.onNodeWithText("网络不可用", substring = true).assertExists()
        // An icon-only control: matched by its content-desc, which is also what accessibility reads.
        composeRule.onNodeWithContentDescription("关闭提示").assertExists().performClick()
        assertTrue(dismissed, "the banner must be dismissible")
    }
}
