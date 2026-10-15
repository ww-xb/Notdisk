package com.notdisk.provider.p115.api

/**
 * Every 115 endpoint this integration calls.
 *
 * ### Provenance rule
 * Each entry carries the section of `API_REFERENCE.md` it came from, and the manual's own
 * confidence marker (`✅` measured / `📖` source-only). This is not decoration: §"执行要求" of the
 * brief forbids inventing endpoints, and several plausible-looking ones in circulation are wrong —
 * most notably `/files/edit` for renaming, which returns `errno 1001` (§5.2). Keeping the citation
 * next to the constant is what makes that reviewable.
 *
 * Endpoints marked `SOURCE_ONLY` are declared here but **must not** be called by production code
 * until they have been verified against a live account; the corresponding capability is reported as
 * `UNVERIFIED`. See `docs/api-mapping.md` for the full table.
 */
internal object P115Endpoints {

    // ------------------------------------------------------------------ §2 authentication

    /** §2.1 Step 1 ✅ — issue a QR-login token. */
    const val QR_TOKEN = "${P115Hosts.QRCODE_API}/api/1.0/web/1.0/token"

    /** §2.1 Step 1 ✅ — server-rendered QR PNG fallback (`?qrfrom=1&uid=…`). */
    const val QR_IMAGE = "${P115Hosts.QRCODE_API}/api/1.0/web/1.0/qrcode"

    /** §2.1 Step 2 ✅ — poll QR status (`?uid=&time=&sign=`). */
    const val QR_STATUS = "${P115Hosts.QRCODE_API}/get/status/"

    /**
     * §2.1 Step 3 ✅ — exchange the scanned QR token for a session.
     *
     * Note the shape: the four credential cookies come back in the **response body**
     * (`data.cookie`), not in `Set-Cookie`. See [P115Credential].
     */
    const val QR_LOGIN = "${P115Hosts.PASSPORT_API}/app/1.0/web/1.0/login/qrcode"

    /** §2.1 Step 5 ✅ — validate the current session. */
    const val SESSION_NAV = "${P115Hosts.MY}/?ct=ajax&ac=nav"

    /** §2.3 ✅ — whether the account has a safe key set (`{"is_set":true,"is_new":true}`). */
    const val SECURITY_KEY_STATUS = "${P115Hosts.PASSPORT_API}/app/1.0/web/8.2/user/security_key_status"

    /** §2.5 — logout (`?goto=<return url>`). */
    const val LOGOUT = "${P115Hosts.PASSPORT_API}/app/1.0/web/1.0/logout/logout/"

    /** §2.2 Step 1 ✅ — RSA public key for password login. SOURCE_ONLY for the flow as a whole. */
    const val LOGIN_GET_KEY = "${P115Hosts.PASSPORT_API}/app/1.0/web/5.0.1/login/getKey"

    /** §2.2 Step 3 ✅ — password login. SOURCE_ONLY as an interactive flow in this build. */
    const val LOGIN_PASSWORD = "${P115Hosts.PASSPORT_API}/app/1.0/web/1.0/login/login"

    /** §2.3 — send the 2FA SMS. SOURCE_ONLY. */
    const val TWO_FACTOR_SMS = "${P115Hosts.PASSPORT_API}/app/1.0/web/1.0/code/sms/login"

    /** §2.3 — verify the 2FA code. SOURCE_ONLY. */
    const val TWO_FACTOR_VERIFY = "${P115Hosts.PASSPORT_API}/app/1.0/web/1.0/login/vip"

    // ------------------------------------------------------------------ §3 file listing

    /** §3.1 ✅ — the core listing endpoint. `cid=0` is the root. */
    const val FILES = "${P115Hosts.WEB_API}/files"

    /** §3.2 ✅ — folder/file detail including the full `paths` chain. */
    const val CATEGORY_GET = "${P115Hosts.WEB_API}/category/get"

    /** §3.3 ✅ — complete per-file attributes (returns an array). */
    const val FILES_GET_INFO = "${P115Hosts.WEB_API}/files/get_info"

    /** §3.4 ✅ — search. */
    const val FILES_SEARCH = "${P115Hosts.WEB_API}/files/search"

    /** §3.5 ✅ — resolve a human path to an id. */
    const val FILES_GETID = "${P115Hosts.WEB_API}/files/getid"

    /** §3.7 — the category tree. SOURCE_ONLY. */
    const val LISTS = "${P115Hosts.WEB_API}/lists"

    /** §3.8 ✅ — recent/history, the only documented source of playback position. */
    const val HISTORY_LIST = "${P115Hosts.WEB_API}/history/list"

    // ------------------------------------------------------------------ §5 file operations

    /** §5.1 ✅ — create a folder (`pid`, `cname`). */
    const val FILES_ADD = "${P115Hosts.WEB_API}/files/add"

    /**
     * §5.2 ✅ — rename.
     *
     * This is the *only* working rename endpoint. The widely-circulated `/files/edit` with
     * `cid`/`file_id`/`n` returns `errno 1001`; the manual measured it explicitly.
     */
    const val FILES_BATCH_RENAME = "${P115Hosts.WEB_API}/files/batch_rename"

    /** §5.3 ✅ — move. Requires `fid[0]=…&fid[1]=…`, **not** `file_ids=`. */
    const val FILES_MOVE = "${P115Hosts.WEB_API}/files/move"

    /** §5.4 ✅ — copy. Same indexed-`fid` requirement as move. */
    const val FILES_COPY = "${P115Hosts.WEB_API}/files/copy"

    /** §5.3 ✅ — progress of an asynchronous move. */
    const val FILES_MOVE_PROGRESS = "${P115Hosts.WEB_API}/files/move_progress"

    /** §5.5 — set the description. POST only (GET returns 405). SOURCE_ONLY. */
    const val FILES_DESC = "${P115Hosts.WEB_API}/files/desc"

    /** §5.6 ✅ — star/unstar. POST only. */
    const val FILES_STAR = "${P115Hosts.WEB_API}/files/star"

    /** §5.7 ✅ — pin/unpin (`file_id`, comma-separated for batches). */
    const val FILES_TOP = "${P115Hosts.WEB_API}/files/top"

    /** §5.8 — per-folder sort preference. SOURCE_ONLY. */
    const val FILES_ORDER = "${P115Hosts.WEB_API}/files/order"

    // ------------------------------------------------------------------ §7 download

    /** §7.1 ✅ — resolve a short-lived direct download link. */
    const val FILES_DOWNLOAD = "${P115Hosts.WEB_API}/files/download"

    // ------------------------------------------------------------------ §8 trash

    /** §8.1 ✅ — list the recycle bin. */
    const val RB = "${P115Hosts.WEB_API}/rb"

    /** §8.2 ✅ — delete to the recycle bin. Requires `fid[i]`. */
    const val RB_DELETE = "${P115Hosts.WEB_API}/rb/delete"

    /** §8.3 ✅ — restore. Requires `rid[i]` (not `rid[]`, which yields `errno 231012`). */
    const val RB_REVERT = "${P115Hosts.WEB_API}/rb/revert"

    /** §8.4 ✅ — permanent delete. Requires `rid[i]` **and** the account's safe key. */
    const val RB_CLEAN = "${P115Hosts.WEB_API}/rb/clean"

    // ------------------------------------------------------------------ §9 space / categories

    /** §9.3 ✅ — storage statistics (note the vendor's `pecent`/`type_summury` misspellings). */
    const val INDEX_INFO = "${P115Hosts.WEB_API}/files/index_info"

    /** §13.2 ✅ — VIP limit lookup. */
    const val VIP_LIMIT = "${P115Hosts.WEB_API}/user/vip_limit"

    /** §13.1 ✅ — user preferences. */
    const val USER_SETTING = "${P115Hosts.WEB_API}/user/setting"

    // ------------------------------------------------------------------ §10 media

    /** §10.1 ✅ — video metadata + the logical `video_url`. */
    const val FILES_VIDEO = "${P115Hosts.WEB_API}/files/video"

    /** §10.3 — playback position for a folder playlist. SOURCE_ONLY. */
    const val APS_FOLDER_PLAYLONG = "${P115Hosts.APS}/getFolderPlaylong"

    /** §10.4 — music listing endpoints. SOURCE_ONLY. */
    const val FILES_MUSIC_LIST = "${P115Hosts.WEB_API}/files/music_list"

    // ------------------------------------------------------------------ §11 preview

    /** §11.1 ✅ — the unified preview entry point (POST, `pickcode`). */
    const val FILES_PREVIEW = "${P115Hosts.WEB_API}/files/preview"

    /** §11.2 ✅ — image thumbnail / original / signed source URL. */
    const val FILES_IMAGE = "${P115Hosts.WEB_API}/files/image"

    // ------------------------------------------------------------------ §12 share

    /** §12.1 ✅ — list my shares. */
    const val USERSHARE_LIST = "${P115Hosts.WEB_API}/usershare/list"

    /** §12.2 — create/enable a share. SOURCE_ONLY: never verified. */
    const val USERSHARE_SHARE = "${P115Hosts.WEB_API}/usershare/share"

    /** §12.3 — share detail for a file. SOURCE_ONLY. */
    const val USERSHARE_SHAREINFO = "${P115Hosts.WEB_API}/usershare/shareinfo"
}

/**
 * Marks how much trust an endpoint or capability has earned.
 *
 * This mirrors the manual's own `✅`/`📖` convention so that the capability declarations in the
 * provider can be derived from a single source of truth rather than from a developer's memory.
 */
internal enum class P115Verification {
    /** Measured in a real session by the reference manual (`✅`). */
    MEASURED,

    /** Present in the vendor's front-end source but never exercised (`📖`). */
    SOURCE_ONLY,

    /** Requires developer credentials this build does not have (OAuth). */
    REQUIRES_APP_CREDENTIALS,
    ;

    /** Only measured endpoints may back a `SUPPORTED` capability. */
    val permitsSupportedCapability: Boolean get() = this == MEASURED
}
