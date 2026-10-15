package com.notdisk.provider.p115.auth

/**
 * Parses a pasted 115 session cookie into a [P115Credential].
 *
 * ### Why this is a separate, pure function
 * Cookie parsing is where a "just split on `;`" implementation quietly goes wrong, and every failure
 * mode is a *user-visible* one:
 *
 *  - **Browsers copy more than the cookie.** Devtools' "Copy as cURL" and most clipboard sources
 *    include `Cookie:` as a prefix, and some copy the value with surrounding quotes. A parser that
 *    does not strip those produces a credential whose first key is `Cookie: UID`, so the session
 *    fails with no explanation.
 *  - **Pasted cookies arrive on several lines.** The devtools *Application → Cookies* view is
 *    usually copied row by row.
 *  - **`Set-Cookie` attributes are not credentials.** A user pasting a response header brings
 *    `Path`, `Domain`, `Expires`, `HttpOnly`, `Secure`, `SameSite` along. Treating those as cookies
 *    is harmless only by accident; naming them here makes the omission deliberate.
 *  - **The key casing is not fixed.** The vendor's own front-end writes `UID`, while `Set-Cookie`
 *    uses whatever the server chose. Comparison is therefore case-insensitive, and `USERSESSIONID`
 *    is matched under its several observed spellings.
 *
 * Being pure also makes every one of those a unit test rather than a hope — see
 * `P115CookieParserTest`.
 *
 * ### What must be present
 * §2.1 Step 4 records that `webapi.115.com` authenticates with `UID`/`CID`/`SEID`/`KID`;
 * `USERSESSIONID` is the web front-end's own HttpOnly ticket and is observed to be absent on some
 * responses. The four core cookies are therefore required and the rest are optional — which is
 * exactly the split [P115Credential.isComplete] encodes.
 */
internal object P115CookieParser {

    /** `Set-Cookie` attributes, never credentials. Matched case-insensitively. */
    private val ATTRIBUTE_NAMES = setOf(
        "path",
        "domain",
        "expires",
        "max-age",
        "httponly",
        "secure",
        "samesite",
        "priority",
        "comment",
        "version",
    )

    /**
     * Parses [raw], returning `null` when it does not contain the four required cookies.
     *
     * The returned credential is structurally complete but **not yet verified**: only the vendor can
     * say whether a session is alive, which is why `P115AuthProvider` checks it against
     * `my.115.com/?ct=ajax&ac=nav` before persisting anything.
     */
    fun parse(raw: String): P115Credential? {
        val pairs = extractPairs(raw)
        if (pairs.isEmpty()) return null

        val userSessionId = pairs.firstValueOf(
            "usersessionid",
            "user_session_id",
            "user-session-id",
        )
        val uid = pairs.firstValueOf("uid")
        val cid = pairs.firstValueOf("cid")
        val seid = pairs.firstValueOf("seid")
        val kid = pairs.firstValueOf("kid")
        val phpSessionId = pairs.firstValueOf("phpsessid")

        if (uid.isNullOrBlank() || cid.isNullOrBlank() ||
            seid.isNullOrBlank() || kid.isNullOrBlank()
        ) {
            return null
        }

        return P115Credential(
            // Not every 115 session response carries this; `P115Credential.cookieHeader` omits a
            // blank member rather than sending `USERSESSIONID=` with no value, which the vendor
            // would read as a malformed session.
            userSessionId = userSessionId.orEmpty(),
            uid = uid,
            cid = cid,
            seid = seid,
            kid = kid,
            // Filled in by the caller once the vendor's `nav` response names the account; a cookie
            // alone does not carry a display id.
            userId = uid.substringBefore('_'),
            phpSessionId = phpSessionId,
            obtainedAt = 0L,
        )
    }

    /**
     * Pulls `name=value` pairs out of whatever shape the user pasted.
     *
     * The order of the normalisations matters and is the whole point of this function:
     *  1. drop surrounding quotes, which some clipboard paths add (done **first**, so a quoted
     *     `"UID=…; …"` is one value rather than a first key of `"UID`);
     *  2. strip a leading `Cookie:` / `Set-Cookie:` header name (and any `-H 'Cookie: …'` framing);
     *  3. split on `;`, `\n` and `\r`, so both the header form and the row-per-cookie form work;
     *  4. ignore `Set-Cookie` attributes.
     *
     * A `value` may itself contain `=` (some vendor cookies are base64), so each pair is split on the
     * **first** `=` only.
     */
    private fun extractPairs(raw: String): List<Pair<String, String>> {
        val headerStripped = raw
            .trim()
            // Quotes first: a value quoted as a whole would otherwise leave `"UID` as the first key.
            .removeSurrounding("\"")
            .removeSurrounding("'")
            // `Cookie: UID=…` and `-H 'Cookie: UID=…'` both reduce to their payload.
            .replace(Regex("""(?i)\bcookie\s*:\s*"""), " ")
            .replace(Regex("""^\s*-\w+\s*'?"""), " ")
            .trim()

        return headerStripped
            .split(';', '\n', '\r')
            .mapNotNull { segment ->
                val trimmed = segment.trim().removeSurrounding("\"").trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@mapNotNull null

                val name = trimmed.substring(0, separator).trim()
                val value = trimmed.substring(separator + 1).trim().removeSurrounding("\"")
                if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
                if (name.lowercase() in ATTRIBUTE_NAMES) return@mapNotNull null

                name.lowercase() to value
            }
    }

    /**
     * The value of the first key in [names] that was present.
     *
     * `null` (rather than an empty string) distinguishes "the user did not paste it" from "the user
     * pasted it with an empty value", which is the difference between an optional cookie and a
     * malformed paste.
     */
    private fun List<Pair<String, String>>.firstValueOf(vararg names: String): String? {
        names.forEach { name ->
            firstOrNull { it.first == name }?.second?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
