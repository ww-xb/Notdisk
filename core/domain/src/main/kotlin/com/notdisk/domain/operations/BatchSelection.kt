package com.notdisk.domain.operations

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.FileId
import com.notdisk.domain.internal.invalidArgument

/**
 * Validation shared by every bulk use case: move, copy, delete, star, trash restore, purge, share.
 *
 * ### Why this is a value type rather than a `Boolean`-returning helper
 * The batch use cases have to do two things to a selection, in this order:
 *
 *  1. **reject** a selection that cannot mean anything (empty), producing a typed refusal; and
 *  2. **normalise** a selection that does mean something but is malformed (duplicate ids),
 *     producing the list that is actually forwarded.
 *
 * A helper that returned `Boolean` would cover step 1 and force every caller to re-derive step 2,
 * which is how six batch use cases end up with six slightly different dedupe rules — and dedupe is
 * where the subtle bugs live. Returning `Boolean` is also *silently ignorable*: a caller that
 * forgets to check it compiles fine and sends an empty batch. Modelling the outcome as a type with
 * an explicit `refusal ?: forward` split makes both halves impossible to skip, because the only way
 * to obtain the list to forward is through [BatchSelection.validatedOrNull].
 *
 * ### Why duplicates are normalised rather than rejected
 * A repeated [FileId] in a selection is a *UI* artefact, not a user error: multi-select across a
 * page boundary, a selection restored from `SavedState` after a config change, or "select all"
 * applied twice can all produce one. The user's intent is unambiguous, so refusing it would make
 * the app fail at a normal interaction. What is *not* harmless is forwarding it:
 *
 *  - for **copy**, a repeated id commonly creates a duplicate remote file — actual data damage from
 *    something the user cannot see;
 *  - for **delete/purge**, a repeated id produces a spurious per-item failure, so a fully successful
 *    batch renders as "部分失败", which trains users to ignore the partial-failure indicator — the
 *    one signal that must stay trustworthy, because it is how real data loss is surfaced.
 *
 * ### Why order is preserved instead of using a `Set`
 * [com.notdisk.core.provider.BatchOperationResult] reports per-item outcomes, and the UI pairs
 * each outcome back to the row the user selected. Reordering the selection would make a caller that
 * zips results against its own list attribute one file's failure to another file's row — a silent
 * mis-attribution in the exact place the user is being told what went wrong. Order preservation is
 * therefore part of this type's contract, not a nicety, and it is asserted by tests.
 */
internal class BatchSelection private constructor(
    /** The order-preserving, duplicate-free selection to forward to the provider. */
    val fileIds: List<FileId>,
    /** Non-null when the selection cannot be acted on at all. */
    val refusal: CloudError.InvalidArgument?,
    /** `true` when duplicates were removed; callers may use it for diagnostics, never for control. */
    val normalised: Boolean,
) {

    /**
     * The validated selection to forward, or `null` when [refusal] applies.
     *
     * The `refusalOrNull()?.let { return it }` idiom at each call site is intentional: it keeps the
     * refusal path adjacent to the validation and makes the compiler enforce that the two are
     * considered together, which a `Boolean`-returning helper would not.
     */
    fun validatedOrNull(): List<FileId>? = fileIds.takeIf { refusal == null }

    companion object {
        /**
         * Validates and normalises a batch selection.
         *
         * @param fileIds the caller's selection, in the order results should be reported.
         * @param fieldName the user-facing name of the offending field, so the message lands on the
         *   right control. Defaults to "选择" (the whole selection).
         */
        fun of(fileIds: List<FileId>, fieldName: String = "选择"): BatchSelection {
            if (fileIds.isEmpty()) {
                val error = invalidArgument(fieldName, "不能为空")
                // `invalidArgument` returns CloudResult.Failure; unwrap so callers hold one shape.
                val refusal = (error as CloudResult.Failure).error as CloudError.InvalidArgument
                return BatchSelection(fileIds = emptyList(), refusal = refusal, normalised = false)
            }

            // LinkedHashSet: de-duplicates while preserving first-seen order, in one pass.
            val distinct = LinkedHashSet(fileIds).toList()
            return BatchSelection(
                fileIds = distinct,
                refusal = null,
                normalised = distinct.size != fileIds.size,
            )
        }
    }
}

/**
 * Convenience wrapper so a batch use case reads as a two-line guard.
 *
 * ```kotlin
 * val selection = BatchSelection.of(fileIds)
 * selection.refusalOrNull()?.let { return it }
 * ```
 */
internal fun BatchSelection.refusalOrNull(): CloudResult<Nothing>? =
    refusal?.let { CloudResult.Failure(it) }

/**
 * The same validation for trash selections, which are addressed by *trash* id, not [FileId].
 *
 * ### Why this is a separate function rather than a generic one
 * [com.notdisk.core.provider.TrashItem.trashId] is a `String` and a [FileId] is a value class
 * wrapping one, so a generic helper would accept both — and silently let a caller pass a *file* id
 * where a *trash* id belongs. On 115 those are different identifiers (`rid` verus `fid`/`pickcode`),
 * and the vendor's response to the mix-up is not a clean refusal: it is either a no-op that reports
 * success, so the UI shows "已恢复" while nothing moved, or the restoration of the wrong entry.
 *
 * Keeping two distinctly-typed helpers means the compiler rejects the mistake at the call site,
 * which is the only place it can be caught cheaply. The duplication is four lines and buys a class
 * of bug that cannot be tested for, because it cannot be written.
 */
internal fun validateTrashSelection(trashIds: List<String>): CloudResult<Nothing>? = when {
    trashIds.isEmpty() -> invalidArgument("回收站条目", "不能为空")
    trashIds.any { it.isBlank() } -> invalidArgument("回收站条目", "包含空标识")
    else -> null
}

/**
 * Order-preserving de-duplication for trash selections.
 *
 * The same reasoning as [BatchSelection]: a repeated trash id makes a wholly successful batch report
 * a spurious per-item failure, and for [PurgeFromTrashUseCase] it means sending a destructive
 * request for an entry that is already gone.
 */
internal fun dedupeTrashSelection(trashIds: List<String>): List<String> =
    LinkedHashSet(trashIds).toList()
