package com.notdisk.provider.baidu

import com.notdisk.provider.baidu.auth.BaiduCredential
import com.notdisk.provider.baidu.auth.BaiduCookieParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [BaiduCookieParser] and [BaiduCredential].
 *
 * ### Why this is a pure-function test
 * Cookie parsing is where a "just split on `;`" implementation quietly goes wrong, and **every failure
 * mode is user-visible**: a parse that fails leaves the user unable to bind an account; a parse that
 * silently half-succeeds creates an account row that renders as usable and then fails on the first tap.
 * Making the parser pure turns each of those into a named test instead of a support ticket.
 */
class BaiduCookieParserTest {

    @Test
    fun `parses a browser cookie header`() {
        val raw = "BDUSS=abc123def456; STOKEN=deadbeef; BAIDUID=1122334455; BIDUPSID=AABBCC"
        val credential = BaiduCookieParser.parse(raw)

        assertNotNull(credential)
        assertEquals("abc123def456", credential.bduss)
        assertEquals("deadbeef", credential.stoken)
        assertTrue(credential.isComplete)
        // Everything else is preserved verbatim: Baidu's risk control fingerprints the device, so
        // pruning the cookie jar would make an otherwise-valid session look like a new device.
        assertEquals(2, credential.extraCookies.size)
    }

    /**
     * The `Cookie:` prefix is the single most common paste shape (devtools' "Copy as cURL"), and a
     * parser that keeps it would produce a first key of `Cookie: BDUSS` — a session that fails with no
     * explanation.
     */
    @Test
    fun `strips a leading cookie header name`() {
        val credential = BaiduCookieParser.parse("Cookie: BDUSS=abc123; STOKEN=deadbeef")
        assertNotNull(credential)
        assertEquals("abc123", credential.bduss)
    }

    @Test
    fun `strips curl style framing and surrounding quotes`() {
        val credential = BaiduCookieParser.parse("-H 'Cookie: BDUSS=abc123; STOKEN=deadbeef'")
        assertNotNull(credential)
        assertEquals("abc123", credential.bduss)

        val quoted = BaiduCookieParser.parse("\"BDUSS=abc123; STOKEN=deadbeef\"")
        assertNotNull(quoted)
        assertEquals("abc123", quoted.bduss)
    }

    /**
     * The devtools *Application → Cookies* table is copied row by row, and its rows are
     * **tab**-separated rather than `name=value`. A parser that only handled `;` would reject a paste
     * that looks perfectly reasonable to the user.
     */
    @Test
    fun `parses a tab separated cookie table`() {
        val raw = "BDUSS\tabc123def456\nSTOKEN\tdeadbeef\nBAIDUID\t1122334455"
        val credential = BaiduCookieParser.parse(raw)

        assertNotNull(credential)
        assertEquals("abc123def456", credential.bduss)
        assertEquals("deadbeef", credential.stoken)
    }

    /**
     * `Set-Cookie` attributes are not credentials. Treating them as cookies would put `Path=/` into the
     * emitted header, which is a malformed session.
     */
    @Test
    fun `ignores set-cookie attributes`() {
        val raw = "BDUSS=abc123; Path=/; Domain=.baidu.com; Expires=Wed, 21 Oct 2026 07:28:00 GMT; " +
            "HttpOnly; Secure; SameSite=None"
        val credential = BaiduCookieParser.parse(raw)

        assertNotNull(credential)
        assertEquals("abc123", credential.bduss)
        assertTrue(
            credential.extraCookies.none { (name, _) ->
                name.lowercase() in setOf("path", "domain", "expires", "httponly", "secure", "samesite")
            },
            "a Set-Cookie attribute leaked into the credential: ${credential.extraCookies}",
        )
    }

    /**
     * A `BDUSS` is base64-like and often ends in `=`. Splitting on the *last* `=` instead of the first
     * would silently truncate the token — which produces a credential that authenticates nowhere.
     */
    @Test
    fun `a value containing equals signs is preserved intact`() {
        val credential = BaiduCookieParser.parse("BDUSS=abc123==; STOKEN=xyz=")
        assertNotNull(credential)
        assertEquals("abc123==", credential.bduss)
        assertEquals("xyz=", credential.stoken)
    }

    /** Cookie names are case-insensitive for lookup, so a lower-cased paste must still work. */
    @Test
    fun `key casing is irrelevant`() {
        val credential = BaiduCookieParser.parse("bduss=abc123; stoken=deadbeef")
        assertNotNull(credential)
        assertEquals("abc123", credential.bduss)
        assertEquals("deadbeef", credential.stoken)
    }

    /** Without `BDUSS` there is no credential at all: it is the one load-bearing cookie (§1.5). */
    @Test
    fun `a paste without bduss is rejected rather than partially accepted`() {
        assertNull(BaiduCookieParser.parse("STOKEN=deadbeef; BAIDUID=1122334455"))
        assertNull(BaiduCookieParser.parse(""))
        assertNull(BaiduCookieParser.parse("   "))
        assertNull(BaiduCookieParser.parse("not a cookie at all"))
    }

    /** `STOKEN` is documented as optional for the `/api` surface, so its absence must not reject. */
    @Test
    fun `stoken is optional`() {
        val credential = BaiduCookieParser.parse("BDUSS=abc123")
        assertNotNull(credential)
        assertTrue(credential.isComplete)
        assertNull(credential.stoken)
    }

    /**
     * The emitted header is what actually authenticates, so its exact shape is worth asserting:
     * `BDUSS` first, blank values omitted.
     */
    @Test
    fun `cookie header puts bduss first and omits blanks`() {
        val credential = BaiduCredential(
            bduss = "abc123",
            stoken = null,
            extraCookies = listOf("BAIDUID" to "9988"),
            userId = null,
            obtainedAt = 0,
        )
        val header = credential.cookieHeader()

        assertTrue(header.startsWith("BDUSS=abc123"), "BDUSS must come first, got: $header")
        assertTrue(header.contains("BAIDUID=9988"))
        // A blank member would be `STOKEN=`, which the vendor reads as a malformed session.
        assertTrue(!header.contains("STOKEN="), "a blank STOKEN was emitted: $header")
        assertTrue(!header.contains("=;"), "an empty value was emitted: $header")
    }

    /**
     * When a user pastes a full header, `BDUSS`/`STOKEN` can appear both as the named field and in the
     * extras. Emitting them twice produces a duplicated header member, which some servers reject.
     */
    @Test
    fun `a duplicated bduss is emitted exactly once`() {
        val credential = BaiduCredential(
            bduss = "abc123",
            stoken = "deadbeef",
            extraCookies = listOf("BDUSS" to "abc123", "STOKEN" to "deadbeef", "BAIDUID" to "9988"),
            userId = null,
            obtainedAt = 0,
        )
        val header = credential.cookieHeader()
        assertEquals(1, Regex("BDUSS=").findAll(header).count(), "BDUSS appeared twice: $header")
        assertEquals(1, Regex("STOKEN=").findAll(header).count(), "STOKEN appeared twice: $header")
    }

    /**
     * A credential must never leak through string interpolation. This is the one test in the file that
     * asserts an *absence*, and it is the most important: a credential in a log line is unrecoverable.
     */
    @Test
    fun `a credential never renders its secret through toString`() {
        val credential = BaiduCredential(
            bduss = "SUPER_SECRET_BDUSS_VALUE",
            stoken = "SECRET_STOKEN",
            extraCookies = listOf("BAIDUID" to "SECRET_BAIDUID"),
            userId = "1714888383",
            obtainedAt = 0,
        )
        val rendered = credential.toString()

        assertTrue(!rendered.contains("SUPER_SECRET_BDUSS_VALUE"), "BDUSS leaked: $rendered")
        assertTrue(!rendered.contains("SECRET_STOKEN"), "STOKEN leaked: $rendered")
        assertTrue(!rendered.contains("SECRET_BAIDUID"), "a cookie leaked: $rendered")
        assertTrue(rendered.contains("***"), "the redaction marker is missing: $rendered")
    }
}
