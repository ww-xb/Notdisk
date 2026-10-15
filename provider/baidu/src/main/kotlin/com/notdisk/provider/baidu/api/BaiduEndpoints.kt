package com.notdisk.provider.baidu.api

/**
 * The hosts this integration talks to.
 *
 * Transcribed from `BaiduPan_Web_API_完整梳理.md` §1.1, which extracts `window.host` from the
 * vendor's own bundle. The web client uses `baseURL: ""` (§_share_fileops §0.1), i.e. every
 * business call is same-origin relative to `pan.baidu.com`; only the auxiliary hosts below are
 * absolute.
 *
 * Only the hosts this build actually calls are declared. Adding an unused host would make the
 * dependency surface look larger than it is.
 */
internal object BaiduHosts {
    /** The single API gateway: every `/api/…`, `/share/…` and `/rest/2.0/…` call. */
    const val PAN = "https://pan.baidu.com"

    /**
     * The download landing host a `dlink` points at (`d.pcs.baidu.com/file/<md5>?…`).
     *
     * Declared because it appears inside resolved links and because tests need to recognise it; this
     * build never *constructs* a URL on it.
     */
    const val D_PCS = "https://d.pcs.baidu.com"
}

/**
 * Every Baidu Netdisk endpoint this integration calls.
 *
 * ### Provenance rule
 * Each entry carries the section of the reference material it came from, together with that
 * document's own confidence marker, so the "do not invent endpoints" rule stays reviewable:
 *
 *  - `✅` — **measured**: the endpoint was observed on the wire against a live account, or the
 *    vendor's bundle contains a complete URL literal *and* a call site.
 *  - `⚠️` — **inferred**: the vendor's bundle implies it (`actionType` passed straight into a query
 *    parameter), but no literal was found. The corresponding capability is never reported
 *    `SUPPORTED` on this basis alone.
 *  - `❓` — **unknown**: documented as unresolved. Declared nowhere, because a constant that is never
 *    safe to call is worse than an absent one.
 *
 * Endpoints whose request shape is not fully determined are deliberately **absent** rather than
 * guessed — see [BaiduVerification] and `docs/api-mapping.md`.
 */
internal object BaiduEndpoints {

    // ------------------------------------------------------------------ §2.4 session / tokens

    /**
     * §2.4 ✅ — the template-variable endpoint. This is the single source of `bdstoken`, and (with a
     * different `fields` list) of the download-signature material.
     *
     * `fields` is a **JSON array string**, e.g. `["bdstoken","token","uk","isdocuser","servertime"]`.
     */
    const val GET_TEMPLATE_VARIABLE = "${BaiduHosts.PAN}/api/gettemplatevariable"

    /**
     * §2.4 ✅ — login-state probe. Also the cheapest way to validate a pasted cookie before it is
     * persisted: it is cheap, read-only, and answers `errno: 0` only for a live session.
     */
    const val LOGIN_STATUS = "${BaiduHosts.PAN}/api/loginStatus"

    /** §9.1 ✅ — the vendor profile. Carries `login_info.bdstoken` as a secondary token source. */
    const val USER_GETINFO = "${BaiduHosts.PAN}/api/user/getinfo"

    /** §9.2 ✅ — storage quota. */
    const val QUOTA = "${BaiduHosts.PAN}/api/quota"

    /** §2.5 ✅ — logout. */
    const val LOGOUT = "${BaiduHosts.PAN}/api/logout"

    // ------------------------------------------------------------------ §3.2 file listing

    /** §3.2 ✅ — the core directory listing. `dir` is an **absolute path**, never an id. */
    const val LIST = "${BaiduHosts.PAN}/api/list"

    /** §3.3 ✅ — the flat category listing. Returns `info[]`, not `list[]`. */
    const val CATEGORY_LIST = "${BaiduHosts.PAN}/api/categorylist"

    /** §3.4 ✅ — recursive search. Sends `key` + `recursion`, and **deletes** `dir`. */
    const val SEARCH = "${BaiduHosts.PAN}/api/search"

    /**
     * §3.5 ✅ — batch metadata. Accepts `target` as a JSON array of absolute paths; with `dlink=1`
     * it also returns the signed download link for each entry.
     */
    const val FILEMETAS = "${BaiduHosts.PAN}/api/filemetas"

    // ------------------------------------------------------------------ §3.6 file operations

    /**
     * §3.6 ✅ — the **single** mutating entry point. Every rename/move/copy/delete goes through it
     * with an `opera` query parameter and a `filelist` JSON-string body.
     *
     * `opera` has no centralized enum in the vendor's bundle: `rename` and `import` exist as URL
     * literals, while `move`/`copy`/`delete`/`unzip` are passed through from `actionType` values and
     * are therefore marked ⚠️ in the reference material.
     */
    const val FILEMANAGER = "${BaiduHosts.PAN}/api/filemanager"

    /** §3.7 ✅ — create a folder (`isdir=1`) or finalise an upload (`isdir=0`). */
    const val CREATE = "${BaiduHosts.PAN}/api/create"

    /** §3.8 ✅ — poll an asynchronous file operation started by [FILEMANAGER]. */
    const val TASKQUERY = "${BaiduHosts.PAN}/api/taskquery"

    // ------------------------------------------------------------------ §3.10 recycle bin

    /**
     * §3.10 ✅ — the recycle bin listing. **The trailing slash is required**: the vendor's bundle
     * builds it as `"".concat("/api/recycle/list", "/")`.
     */
    const val RECYCLE_LIST = "${BaiduHosts.PAN}/api/recycle/list/"

    /** §3.10 ✅ — restore from the recycle bin; body `fidlist` (a JSON array string). */
    const val RECYCLE_RESTORE = "${BaiduHosts.PAN}/api/recycle/restore"

    /** §3.10 ✅ — permanently delete from the recycle bin. */
    const val RECYCLE_DELETE = "${BaiduHosts.PAN}/api/recycle/delete"

    /** §3.10 ✅ — empty the recycle bin. `async=1` is hard-coded in the URL by the vendor. */
    const val RECYCLE_CLEAR = "${BaiduHosts.PAN}/api/recycle/clear"

    // ------------------------------------------------------------------ §5.3 download

    /**
     * §5.3 ✅ — resolve a short-lived `dlink` for one's own files.
     *
     * Requires `sign` + `timestamp` computed from the server-delivered `sign2` **JavaScript source**
     * (§5.2). This build cannot execute that source, so the endpoint is declared for reviewability
     * but is never called — see [BaiduVerification.REQUIRES_JS_ENGINE].
     */
    const val DOWNLOAD = "${BaiduHosts.PAN}/api/download"

    // ------------------------------------------------------------------ §7.3 media

    /**
     * §7.3 ✅ — the real playback authorisation endpoint. **Two-phase**: the first call returns
     * `errno 133` with an `adToken`, the second (with `isplayer=1&check_blue=1&adToken=…`) returns
     * the HLS master playlist as `application/x-mpegURL`.
     *
     * Note this is *not* `/api/transcoding/authorize`, which belongs to Office/image **format
     * conversion** and never to playback (the reference material corrects this explicitly).
     */
    const val STREAMING = "${BaiduHosts.PAN}/api/streaming"

    /** §7.5 ✅ — AI-subtitle status/apply. */
    const val AUTOSUBTITLE = "${BaiduHosts.PAN}/richsearch/autosubtitle/common"

    // ------------------------------------------------------------------ §6 share (declared, unused)

    /**
     * §6.1 ✅ — create a share.
     *
     * Declared for completeness and **never called**: the vendor requires a bound phone number
     * (`errno 2`) and the successful response shape was never measured. Publishing a file to a URL
     * on an unmeasured protocol is the single most dangerous action available, so the capability is
     * reported `UNVERIFIED`.
     */
    const val SHARE_PSET = "${BaiduHosts.PAN}/share/pset"

    /** §6.2 ✅ — the share list. */
    const val SHARE_RECORD = "${BaiduHosts.PAN}/share/record"
}

/**
 * How much trust an endpoint has earned, mirroring the reference material's own markers.
 *
 * Making this explicit is what lets the capability declaration in `BaiduCloudProvider` be derived
 * from a single source of truth instead of from a developer's memory: only [MEASURED] endpoints may
 * back a `SUPPORTED` capability.
 */
internal enum class BaiduVerification {
    /** Observed on the wire, or backed by a bundle URL literal plus a call site (`✅`). */
    MEASURED,

    /** Implied by the vendor's bundle but never seen literally (`⚠️`). */
    INFERRED,

    /**
     * The protocol is documented, but driving it requires executing JavaScript delivered by the
     * server at runtime (the `sign2` download signature, §5.2). A Kotlin client without an embedded
     * JS engine cannot produce a correct request, so this build does not try.
     */
    REQUIRES_JS_ENGINE,

    /** Documented as unresolvable (`❓`): parameters or response shape unknown. */
    UNKNOWN,
    ;

    /** Only measured endpoints may back a `SUPPORTED` capability. */
    val permitsSupportedCapability: Boolean get() = this == MEASURED
}
