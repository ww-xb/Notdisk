package com.notdisk.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.data.repository.AccountRepository
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.ProviderDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The accounts screen's state machine.
 *
 * ### Why the UI state is a single immutable value rather than several `StateFlow`s
 * The screen has states that are only meaningful in combination: "a QR challenge is showing" implies
 * "the account list is not being interacted with", and "an error is showing" must not coexist with a
 * spinner. A single [AccountsUiState] makes the impossible combinations unrepresentable; separate
 * flows would let them happen and force the composable to defend against them.
 *
 * ### Why authorisation is a small explicit state machine
 * The project brief forbids faking a login, and 115's real flow has four observable steps (fetch
 * token → poll → confirmed → exchange). Modelling them explicitly means the UI can show *what is
 * actually happening* — including "已扫码，请在手机上确认" — instead of a generic spinner that would be
 * indistinguishable from a hang.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    /** The running poll loop, so it can be cancelled when the user leaves or starts a new flow. */
    private var pollJob: Job? = null

    init {
        loadAccounts()
    }

    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, banner = null) }

            when (val result = accounts.listAccounts()) {
                is CloudResult.Failure -> _state.update {
                    it.copy(isLoading = false, banner = Banner.from(result.error))
                }

                is CloudResult.Success -> _state.update { current ->
                    current.copy(
                        isLoading = false,
                        providers = accounts.providerCatalog(),
                        accounts = result.data.accounts,
                        // Provider-side failures are surfaced as a dismissible warning rather than an
                        // error page: the accounts that *did* load are still usable, and hiding them
                        // because one unrelated cloud was unreachable would be worse.
                        banner = result.data.failures.firstOrNull()?.let { failure ->
                            Banner(
                                message = "${failure.providerName}：${failure.error.userFacingMessage}",
                                isWarning = true,
                            )
                        },
                    )
                }
            }
        }
    }

    /** Starts an interactive authorisation and drives the poll loop. */
    fun beginAuthorization(providerId: ProviderId, flow: AuthFlowKind) {
        if (flow == AuthFlowKind.MANUAL_CREDENTIAL) {
            // The credential is entered in a dialog *before* any request is issued, so the flow is
            // staged rather than started: the dialog's confirm button calls
            // [submitManualCredential]. Starting the flow here would immediately fail the
            // provider's "no credential supplied" check and show the user an error for a form they
            // have not filled in yet.
            _state.update {
                it.copy(
                    manualCredentialProviderId = providerId,
                    pollHint = null,
                    banner = null,
                )
            }
            return
        }

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.update {
                it.copy(authorization = AuthorizationState.Idle, pollHint = null, banner = null)
            }

            when (val result = accounts.beginAuthorization(providerId, flow)) {
                is CloudResult.Failure -> _state.update {
                    it.copy(
                        authorization = AuthorizationState.Idle,
                        banner = Banner.from(result.error),
                    )
                }

                is CloudResult.Success -> {
                    val challenge = result.data
                    _state.update {
                        it.copy(
                            authorization = AuthorizationState.Waiting(challenge),
                            providerId = providerId,
                        )
                    }

                    // `Completed` means the provider produced credentials synchronously (no user
                    // interaction). Nothing to poll, so the list is refreshed immediately.
                    if (challenge is AuthChallenge.Completed) {
                        onAuthorizationFinished(providerId, challenge.accountId)
                        return@launch
                    }

                    pollUntilTerminal(providerId, challenge.challengeId)
                }
            }
        }
    }

    /** Closes the manual-credential dialog without submitting anything. */
    fun cancelManualCredential() {
        _state.update { it.copy(manualCredentialProviderId = null) }
    }

    /**
     * Submits a pasted session cookie.
     *
     * The dialog stays open on failure, because the overwhelmingly likely cause is a bad paste and
     * closing it would force the user to start over from the provider card.
     */
    fun submitManualCredential(credential: String) {
        val providerId = _state.value.manualCredentialProviderId ?: return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.update { it.copy(isSubmittingCredential = true, banner = null) }

            val result = accounts.beginAuthorization(
                providerId = providerId,
                flow = AuthFlowKind.MANUAL_CREDENTIAL,
                params = com.notdisk.core.provider.AuthFlowParams(manualCredential = credential),
            )

            _state.update { it.copy(isSubmittingCredential = false) }

            when (result) {
                is CloudResult.Failure -> _state.update {
                    it.copy(banner = Banner.from(result.error))
                }

                is CloudResult.Success -> when (val challenge = result.data) {
                    is AuthChallenge.Completed -> {
                        _state.update { it.copy(manualCredentialProviderId = null) }
                        onAuthorizationFinished(providerId, challenge.accountId)
                    }

                    // A manual-credential flow has nothing to poll: a provider that answers with
                    // any other challenge shape has not implemented what it advertised, and saying
                    // so is better than spinning on a code that will never advance.
                    else -> _state.update {
                        it.copy(
                            manualCredentialProviderId = null,
                            banner = Banner(
                                message = "该网盘没有完成 Cookie 登录所需的实现",
                                isWarning = true,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Polls the challenge until it reaches a terminal state.
     *
     * ### Cadence
     * `API_REFERENCE.md` §2.1 records that 115 throttles its QR status endpoint to roughly one
     * effective response per 30 seconds and explicitly warns that the web client's 500 ms loop
     * "可能触发风控". This loop therefore polls every [POLL_INTERVAL_MILLIS] (2.5 s) — responsive
     * enough that a scan feels immediate, far from the rate that risks a risk-control response.
     *
     * ### Why a `delay` loop rather than a Flow
     * The loop has a side effect (state updates) and a cancellation condition, and it runs once per
     * authorisation attempt. A Flow would add an operator graph for a single consumer.
     */
    private suspend fun pollUntilTerminal(providerId: ProviderId, challengeId: String) {
        var elapsed = 0L
        while (elapsed < CHALLENGE_TIMEOUT_MILLIS) {
            delay(POLL_INTERVAL_MILLIS)
            elapsed += POLL_INTERVAL_MILLIS

            when (val result = accounts.pollAuthorization(providerId, challengeId)) {
                is CloudResult.Failure -> {
                    // A transient failure must not kill the challenge: the user may still scan. Only a
                    // provider-reported terminal state or the local timeout ends the loop.
                    _state.update { it.copy(pollHint = "网络异常，正在重试…") }
                }

                is CloudResult.Success -> when (val challengeState = result.data) {
                    is AuthChallengeState.Pending -> _state.update {
                        it.copy(pollHint = challengeState.hint ?: "请使用网盘 App 扫码")
                    }

                    AuthChallengeState.Scanned -> _state.update {
                        it.copy(pollHint = "已扫码，请在手机上确认登录")
                    }

                    is AuthChallengeState.Succeeded -> {
                        onAuthorizationFinished(providerId, challengeState.accountId)
                        return
                    }

                    is AuthChallengeState.Failed -> {
                        _state.update {
                            it.copy(
                                authorization = AuthorizationState.Idle,
                                banner = Banner(
                                    message = challengeState.message,
                                    isWarning = challengeState.retryable,
                                ),
                            )
                        }
                        return
                    }
                }
            }
        }

        // The local timeout fires only when the provider never reported a terminal state, which means
        // the QR is past its documented ~120 s validity anyway.
        cancelAuthorization()
        _state.update {
            it.copy(
                authorization = AuthorizationState.Idle,
                banner = Banner(message = "授权超时，请重新获取二维码", isWarning = true),
            )
        }
    }

    /**
     * @param providerId the provider the account was bound to. Passed explicitly rather than read
     *   from state because the manual-credential path never sets `state.providerId` (it has no
     *   challenge to poll), so reading it back would verify against the wrong provider.
     */
    private fun onAuthorizationFinished(providerId: ProviderId, accountId: AccountId) {
        _state.update {
            it.copy(
                authorization = AuthorizationState.Idle,
                banner = Banner(message = "登录成功", isWarning = false),
            )
        }
        // Re-reading the authoritative list is what surfaces the new account, its real display name
        // and its membership tier.
        loadAccounts()

        viewModelScope.launch {
            accounts.verifyAccount(providerId, accountId)
            loadAccounts()
        }
    }

    fun cancelAuthorization() {
        pollJob?.cancel()
        pollJob = null
        val providerId = _state.value.providerId
        val challengeId = (_state.value.authorization as? AuthorizationState.Waiting)
            ?.challenge?.challengeId

        _state.update { it.copy(authorization = AuthorizationState.Idle, pollHint = null) }

        if (providerId != null && challengeId != null) {
            viewModelScope.launch { accounts.cancelAuthorization(providerId, challengeId) }
        }
    }

    fun verifyAccount(providerId: ProviderId, accountId: AccountId) {
        viewModelScope.launch {
            _state.update { it.copy(verifyingAccountId = accountId) }
            val result = accounts.verifyAccount(providerId, accountId)

            _state.update { current ->
                current.copy(
                    verifyingAccountId = null,
                    banner = when (result) {
                        is CloudResult.Success -> Banner(message = "授权有效", isWarning = false)
                        is CloudResult.Failure -> Banner.from(result.error)
                    },
                    // An account that just failed verification must show as expired immediately, so
                    // the list is reloaded rather than patched in place.
                    accounts = current.accounts.map { account ->
                        if (account.accountId == accountId && result is CloudResult.Failure) {
                            if (result.error.requiresReauthentication) {
                                account.copy(authState = AuthState.EXPIRED)
                            } else {
                                account
                            }
                        } else {
                            account
                        }
                    },
                )
            }
        }
    }

    fun removeAccount(providerId: ProviderId, accountId: AccountId) {
        viewModelScope.launch {
            _state.update { it.copy(removingAccountId = accountId) }
            when (val result = accounts.removeAccount(providerId, accountId)) {
                is CloudResult.Success -> {
                    _state.update { it.copy(removingAccountId = null) }
                    loadAccounts()
                }

                is CloudResult.Failure -> _state.update {
                    it.copy(removingAccountId = null, banner = Banner.from(result.error))
                }
            }
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    override fun onCleared() {
        // The poll loop holds a reference to this ViewModel's scope; leaving it running after the
        // screen is gone would keep issuing requests the user cannot see or cancel.
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /**
         * 2.5 s. Chosen against §2.1's documented throttle: fast enough that a scan feels instant,
         * ~12× slower than the web client's 500 ms loop that the manual warns about.
         */
        const val POLL_INTERVAL_MILLIS = 2_500L

        /**
         * 130 s: the manual states the QR is valid for about 120 s (§2.1 Step 2), so the loop outlives
         * it slightly and lets the *provider* report expiry rather than the client guessing first.
         */
        const val CHALLENGE_TIMEOUT_MILLIS = 130_000L
    }
}

/**
 * Everything the accounts screen renders.
 *
 * The in-flight [AuthorizationState.Waiting] challenge is part of the state (rather than held by the
 * composable) so that a configuration change does not lose an in-flight QR code, which would force
 * the user to scan again.
 */
data class AccountsUiState(
    val isLoading: Boolean = true,
    val providers: List<ProviderDescriptor> = emptyList(),
    val accounts: List<CloudAccount> = emptyList(),
    val authorization: AuthorizationState = AuthorizationState.Idle,
    val pollHint: String? = null,
    val providerId: ProviderId? = null,
    val verifyingAccountId: AccountId? = null,
    val removingAccountId: AccountId? = null,
    val banner: Banner? = null,
    /**
     * The provider whose manual-credential (cookie) dialog is open, or `null`.
     *
     * A *provider id* rather than a boolean because the dialog needs the provider's name in its
     * title, and because a second "add account" tap while it is open must not be able to bind the
     * pasted cookie to whichever provider was tapped last.
     */
    val manualCredentialProviderId: ProviderId? = null,
    /** A cookie is being verified against the vendor; the dialog's confirm button shows progress. */
    val isSubmittingCredential: Boolean = false,
) {
    val hasAccounts: Boolean get() = accounts.isNotEmpty()

    val canAddAccount: Boolean
        get() = providers.any { it.availableAuthFlows.isNotEmpty() }

    /** The provider name for the open manual-credential dialog. */
    val manualCredentialProviderName: String?
        get() = manualCredentialProviderId?.let { id ->
            providers.firstOrNull { it.id == id }?.displayName ?: id.value
        }
}

/** The interactive-authorisation phase. */
sealed interface AuthorizationState {
    data object Idle : AuthorizationState

    /** A challenge is on screen and being polled. */
    data class Waiting(val challenge: AuthChallenge) : AuthorizationState
}

/** A one-line message shown above the list. */
data class Banner(
    val message: String,
    val isWarning: Boolean,
) {
    companion object {
        /**
         * Turns a typed error into a banner.
         *
         * `requiresReauthentication` and `Unsupported` are marked as warnings because they need a
         * *different action* from the user ("重新登录" / "换个网盘"), not merely a retry — colour and
         * wording both matter for that.
         */
        fun from(error: CloudError): Banner = Banner(
            message = error.userFacingMessage,
            isWarning = error.requiresReauthentication ||
                error is CloudError.Unsupported ||
                error is CloudError.NetworkUnavailable,
        )
    }
}

/** Unused-import guard: keeps `getOrNull` documented as the sanctioned unwrap at UI boundaries. */
@Suppress("unused")
private val unwrapAtBoundary: (CloudResult<Int>) -> Int? = { it.getOrNull() }
