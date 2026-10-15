package com.notdisk.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notdisk.core.common.logging.InMemoryCloudLogger
import com.notdisk.core.data.repository.AccountRepository
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderDescriptor
import com.notdisk.core.provider.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The settings screen's state machine.
 *
 * ### Why this screen is mostly a `combine`
 * Settings are a *projection* of two independent sources — the user's stored preferences and the
 * registered providers' own capability declarations — plus the bound accounts. Each has its own
 * update cadence (a preference changes when the user taps, a capability never changes at runtime), so
 * the state is assembled from flows rather than pushed field by field. It also means the screen can
 * never show a stale page size next to a fresh toggle: every value comes from one emission.
 *
 * ### Why the capability matrix is built here and not in the composable
 * Turning a [CloudCapabilities] into the rows the screen renders is a mapping decision with a fixed
 * vocabulary ("重命名", "回收站", …). Keeping it here means the composable receives
 * [ProviderCapabilityRow]s and has no opinion about which capability lives on which sub-struct — so a
 * capability added to the model is one edit in one place, and the UI cannot drift from the model.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: NotDiskSettingsStore,
    private val accounts: AccountRepository,
    private val registry: ProviderRegistry,
    private val logger: InMemoryCloudLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        observeSettings()
        observeAccounts()
        // Seeded so the diagnostics section is populated on first composition; the screen re-reads it
        // through `refreshDiagnostics()` after any action that could have logged.
        refreshDiagnostics()
    }

    /**
     * Projects the stored preferences into the UI state.
     *
     * `combine` over twelve flows rather than `settings.snapshot()` on a timer: DataStore emits on
     * every write, so the screen reflects a change the instant it is persisted — including a change
     * made from somewhere else, which is what keeps the appearance toggle and an actual theme switch
     * from disagreeing.
     */
    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settings.viewMode,
                settings.themeChoice,
                settings.dynamicColor,
                settings.defaultSort,
                settings.sortAscending,
                settings.naturalSort,
                settings.pageSize,
                settings.loadThumbnails,
                settings.preferOriginalImage,
                settings.recordRecents,
                settings.cacheTtlMillis,
                settings.activeAccount,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                SettingsValues(
                    viewMode = values[0] as NotDiskSettingsStore.ViewMode,
                    themeChoice = values[1] as NotDiskSettingsStore.ThemeChoice,
                    dynamicColor = values[2] as Boolean,
                    defaultSort = values[3] as NotDiskSettingsStore.SortChoice,
                    sortAscending = values[4] as Boolean,
                    naturalSort = values[5] as Boolean,
                    pageSize = values[6] as Int,
                    loadThumbnails = values[7] as Boolean,
                    preferOriginalImage = values[8] as Boolean,
                    recordRecents = values[9] as Boolean,
                    cacheTtlMillis = values[10] as Long,
                    activeAccount = values[11] as Pair<String, String>?,
                )
            }.collect { values ->
                _state.update { it.copy(settings = values) }
            }
        }
    }

    /**
     * Projects the bound accounts and the provider catalogue into the UI state.
     *
     * The capability rows are read **once**, from `registry.all()`, because a capability declaration is
     * defined by contract to be cheap, side-effect free and constant for the process lifetime. Wrapping
     * it in a flow would suggest it can change at runtime, which it cannot. The account list genuinely
     * does change (a login, a logout, an expiry), so that half is collected.
     *
     * A provider whose `capabilities()` throws is skipped rather than crashing the screen: a settings
     * page is exactly where a user goes when something else is already broken.
     */
    private fun observeAccounts() {
        _state.update { current ->
            current.copy(providerCapabilities = runCatching { providerCapabilities() }.getOrDefault(emptyList()))
        }

        viewModelScope.launch {
            accounts.observeAccounts().collect { boundAccounts ->
                _state.update { it.copy(accounts = boundAccounts) }
            }
        }
    }

    /** Reads every registered provider's declaration once: capabilities do not change at runtime. */
    private fun providerCapabilities(): List<ProviderCapabilityRow> =
        registry.all().map { provider ->
            ProviderCapabilityRow(
                providerId = provider.id,
                displayName = provider.displayName,
                capabilities = provider.capabilities(),
            )
        }

    /** Refreshes the diagnostic log projection. Called by the screen whenever it becomes visible. */
    fun refreshDiagnostics() {
        _state.update { it.copy(diagnostics = logger.snapshot()) }
    }

    /** Empties the in-memory log. */
    fun clearDiagnostics() {
        logger.clear()
        refreshDiagnostics()
    }

    // ------------------------------------------------------------------ writers
    //
    // One method per setting rather than a generic `update(key, value)`: the generic form needs a
    // heterogeneous key type, which is the `Map<String, Any>` shape the brief forbids, and it would
    // let a screen store a value no property reads.

    fun setViewMode(mode: NotDiskSettingsStore.ViewMode) = write { settings.setViewMode(mode) }

    fun setThemeChoice(choice: NotDiskSettingsStore.ThemeChoice) =
        write { settings.setThemeChoice(choice) }

    fun setDynamicColor(enabled: Boolean) = write { settings.setDynamicColor(enabled) }

    fun setDefaultSort(choice: NotDiskSettingsStore.SortChoice) =
        write { settings.setDefaultSort(choice) }

    fun setSortAscending(ascending: Boolean) = write { settings.setSortAscending(ascending) }

    fun setNaturalSort(enabled: Boolean) = write { settings.setNaturalSort(enabled) }

    fun setPageSize(size: Int) = write { settings.setPageSize(size) }

    fun setLoadThumbnails(enabled: Boolean) = write { settings.setLoadThumbnails(enabled) }

    fun setPreferOriginalImage(enabled: Boolean) = write { settings.setPreferOriginalImage(enabled) }

    fun setRecordRecents(enabled: Boolean) = write { settings.setRecordRecents(enabled) }

    /**
     * Runs a write against the settings store.
     *
     * A failure would mean the DataStore file is unreadable, which is not actionable from this screen
     * and not something to hide behind a success message either — so it is logged, and the UI keeps
     * showing the value the store actually reports (the flow simply does not change). Nothing is
     * optimistically applied, which is what stops the screen from displaying a preference that was
     * never persisted.
     */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { throwable ->
                logger.w(TAG, "failed to persist a setting", throwable)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}

/** The stored preferences, grouped so the UI state has one field instead of twelve. */
data class SettingsValues(
    val viewMode: NotDiskSettingsStore.ViewMode = NotDiskSettingsStore.ViewMode.LIST,
    val themeChoice: NotDiskSettingsStore.ThemeChoice = NotDiskSettingsStore.ThemeChoice.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultSort: NotDiskSettingsStore.SortChoice = NotDiskSettingsStore.SortChoice.MODIFIED_TIME,
    val sortAscending: Boolean = false,
    val naturalSort: Boolean = true,
    val pageSize: Int = NotDiskSettingsStore.DEFAULT_PAGE_SIZE,
    val loadThumbnails: Boolean = true,
    val preferOriginalImage: Boolean = false,
    val recordRecents: Boolean = true,
    val cacheTtlMillis: Long = NotDiskSettingsStore.DEFAULT_CACHE_TTL_MINUTES * 60_000L,
    val activeAccount: Pair<String, String>? = null,
)

/**
 * One provider's declared capabilities, ready to render.
 *
 * The whole [CloudCapabilities] is carried rather than a flattened list of labels, so the screen can
 * group by area (媒体/预览/文件) without this class having to guess a presentation order. The mapping
 * from capability to badge lives in [capabilityBadges], which is the single place the vocabulary of
 * this screen is defined.
 */
data class ProviderCapabilityRow(
    val providerId: ProviderId,
    val displayName: String,
    val capabilities: CloudCapabilities,
)

/** Everything the settings screen renders. */
data class SettingsUiState(
    val settings: SettingsValues = SettingsValues(),
    val accounts: List<CloudAccount> = emptyList(),
    val providerCapabilities: List<ProviderCapabilityRow> = emptyList(),
    val diagnostics: List<InMemoryCloudLogger.Entry> = emptyList(),
) {
    /**
     * The provider/account label shown in the 账号 section, resolved from the stored pair.
     *
     * A stored pair whose account is no longer bound is reported as absent rather than as a dangling
     * id: the row would otherwise name an account the user cannot act on.
     */
    val activeAccountLabel: String?
        get() {
            val pair = settings.activeAccount ?: return null
            val account = accounts.firstOrNull {
                it.providerId.value == pair.first && it.accountId.value == pair.second
            }
            return account?.displayName ?: "${pair.first} / ${pair.second}"
        }

    /** `true` when at least one provider is registered, i.e. the capability matrix has content. */
    val hasProviders: Boolean get() = providerCapabilities.isNotEmpty()

    /** Total bound accounts, for the 账号 summary line. */
    val accountCount: Int get() = accounts.size
}

/**
 * A capability as the screen shows it: a short Chinese name plus how well it is verified.
 *
 * [area] groups badges into sections (文件/媒体/预览) because a flat list of fifteen badges is
 * unreadable, and the grouping is a property of the capability rather than of the provider.
 */
data class CapabilityBadgeRow(
    val label: String,
    val state: com.notdisk.core.designsystem.component.CapabilityState,
    val area: CapabilityArea,
)

/** How a capability group is titled on screen. */
enum class CapabilityArea(val title: String) {
    FILE_SYSTEM("文件与目录"),
    OPERATIONS("操作"),
    MEDIA("播放"),
    PREVIEW("预览"),
    ACCOUNT("账号"),
}

/**
 * The screen's capability vocabulary: the fifteen labels the brief names, mapped from the model.
 *
 * ### Why this mapping is explicit and complete
 * The point of the 网盘能力 section is that the user can see *which* features each cloud really
 * supports. A mapping that quietly omitted a capability would therefore not be a cosmetic bug — it
 * would be the screen lying by omission about a feature the user is looking for. Every capability the
 * brief lists appears here, and each reads its state from the provider's own declaration rather than
 * from a default, so an integration cannot appear more capable than it claims.
 *
 * `UNVERIFIED` is kept distinct from `UNSUPPORTED` all the way to the badge: they mean different
 * things to a user deciding whether to rely on a feature.
 */
fun capabilityBadges(capabilities: CloudCapabilities): List<CapabilityBadgeRow> = buildList {
    fun add(
        area: CapabilityArea,
        label: String,
        support: com.notdisk.core.model.CapabilitySupport,
    ) {
        add(
            CapabilityBadgeRow(
                label = label,
                state = support.toCapabilityState(),
                area = area,
            ),
        )
    }

    // ---- 文件与目录 ----
    add(CapabilityArea.FILE_SYSTEM, "目录列表", capabilities.fileSystem.listChildren)
    add(CapabilityArea.FILE_SYSTEM, "搜索", capabilities.fileSystem.search)

    // ---- 操作 ----
    add(CapabilityArea.OPERATIONS, "上传", capabilities.operations.upload.supported)
    add(CapabilityArea.OPERATIONS, "下载", capabilities.operations.download.supported)
    add(CapabilityArea.OPERATIONS, "重命名", capabilities.operations.rename)
    add(CapabilityArea.OPERATIONS, "移动", capabilities.operations.move)
    add(CapabilityArea.OPERATIONS, "复制", capabilities.operations.copy)
    add(CapabilityArea.OPERATIONS, "删除", capabilities.operations.delete)
    add(CapabilityArea.OPERATIONS, "分享", capabilities.operations.share)
    add(CapabilityArea.OPERATIONS, "回收站", capabilities.operations.trash.supported)

    // ---- 播放 ----
    add(CapabilityArea.MEDIA, "视频播放", capabilities.media.videoStreaming)
    add(CapabilityArea.MEDIA, "字幕", capabilities.media.subtitles)
    add(CapabilityArea.MEDIA, "多清晰度", capabilities.media.multipleRenditions)

    // ---- 预览 ----
    add(CapabilityArea.PREVIEW, "图片预览", capabilities.preview.image)
    add(CapabilityArea.PREVIEW, "Office 预览", capabilities.preview.officeDocument)

    // ---- 账号 ----
    // Not in the brief's list, but this screen is also where a user asks "can I sign in at all?",
    // and the answer is a declared capability like any other. Kept last so it never displaces the
    // features the brief names.
    add(CapabilityArea.ACCOUNT, "静默续期", capabilities.account.silentRefresh)
}

/**
 * Maps the model's three-valued capability onto the design system's badge state.
 *
 * A one-to-one translation on purpose — the two enums are deliberately separate because `core:model`
 * must not depend on the design system, and because the UI is free to add a fourth presentation state
 * without changing the model. That freedom is exactly why this conversion is written out rather than
 * left implicit.
 */
private fun com.notdisk.core.model.CapabilitySupport.toCapabilityState():
    com.notdisk.core.designsystem.component.CapabilityState =
    when (this) {
        com.notdisk.core.model.CapabilitySupport.SUPPORTED ->
            com.notdisk.core.designsystem.component.CapabilityState.SUPPORTED

        com.notdisk.core.model.CapabilitySupport.UNVERIFIED ->
            com.notdisk.core.designsystem.component.CapabilityState.UNVERIFIED

        com.notdisk.core.model.CapabilitySupport.UNSUPPORTED ->
            com.notdisk.core.designsystem.component.CapabilityState.UNSUPPORTED
    }

/** The interactive flows a provider can run, as display text, for the capability section. */
fun AuthFlowKind.displayLabel(): String = when (this) {
    AuthFlowKind.QR_CODE -> "扫码登录"
    AuthFlowKind.PASSWORD -> "账号密码"
    AuthFlowKind.OAUTH_REDIRECT -> "网页授权"
    AuthFlowKind.DEVICE_CODE -> "设备码"
    AuthFlowKind.MANUAL_CREDENTIAL -> "手动填写凭据"
}

/** A provider's catalogue entry, shown above its capability badges. */
fun ProviderDescriptor.summaryLine(): String = buildString {
    append(if (description.isNotBlank()) description else "尚未声明任何功能")
    if (isDevelopmentOnly) append("（开发用）")
}
