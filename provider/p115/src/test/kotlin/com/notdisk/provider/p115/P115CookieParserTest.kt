package com.notdisk.provider.p115

import com.notdisk.provider.p115.auth.P115CookieParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The cookie parser, which is the whole of the manual-credential flow's input handling.
 *
 * ### Why this is worth its own test class
 * Every failure mode here is user-visible, and every one of them looks like "the app is broken"
 * rather than "the paste was wrong":
 *
 *  - a `Cookie:` prefix left on the first key produces a credential whose `UID` is missing, so the
 *    session silently fails;
 *  - a `Set-Cookie` attribute treated as a cookie value gives the vendor a header it rejects;
 *  - a lowercase `uid=` (which some browser tooling emits) missed by an exact-match lookup loses the
 *    account id;
 *  - a `;` inside a value split naively truncates the session key.
 *
 * The parser is a pure function precisely so each of those is an assertion instead of a hope.
 */
class P115CookieParserTest {

    private val complete =
        "UID=20856775_A1_1700000000; CID=0123456789abcdef0123456789abcdef; " +
            "SEID=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
            "0123456789abcdef0123456789abcdef0123456789abcdef; " +
            "KID=fedcba9876543210fedcba9876543210"

    @Test
    fun `a complete cookie header parses into the four required values`() {
        val credential = P115CookieParser.parse(complete)

        assertNotNull(credential, "a complete cookie must parse")
        assertEquals("20856775_A1_1700000000", credential.uid)
        assertEquals("0123456789abcdef0123456789abcdef", credential.cid)
        assertEquals("fedcba9876543210fedcba9876543210", credential.kid)
        assertEquals("20856775", credential.userId, "the user id is derived from the UID prefix")
    }

    /**
     * The four core cookies are enough; `USERSESSIONID` is genuinely optional.
     *
     * §2.1 records that `webapi.115.com` authenticates with `UID`/`CID`/`SEID`/`KID` and that the web
     * front-end's own ticket is absent on some responses. Requiring it would reject a working pasted
     * session; the header must also omit it rather than sending an empty value.
     */
    @Test
    fun `a session without USERSESSIONID is accepted and omits it from the header`() {
        val credential = P115CookieParser.parse(complete)

        assertNotNull(credential)
        assertEquals("", credential.userSessionId)
        val header = credential.cookieHeader()
        assertEquals(
            false,
            header.contains("USERSESSIONID="),
            "an absent ticket must be omitted, not sent empty: $header",
        )
    }

    /**
     * `Set-Cookie` attributes are not credentials.
     *
     * A user who copies a *response* header brings `Path`, `Domain`, `Expires`, `HttpOnly`, `Secure`
     * and `SameSite` along. Treating them as cookies is harmless only by accident, and one of them
     * (`Expires`) contains a comma and a space, which is exactly the shape that breaks a naive
     * parser.
     */
    @Test
    fun `Set-Cookie attributes are ignored`() {
        val pasted = "UID=1_A1_2; Path=/; Domain=.115.com; Expires=Wed, 21 Oct 2026 07:28:00 GMT; " +
            "HttpOnly; Secure; SameSite=Lax; CID=c; SEID=s; KID=k"

        val credential = P115CookieParser.parse(pasted)

        assertNotNull(credential, "the credential must survive attributes mixed in")
        assertEquals("1_A1_2", credential.uid)
        assertEquals("c", credential.cid)
        assertEquals("s", credential.seid)
        assertEquals("k", credential.kid)
    }

    @Test
    fun `a leading Cookie header name is stripped`() {
        val credential = P115CookieParser.parse("Cookie: $complete")

        assertNotNull(credential, "`Cookie: ` prefix must not become part of the first key")
        assertEquals("20856775_A1_1700000000", credential.uid)
    }

    /** Devtools' cookie table is usually copied one row per line. */
    @Test
    fun `a multi-line paste is accepted`() {
        val pasted = complete.replace("; ", "\n")

        val credential = P115CookieParser.parse(pasted)

        assertNotNull(credential)
        assertEquals("0123456789abcdef0123456789abcdef", credential.cid)
    }

    @Test
    fun `key casing does not matter`() {
        val credential = P115CookieParser.parse(
            "uid=1_A1_2; cid=c; seid=s; kid=k",
        )

        assertNotNull(credential, "lower-case keys are emitted by some browser tooling")
        assertEquals("1_A1_2", credential.uid)
    }

    @Test
    fun `surrounding quotes are removed`() {
        val credential = P115CookieParser.parse("\"UID=1_A1_2; CID=c; SEID=s; KID=k\"")

        assertNotNull(credential)
        assertEquals("1_A1_2", credential.uid)
    }

    /**
     * A value may legitimately contain `=`, so only the *first* separator splits a pair.
     *
     * `SEID` in particular is a long hex string, but the same endpoint has shipped base64 values, and
     * splitting on every `=` would truncate them at the first padding character.
     */
    @Test
    fun `a value containing an equals sign is preserved`() {
        val credential = P115CookieParser.parse("UID=1_A1_2; CID=c; SEID=s=s; KID=k")

        assertNotNull(credential)
        assertEquals("s=s", credential.seid)
    }

    @Test
    fun `a missing core cookie is rejected rather than guessed`() {
        assertNull(
            P115CookieParser.parse("UID=1_A1_2; CID=c; SEID=s"),
            "a session without KID cannot authenticate and must not be silently accepted",
        )
    }

    @Test
    fun `an unrelated paste is rejected`() {
        assertNull(P115CookieParser.parse("hello world"))
        assertNull(P115CookieParser.parse(""))
        assertNull(P115CookieParser.parse("   "))
        assertNull(P115CookieParser.parse("sessionid=abc; theme=dark"))
    }
}
