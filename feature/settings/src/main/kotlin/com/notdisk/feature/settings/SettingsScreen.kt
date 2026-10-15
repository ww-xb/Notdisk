package com.notdisk.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.designsystem.component.CapabilityBadge
import com.notdisk.core.designsystem.component.StatRow
import com.notdisk.core.designsystem.format.FileSizeFormatter
import com.notdisk.core.designsystem.component.EmptyState
import com.notdisk.core.designsystem.format.CloudDateTimeFormatter
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.Dimens
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState

/**
 * The settings destination.
 *
 * ### Why `versionName` is a parameter and not `BuildConfig.VERSION_NAME`
 * Build-config generation is disabled project-wide (see `gradle.properties`) so that no module can
 * accidentally couple itself to a generated class — and because this is a *library* module, a
 * `BuildConfig` field here would report the module's own version, not the app's. The application
 * supplies the real value at the navigation call site, which is the only place that knows it.
 *
 * @param onOpenAccounts navigates to the account manager. This screen deliberately does not embed
 *   account binding: credential handling lives in exactly one place, and a second copy here would
 *   be a second place to get it wrong. Accounts *are* reached from settings (the bottom bar has no
 *   accounts tab), so this is the app's only route to them.
 */
@Composable
fun SettingsRoute(
    versionName: String,
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The log ring buffer is fed by everything else in the app, including work that completes while
    // this screen is off-screen. Re-reading it when the screen appears is what makes the diagnostics
    // section show what actually happened rather than what had happened at construction time.
    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    // The app version shown in this screen. `versionName` is passed in from the app module (the
    // only place that knows it, since build-config generation is off project-wide); the parameter
    // stays so a future caller can override it, but the route no longer hardcodes it.
    SettingsScreen(
        state = state,
        versionName = versionName,
        providerIds = state.providerCapabilities.map { it.providerId.value },
        onThemeChoice = viewModel::setThemeChoice,
        onDynamicColor = viewModel::setDynamicColor,
        onViewMode = viewModel::setViewMode,
        onDefaultSort = viewModel::setDefaultSort,
        onSortAscending = viewModel::setSortAscending,
        onNaturalSort = viewModel::setNaturalSort,
        onPageSize = viewModel::setPageSize,
        onLoadThumbnails = viewModel::setLoadThumbnails,
        onPreferOriginalImage = viewModel::setPreferOriginalImage,
        onRecordRecents = viewModel::setRecordRecents,
        onOpenAccounts = onOpenAccounts,
        onRefreshDiagnostics = viewModel::refreshDiagnostics,
        onClearDiagnostics = viewModel::clearDiagnostics,
        modifier = modifier,
    )
}

/**
 * The stateless settings surface.
 *
 * Stateless so it renders from a literal [SettingsUiState] in a `@Preview` and in a screenshot test —
 * which for this screen matters more than usual, because it is the only place the capability system
 * becomes visible to a user and the only practical way to check seven sections is to render them.
 *
 * This screen is **read-only with respect to the network**: it displays declarations and preferences
 * and never issues a request. That is what lets it be opened when something else is broken.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    versionName: String,
    providerIds: List<String>,
    onThemeChoice: (NotDiskSettingsStore.ThemeChoice) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onViewMode: (NotDiskSettingsStore.ViewMode) -> Unit,
    onDefaultSort: (NotDiskSettingsStore.SortChoice) -> Unit,
    onSortAscending: (Boolean) -> Unit,
    onNaturalSort: (Boolean) -> Unit,
    onPageSize: (Int) -> Unit,
    onLoadThumbnails: (Boolean) -> Unit,
    onPreferOriginalImage: (Boolean) -> Unit,
    onRecordRecents: (Boolean) -> Unit,
    onOpenAccounts: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The account summary's quota line is supplied by the caller, which owns the one-shot
     * `AccountRepository.storageQuota` read.
     *
     * Defaulted to empty because an absent entry is the honest rendering for a provider that
     * reports no usage; the map is typed so a reader cannot misread what is in it.
     */
    quotas: Map<AccountId, StorageQuota> = emptyMap(),
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The nav host hands this destination a zero inset (see `NotDiskNavHost`), because
                // the *file browser* owns its own `TopAppBar` padding. This screen has no app bar, so
                // without this the first section header sits underneath the status bar. It is the
                // same class of defect as the reported "顶部有点空", mirrored.
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            AppearanceSection(
                choice = state.settings.themeChoice,
                dynamicColor = state.settings.dynamicColor,
                onThemeChoice = onThemeChoice,
                onDynamicColor = onDynamicColor,
            )

            BrowsingSection(
                settings = state.settings,
                onViewMode = onViewMode,
                onDefaultSort = onDefaultSort,
                onSortAscending = onSortAscending,
                onNaturalSort = onNaturalSort,
                onPageSize = onPageSize,
                onLoadThumbnails = onLoadThumbnails,
                onPreferOriginalImage = onPreferOriginalImage,
            )

            PrivacySection(
                recordRecents = state.settings.recordRecents,
                onRecordRecents = onRecordRecents,
            )

            AccountsSection(
                state = state,
                onOpenAccounts = onOpenAccounts,
                quotas = quotas,
            )

            CapabilitySection(state = state)

            DiagnosticsSection(
                entries = state.diagnostics,
                onRefresh = onRefreshDiagnostics,
                onClear = onClearDiagnostics,
            )

            AboutSection(
                versionName = versionName,
                providerIds = providerIds,
            )
        }
    }
}

// ------------------------------------------------------------------ 1. 外观

/**
 * Theme choice and dynamic colour.
 *
 * The three theme options are presented as chips rather than a switch because "follow the system" is a
 * third state a boolean cannot express — the same reason `ThemeMode` is an enum in the design system.
 */
@Composable
private fun AppearanceSection(
    choice: NotDiskSettingsStore.ThemeChoice,
    dynamicColor: Boolean,
    onThemeChoice: (NotDiskSettingsStore.ThemeChoice) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    SectionHeaderWithIcon(title = "外观", icon = Icons.Filled.Palette)

    SettingRow(
        title = "主题",
        subtitle = "「跟随系统」会随设备的深色模式自动切换",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Small)) {
            ThemeChoice.entries.forEach { option ->
                FilterChip(
                    selected = option.storeValue == choice,
                    onClick = { onThemeChoice(option.storeValue) },
                    label = { Text(text = option.label) },
                )
            }
        }
    }

    SwitchRow(
        title = "动态取色",
        subtitle = "Android 12 及以上会从壁纸提取配色；关闭后使用应用的固定配色",
        checked = dynamicColor,
        onCheckedChange = onDynamicColor,
    )
}

/** The three theme options with their Chinese labels, paired to the store's enum. */
private enum class ThemeChoice(val storeValue: NotDiskSettingsStore.ThemeChoice, val label: String) {
    SYSTEM(NotDiskSettingsStore.ThemeChoice.SYSTEM, "跟随系统"),
    LIGHT(NotDiskSettingsStore.ThemeChoice.LIGHT, "浅色"),
    DARK(NotDiskSettingsStore.ThemeChoice.DARK, "深色"),
}

// ------------------------------------------------------------------ 2. 文件浏览

/**
 * Browsing defaults.
 *
 * Page size is a slider bounded by the store's own `MIN_PAGE_SIZE`/`MAX_PAGE_SIZE` rather than by
 * literals here: the store clamps on write, so a slider with different bounds would let the user pick a
 * value that is silently changed underneath them.
 */
@Composable
private fun BrowsingSection(
    settings: SettingsValues,
    onViewMode: (NotDiskSettingsStore.ViewMode) -> Unit,
    onDefaultSort: (NotDiskSettingsStore.SortChoice) -> Unit,
    onSortAscending: (Boolean) -> Unit,
    onNaturalSort: (Boolean) -> Unit,
    onPageSize: (Int) -> Unit,
    onLoadThumbnails: (Boolean) -> Unit,
    onPreferOriginalImage: (Boolean) -> Unit,
) {
    SectionHeaderWithIcon(title = "文件浏览", icon = Icons.Filled.Tune)

    SettingRow(title = "默认视图", subtitle = "打开文件夹时使用的排列方式") {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Small)) {
            FilterChip(
                selected = settings.viewMode == NotDiskSettingsStore.ViewMode.LIST,
                onClick = { onViewMode(NotDiskSettingsStore.ViewMode.LIST) },
                label = { Text(text = "列表") },
            )
            FilterChip(
                selected = settings.viewMode == NotDiskSettingsStore.ViewMode.GRID,
                onClick = { onViewMode(NotDiskSettingsStore.ViewMode.GRID) },
                label = { Text(text = "网格") },
            )
        }
    }

    SettingRow(title = "默认排序", subtitle = "新打开的文件夹按此排序") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
            verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
        ) {
            SortChoice.entries.forEach { option ->
                FilterChip(
                    selected = option.storeValue == settings.defaultSort,
                    onClick = { onDefaultSort(option.storeValue) },
                    label = { Text(text = option.label) },
                )
            }
        }
    }

    SwitchRow(
        title = "升序排列",
        subtitle = "关闭时使用降序（最新的在前）",
        checked = settings.sortAscending,
        onCheckedChange = onSortAscending,
    )

    SwitchRow(
        title = "自然排序",
        subtitle = "让「文件2」排在「文件10」之前，而不是按字符比较",
        checked = settings.naturalSort,
        onCheckedChange = onNaturalSort,
    )

    SettingRow(
        title = "每页加载数量",
        subtitle = "当前 ${settings.pageSize} 项（可调范围 " +
            "${NotDiskSettingsStore.MIN_PAGE_SIZE}–${NotDiskSettingsStore.MAX_PAGE_SIZE}）。" +
            "数值越小越省流量，越大滚动越少中断。",
    ) {
        Slider(
            value = settings.pageSize.toFloat(),
            onValueChange = { onPageSize(it.toInt()) },
            valueRange = NotDiskSettingsStore.MIN_PAGE_SIZE.toFloat()..
                NotDiskSettingsStore.MAX_PAGE_SIZE.toFloat(),
            // A step of ten keeps the value round: a page size of 37 is not a meaningful request and
            // only makes two devices disagree about what "the same folder" returned.
            steps = (NotDiskSettingsStore.MAX_PAGE_SIZE - NotDiskSettingsStore.MIN_PAGE_SIZE) / 10 - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SwitchRow(
        title = "加载缩略图",
        subtitle = "关闭后列表更省流量、滚动更流畅，但不会显示图片预览",
        checked = settings.loadThumbnails,
        onCheckedChange = onLoadThumbnails,
    )

    SwitchRow(
        title = "图片优先使用原图",
        subtitle = "打开后在图片预览中请求原图；原图可能有数十兆，流量消耗明显",
        checked = settings.preferOriginalImage,
        onCheckedChange = onPreferOriginalImage,
    )
}

/** The sort options with their Chinese labels, paired to the store's enum. */
private enum class SortChoice(val storeValue: NotDiskSettingsStore.SortChoice, val label: String) {
    NAME(NotDiskSettingsStore.SortChoice.NAME, "名称"),
    MODIFIED_TIME(NotDiskSettingsStore.SortChoice.MODIFIED_TIME, "修改时间"),
    SIZE(NotDiskSettingsStore.SortChoice.SIZE, "大小"),
    TYPE(NotDiskSettingsStore.SortChoice.TYPE, "类型"),
}

// ------------------------------------------------------------------ 3. 隐私

/**
 * Privacy controls, stated accurately.
 *
 * ### Why the copy here is written the way it is
 * Every sentence describes something that is *actually true of this build*, which is a constraint
 * rather than a style preference: a privacy panel that overstates the app's protections is worse than
 * no panel, because it is the screen a user consults to make a decision.
 *
 *  - Credentials live in the provider-owned encrypted store, not in the shared database — true, and
 *    visible in `AccountRepository`'s contract that the provider's store is authoritative.
 *  - There is no analytics or telemetry component in this app, and no crash reporter — there is no
 *    such dependency anywhere in the build, so the claim is checkable rather than aspirational.
 *  - The in-memory log is redacted: signed URLs are stripped of their query string and identifiers are
 *    shortened before they reach the ring buffer (`LogRedaction`).
 *
 * Nothing here claims end-to-end encryption, anonymity, or that no data leaves the device: playback
 * and preview necessarily contact the vendor.
 */
@Composable
private fun PrivacySection(
    recordRecents: Boolean,
    onRecordRecents: (Boolean) -> Unit,
) {
    SectionHeaderWithIcon(title = "隐私", icon = Icons.Filled.PrivacyTip)

    SwitchRow(
        title = "记录最近访问",
        subtitle = "在本地保留最近打开的文件，便于快速回到；关闭后不再写入这条记录",
        checked = recordRecents,
        onCheckedChange = onRecordRecents,
    )

    InfoBlock(
        lines = listOf(
            "账号凭据由各网盘 Provider 自己的加密存储保管，不会写入应用的普通数据库，也不会写进日志。",
            "本应用不包含任何统计、埋点或崩溃上报组件，不会把使用数据发送给第三方。",
            "播放与预览必须访问对应网盘的服务端，这是功能本身的要求，不是数据采集。",
            "诊断日志仅保存在内存中，且写入前已做脱敏：签名地址会被去掉查询参数，账号与文件标识只保留前几位。",
        ),
    )
}

// ------------------------------------------------------------------ 4. 账号

/**
 * A summary of bound accounts plus the route into account management.
 *
 * ### Why the accounts *list* is here and binding is not
 * The bottom bar no longer has an accounts tab, so this section is the app's only route into account
 * management and has to carry more than a count. It therefore renders each bound account (name,
 * provider, auth state, membership) inline — a user who came to settings to check "is my 115 still
 * logged in?" should not have to open a second screen to find out — while every *mutating* action
 * (bind, re-authorise, verify, remove) stays on the accounts screen, where credential handling
 * lives in exactly one place.
 *
 * @param quotas storage usage per account, when the caller has fetched it. An absent entry omits the
 *   line entirely rather than drawing an empty bar, which would claim "zero bytes used".
 */
@Composable
private fun AccountsSection(
    state: SettingsUiState,
    onOpenAccounts: () -> Unit,
    quotas: Map<AccountId, StorageQuota>,
) {
    SectionHeaderWithIcon(title = "账号", icon = Icons.Filled.ManageAccounts)

    if (state.accounts.isEmpty()) {
        SettingRow(
            title = "尚未绑定任何网盘账号",
            subtitle = "绑定后，文件页会显示对应网盘的内容",
        ) {
            Button(onClick = onOpenAccounts) { Text(text = "去绑定") }
        }
        return
    }

    state.accounts.forEach { account ->
        SettingsAccountRow(
            account = account,
            providerDisplayName = state.providerCapabilities
                .firstOrNull { it.providerId == account.providerId }
                ?.displayName
                ?: account.providerId.value,
            quota = quotas[account.accountId],
        )
    }

    SettingRow(
        title = "共 ${state.accounts.size} 个账号",
        subtitle = state.activeAccountLabel?.let { "默认打开：$it" }
            ?: "文件页会按账号数量决定是否以网盘名分组",
    ) {
        OutlinedButton(onClick = onOpenAccounts) { Text(text = "管理账号") }
    }
}

/** One bound account, read-only. *Changing* anything about it happens on the accounts screen. */
@Composable
private fun SettingsAccountRow(
    account: CloudAccount,
    providerDisplayName: String,
    quota: StorageQuota?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The auth state is the one fact that decides whether the account is usable, so it is
            // coloured and named rather than left implicit in a "manage" screen.
            Text(
                text = account.authState.chineseLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = if (account.authState == AuthState.AUTHORIZED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Text(
            text = providerDisplayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        quota?.let { usage ->
            StatRow(
                label = "已用空间",
                value = usage.usedLabel ?: usage.usedBytes?.let(FileSizeFormatter::formatBytes)
                    ?: "未提供",
                progress = usage.fraction(),
                supportingText = usage.totalBytes?.let { total ->
                    "共 ${usage.totalLabel ?: FileSizeFormatter.formatBytes(total)}"
                },
            )
        }
    }
}

/** The Chinese name of an [AuthState], for the settings summary. */
private fun AuthState.chineseLabel(): String = when (this) {
    AuthState.AUTHORIZED -> "已授权"
    AuthState.EXPIRED -> "已过期"
    AuthState.REVOKED -> "已退出"
    AuthState.PENDING_AUTHORIZATION -> "等待授权"
    AuthState.UNKNOWN -> "状态未知"
}

/**
 * Used fraction of the quota, or `null` when either side is unreported.
 *
 * Returned as `null` rather than `0f` for a partial report: "we do not know how full this is" and
 * "this is empty" must not look the same, which is exactly what [StatRow]'s `Float?` models.
 */
private fun StorageQuota.fraction(): Float? {
    val used = usedBytes ?: return null
    val total = totalBytes?.takeIf { it > 0 } ?: return null
    return (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

// ------------------------------------------------------------------ 5. 网盘能力

/**
 * The capability matrix: what each registered cloud says it can do.
 *
 * ### Why this section is the point of the screen
 * This app integrates vendors whose APIs differ substantially, and the honest position is to show the
 * user which features are verified, which are merely documented, and which do not exist — rather than
 * to render a uniform set of buttons that fail unpredictably. That is exactly what `CapabilitySupport`
 * and `CapabilityBadge` were built for, and this is the only screen where a user can see the result.
 *
 * The badges are grouped by area because fifteen badges in one row is unreadable, and each badge's
 * colour carries the meaning its label cannot fit ("未验证" vs "不支持").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilitySection(state: SettingsUiState) {
    SectionHeaderWithIcon(title = "网盘能力", icon = Icons.Filled.Verified)

    if (!state.hasProviders) {
        EmptyState(
            title = "没有已注册的网盘",
            icon = Icons.Filled.Verified,
            description = "这个版本没有启用任何网盘接入，因此没有可展示的能力声明。",
        )
        return
    }

    state.providerCapabilities.forEach { provider ->
        ProviderCapabilityBlock(provider)
    }

    InfoBlock(
        lines = listOf(
            "「支持」表示该功能已用真实接口验证过；「未验证」表示厂商文档有说明，但本版本尚未实测；" +
                "「不支持」表示该网盘没有这项能力。",
            "未验证的功能仍可能可用，但应用不会假装它一定成功。",
        ),
    )
}

/** One provider's declaration, grouped into areas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCapabilityBlock(provider: ProviderCapabilityRow) {
    val badges = capabilityBadges(provider.capabilities)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = provider.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start = Dimens.Large,
                end = Dimens.Large,
                top = Dimens.Medium,
            ),
        )

        CapabilityArea.entries.forEach { area ->
            val inArea = badges.filter { it.area == area }
            if (inArea.isEmpty()) return@forEach

            Text(
                text = area.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Dimens.Large,
                    top = Dimens.Small,
                    bottom = Dimens.XSmall,
                ),
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.Large),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
                verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
            ) {
                inArea.forEach { badge ->
                    CapabilityBadge(capabilityName = badge.label, state = badge.state)
                }
            }
        }

        // The protocol list is shown because it explains a user-visible behaviour: a cloud that only
        // emits HLS cannot be played by an external player that expects a plain MP4 link.
        val protocols = provider.capabilities.media.protocols
        if (protocols.isNotEmpty()) {
            Text(
                text = "支持的传输协议：" + protocols.joinToString("、") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.Large, vertical = Dimens.XSmall),
            )
        }

        val flows = provider.capabilities.account.interactiveFlows
        Text(
            text = if (flows.isEmpty()) {
                "登录方式：该网盘未声明可交互的登录方式"
            } else {
                "登录方式：" + flows.joinToString("、") { it.displayLabel() }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.Large, vertical = Dimens.XSmall),
        )
    }
}

// ------------------------------------------------------------------ 6. 诊断

/**
 * The in-memory diagnostic log.
 *
 * The log is a bounded ring buffer, already redacted at the point of writing, and it is *in memory
 * only* — there is no file and nothing is uploaded. The section says so, because a user reading log
 * lines is entitled to know where they came from and how long they live.
 */
@Composable
private fun DiagnosticsSection(
    entries: List<com.notdisk.core.common.logging.InMemoryCloudLogger.Entry>,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    SectionHeaderWithIcon(title = "诊断", icon = Icons.Filled.Storage)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Text(text = "  刷新", modifier = Modifier.padding(start = Dimens.XSmall))
        }
        OutlinedButton(onClick = onClear, enabled = entries.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.ClearAll,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Text(text = "  清空", modifier = Modifier.padding(start = Dimens.XSmall))
        }
        Text(
            text = "${entries.size} 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (entries.isEmpty()) {
        EmptyState(
            title = "暂无日志",
            icon = Icons.Filled.DeleteSweep,
            description = "应用运行过程中的诊断信息会显示在这里。",
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
            verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
        ) {
            // Newest first: the reason anyone opens this panel is the thing that just happened.
            entries.asReversed().forEach { entry ->
                LogEntryRow(entry)
            }
        }
    }

    InfoBlock(
        lines = listOf(
            "日志只保存在内存中，退出应用即清空，不会写入文件，也不会上传。",
            "写入前已脱敏：签名地址的查询参数会被移除，账号与文件标识只保留前六位。",
        ),
    )
}

/** One log line: level, tag, message and time. */
@Composable
private fun LogEntryRow(entry: com.notdisk.core.common.logging.InMemoryCloudLogger.Entry) {
    val levelColor = when (entry.level) {
        'E' -> MaterialTheme.colorScheme.error
        'W' -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.level.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = levelColor,
            )
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = CloudDateTimeFormatter.formatTime(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------------ 7. 关于

/**
 * App identity.
 *
 * The registered provider ids are listed because they are the single most useful fact in a bug report:
 * they say exactly which integrations this build contains, and a provider missing from the list
 * explains a whole class of "the feature is not there" reports without any further diagnosis.
 */
@Composable
private fun AboutSection(versionName: String, providerIds: List<String>) {
    SectionHeaderWithIcon(title = "关于", icon = Icons.Filled.Verified)

    InfoBlock(
        lines = listOf(
            "应用名称：NotDisk 网盘聚合",
            "版本：$versionName",
        ),
    )
    InfoBlock(
        lines = listOf(
            "已注册的网盘：" + if (providerIds.isEmpty()) "无" else providerIds.joinToString("、"),
        ),
    )
}

// ------------------------------------------------------------------ shared pieces

/** A section header with an icon, matching the design system's header rhythm. */
@Composable
private fun SectionHeaderWithIcon(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Large, end = Dimens.Large, top = Dimens.XLarge, bottom = Dimens.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A labelled row whose content is supplied by the caller.
 *
 * The label column is a fixed width so the controls down a section line up: a settings screen whose
 * switches start at four different x-positions looks broken and is genuinely harder to scan.
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.padding(top = Dimens.XSmall)) { content() }
    }
}

/** A row with a [Switch], used for every boolean preference. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A block of explanatory copy, one line per entry. */
@Composable
private fun InfoBlock(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        lines.forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ previews

/**
 * Previews covering the states that differ structurally: a full page of preferences, the empty
 * diagnostics list, and the no-provider case. The capability matrix is rendered from a literal
 * declaration so a reviewer can see all three badge states at once.
 */
private fun previewCapabilities(
    providerId: String,
    listChildren: CapabilitySupport = CapabilitySupport.SUPPORTED,
) = CloudCapabilities(providerId = ProviderId(providerId)).let { base ->
    base.copy(
        fileSystem = base.fileSystem.copy(
            listChildren = listChildren,
            search = CapabilitySupport.SUPPORTED,
        ),
        operations = base.operations.copy(
            rename = CapabilitySupport.SUPPORTED,
            move = CapabilitySupport.SUPPORTED,
            copy = CapabilitySupport.UNVERIFIED,
            delete = CapabilitySupport.SUPPORTED,
            share = CapabilitySupport.UNVERIFIED,
            trash = base.operations.trash.copy(supported = CapabilitySupport.SUPPORTED),
            upload = base.operations.upload.copy(supported = CapabilitySupport.SUPPORTED),
            download = base.operations.download.copy(supported = CapabilitySupport.SUPPORTED),
        ),
        media = base.media.copy(
            videoStreaming = CapabilitySupport.SUPPORTED,
            subtitles = CapabilitySupport.SUPPORTED,
            multipleRenditions = CapabilitySupport.UNSUPPORTED,
        ),
        preview = base.preview.copy(
            image = CapabilitySupport.SUPPORTED,
            officeDocument = CapabilitySupport.UNVERIFIED,
        ),
        account = base.account.copy(
            silentRefresh = CapabilitySupport.UNSUPPORTED,
            interactiveFlows = setOf(AuthFlowKind.QR_CODE, AuthFlowKind.PASSWORD),
        ),
    )
}

private fun previewState(
    diagnostics: List<com.notdisk.core.common.logging.InMemoryCloudLogger.Entry> = emptyList(),
    providers: List<ProviderCapabilityRow> = listOf(
        ProviderCapabilityRow(
            providerId = ProviderId("p115"),
            displayName = "115 网盘",
            capabilities = previewCapabilities("p115"),
        ),
    ),
) = SettingsUiState(
    settings = SettingsValues(
        themeChoice = NotDiskSettingsStore.ThemeChoice.SYSTEM,
        pageSize = 40,
        activeAccount = "p115" to "acct-1",
    ),
    accounts = listOf(
        CloudAccount(
            accountId = AccountId("acct-1"),
            providerId = ProviderId("p115"),
            remoteUserId = "12345",
            displayName = "示例账号",
            authState = AuthState.AUTHORIZED,
        ),
    ),
    providerCapabilities = providers,
    diagnostics = diagnostics,
)

@Preview(name = "设置（含能力矩阵）", showBackground = true, heightDp = 2400)
@Composable
private fun SettingsScreenPreview() {
    NotDiskTheme(dynamicColor = false) {
        SettingsScreen(
            state = previewState(
                diagnostics = listOf(
                    com.notdisk.core.common.logging.InMemoryCloudLogger.Entry(
                        level = 'I',
                        tag = "ProviderRegistry",
                        message = "registered provider 'p115' (115 网盘)",
                        timestamp = 1_700_000_000_000L,
                    ),
                    com.notdisk.core.common.logging.InMemoryCloudLogger.Entry(
                        level = 'W',
                        tag = "MediaCoordinator",
                        message = "playback failed and the resource is not refreshable",
                        timestamp = 1_700_000_060_000L,
                    ),
                ),
            ),
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
            onOpenAccounts = {},
            onRefreshDiagnostics = {},
            onClearDiagnostics = {},
        )
    }
}

@Preview(name = "设置（无网盘、无日志）", showBackground = true, heightDp = 1600)
@Composable
private fun SettingsScreenEmptyPreview() {
    NotDiskTheme(dynamicColor = false) {
        SettingsScreen(
            state = previewState(providers = emptyList()).copy(accounts = emptyList()),
            versionName = "0.1.0",
            providerIds = emptyList(),
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
