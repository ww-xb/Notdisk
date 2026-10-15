package com.notdisk.domain.operations

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.invalidArgument
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Creates a folder under a parent.
 *
 * ### Why the name is validated here rather than only by the vendor
 * A folder name is the one piece of file metadata the user types by hand, so it is the one that
 * arrives malformed. The vendor's reaction to a bad name is unpredictable across integrations:
 * some return a generic error code, some silently create a differently-named folder, and some
 * create it *successfully* with a name that then cannot be addressed (a bare `""` on one vendor
 * produced an unopenable entry). Turning that into a local, typed refusal means:
 *
 *  - the user gets a message next to the input they just typed, before a spinner appears;
 *  - the app never has to reconcile a half-created remote object;
 *  - tests can assert the provider was not called at all, which is what makes "no doomed
 *    requests" a checkable property.
 *
 * The rules enforced are the ones that are true for *every* vendor, not a vendor's specific policy:
 *
 *  - **non-blank after trimming** — a folder named `"   "` is indistinguishable from one with no
 *    name in every UI the app has, so it is refused rather than persisted as a trap;
 *  - **no path separator or NUL** — a `/` in the name means "nested path" to some vendors and
 *    "literal character" to others, so accepting it would make the same input mean two different
 *    things depending on which account is active. `\` is included for the same reason;
 *  - **no `.` or `..`** — these collide with parent/self references in path-based resolvers
 *    (see [com.notdisk.domain.files.ResolvePathUseCase] for why the *path* layer does not ban
 *    them, and why the *name* layer must);
 *  - **a length ceiling** — vendors that accept an over-long name either truncate it (creating a
 *    name the user did not choose) or fail deep in an object-store key check. The ceiling is
 *    applied to the **UTF-8 byte length**, not the character count, because that is what HTTP and
 *    object-store keys actually limit; a 200-character Chinese name is 600 bytes and fails on
 *    vendors where a 200-character ASCII name passes.
 *
 * The name is **not** trimmed for the forwarded value: trimming would silently rename the user's
 * folder. Whitespace inside a name is meaningful; leading/trailing whitespace is *allowed through
 * as typed* once the name is not entirely blank, because a vendor that accepts it will round-trip
 * it, and one that normalises it is the authority on doing so.
 */
class CreateFolderUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration to create in.
     * @param accountId the local account id.
     * @param parentId the folder to create inside.
     * @param name the new folder's name, as typed by the user.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem> {
        validateFolderName(name)?.let { refusal -> return refusal }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.operations.createFolder,
                capability = CAPABILITY_CREATE_FOLDER,
                message = "该网盘不支持新建文件夹",
            ).flatMap { provider.operations.createFolder(accountId, parentId, name) }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_CREATE_FOLDER: String = "operations.createFolder"

        /** Conservative ceiling on the UTF-8 encoded name. Most vendors allow 255 bytes. */
        const val MAX_NAME_BYTES: Int = 255

        /**
         * Shared name validation, used by every use case that introduces a name to the remote side
         * ([CreateFolderUseCase] and [RenameUseCase]).
         *
         * Extracted so the two cannot drift: a name that is legal to create but illegal to rename
         * to is a bug users find by creating a folder and then being unable to fix its typo.
         *
         * @return `null` when [name] is acceptable, otherwise the typed refusal to return.
         */
        internal fun validateFolderName(name: String): CloudResult<Nothing>? = when {
            name.isBlank() -> invalidArgument("名称", "不能为空")
            name.contains('/') || name.contains('\\') ->
                invalidArgument("名称", "不能包含路径分隔符")
            name.contains('\u0000') -> invalidArgument("名称", "包含非法字符")
            name == "." || name == ".." -> invalidArgument("名称", "不能为 . 或 ..")
            name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES ->
                invalidArgument("名称", "过长（超过 $MAX_NAME_BYTES 字节）")
            else -> null
        }
    }
}
