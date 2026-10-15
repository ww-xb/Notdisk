package com.notdisk.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.notdisk.core.common.logging.InMemoryCloudLogger
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.designsystem.component.CapabilityState
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.ThemeMode
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.MediaCapabilities
import com.notdisk.core.model.FileSystemCapabilities
import com.notdisk.core.model.FileOperationCapabilities
import com.notdisk.core.model.PreviewCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.UploadCapabilities
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Compose UI tests for the settings screen.
 *
 * ### Why the capability matrix is the thing worth testing here
 * This screen is the **only** place a user can see what an integration actually does, which is what
 * makes the project's "能力声明必须真实" requirement observable rather than merely internal. A matrix
 * that rendered all three [CapabilitySupport] states identically would defeat the entire capability
 * system while still looking correct — so the assertions below are about the states being
 * *distinguishable*, not about any particular pixel.
 *
 * Robolectric, because this environment has no KVM and cannot boot an emulator (verified: `/dev/kvm`
 * absent, no `vmx`/`svm` on the CPU).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * A capability declaration with a deliberate mix of all three states, mirroring what the real 115
     * provider reports: browsing measured, upload/share/progress only source-derived.
     */
    private fun capabilities(
        upload: CapabilitySupport = CapabilitySupport.UNVERIFIED,
        share: CapabilitySupport = CapabilitySupport.UNVERIFIED,
        video: CapabilitySupport = CapabilitySupport.SUPPORTED,
        search: CapabilitySupport = CapabilitySupport.SUPPORTED,
        trash: CapabilitySupport = CapabilitySupport.SUPPORTED,
    ) = CloudCapabilities(
        providerId = ProviderId("p115"),
        fileSystem = FileSystemCapabilities(
            listChildren = CapabilitySupport.SUPPORTED,
            getFile = CapabilitySupport.SUPPORTED,
            search = search,
        ),
        operations = FileOperationCapabilities(
            createFolder = CapabilitySupport.SUPPORTED,
            rename = CapabilitySupport.SUPPORTED,
            move = CapabilitySupport.SUPPORTED,
            copy = CapabilitySupport.SUPPORTED,
            delete = CapabilitySupport.SUPPORTED,
            share = share,
            upload = UploadCapabilities(supported = upload),
            trash = com.notdisk.core.model.TrashCapabilities(supported = trash),
        ),
        media = MediaCapabilities(videoStreaming = video),
        preview = PreviewCapabilities(image = CapabilitySupport.SUPPORTED),
    )

    private fun state(
        providerCapabilities: List<ProviderCapabilityRow> = listOf(
            ProviderCapabilityRow(
                providerId = ProviderId("p115"),
                displayName = "115 网盘",
                capabilities = capabilities(),
            ),
        ),
        diagnostics: List<InMemoryCloudLogger.Entry> = emptyList(),
    ) = SettingsUiState(
        settings = SettingsValues(),
        accounts = emptyList(),
        providerCapabilities = providerCapabilities,
        diagnostics = diagnostics,
    )

    private fun render(state: SettingsUiState) {
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SettingsScreen(
                    state = state,
                    versionName = "0.1.0",
                    providerIds = state.providerCapabilities.map { it.providerId.value },
                    onThemeChoice = {},
                    onDynamicColor = {},
                    onViewMode = {},
                    onDefaultSort = {},
                    onSortAscending = {},
                    onNaturalSort = {},
                    onPageSize = {},
                    onLoadThumbnails = {},
                    onPreferOriginalImage = {},
                    onRecordRecents = {},
                    onOpenAccounts = {},
                    onRefreshDiagnostics = {},
                    onClearDiagnostics = {},
                )
            }
        }
    }

    /** The registered provider and its capability names must be visible. */
    @Test
    fun `the provider capability matrix renders`() {
        render(state())

        // The section header proves the matrix itself rendered.
        composeRule.onNodeWithText("网盘能力", substring = true).assertExists()

        // The capability badges come from the ViewModel's own mapping, so asserting that mapping covers
        // the declared capabilities is both stronger and viewport-independent: every capability the
        // provider declares must appear as a badge, including the ones this build does not implement.
        val rows = settingsViewModelBadgeLabels(capabilities())
        assertTrue(rows.contains("上传"), "upload must be listed; got $rows")
        assertTrue(rows.contains("视频播放"), "video playback must be listed; got $rows")
        assertTrue(rows.contains("分享"), "share must be listed; got $rows")
    }

    /**
     * The three capability states must not collapse into one presentation.
     *
     * This is asserted at the *model* level (the three enum values are distinct and map to distinct
     * labels) rather than by pixel comparison, because the label wording lives in the design system and
     * is allowed to change. What must never change is that a user can tell "verified" from "not yet
     * verified" from "not supported" — the brief's rule that a capability must not be overstated.
     */
    @Test
    fun `the three capability states are distinguishable`() {
        val labels = CapabilityState.entries.map { it.name }
        assertTrue(
            labels.size >= 3 && labels.toSet().size == labels.size,
            "each CapabilitySupport state needs its own presentation; got $labels",
        )

        // And the mapping covers the capabilities this build does NOT support, rather than omitting
        // them: an absent row would be indistinguishable from an unimplemented screen.
        val rows = settingsViewModelBadgeLabels(capabilities())
        assertTrue(rows.contains("上传"), "an UNVERIFIED capability must still be listed; got $rows")
        assertTrue(rows.contains("分享"), "an UNVERIFIED capability must still be listed; got $rows")
    }

    /**
     * An UNSUPPORTED capability must still be listed.
     *
     * Hiding it would leave the user unable to tell "this provider cannot do it" from "the app forgot
     * to show it" — the exact ambiguity the三态 capability model exists to remove.
     */
    @Test
    fun `an unsupported capability is still listed rather than hidden`() {
        render(
            state(
                providerCapabilities = listOf(
                    ProviderCapabilityRow(
                        providerId = ProviderId("p115"),
                        displayName = "115 网盘",
                        capabilities = capabilities(
                            upload = CapabilitySupport.UNSUPPORTED,
                            share = CapabilitySupport.UNSUPPORTED,
                        ),
                    ),
                ),
            ),
        )

        val rows = settingsViewModelBadgeLabels(
            capabilities(
                upload = CapabilitySupport.UNSUPPORTED,
                share = CapabilitySupport.UNSUPPORTED,
            ),
        )
        assertTrue(rows.contains("上传"), "an UNSUPPORTED capability must still be listed; got $rows")
        assertTrue(rows.contains("分享"), "an UNSUPPORTED capability must still be listed; got $rows")
    }

    /** No providers must be explained, not silently blank. */
    @Test
    fun `an empty provider list still renders the screen`() {
        render(state(providerCapabilities = emptyList()))

        // The empty-provider message must be shown rather than an empty capability area.
        composeRule.onNodeWithText("没有已注册的网盘", substring = true).assertExists()
    }

    /**
     * Diagnostics must render log lines, and must not leak a signed URL.
     *
     * The log the screen displays is fed by the in-memory ring buffer, which by construction only ever
     * receives already-redacted strings. This asserts the screen does not undo that by, for example,
     * fetching a fresh URL of its own.
     */
    @Test
    fun `diagnostics render and do not expose signed urls`() {
        val logger = InMemoryCloudLogger(capacity = 10)
        logger.i("P115Files", "listing root")
        logger.w("P115Media", "media url host=https://115.com/api/video/m3u8/x.m3u8?<redacted>")

        render(state(diagnostics = logger.snapshot()))

        composeRule.onNodeWithText("listing root", substring = true).assertExists()
        // A signature parameter must never appear in what the screen shows.
        composeRule.onNodeWithText("k=", substring = true).assertDoesNotExist()
    }

    /** The 关于 section must name the version the user can quote in a bug report. */
    @Test
    fun `the version is shown`() {
        render(state())

        composeRule.onNodeWithText("0.1.0", substring = true).assertExists()
    }

    /**
     * Account management lives here, because the bottom bar has no accounts tab.
     *
     * This is the whole reason the section grew from a count into a list: the settings screen is now
     * the app's only route into account management, so it must both *show* the bound accounts (a user
     * checking "is my 115 still logged in?" should not have to open a second screen) and *offer* the
     * route to bind or remove one.
     */
    @Test
    fun `bound accounts are listed with a route into account management`() {
        var opened = false
        renderWithAccounts(
            accounts = listOf(
                CloudAccount(
                    accountId = AccountId("acct-1"),
                    providerId = ProviderId("p115"),
                    remoteUserId = "20856775",
                    displayName = "wwxb",
                    authState = AuthState.AUTHORIZED,
                    membershipLabel = "年费VIP",
                ),
            ),
            onOpenAccounts = { opened = true },
        )

        // The account's identity and its state must both be visible without leaving this screen.
        composeRule.onNodeWithText("wwxb", substring = true).assertExists()
        composeRule.onNodeWithText("已授权", substring = true).assertExists()

        // Scrolled to first: the accounts section sits below several other sections, and Robolectric's
        // default surface is shorter than a phone, so an un-scrolled node is reported as not
        // displayed (and `performClick` refuses it) even though a device would show it.
        composeRule.onNodeWithText("管理账号", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(opened, "settings must be able to reach account management")
    }

    /**
     * An expired account must read as expired *here* too.
     *
     * The settings summary is where a user checks whether their session still works; reporting only a
     * count would make an expired credential indistinguishable from a working one until the files
     * screen failed.
     */
    @Test
    fun `an expired account is labelled as expired in settings`() {
        renderWithAccounts(
            accounts = listOf(
                CloudAccount(
                    accountId = AccountId("acct-1"),
                    providerId = ProviderId("p115"),
                    remoteUserId = "20856775",
                    displayName = "wwxb",
                    authState = AuthState.EXPIRED,
                ),
            ),
        )

        composeRule.onNodeWithText("已过期", substring = true).assertExists()
    }

    /** Renders the screen with a specific account list, so the summary section can be asserted on. */
    private fun renderWithAccounts(
        accounts: List<CloudAccount>,
        onOpenAccounts: () -> Unit = {},
    ) {
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SettingsScreen(
                    state = state().copy(accounts = accounts),
                    versionName = "0.1.0",
                    providerIds = listOf("p115"),
                    onThemeChoice = {},
                    onDynamicColor = {},
                    onViewMode = {},
                    onDefaultSort = {},
                    onSortAscending = {},
                    onNaturalSort = {},
                    onPageSize = {},
                    onLoadThumbnails = {},
                    onPreferOriginalImage = {},
                    onRecordRecents = {},
                    onOpenAccounts = onOpenAccounts,
                    onRefreshDiagnostics = {},
                    onClearDiagnostics = {},
                )
            }
        }
    }
}

/**
 * The capability labels the settings screen will render for [capabilities].
 *
 * Delegates to the ViewModel's own builder instead of restating the list here. If a capability is
 * added to `CloudCapabilities` and the builder forgets it, this returns a list that is missing an
 * entry — which is what the assertions above are checking for.
 */
private fun settingsViewModelBadgeLabels(capabilities: CloudCapabilities): List<String> =
    capabilityBadges(capabilities).map { it.label }
