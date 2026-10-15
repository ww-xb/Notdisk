package com.notdisk.domain.files

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.invalidArgument
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Runs a server-side search.
 *
 * ### Why the blank-keyword guard is here *and* in the model
 * [FileSearchQuery]'s `init` block already rejects a blank keyword, so from Kotlin a blank query
 * cannot be constructed. The guard in this use case exists anyway, for two reasons:
 *
 *  1. **It produces the right error rather than an exception.** The model throws
 *     `IllegalArgumentException` from a constructor. In a UI, that arrives during composition or
 *     event handling and crashes the screen. Here it becomes a typed
 *     [com.notdisk.core.common.error.CloudError.InvalidArgument] the screen can render next to
 *     the input. A search box with a "search" button is exactly where a stray empty query comes
 *     from — the user taps the button on an empty field — so this is a normal user action, not a
 *     programming error, and it must not crash.
 *  2. **It is enforced before the network, and observably so.** Tests assert that a rejected query
 *     results in *zero* provider calls, which is what makes "we never send a doomed request" a
 *     checkable property rather than a claim.
 *
 * The guard trims, too: a query of `"   "` is blank to a user and to a vendor, and sending it
 * would either return everything or be rejected. The **untrimmed** keyword is what gets forwarded
 * otherwise, because leading/trailing spaces can be meaningful in filenames and the vendor's own
 * matching semantics are the authority.
 *
 * ### The capability check is not a formality
 * [com.notdisk.core.model.FileSystemCapabilities.search] is
 * [com.notdisk.core.model.CapabilitySupport.UNSUPPORTED] for vendors without a search endpoint.
 * Those providers *can* still offer search by filtering a folder client-side, but that is a
 * different feature with different semantics (bounded to what has been listed, no server-side sort,
 * no category filter), and it belongs to whatever layer decides to walk pages — not to a use case
 * whose contract is "ask the server". Silently degrading here would make the same call return
 * results for one provider and nothing for another with no way for the caller to tell why.
 *
 * The optional `category` / `suffixes` filters are **not** gated, deliberately. A provider that
 * does not support them must express that by narrowing client-side or by returning the unfiltered
 * page; refusing the whole search because a filter is unsupported would turn a soft degradation
 * into a hard failure. [com.notdisk.core.model.FileSystemCapabilities.categoryFilter] and
 * `suffixFilter` are exposed so the *UI* can decide whether to render the filter chips at all —
 * the honest place for that decision, since only the UI knows whether it can post-filter.
 */
class SearchFilesUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to search.
     * @param accountId the local account id.
     * @param query the unified search request. `keyword` must not be blank.
     * @return one page of results. Note that search paging follows the same cursor-progress
     *   invariant as folder listing; providers that return unpaginated search results report
     *   `nextCursor = null` (115 does exactly this), which correctly disables "load more".
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        query: FileSearchQuery,
    ): CloudResult<FilePage> {
        if (query.keyword.isBlank()) {
            return invalidArgument("搜索关键词", "不能为空")
        }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.fileSystem.search,
                capability = CAPABILITY_SEARCH,
                message = "该网盘不支持搜索",
            ).flatMap { provider.files.search(accountId, query) }
        }
    }

    /**
     * Convenience overload for the common "search this keyword from the root" call.
     *
     * The blank check happens **before** the [FileSearchQuery] is constructed, because that type
     * rejects a blank keyword from its `init` block by throwing. This overload is the one a UI is
     * most likely to hand raw input to, so it must be the one that cannot throw.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        keyword: String,
    ): CloudResult<FilePage> {
        if (keyword.isBlank()) {
            return invalidArgument("搜索关键词", "不能为空")
        }
        return invoke(
            providerId = providerId,
            accountId = accountId,
            query = FileSearchQuery(keyword = keyword),
        )
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_SEARCH: String = "fileSystem.search"
    }
}
