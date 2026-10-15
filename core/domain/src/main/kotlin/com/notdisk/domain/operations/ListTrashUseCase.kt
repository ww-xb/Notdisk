package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.core.provider.TrashPage
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Lists the recycle bin.
 *
 * ### Why the trash is not just another folder
 * It *looks* like one — a flat list with a name, a size and a deletion time — and every attempt to
 * model it as one produces bugs, because three of its properties break folder assumptions:
 *
 *  1. **Identity is different.** A trash entry is addressed by a *trash* id
 *     ([com.notdisk.core.provider.TrashItem.trashId]) — 115's `rid` — which is **not** the file
 *     id it had while alive. Every trash operation therefore takes `List<String>`, not
 *     `List<FileId>`, and conflating the two is a documented upstream bug source. The type system
 *     is used to make the mistake impossible rather than merely discouraged.
 *  2. **Entries are not browsable.** There is no "open this folder in the trash", so a trash entry
 *     cannot host a `FileId`-based navigation stack.
 *  3. **It can be locked.** [TrashPage.protectedBySecret] reports whether the account has a
 *     recycle-bin password set (115's `rb_pass`). That changes what the UI may offer: restoration
 *     and purging will demand a secret the user must type, and offering the buttons without warning
 *     produces a failure that looks like a bug.
 *
 * ### Why this is gated but does not validate a cursor
 * `operations.trash.list` is checked because a provider with no recycle bin must produce a typed
 * refusal rather than an empty list. An empty list is the *wrong* signal here in a way that matters:
 * "你的回收站是空的" tells the user their deleted files are gone forever, whereas "该网盘不支持回收站"
 * tells them the truth. Those two screens must not be reachable from the same code path.
 *
 * The cursor is **not** validated for progress, unlike [com.notdisk.domain.files.ListChildrenUseCase].
 * Trash listings are short, bounded, and loaded eagerly by every UI that shows them; the invariant
 * would add a failure mode to a screen where an accidental extra page is harmless. The deliberate
 * asymmetry is worth stating so it does not read as an oversight: the cursor-progress invariant is
 * applied where a non-advancing cursor causes an *unbounded* loop, which is the feed, and not where
 * it causes one redundant request.
 */
class ListTrashUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to list from.
     * @param accountId the local account id.
     * @param cursor opaque cursor from the previous [TrashPage]; `null` for the first page.
     * @param pageSize entries per page. Providers clamp to their own maximum.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        cursor: String? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): CloudResult<TrashPage> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.trash.list,
                capability = CAPABILITY_TRASH_LIST,
                message = "该网盘不支持回收站",
            ).flatMap { provider.operations.listTrash(accountId, cursor, pageSize) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_TRASH_LIST: String = "operations.trash.list"

        /** Matches the provider contract's own default; trash pages are shown eagerly. */
        const val DEFAULT_PAGE_SIZE: Int = 30
    }
}
