package com.notdisk.feature.accounts

import android.content.ClipData

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.notdisk.core.designsystem.component.CloudAccountChip
import com.notdisk.core.designsystem.component.ConfirmDialog
import com.notdisk.core.designsystem.component.EmptyState
import com.notdisk.core.designsystem.component.LoadingState
import com.notdisk.core.designsystem.component.SectionHeader
import com.notdisk.core.designsystem.component.StatRow
import com.notdisk.core.designsystem.format.CloudDateTimeFormatter
import com.notdisk.core.designsystem.format.FileSizeFormatter
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.Dimens
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.ProviderDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The stateful entry point of the accounts screen.
 *
 * ### Why a route wrapper
 * [AccountsScreen] takes a plain state value and plain lambdas, so it is renderable from a
 * `@Preview`, from a screenshot test, and from the settings screen's "manage accounts" entry point
 * without a Hilt graph. This wrapper is the only place that touches a `ViewModel`, which is what
 * keeps the screen a pure function of its state.
 *
 * ### Why selection is a callback rather than a navigation call
 * `feature:accounts` must not import a navigation library or know that a files screen exists. The
 * app module owns the graph and decides what "设为当前" means (see `NotDiskNavHost`); this module
 * only reports that the user chose an account.
 *
 * @param onAccountSelected invoked with the raw ids of the account the user made current. Raw
 *   strings rather than `ProviderId`/`AccountId`, matching the app's route arguments, which must
 *   survive process death as primitives.
 */
@Composable
fun AccountsRoute(
    onAccountSelected: (providerId: String, accountId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AccountsScreen(
        state = state,
        onAccountSelected = onAccountSelected,
        onVerify = { providerId, accountId ->
            viewModel.verifyAccount(ProviderId(providerId), AccountId(accountId))
        },
        onRemove = { providerId, accountId ->
            viewModel.removeAccount(ProviderId(providerId), AccountId(accountId))
        },
        onBeginAuthorization = { providerId, flow ->
            viewModel.beginAuthorization(ProviderId(providerId), flow)
        },
        onCancelAuthorization = viewModel::cancelAuthorization,
        onDismissBanner = viewModel::dismissBanner,
        onReload = viewModel::loadAccounts,
        onCancelManualCredential = viewModel::cancelManualCredential,
        onSubmitManualCredential = viewModel::submitManualCredential,
        modifier = modifier,
    )
}

/**
 * The accounts screen: which clouds are connected, whether they still work, and how to add another.
 *
 * ### Why the screen is capability- and descriptor-driven
 * The list of "add account" buttons is generated from [AccountsUiState.providers] and each
 * provider's own `availableAuthFlows`. There is no provider name anywhere in this file, so adding a
 * cloud is adding a `CloudProvider` implementation — this screen renders its flows, its
 * configuration notes and its development-only badge without a code change. That is also why a
 * provider with *no* usable flow is rendered as an explanatory row instead of a dead button: a
 * button that cannot work is worse than a sentence that says why.
 *
 * ### Why authorisation is drawn from the challenge rather than from a generic spinner
 * `AuthChallenge` has four shapes and each needs a different affordance. Rendering a QR flow as a
 * spinner would hide the one thing the user must act on, and rendering a device-code flow without
 * its code would make it impossible to complete. The screen therefore branches on the *challenge
 * type* — a protocol fact — and never on the provider.
 *
 * @param state the complete screen state; this composable owns no state beyond which confirmation
 *   dialog is open.
 * @param onAccountSelected reports the account the user wants to browse.
 * @param onVerify asks the provider to re-check a stored credential.
 * @param onRemove disconnects an account (after a confirmation).
 * @param onBeginAuthorization starts an interactive flow; the *flow* comes from the button that was
 *   pressed, which is one button per advertised flow.
 * @param onCancelAuthorization abandons an in-flight challenge and releases its server-side state.
 * @param onDismissBanner clears the one-line message.
 * @param onReload re-reads the account list, used by the empty and error states.
 * @param quotas storage usage per account, when the caller has fetched it. Defaulted to empty
 *   because the quota is a *network* fact that lives behind `AccountRepository.storageQuota`, not
 *   part of [AccountsUiState]: folding it in would make this screen's state depend on a per-provider
 *   round trip. The map is typed ([AccountId] to [StorageQuota]), so a reader cannot misread what is
 *   in it, and an absent entry simply omits the bar — which is the honest rendering for a provider
 *   that reports no usage.
 */
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onAccountSelected: (providerId: String, accountId: String) -> Unit,
    onVerify: (providerId: String, accountId: String) -> Unit,
    onRemove: (providerId: String, accountId: String) -> Unit,
    onBeginAuthorization: (providerId: String, flow: AuthFlowKind) -> Unit,
    onCancelAuthorization: () -> Unit,
    onDismissBanner: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    quotas: Map<AccountId, StorageQuota> = emptyMap(),
    onCancelManualCredential: () -> Unit = {},
    onSubmitManualCredential: (String) -> Unit = {},
) {
    var removeTarget by remember { mutableStateOf<CloudAccount?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // The nav host hands this destination a zero inset (see `NotDiskNavHost`), because the
            // file browser owns its own `TopAppBar` padding. This screen has no app bar of its own,
            // so without this the first section header ("已连接的账号") is drawn underneath the
            // status bar. Reached from 设置 → 账号 and, now, from the files tab's 去绑定账号 button.
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        state.banner?.let { banner ->
            BannerStrip(banner = banner, onDismiss = onDismissBanner)
        }

        (state.authorization as? AuthorizationState.Waiting)?.let { waiting ->
            AuthorizationPanel(
                challenge = waiting.challenge,
                pollHint = state.pollHint,
                onCancel = onCancelAuthorization,
            )
        }

        when {
            state.isLoading && state.accounts.isEmpty() && state.providers.isEmpty() ->
                LoadingState(label = "正在读取已连接的账号…")

            !state.isLoading && state.accounts.isEmpty() && !state.canAddAccount ->
                EmptyState(
                    title = "没有可用的网盘",
                    icon = Icons.Filled.CloudOff,
                    description = "当前版本没有注册任何可绑定的网盘 Provider，因此无法添加账号。" +
                        "这通常意味着构建时缺少了一个 provider 模块。",
                    actionLabel = "重新加载",
                    onAction = onReload,
                )

            else -> {
                if (state.accounts.isEmpty() && !state.isLoading) {
                    EmptyState(
                        title = "还没有连接任何网盘账号",
                        icon = Icons.Filled.AccountCircle,
                        description = "在下方选择要连接的网盘并完成登录，之后就可以在这里浏览和播放其中的文件。",
                    )
                }

                if (state.accounts.isNotEmpty()) {
                    SectionHeader(
                        title = "已连接的账号",
                        countText = "${state.accounts.size} 个",
                        showDivider = true,
                    )
                    state.accounts.forEach { account ->
                        AccountCard(
                            account = account,
                            // The vendor's own display name, resolved from the descriptor catalog.
                            // Falling back to the raw provider id would show a user "p115" where
                            // every other surface says "115 网盘".
                            providerDisplayName = state.providers
                                .firstOrNull { it.id == account.providerId }
                                ?.displayName
                                ?: account.providerId.value,
                            quota = quotas[account.accountId],
                            isVerifying = state.verifyingAccountId == account.accountId,
                            isRemoving = state.removingAccountId == account.accountId,
                            onSelect = {
                                onAccountSelected(account.providerId.value, account.accountId.value)
                            },
                            onVerify = {
                                onVerify(account.providerId.value, account.accountId.value)
                            },
                            onRemove = { removeTarget = account },
                        )
                    }
                }

                AddAccountSection(
                    providers = state.providers,
                    enabled = state.authorization is AuthorizationState.Idle,
                    onBeginAuthorization = onBeginAuthorization,
                )
            }
        }
    }

    val target = removeTarget
    ConfirmDialog(
        title = target?.let { "断开「${it.displayName}」？" } ?: "断开账号？",
        message = "该账号的访问凭据会从本机删除，本地缓存的文件记录也会一并清除。" +
            "网盘上的文件不会被删除，随时可以重新登录。",
        visible = target != null,
        onDismiss = { removeTarget = null },
        onConfirm = {
            target?.let { onRemove(it.providerId.value, it.accountId.value) }
            removeTarget = null
        },
        confirmLabel = "断开连接",
        destructive = true,
    )

    ManualCredentialDialog(
        providerName = state.manualCredentialProviderName,
        visible = state.manualCredentialProviderId != null,
        isSubmitting = state.isSubmittingCredential,
        onDismiss = onCancelManualCredential,
        onConfirm = onSubmitManualCredential,
    )
}

/**
 * The cookie-paste dialog for [AuthFlowKind.MANUAL_CREDENTIAL].
 *
 * ### Why a multi-line field rather than the shared [TextInputDialog]
 * A 115 cookie is a 300+ character string pasted from devtools. `TextInputDialog` is single-line
 * and pre-selects its content, which is exactly right for renaming a file and exactly wrong here:
 * a single-line field shows about forty characters of a value the user must be able to *eyeball*
 * (a truncated paste is the most likely failure), and it would silently accept a mangled value.
 * This dialog therefore shows a wrapping, multi-line field and states what the value must contain.
 *
 * ### Why the confirm button is not disabled on a length check
 * There is no client-side rule for "a valid cookie": only the vendor can say. A length heuristic
 * would reject a legitimately short session and accept a truncated long one. The button is disabled
 * only while nothing has been typed, which prevents the one mistake that needs no network call to
 * detect.
 */
@Composable
private fun ManualCredentialDialog(
    providerName: String?,
    visible: Boolean,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(visible) { mutableStateOf("") }

    if (!visible) return

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(text = "使用 Cookie 登录${providerName?.let { "：$it" } ?: ""}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Small)) {
                Text(
                    text = "在浏览器中登录 115 后，打开开发者工具的「网络」面板，" +
                        "复制任意请求的 Cookie 请求头，粘贴到下面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "需要包含 UID、CID、SEID、KID 四项；可直接粘贴整行 Cookie 内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSubmitting,
                    // Multi-line on purpose: see this function's KDoc.
                    singleLine = false,
                    minLines = 4,
                    maxLines = 8,
                    label = { Text(text = "Cookie 内容") },
                    placeholder = { Text(text = "UID=…; CID=…; SEID=…; KID=…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                Text(
                    text = "凭据会保存到本机的加密存储（Android Keystore），不会写入日志或普通数据库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = !isSubmitting && value.isNotBlank(),
            ) {
                Text(text = if (isSubmitting) "验证中…" else "登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(text = "取消") }
        },
    )
}

/**
 * One connected account.
 *
 * ### Why the identity and the status are two components rather than one
 * [CloudAccountChip] owns the *identity* question ("which account is this, and is it usable?") and
 * is shared with any future account switcher. The storage [StatRow] is a separate concern that only
 * this screen has, and it is genuinely optional — a provider that does not report a quota gets no
 * bar at all rather than an empty one, because an empty bar reads as "zero bytes used", which is a
 * different and usually false claim.
 *
 * @param providerDisplayName the vendor's own name for the provider, resolved from the descriptor
 *   catalog. Never the raw [ProviderId]: a user should not have to read "p115" where the rest of the
 *   app says "115 网盘".
 * @param quota the account's storage usage, or `null` when the provider does not report it (or the
 *   one-shot read has not returned).
 */
@Composable
private fun AccountCard(
    account: CloudAccount,
    providerDisplayName: String,
    quota: StorageQuota?,
    isVerifying: Boolean,
    isRemoving: Boolean,
    onSelect: () -> Unit,
    onVerify: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.XSmall),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(vertical = Dimens.Small)) {
            CloudAccountChip(
                providerName = providerDisplayName,
                accountDisplayName = account.displayName,
                // The chip maps the raw status text itself, so the domain enum is passed through by
                // name: one conversion point, and an unknown value degrades to "未知".
                authStatus = account.authState.name,
                accountDetail = account.detailLine(),
                // Tapping the chip is the same as tapping 设为当前: the chip is the most obvious
                // target on the card, and making it inert would be surprising.
                onClick = onSelect,
            )

            // Absent for a provider that does not report usage, or before the one-shot quota read
            // has returned. Rendering an empty bar instead would claim "zero bytes used", which is
            // a different and usually false statement.
            quota?.let { usage ->
                StatRow(
                    label = "已用空间",
                    value = usage.usedLabel ?: FileSizeFormatter.formatBytes(usage.usedBytes),
                    progress = usage.fraction(),
                    supportingText = usage.totalBytes?.let { total ->
                        "共 ${usage.totalLabel ?: FileSizeFormatter.formatBytes(total)}"
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.Medium, vertical = Dimens.XSmall),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSelect, enabled = !isRemoving) { Text("设为当前") }
                OutlinedButton(onClick = onVerify, enabled = !isVerifying && !isRemoving) {
                    if (isVerifying) {
                        // A spinner inside the button, not a screen-wide one: only this account is
                        // being checked, and blanking the list would hide the others.
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconSmall),
                            strokeWidth = Dimens.Hairline,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Text(
                        text = if (isVerifying) "验证中…" else "验证",
                        modifier = Modifier.padding(start = Dimens.XSmall),
                    )
                }
                TextButton(onClick = onRemove, enabled = !isRemoving) {
                    Text(
                        text = if (isRemoving) "断开中…" else "移除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The "add an account" section: one card per provider, one button per flow that provider advertises.
 *
 * ### Why the buttons come from `availableAuthFlows`
 * The set is the provider's own declaration of what it can actually run *in this build* — a 115
 * integration without a registered OAuth client id simply does not list `OAUTH_REDIRECT`, and this
 * screen needs no knowledge of that. A provider that lists nothing is rendered as an explanatory row
 * with its `configurationNotes`, because "no button" without a reason is indistinguishable from a
 * bug.
 */
@Composable
private fun AddAccountSection(
    providers: List<ProviderDescriptor>,
    enabled: Boolean,
    onBeginAuthorization: (providerId: String, flow: AuthFlowKind) -> Unit,
) {
    SectionHeader(
        title = "添加账号",
        countText = "${providers.count { it.availableAuthFlows.isNotEmpty() }} 个可添加",
        showDivider = true,
    )

    if (providers.isEmpty()) {
        Text(
            text = "没有可用的网盘 Provider。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.Large, vertical = Dimens.Medium),
        )
        return
    }

    providers.forEach { provider ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.Large, vertical = Dimens.XSmall),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(Dimens.Medium),
                verticalArrangement = Arrangement.spacedBy(Dimens.Small),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
                ) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (provider.isDevelopmentOnly) {
                        // Badged, never hidden: a developer build legitimately ships an offline
                        // provider, but a user must never mistake it for a real cloud account.
                        Text(
                            text = "仅供开发",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = Dimens.Small, vertical = Dimens.Hairline),
                        )
                    }
                }

                if (provider.description.isNotBlank()) {
                    Text(
                        text = provider.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Configuration notes are shown verbatim: they come from the provider (or from the
                // DI module that registered it) and describe what this *build* still needs, which
                // is exactly the information a failing login button would otherwise hide.
                provider.configurationNotes.forEach { note ->
                    Text(
                        text = "· $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (provider.availableAuthFlows.isEmpty()) {
                    Text(
                        text = "该网盘在当前版本中没有可用的登录方式，暂时无法绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
                    ) {
                        // The order is the enum's declaration order, which is stable across builds —
                        // a `Set` iteration order is not, and buttons that swap places between
                        // launches are a real usability problem.
                        AuthFlowKind.entries.forEach { flow ->
                            if (flow in provider.availableAuthFlows) {
                                Button(
                                    onClick = { onBeginAuthorization(provider.id.value, flow) },
                                    enabled = enabled,
                                ) {
                                    Icon(flow.icon(), contentDescription = null)
                                    Text(
                                        text = flow.chineseLabel(),
                                        modifier = Modifier.padding(start = Dimens.XSmall),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The in-flight authorisation panel.
 *
 * ### The QR decision, and why it is honest rather than a shortcut
 * This module has no QR *encoder*: the project deliberately carries no such dependency, and adding
 * one to draw a code we cannot verify would be worse than what is here.
 *
 * A QR code is nothing but a rendering of a byte string, and `AuthChallenge.QrCode.content` **is**
 * that exact string — the payload the vendor's app decodes after scanning. So the panel shows the
 * payload twice, both of them truthful:
 *  - as selectable monospace text, which the user can copy into the vendor's own app or into any QR
 *    generator on another device (115's web client does exactly this when its canvas fails to
 *    render);
 *  - as `imageUrl`, when the provider supplies a *server-rendered* QR image, via Coil. That is a
 *    picture the vendor produced, not one this app fabricated, so there is nothing to get wrong.
 *
 * The loss is real and worth naming: on a device where the provider returns only `content` and no
 * `imageUrl`, a user with just this phone cannot scan their own screen. The upstream web client has
 * the same limitation. A QR encoder can be plugged in later by adding a dependency and replacing the
 * text block with a `Canvas` drawing of `content` — the challenge data, the countdown, the polling
 * cadence and the cancel affordance all stay exactly as they are, because none of them depend on how
 * the code is drawn.
 *
 * ### Why the polling continues while this panel is open
 * Scanning is asynchronous; the ViewModel polls and pushes `pollHint` ("已扫码，请在手机上确认登录")
 * so the user can see that their scan registered instead of staring at an unchanging code.
 */
@Composable
private fun AuthorizationPanel(
    challenge: AuthChallenge,
    pollHint: String?,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.Medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = challenge.title(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            when (challenge) {
                is AuthChallenge.QrCode -> QrChallengeBody(challenge)
                is AuthChallenge.Redirect -> RedirectChallengeBody(challenge)
                is AuthChallenge.DeviceCode -> DeviceCodeChallengeBody(challenge)
                is AuthChallenge.Completed -> Text(
                    // Reachable only for the instant between the challenge arriving and the list
                    // reloading; saying so is better than an empty panel.
                    text = "授权已完成，正在刷新账号列表…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (!pollHint.isNullOrBlank()) {
                Text(
                    text = pollHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            TextButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text(text = "取消", modifier = Modifier.padding(start = Dimens.XSmall))
            }
        }
    }
}

/** The QR challenge: countdown, the payload as text, and the vendor's image when there is one. */
@Composable
private fun QrChallengeBody(challenge: AuthChallenge.QrCode) {
    // `LocalClipboard` (not the deprecated `LocalClipboardManager`): its write is suspending, which
    // is the API that survives the platform's move to async clipboard access.
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    challenge.expiresAt?.let { expiresAt ->
        CountdownText(expiresAt = expiresAt)
    }

    if (challenge.imageUrl != null) {
        AsyncImage(
            // The provider's own rendered QR. It is a short-lived signed resource, which is exactly
            // why it is fetched here and never cached in the app's own storage.
            model = challenge.imageUrl,
            contentDescription = "登录二维码，请使用网盘 App 扫描",
            modifier = Modifier.size(QR_IMAGE_SIZE),
        )
    }

    Text(
        text = "二维码内容（可复制到网盘 App 或另一台设备生成二维码）：",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    // Monospace + selectable: the payload is an opaque token, and a variable-width font makes it
    // impossible to check a character when a scan fails and the user starts troubleshooting.
    // `SelectionContainer` is a wrapper composable (not a `Modifier`), so it is applied around the
    // text rather than chained onto it.
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
            )
            .padding(Dimens.Small),
    ) {
        Text(
            text = challenge.content,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    TextButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("二维码内容", challenge.content)),
                )
            }
        },
    ) {
        Text("复制二维码内容")
    }
}

/** The redirect challenge: the user must leave the app, so the panel hands over a link. */
@Composable
private fun RedirectChallengeBody(challenge: AuthChallenge.Redirect) {
    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
    Text(
        text = "请在打开的页面中完成登录，完成后回到本应用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Text(
        text = challenge.authorizationUrl,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    // The panel deliberately does not launch a browser itself: opening an external Custom Tab is a
    // platform concern the app module owns, and a feature module that started activities would not
    // be testable or previewable.
    Text(
        text = "若页面没有自动打开，请复制上面的链接到浏览器中打开。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/** The device-code challenge: the code is the whole point, so it is large and copyable. */
@Composable
private fun DeviceCodeChallengeBody(challenge: AuthChallenge.DeviceCode) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Icon(Icons.Filled.Devices, contentDescription = null)
    challenge.expiresAt?.let { CountdownText(expiresAt = it) }
    Text(
        text = challenge.userCode,
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Text(
        text = "请在 ${challenge.verificationUrl} 输入上面的代码完成授权。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    TextButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("设备码", challenge.userCode)))
            }
        },
    ) {
        Text("复制代码")
    }
}

/**
 * A live countdown to [expiresAt].
 *
 * ### Why a countdown at all
 * A QR code that silently stops working is indistinguishable from a hung app: the user keeps
 * scanning, nothing happens, and they conclude the app is broken. Showing the remaining validity
 * turns "it is not working" into "it expired, get a new one", which is the actual situation.
 *
 * The remaining time is recomputed once a second rather than derived from a clock read during
 * composition, so the text cannot be stale by an arbitrary amount after a long frame.
 */
@Composable
private fun CountdownText(expiresAt: Long) {
    var remainingSeconds by remember(expiresAt) {
        mutableStateOf(((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L))
    }

    LaunchedEffect(expiresAt) {
        while (remainingSeconds > 0L) {
            delay(COUNTDOWN_TICK_MILLIS)
            remainingSeconds = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        }
    }

    Text(
        text = if (remainingSeconds > 0L) {
            "有效期剩余 ${remainingSeconds} 秒"
        } else {
            "二维码已过期，请取消后重新获取"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (remainingSeconds > 0L) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

/**
 * The one-line message above the list.
 *
 * ### Why a strip and not a Snackbar
 * A `Snackbar` needs a `SnackbarHost` and a `Scaffold`, and it disappears on a timer. These messages
 * carry information the user may need to *act* on — "授权已失效，请重新登录" is not a transient
 * notification — and it must stay until it is dismissed. The colour is chosen from
 * [Banner.isWarning] so a warning and a confirmation are distinguishable at a glance rather than by
 * reading.
 */
@Composable
private fun BannerStrip(banner: Banner, onDismiss: () -> Unit) {
    val container = if (banner.isWarning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (banner.isWarning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Small),
    ) {
        Text(
            text = banner.message,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "关闭提示", tint = content)
        }
    }
}

/** Chinese labels for the advertised login flows. Here, not on the enum: `core:model` is UI-free. */
private fun AuthFlowKind.chineseLabel(): String = when (this) {
    AuthFlowKind.QR_CODE -> "扫码登录"
    AuthFlowKind.PASSWORD -> "账号密码"
    AuthFlowKind.OAUTH_REDIRECT -> "OAuth 登录"
    AuthFlowKind.DEVICE_CODE -> "设备码"
    AuthFlowKind.MANUAL_CREDENTIAL -> "手动凭据"
}

/** A glyph per flow, so the buttons are scannable rather than five identical shapes. */
private fun AuthFlowKind.icon() = when (this) {
    AuthFlowKind.QR_CODE -> Icons.Filled.QrCode2
    AuthFlowKind.PASSWORD -> Icons.Filled.AccountCircle
    AuthFlowKind.OAUTH_REDIRECT -> Icons.AutoMirrored.Filled.OpenInNew
    AuthFlowKind.DEVICE_CODE -> Icons.Filled.Devices
    AuthFlowKind.MANUAL_CREDENTIAL -> Icons.Filled.AccountCircle
}

/** A heading per challenge type, phrased as the action the user must take. */
private fun AuthChallenge.title(): String = when (this) {
    is AuthChallenge.QrCode -> "请使用网盘 App 扫码登录"
    is AuthChallenge.Redirect -> "请在浏览器中完成登录"
    is AuthChallenge.DeviceCode -> "请在另一台设备上输入验证码"
    is AuthChallenge.Completed -> "授权完成"
}

/** The account's secondary line: membership tier and when the credential was last confirmed. */
private fun CloudAccount.detailLine(): String? {
    val parts = buildList {
        membershipLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        membershipExpiresAt?.let { add("到期 ${CloudDateTimeFormatter.formatDate(it)}") }
        lastVerifiedAt?.let { add("上次验证 ${CloudDateTimeFormatter.format(it)}") }
        if (authState == AuthState.UNKNOWN) add("尚未验证授权状态")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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

/** A 1 Hz tick: fast enough that the countdown never looks stuck, slow enough to cost nothing. */
private const val COUNTDOWN_TICK_MILLIS = 1_000L

private val QR_IMAGE_SIZE = androidx.compose.ui.unit.Dp(200f)

// ---------------------------------------------------------------------------- previews

private fun previewProviders(): List<ProviderDescriptor> = listOf(
    ProviderDescriptor(
        id = ProviderId("mock"),
        displayName = "离线演示网盘 (Mock)",
        description = "文件浏览 · 搜索 · 在线播放 · 图片预览 · 上传",
        availableAuthFlows = setOf(
            AuthFlowKind.QR_CODE,
            AuthFlowKind.MANUAL_CREDENTIAL,
        ),
        isDevelopmentOnly = true,
    ),
    ProviderDescriptor(
        id = ProviderId("p115"),
        displayName = "115 网盘",
        description = "文件浏览 · 搜索 · 在线播放",
        availableAuthFlows = setOf(
            AuthFlowKind.QR_CODE,
            AuthFlowKind.PASSWORD,
            AuthFlowKind.DEVICE_CODE,
        ),
        configurationNotes = listOf("OAuth 登录需要先配置 client id（当前构建未配置）"),
    ),
    ProviderDescriptor(
        id = ProviderId("locked"),
        displayName = "未配置的网盘",
        description = "尚未验证任何功能",
        availableAuthFlows = emptySet(),
        configurationNotes = listOf("缺少 API 凭据，请先配置"),
    ),
)

/**
 * A quota for the preview only.
 *
 * `AccountsUiState.quotas` is populated by the *app* module (it owns the one-shot
 * `AccountRepository.storageQuota` read), so the preview supplies one directly to keep the
 * [StatRow] branch visible in tooling rather than silently unreachable.
 */
private fun previewQuotas(): Map<AccountId, StorageQuota> = mapOf(
    AccountId("preview-1") to StorageQuota(
        totalBytes = 10L * 1024 * 1024 * 1024,
        usedBytes = 4_500_000_000L,
        remainingBytes = 5_500_000_000L,
    ),
)

private fun previewAccounts(): List<CloudAccount> = listOf(
    CloudAccount(
        accountId = AccountId("preview-1"),
        providerId = ProviderId("p115"),
        remoteUserId = "10001",
        displayName = "张三",
        authState = AuthState.AUTHORIZED,
        membershipLabel = "年费 VIP",
        membershipExpiresAt = 1_900_000_000_000L,
        lastVerifiedAt = 1_700_000_000_000L,
    ),
    CloudAccount(
        accountId = AccountId("preview-2"),
        providerId = ProviderId("mock"),
        remoteUserId = "mock-user-1",
        displayName = "Mock 用户",
        authState = AuthState.EXPIRED,
        membershipLabel = "Mock 会员",
    ),
)

/** The normal screen: two accounts of different states and three providers. */
@Preview(name = "账号 (已连接)", showBackground = true, heightDp = 900)
@Composable
private fun AccountsScreenPreview() {
    NotDiskTheme(dynamicColor = false) {
        AccountsScreen(
            state = AccountsUiState(
                isLoading = false,
                providers = previewProviders(),
                accounts = previewAccounts(),
            ),
            quotas = previewQuotas(),
            onAccountSelected = { _, _ -> },
            onVerify = { _, _ -> },
            onRemove = { _, _ -> },
            onBeginAuthorization = { _, _ -> },
            onCancelAuthorization = {},
            onDismissBanner = {},
            onReload = {},
        )
    }
}

/** The empty state, which must look like "nothing yet", not like a failure. */
@Preview(name = "账号 (空)", showBackground = true, heightDp = 700)
@Composable
private fun AccountsScreenEmptyPreview() {
    NotDiskTheme(dynamicColor = false) {
        AccountsScreen(
            state = AccountsUiState(isLoading = false, providers = previewProviders()),
            onAccountSelected = { _, _ -> },
            onVerify = { _, _ -> },
            onRemove = { _, _ -> },
            onBeginAuthorization = { _, _ -> },
            onCancelAuthorization = {},
            onDismissBanner = {},
            onReload = {},
        )
    }
}

/** An in-flight QR challenge, with the payload shown as text (see [AuthorizationPanel]). */
@Preview(name = "账号 (扫码授权中)", showBackground = true, heightDp = 900)
@Composable
private fun AccountsScreenAuthorizationPreview() {
    NotDiskTheme(dynamicColor = false) {
        AccountsScreen(
            state = AccountsUiState(
                isLoading = false,
                providers = previewProviders(),
                accounts = previewAccounts(),
                authorization = AuthorizationState.Waiting(
                    AuthChallenge.QrCode(
                        challengeId = "preview-challenge",
                        content = "https://qrcode.115.com/?uid=preview&sign=0123456789abcdef",
                        expiresAt = System.currentTimeMillis() + 95_000L,
                        imageUrl = null,
                    ),
                ),
                pollHint = "已扫码，请在手机上确认登录",
                providerId = ProviderId("p115"),
            ),
            onAccountSelected = { _, _ -> },
            onVerify = { _, _ -> },
            onRemove = { _, _ -> },
            onBeginAuthorization = { _, _ -> },
            onCancelAuthorization = {},
            onDismissBanner = {},
            onReload = {},
        )
    }
}

/** The warning banner, which must be visibly different from a confirmation. */
@Preview(name = "账号 (警告)", showBackground = true, heightDp = 700)
@Composable
private fun AccountsScreenWarningPreview() {
    NotDiskTheme(dynamicColor = false) {
        AccountsScreen(
            state = AccountsUiState(
                isLoading = false,
                providers = previewProviders(),
                accounts = previewAccounts(),
                banner = Banner(message = "115 网盘：授权已失效，请重新登录", isWarning = true),
            ),
            onAccountSelected = { _, _ -> },
            onVerify = { _, _ -> },
            onRemove = { _, _ -> },
            onBeginAuthorization = { _, _ -> },
            onCancelAuthorization = {},
            onDismissBanner = {},
            onReload = {},
        )
    }
}
