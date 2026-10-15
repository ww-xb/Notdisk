package com.notdisk.domain.accounts

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.cloudRunCatching
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry

/**
 * Reads every bound account across **all** registered providers.
 *
 * ### Why this is a use case and not a loop in the ViewModel
 * The accounts screen is the one screen that cannot be scoped to a single provider: the user
 * thinks in terms of "my accounts", not "115's accounts plus Aliyun's accounts". Somebody has to
 * fan out over the registry, and that somebody must not be the UI — otherwise every future screen
 * that needs the same data re-implements the fan-out, and each implementation resolves the
 * partial-failure question differently.
 *
 * ### The invariant that matters: one broken provider must not break the screen
 * [com.notdisk.core.provider.AuthProvider.listAccounts] is documented as "never contacts the
 * vendor", which makes it *hard* to fail — but not impossible. A provider can still fail because:
 *
 *  - its credential store is unreadable (keystore reset after an OS upgrade, encrypted prefs
 *    corruption, a locked device);
 *  - a row in its local database references a file that no longer parses;
 *  - the provider itself is half-initialised (a build variant registered it without its storage).
 *
 * All of those are *per-provider* problems. If they propagated as a single failure, one corrupt
 * provider would empty the entire accounts list — including the accounts of perfectly healthy
 * providers — and the user's only recovery would be to reinstall. So this use case collects
 * failures per provider and always succeeds with whatever it could read.
 *
 * ### Why a thrown exception is caught here, when nothing else in this module catches one
 * The provider contract forbids throwing for *expected* failures, and every other use case in this
 * module takes that at face value: a throw there is a genuine bug and letting it propagate to the
 * caller's error boundary is correct. This use case is the deliberate exception, and the reason is
 * the blast radius. It is the only call in the app that touches **every** registered provider at
 * once, on the screen the user needs in order to recover from a broken one. A single provider
 * throwing from a corrupted store would take out the accounts list for all of them, leaving the
 * user with no way to see — let alone remove — the account that is broken.
 *
 * [cloudRunCatching] is used rather than a bare `try`, so the conversion is the project's standard
 * one (an [java.io.IOException] becomes [CloudError.NetworkUnavailable], anything else becomes
 * [CloudError.Unknown] carrying the cause) and so that
 * [kotlinx.coroutines.CancellationException] still propagates — a cancelled screen must not be
 * reported as "this provider failed".
 *
 * ### Why the success type is not `List<CloudAccount>`
 * Because "no accounts" and "no accounts *because every provider failed*" are different screens.
 * The former is an empty state with an "add account" button; the latter is an error state with a
 * retry. Collapsing them into an empty list is how apps end up telling users they have no
 * accounts while their accounts are still on disk. [AccountsSnapshot] therefore carries the
 * failures alongside the accounts, and the UI branches on [AccountsSnapshot.isComplete].
 *
 * @see AccountsSnapshot for the payload shape and its ordering guarantees.
 */
class ListAccountsUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * Lists accounts from every registered provider.
     *
     * Never returns a failure for a *provider's* problem — a returned failure, a thrown exception
     * and an unreadable store all land in [AccountsSnapshot.failures] alike. It can still fail only
     * in the pathological case where the registry itself cannot be enumerated, which the registry
     * API does not permit, so in practice callers can treat this as always succeeding.
     */
    suspend operator fun invoke(): CloudResult<AccountsSnapshot> {
        val accounts = mutableListOf<CloudAccount>()
        val failures = mutableListOf<ProviderAccountFailure>()

        // Registration order is the registry's stable order; preserving it keeps the list from
        // shuffling between recompositions (a real annoyance when rows are animated).
        registry.all().forEach { provider ->
            when (val result = captureProviderCall { provider.auth.listAccounts() }) {
                is CloudResult.Success -> accounts += result.data
                is CloudResult.Failure -> failures += ProviderAccountFailure(
                    providerId = provider.id,
                    error = result.error,
                )
            }
        }

        // Deterministic ordering *within* a provider: accounts added earlier come first, ties
        // broken by display name so two accounts added in the same millisecond do not swap
        // positions on every refresh.
        val ordered = accounts.sortedWith(
            compareBy({ it.addedAt }, { it.displayName }, { it.accountId.value }),
        )

        return CloudResult.Success(
            AccountsSnapshot(
                accounts = ordered,
                failures = failures,
                providersQueried = registry.all().size,
            ),
        )
    }
}

/**
 * The payload of [ListAccountsUseCase].
 *
 * @property accounts every account that could be read, ordered by `addedAt` then `displayName`.
 *   Accounts from a provider that failed are absent, not partially represented — a provider
 *   either enumerates its accounts or it does not.
 * @property failures one entry per provider whose `listAccounts()` failed, in registration order.
 *   Empty means the snapshot is complete and the UI may show an honest empty state.
 * @property providersQueried how many providers were asked. Lets the UI distinguish "no providers
 *   are compiled into this build" (a configuration problem, worth a developer-facing message)
 *   from "providers exist but the user has not bound anything" (a normal empty state).
 */
data class AccountsSnapshot(
    val accounts: List<CloudAccount>,
    val failures: List<ProviderAccountFailure> = emptyList(),
    val providersQueried: Int = 0,
) {
    /** `true` when every registered provider answered; the list can be trusted as complete. */
    val isComplete: Boolean get() = failures.isEmpty()

    /** `true` when there is genuinely nothing to show and nothing went wrong. */
    val isEmptyAndComplete: Boolean get() = accounts.isEmpty() && isComplete
}

/**
 * One provider's failure to enumerate its accounts.
 *
 * [providerId] is carried separately from [error] because the two answer different questions:
 * "which integration is broken?" (actionable — the user can re-bind that provider's accounts or
 * the developer can fix it) versus "what went wrong?" (diagnostic).
 */
data class ProviderAccountFailure(
    val providerId: ProviderId,
    val error: CloudError,
)

/**
 * Runs one provider call, flattening "returned a Failure" and "threw" into the same shape.
 *
 * The double `CloudResult` is not a mistake: [cloudRunCatching] wraps the *call*, and the call
 * itself already returns a `CloudResult`. Unwrapping one level here is what lets the caller use a
 * single `when` for both a provider that honestly returned a failure and one that violated the
 * contract by throwing.
 *
 * [CloudResult]'s own contract is that cancellation is never captured; [cloudRunCatching] preserves
 * that by rethrowing [kotlinx.coroutines.CancellationException], so a cancelled accounts screen is
 * never misreported as a provider failure.
 */
private inline fun <T> captureProviderCall(block: () -> CloudResult<T>): CloudResult<T> =
    when (val outcome = cloudRunCatching(block)) {
        is CloudResult.Success -> outcome.data
        is CloudResult.Failure -> outcome
    }
