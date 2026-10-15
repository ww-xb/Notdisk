package com.notdisk.provider.baidu

import com.notdisk.core.model.FileId

/**
 * The concrete encoding behind Baidu Netdisk's opaque [FileId]s.
 *
 * ### The problem this solves, and why the encoding looks unlike the 115 one
 * Baidu's `/api/filemetas`, `/api/streaming`, `/api/download` and `/share/…` surfaces are all
 * **path-addressed**: the reference material shows every one of them keyed on `target`/`path` — a
 * URL-encoded *absolute path* such as `/学习资料/web协议详解/59丨HPACK.mp4` — never on `fs_id`
 * (§3.5's `target=["/path/a.txt"]`, §3.6's `filelist=[{"path":…,"dest":…,"newname":…}]`, §7.3's
 * `path=`). `fs_id` exists and is stable, but the endpoints this client drives do not accept it:
 * the only documented `fs_id` consumers are `/api/recycle/…`'s `fidlist` (§3.10), the statistics
 * `/aipic/image/filemetas?fids=` (§8.3) and the QR-code endpoint (§8.8).
 *
 * So the unified [FileId] has to carry the path, because the path is the identity the remote side
 * actually resolves.
 *
 * ### Encoding
 * ```
 * folder:dir:baidu:/我的文件
 * file:file:baidu:/我的文件/a.txt
 * ```
 *
 * The path is stored **verbatim** after a fixed prefix rather than in `key=value` segments, because
 * there is exactly one field and because a path may itself contain `=`, `:` or `&` (Baidu allows
 * almost any character in a file name). A `key=value` scheme would have to invent escaping for
 * characters the vendor does not escape; a fixed prefix plus "everything after it is the path"
 * needs none.
 *
 * ### The stability rule this satisfies
 * The same file must yield a byte-identical [FileId] whether it was discovered through
 * `/api/list`, `/api/categorylist`, `/api/search` or `/api/filemetas`. All four report the same
 * absolute `path` field for the same file, so deriving the id from `path` alone — and from nothing
 * else — makes that true by construction. The shared contract suite asserts it
 * (`getFile` must agree with the value `listChildren` returned).
 *
 * **Nothing derived is included.** `md5`, `category`, `size` and the signed `thumbs`/`docpreview`
 * URLs all appear in some responses and not others, and the URLs are short-lived secrets. Putting
 * any of them in the id would give one file two identities — the exact bug the 115 integration hit
 * with its `pickcode`.
 *
 * ### What is deliberately absent
 *  - **No URL and no token.** A file id must never be usable as access material. `docpreview`,
 *    `thumbs.*` and `dlink` are all signed bearer resources and live only in resolved models.
 *  - **No account id.** The same path string is valid under any account; scoping comes from
 *    [com.notdisk.core.model.CloudFileKey] and from the `AccountId` passed to every provider call.
 *  - **No expiry.** A path does not expire; a signed URL does.
 */
internal object BaiduFileIdCodec {

    /** Prefix for a folder. Kept as a constant so [decode] and [matches] cannot drift apart. */
    private const val PREFIX_FOLDER = "folder:dir:baidu:"

    /** Prefix for a file. */
    private const val PREFIX_FILE = "file:file:baidu:"

    /** Baidu's root. `/api/list` treats `/` and an omitted `dir` identically (§3.2). */
    const val ROOT_PATH = "/"

    /** Wraps an absolute folder path as a domain [FileId]. */
    fun forFolder(path: String): FileId = FileId(PREFIX_FOLDER + normalise(path))

    /** Wraps an absolute file path as a domain [FileId]. */
    fun forFile(path: String): FileId = FileId(PREFIX_FILE + normalise(path))

    /** Wraps a path, choosing the prefix from [isFolder]. */
    fun forPath(path: String, isFolder: Boolean): FileId =
        if (isFolder) forFolder(path) else forFile(path)

    /**
     * Parses a [FileId] back into its path and kind.
     *
     * Returns `null` for anything this codec did not produce, and callers turn that into
     * `CloudError.InvalidArgument` — never a crash and never a silent substitution. That matters
     * because a [FileId] is persisted in the metadata cache, so an id written by an older build (or
     * a future one) must degrade into a clean error rather than a wrong request against a real
     * account.
     */
    fun decode(fileId: FileId): BaiduRef? {
        val raw = fileId.value
        return when {
            raw.startsWith(PREFIX_FOLDER) ->
                BaiduRef(path = raw.removePrefix(PREFIX_FOLDER), isFolder = true)

            raw.startsWith(PREFIX_FILE) ->
                BaiduRef(path = raw.removePrefix(PREFIX_FILE), isFolder = false)

            else -> null
        }
    }

    /** True when [fileId] was produced by this codec. */
    fun matches(fileId: FileId): Boolean =
        fileId.value.startsWith(PREFIX_FOLDER) || fileId.value.startsWith(PREFIX_FILE)

    /**
     * Normalizes a path to the canonical absolute form the vendor uses.
     *
     * The vendor's own client normalises a root *destination* to the empty string (`"/"===e && (e="")`,
     * §_share_fileops B.1.3) but its `dir` parameter is always sent as an absolute path. This client
     * keeps **one** canonical form — always leading `/`, never trailing, except for the root itself —
     * so a path discovered from any endpoint hashes to the same id.
     */
    fun normalise(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == ROOT_PATH) return ROOT_PATH
        val withLeading = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        // Collapse a trailing slash so `/a/b` and `/a/b/` are one identity, but never collapse the
        // root to an empty string.
        return withLeading.trimEnd('/').ifEmpty { ROOT_PATH }
    }
}

/**
 * A decoded Baidu reference: the absolute path plus whether it denotes a folder.
 *
 * The kind is part of the id rather than inferred at use time because the two are addressed
 * differently: a folder needs `dir`, a file needs `target`/`path` on a file-scoped endpoint, and
 * several endpoints will happily accept the wrong one and return an empty result rather than an
 * error.
 */
internal data class BaiduRef(
    val path: String,
    val isFolder: Boolean,
) {
    /** Re-encodes this reference, used when a mapper needs to return the id it was given. */
    fun encode(): FileId = BaiduFileIdCodec.forPath(path, isFolder)

    /** The parent path, or `null` at the root. Used when a caller must re-resolve a parent. */
    val parentPath: String?
        get() {
            if (path == BaiduFileIdCodec.ROOT_PATH) return null
            val index = path.lastIndexOf('/')
            return when {
                index <= 0 -> BaiduFileIdCodec.ROOT_PATH
                else -> path.substring(0, index)
            }
        }

    /** The final path segment — the display name. */
    val name: String
        get() = path.substringAfterLast('/').ifEmpty { path }
}
