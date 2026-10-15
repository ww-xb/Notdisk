package com.notdisk.domain.files

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Lists one page of a folder's children, guaranteeing that "load more" can never loop forever.
 *
 * ### The bug this use case exists to make impossible
 * Paging is the one place where a provider bug does not look like a bug — it looks like a *hang*.
 * Consider a provider that translates an offset/limit vendor into the unified cursor model by
 * having `listChildren` return the request's own cursor back:
 *
 * ```
 * // provider-side bug
 * val next = request.cursor ?: "0"        // "0" means "page 1"
 * return FilePage(items = page, nextCursor = next)
 * ```
 *
 * The feed screen does the obvious thing — `cursor = page.nextCursor; load()` — so it re-requests
 * page 1 forever. Nothing throws, nothing logs an error, the progress indicator just spins and the
 * user sees the same forty files appended over and over. In a pull-to-refresh list this also
 * silently grows an in-memory list until the process dies, so the *visible* symptom is eventually
 * an OOM with no stack trace pointing at the provider.
 *
 * The unified contract already says the right thing — [FilePage.nextCursor] is `null` when there
 * are no further pages, and "a provider that simply does not know must not return a cursor that
 * re-fetches page 1". A contract sentence, however, is not enforcement; the shared provider
 * contract test catches it in *provider* CI, and this check catches it in *production*, for a
 * provider that was shipped before anyone noticed.
 *
 * ### The invariant, stated exactly
 * > For any request carrying `cursor = c`, a successful response must not have
 * > `nextCursor == c`.
 *
 * The check is deliberately narrow. It does **not** claim to detect every non-advancing pagination
 * (a provider that alternates `"1" → "2" → "1"` still slips through, and catching that would
 * require remembering the whole cursor history across calls, i.e. state that a stateless use case
 * must not hold). It catches the overwhelmingly common failure — echoing the input — at the one
 * place that has both values in hand, and it converts it from an infinite loop into a typed error
 * the UI can render as "this folder could not be loaded".
 *
 * ### Why [CloudError.Unknown] and not something more specific
 * Because nothing more specific would be *true*. The provider did not return an HTTP error, did not
 * refuse, and did not fail to parse; it returned a well-formed page that violates the contract. The
 * available types are all misleading in a way that costs debugging time later:
 *
 *  - [CloudError.ParseError] would send the next engineer hunting for a JSON mapping bug;
 *  - [CloudError.InvalidArgument] would blame the caller, who passed a cursor the provider itself
 *    issued on the previous page;
 *  - [CloudError.Unsupported] would wrongly disable paging for the provider forever.
 *
 * [CloudError.Unknown] is the model's own "anything not covered above", and the message carries the
 * full diagnosis (`cursor`, `providerId`, `parentId`) so the log line is actionable even though the
 * type is generic.
 *
 * ### Also enforced here: the request's own preconditions
 * [ListChildrenRequest] already validates `pageSize` in its `init` block, so a bad page size cannot
 * reach this method from Kotlin. The blank-parent guard is not in the model ([FileId] rejects blank
 * values for the same reason), so the gate is unnecessary; what *is* enforced here is the
 * forwarding of the request object untouched, so the provider sees exactly the cursor, page size
 * and sort the caller asked for — no silent clamping, which would break the invariant above by
 * hiding which cursor was actually sent.
 */
class ListChildrenUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration to ask.
     * @param accountId the local account id (never ambient "current account" state).
     * @param request the page request. `request.cursor == null` means "first page".
     * @return the provider's page, or [CloudError.Unsupported] when the provider is not registered,
     *   or [CloudError.Unknown] when the provider echoed the incoming cursor.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        request: ListChildrenRequest,
    ): CloudResult<FilePage> =
        registry.withProvider(providerId) { provider ->
            provider.files.listChildren(accountId, request).cursorSafeMap { page ->
                enforceCursorProgress(
                    page = page,
                    requestedCursor = request.cursor,
                    providerId = providerId,
                    parentId = request.parentId.value,
                )
            }
        }

    /**
     * Convenience overload for the common "first page / next page" call shape.
     *
     * Exists so the feed does not have to remember [ListChildrenRequest.DEFAULT_PAGE_SIZE] or the
     * default sort; the mapping onto the canonical request type happens once, here, rather than in
     * every ViewModel (where the defaults would drift apart).
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        parentId: com.notdisk.core.model.FileId,
        cursor: String? = null,
        pageSize: Int = ListChildrenRequest.DEFAULT_PAGE_SIZE,
        sort: com.notdisk.core.model.FileSort = com.notdisk.core.model.FileSort.Default,
    ): CloudResult<FilePage> = invoke(
        providerId = providerId,
        accountId = accountId,
        request = ListChildrenRequest(
            parentId = parentId,
            cursor = cursor,
            pageSize = pageSize,
            sort = sort,
        ),
    )

    /**
     * Contract check for the cursor-progress invariant.
     *
     * Extracted as a `@Throws`-free pure function rather than inlined so it can be unit-tested
     * directly without a provider — the invariant is subtle enough to deserve its own tests, and a
     * regression here is invisible until production.
     */
    private fun enforceCursorProgress(
        page: FilePage,
        requestedCursor: String?,
        providerId: ProviderId,
        parentId: String,
    ): FilePage {
        // A first-page request (`cursor == null`) cannot violate the invariant: echoing `null` means
        // "no more pages", which is exactly how a single-page folder is supposed to be reported.
        if (requestedCursor == null) return page

        return if (page.nextCursor == requestedCursor) {
            throw CursorNotAdvancingException(
                message = "Provider $providerId echoed cursor '$requestedCursor' for parent " +
                    "'$parentId'; refusing to loop. A non-advancing cursor would make 'load more' " +
                    "re-fetch the same page forever.",
            )
        } else {
            page
        }
    }
}

/**
 * Internal signal used to turn the echoed-cursor case into a typed failure.
 *
 * Thrown by the cursor-progress check and immediately caught by [cursorSafeMap] so the check can
 * live in a `map`-shaped pipeline without the exception escaping the use case. It is not part of
 * the public contract: callers only ever observe [CloudError.Unknown].
 */
internal class CursorNotAdvancingException(message: String) : IllegalStateException(message)

/**
 * `map`, but translating a [CursorNotAdvancingException] into the typed failure described on
 * [ListChildrenUseCase].
 *
 * File-private because the invariant is a property of *listing*: every other use case's `map`
 * should stay a plain `map`, and a shared helper would invite unrelated code to start throwing
 * through result pipelines.
 */
private inline fun <T> CloudResult<T>.cursorSafeMap(transform: (T) -> T): CloudResult<T> =
    when (this) {
        is CloudResult.Success -> try {
            CloudResult.Success(transform(data))
        } catch (e: CursorNotAdvancingException) {
            CloudResult.Failure(CloudError.Unknown(message = e.message ?: "cursor 未推进"))
        }
        is CloudResult.Failure -> this
    }
