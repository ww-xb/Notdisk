package com.notdisk.provider.baidu.auth

/**
 * Parses a pasted Baidu Netdisk session cookie into a [BaiduCredential].
 *
 * ### Why this is a separate, pure function
 * Cookie parsing is where a "just split on `;`" implementation quietly goes wrong, and every failure
 * mode is a *user-visible* one:
 *
 *  - **Browsers copy more than the cookie.** Devtools' "Copy as cURL" and several clipboard sources
 *    include a `Cookie:` prefix, and copy tools frequently wrap the value in quotes. A parser that
 *    does not strip those produces a credential whose first key is `Cookie: BDUSS`, so the session
 *    fails with no explanation of why.
 *  - **Pasted cookies arrive on several lines.** The devtools *Application → Cookies* view is
 *    normally copied row by row, one `name  value` pair per line — and in that view the separator is
 *    a **tab**, not an `=`.
 *  - **`Set-Cookie` attributes are not credentials.** A user pasting a response header brings `Path`,
 *    `Domain`, `Expires`, `HttpOnly`, `Secure`, `SameSite` along. Treating those as cookies is
 *    harmless only by accident; naming them here makes the omission deliberate.
 *  - **The key casing is not fixed.** Cookie names are case-sensitive per RFC 6265, but Baidu's own
 *    front end writes `BDUSS` while some tooling lower-cases. Comparison is therefore
 *    case-insensitive, and the canonical casing is restored on output.
 *
 * Being pure also makes every one of those a unit test rather than a hope — see `BaiduCookieParserTest`.
 *
 * ### What must be present
 * §1.5 is explicit that `BDUSS` is the "登录凭证主令牌" and that `STOKEN` is the pcs security token.
 * `BDUSS` is therefore **required**; everything else is preserved if present. The split matters
 * because requiring `STOKEN` would reject a cookie set the vendor's own `/api/…` surface accepts,
 * while accepting a paste with no `BDUSS` would create an account row that fails on first use.
 */
internal object BaiduCookieParser {

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
     * Parses [raw], returning `null` when it contains no usable `BDUSS`.
     *
     * The returned credential is structurally complete but **not yet verified**: only the server can
     * say whether a session is alive, which is why `BaiduAuthProvider` checks it against
     * `/api/loginStatus` before persisting anything.
     */
    fun parse(raw: String): BaiduCredential? {
        val pairs = extractPairs(raw)
        if (pairs.isEmpty()) return null

        val bduss = pairs.firstValueOf("bduss") ?: return null

        // Everything except the two named fields is carried through verbatim, de-duplicated by name
        // so a paste that mentions a cookie twice does not produce a doubled header.
        val extras = LinkedHashMap<String, String>()
        pairs.forEach { (name, value) ->
            if (name.equals("bduss", true) || name.equals("stoken", true)) return@forEach
            extras.putIfAbsent(name, value)
        }

        return BaiduCredential(
            bduss = bduss,
            stoken = pairs.firstValueOf("stoken"),
            // The canonical spelling is restored from the lower-cased parse key so the emitted header
            // reads `BAIDUID=…` rather than the lowercase form some clipboard paths produce.
            extraCookies = extras.map { (name, value) -> canonicalName(name) to value },
            // Not derivable from a cookie: `uk` comes from `/api/loginStatus`, and the caller fills it
            // in after the pre-persist verification.
            userId = null,
            obtainedAt = 0L,
        )
    }

    /**
     * Pulls `name=value` pairs out of whatever shape the user pasted.
     *
     * The order of the normalisations matters and is the whole point of this function:
     *  1. drop surrounding quotes, which some clipboard paths add — done **first**, so a quoted
     *     `"BDUSS=…; …"` is one value rather than a first key of `"BDUSS`;
     *  2. strip a leading `Cookie:` / `Set-Cookie:` header name and any `-H 'Cookie: …'` framing;
     *  3. split on `;`, `\n` and `\r`, so both the header form and the row-per-cookie form work;
     *  4. treat a **tab** as an `=` — the devtools cookie table is tab-separated and pastes that way;
     *  5. ignore `Set-Cookie` attributes.
     *
     * A `value` may itself contain `=` (Baidu's `BDUSS` is base64-like and often ends in `=`), so each
     * pair is split on the **first** `=` only.
     */
    private fun extractPairs(raw: String): List<Pair<String, String>> {
        val headerStripped = raw
            .trim()
            // Quotes first: a value quoted as a whole would otherwise leave `"BDUSS` as the first key.
            .removeSurrounding("\"")
            .removeSurrounding("'")
            // `Cookie: BDUSS=…` and `-H 'Cookie: BDUSS=…'` both reduce to their payload.
            .replace(Regex("""(?i)\bcookie\s*:\s*"""), " ")
            .replace(Regex("""^\s*-\w+\s*'?"""), " ")
            .trim()

        return headerStripped
            .split(';', '\n', '\r')
            .mapNotNull { segment ->
                val trimmed = segment.trim().removeSurrounding("\"").trim()
                if (trimmed.isEmpty()) return@mapNotNull null

                // A tab-separated row from the devtools table: `BDUSS<TAB>value`. Converting the
                // first tab to `=` keeps the rest of the value intact (a value never contains a tab).
                val normalised = trimmed.replaceFirst('\t', '=')

                val separator = normalised.indexOf('=')
                if (separator <= 0) return@mapNotNull null

                val name = normalised.substring(0, separator).trim()
                val value = normalised.substring(separator + 1).trim().removeSurrounding("\"")
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

    /**
     * Restores the vendor's canonical casing for the cookies §1.5 names.
     *
     * Case is not semantically meaningful for lookup (the vendor's own client matches
     * case-insensitively in places), but a header that reads exactly like the vendor's makes captured
     * traffic comparable during debugging. Unknown names are upper-cased, which matches every Baidu
     * cookie the reference material lists.
     */
    private fun canonicalName(lowercaseName: String): String = when (lowercaseName) {
        "baiduid" -> "BAIDUID"
        "baiduid_bfess" -> "BAIDUID_BFESS"
        "bidupsid" -> "BIDUPSID"
        "pstm" -> "PSTM"
        "h_ps_pssid" -> "H_PS_PSSID"
        "csrftoken" -> "csrfToken"
        "bdclnd" -> "BDCLND"
        "ndut_fmt" -> "ndut_fmt"
        else -> lowercaseName.uppercase()
    }
}
