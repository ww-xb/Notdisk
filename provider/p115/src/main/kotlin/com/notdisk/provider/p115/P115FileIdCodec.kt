package com.notdisk.provider.p115

import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import java.util.concurrent.ConcurrentHashMap

/**
 * The concrete encoding behind 115's opaque [FileId]s.
 *
 * ### The problem this solves
 * `API_REFERENCE.md` §1.5 and §15.2 item 7 explain that 115 addresses things with several
 * near-identical identifiers that are easy to confuse:
 *
 * | Field | Meaning                                                       |
 * |-------|---------------------------------------------------------------|
 * | `cid` | a **folder** id (also used as the *parent* folder id)         |
 * | `fid` | a **file** id                                                 |
 * | `pc`  | a **pickcode** — the preferred key for single-file operations |
 * | `rid` | a **recycle-bin** entry id, which is *not* the original id    |
 *
 * The unified domain model has exactly one [FileId], so it must answer two questions without
 * guessing: is this a file or a folder (which decides `fid` vs `cid`), and what are the vendor's own
 * ids (needed verbatim by the batch endpoints)?
 *
 * ### Encoding — and why the pickcode is deliberately NOT part of it
 * ```
 * folder:115:cid=<cid>
 * file:115:fid=<fid>:cid=<parentCid>
 * ```
 *
 * An earlier version of this codec embedded `pc=<pickcode>` in the id. That was a **real bug**, and
 * the shared provider contract test caught it: 115 reports the pickcode as `pc` in listings and as
 * `pick_code` in `category/get`, and the two can differ (a listing may omit it entirely). Since
 * [FileId] is a value class used as a cache key, a pickcode-dependent encoding meant **the same file
 * had two identities** — two cache rows, two "recent" entries, and a media refresh that could not
 * find the file it had just played.
 *
 * Identity must be stable; the pickcode is *derived metadata*. It is therefore cached separately in
 * [P115PickcodeCache], keyed by the stable id, and resolved on demand when a single-file endpoint
 * needs it.
 *
 * ### What is deliberately absent
 *  - **No URL and no token.** A file id must never be usable as access material (§16.3 of the brief);
 *    a pickcode is an *identifier*, not a credential — it cannot fetch bytes on its own.
 *  - **No account id.** The same id string is valid under any account; scoping comes from
 *    `CloudFileKey` and from the `AccountId` passed to every provider call.
 *  - **No expiry.** A file id does not expire; a `file_url`/`thumb` URL does. Keeping them in
 *    separate types is what makes it impossible to persist a signed URL as if it were a file id.
 */
internal data class P115Ref(
    /** Vendor folder id, or the *parent* folder id for a file. `0` is the root. */
    val cid: String?,
    /** Vendor file id. `null` for folders, which have no `fid`. */
    val fid: String?,
) {
    val isFolder: Boolean get() = fid == null

    /** The id a single-file endpoint needs, when this ref addresses a file. */
    val singleFileId: String? get() = fid

    /** The id a folder endpoint needs (either a folder's own cid, or a file's parent cid). */
    val folderId: String get() = cid ?: ROOT_CID

    fun encode(): String = if (isFolder) {
        "$PREFIX_FOLDER:cid=$folderId"
    } else {
        buildString {
            append(PREFIX_FILE)
            append(":fid=").append(fid)
            if (cid != null) append(":cid=").append(cid)
        }
    }
}

/** The synthetic root. 115 never returns it from `/files`; it is materialised by the provider. */
internal const val ROOT_CID = "0"

internal const val PREFIX_FOLDER = "folder:115"
internal const val PREFIX_FILE = "file:115"

/**
 * Encodes/decodes [FileId]s for the 115 provider.
 *
 * ### Failure behaviour
 * [decode] returns `null` for anything it cannot parse, and callers turn that into
 * `CloudError.InvalidArgument` — never a crash and never a silent substitution. This matters because
 * a `FileId` is persisted in the metadata cache, so an id written by an older build must degrade into
 * a clean error rather than a wrong request.
 */
internal object P115FileIdCodec {

    /** Wraps a folder reference as a domain [FileId]. */
    fun forFolder(cid: String): FileId = FileId(P115Ref(cid = cid, fid = null).encode())

    /** Wraps a file reference as a domain [FileId]. */
    fun forFile(fid: String, parentCid: String?): FileId =
        FileId(P115Ref(cid = parentCid, fid = fid).encode())

    /**
     * Parses a [FileId] back into its vendor components.
     *
     * Tolerant of a legacy `:pc=<pickcode>` segment left in an id by an earlier build: the segment is
     * accepted and ignored, so an account's cached rows keep working instead of becoming
     * un-resolvable after the upgrade.
     */
    fun decode(fileId: FileId): P115Ref? {
        val raw = fileId.value
        val prefix = when {
            raw.startsWith("$PREFIX_FOLDER:") -> PREFIX_FOLDER
            raw.startsWith("$PREFIX_FILE:") -> PREFIX_FILE
            else -> return null
        }

        var cid: String? = null
        var fid: String? = null

        raw.removePrefix("$prefix:").split(':').forEach { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            val value = part.substringAfter('=', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
            when (key) {
                "cid" -> cid = value
                "fid" -> fid = value
                // `pc` is intentionally ignored — see the class KDoc for why it is not identity.
            }
        }

        return when (prefix) {
            PREFIX_FOLDER -> P115Ref(cid = cid ?: ROOT_CID, fid = null)
            // A file id without an `fid` is not recoverable: reject instead of fabricating one.
            PREFIX_FILE -> fid?.let { P115Ref(cid = cid, fid = it) }
            else -> null
        }
    }

    /** True when [fileId] was produced by this codec. */
    fun matches(fileId: FileId): Boolean =
        fileId.value.startsWith("$PREFIX_FOLDER:") || fileId.value.startsWith("$PREFIX_FILE:")
}

/**
 * Remembers each file's pickcode, keyed by its **stable** id and scoped per account.
 *
 * ### Why this is a cache and not part of the id
 * 115 needs a pickcode for several single-file endpoints (`files/video`, `files/preview`,
 * `files/image`) but only reports it in *some* responses, and the value is not part of the file's
 * identity. Caching it here gives both properties at once:
 *
 *  - [FileId] stays stable across calls, so cache keys and "recent files" rows are correct;
 *  - playback and preview get the pickcode for free in the common case, because every listing and
 *    detail call populates the cache;
 *  - when the cache misses (a cold start, a deep link into a file never listed) the caller falls
 *    back to one `files/get_info` request instead of failing.
 *
 * ### Scope
 * Per-account, because two accounts bound to the same provider may address different files and must
 * never share resolved metadata. The cache is in-memory only: a pickcode is not a secret, but it is
 * also not stable metadata worth persisting, and a stale entry would be worse than a re-fetch.
 */
internal class P115PickcodeCache {

    private val entries = ConcurrentHashMap<String, String>()

    private fun key(accountId: AccountId, fileId: FileId): String =
        "${accountId.value}\u0000${fileId.value}"

    /** Records the pickcode discovered while listing or fetching a file. */
    fun put(accountId: AccountId, fileId: FileId, pickcode: String?) {
        if (pickcode.isNullOrBlank()) return
        entries[key(accountId, fileId)] = pickcode
    }

    fun get(accountId: AccountId, fileId: FileId): String? = entries[key(accountId, fileId)]

    fun invalidate(accountId: AccountId, fileId: FileId) {
        entries.remove(key(accountId, fileId))
    }

    /** Drops everything for one account, e.g. after a logout. */
    fun invalidateAccount(accountId: AccountId) {
        val marker = "${accountId.value}\u0000"
        entries.keys.filter { it.startsWith(marker) }.forEach(entries::remove)
    }

    val size: Int get() = entries.size
}
