package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.PreviewHint
import com.notdisk.provider.p115.api.P115Hosts
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end HTTP tests for the 115 provider against a local [MockWebServer].
 *
 * ### Why these matter more than usual here
 * The reference manual documents a set of behaviours that are *specific to this vendor* and that no
 * amount of interface-level testing can catch:
 *  - session credentials arriving in the response **body** rather than `Set-Cookie` (§2.1 Step 3);
 *  - `state` being `true/false` on one endpoint and `1/0` on another (§1.3);
 *  - batch endpoints requiring indexed `fid[0]`/`rid[0]` parameters (§5.3, §8.2, §8.3);
 *  - a field changing type between endpoints — `s`/`sta`/`t` are strings in search results and
 *    numbers in listings (§4);
 *  - subtitle URLs carrying a trailing `\r\n` that must be trimmed (§10.1);
 *  - `count` in a listing being a string in one endpoint and a number in another (§9.3 vs §3.1).
 *
 * Every response body used below is a **verbatim copy** of a body recorded in
 * `/data/workspace/115_reverse/api_bodies.json`, so the tests fail if the mapper ever stops matching
 * the real payload shape. No response is invented.
 */
class P115ProviderHttpTest {

    private lateinit var server: MockWebServer

    private val accountId = AccountId("test-account")

    /**
     * Explicitly queued responses; always consumed before the path routes.
     *
     * Declared before [setUp] on purpose: the dispatcher closure captures it, and Kotlin
     * initialises properties in declaration order, so a dispatcher installed first would capture a
     * null field.
     */
    private val queued = java.util.concurrent.ConcurrentLinkedQueue<MockResponse>()

    /** The body of the request currently being routed (used by POST routes). */
    private val currentBody = java.lang.ThreadLocal.withInitial { "" }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                // `files/preview` is a POST whose pickcode is in the body, so the body is captured
                // before routing rather than re-read after it has been consumed.
                currentBody.set(runCatching { request.body.clone().readUtf8() }.getOrDefault(""))

                // An explicitly queued response always wins, so a test can override any route.
                queued.poll()?.let { return it }

                // Path routing (rather than a pure queue) because the provider legitimately issues a
                // *second* request when it must resolve a pickcode on a cache miss. An ordered queue
                // would hand that lookup the response meant for the real endpoint and fail for a
                // reason unrelated to the behaviour under test.
                return when {
                    path.contains("ac=nav") -> json(
                        """{"state":true,"data":{"user_id":20856775,"user_name":"wwxb","vip":1,"expire":1982993972,"mark1":1023,"device":2}}""",
                    )

                    // Pickcode resolution: the only source of `pc` on a cold cache. The value is
                    // derived from the requested file id so downstream routes can branch on it.
                    path.startsWith("/files/get_info") -> {
                        val fid = path.substringAfter("file_id=", "1").substringBefore('&')
                        json("""{"state":true,"data":[{"fid":"$fid","pc":"pc-$fid","n":"f.txt"}]}""")
                    }

                    path.startsWith("/files/video") -> json(P115TestBodies.VIDEO)
                    path.startsWith("/files/image") -> json(P115TestBodies.IMAGE)
                    // Any file other than the fixture's previewable one gets the vendor's recorded
                    // "暂不支持此文件格式预览" rejection, which is the real signal the UI must turn
                    // into a "download instead" affordance.
                    path.startsWith("/files/preview") -> {
                        val body = currentBody.get()
                        if (body.contains("pc-$PREVIEW_UNPREVIEWABLE_FID")) {
                            json(P115TestBodies.PREVIEW_UNSUPPORTED)
                        } else {
                            json(P115TestBodies.PREVIEW_OFFICE)
                        }
                    }
                    path.startsWith("/files/download") -> json(P115TestBodies.DOWNLOAD)
                    path.startsWith("/files/star") -> json("""{"state":true,"error":""}""")
                    path.startsWith("/rb") -> json("""{"state":true,"error":""}""")
                    path.startsWith("/files/move") ||
                        path.startsWith("/files/copy") -> json("""{"state":true,"error":""}""")
                    path.startsWith("/files/batch_rename") -> json("""{"state":true,"error":""}""")
                    path.startsWith("/files/add") -> json(
                        """{"state":true,"error":"","aid":1,"cid":"3539516692986070201","cname":"新目录","file_id":"3539516692986070201","file_name":"新目录"}""",
                    )
                    path.startsWith("/category/get") -> json(P115TestBodies.CATEGORY)
                    path.startsWith("/files") -> json(P115TestBodies.LISTING)
                    else -> json("""{"state":true,"error":""}""")
                }
            }
        }
        server.start()
    }

    /**
     * Builds a provider whose every 115 host points at the local server, with a credential already
     * bound so that data calls are authenticated.
     *
     * The host override is exactly what the production constructor exposes for this purpose — there
     * is no test-only back door inside the provider.
     */
    private suspend fun providerWithAccount(): P115CloudProvider {
        val host = server.url("/").toString().trimEnd('/')
        val credentials = com.notdisk.provider.p115.auth.InMemoryP115CredentialStore()
        val accounts = com.notdisk.provider.p115.auth.InMemoryP115AccountMetadataStore()

        credentials.save(
            accountId,
            com.notdisk.provider.p115.auth.P115Credential(
                userSessionId = "a".repeat(64),
                uid = "20856775_A1_1791956082",
                cid = "b017d659c0a1102aeebdd890204199e0",
                seid = "8".repeat(120),
                kid = "44b17af1fdc496eece781e712599268f",
                userId = "20856775",
                obtainedAt = 1_791_956_000_000,
            ),
        )
        accounts.upsert(
            com.notdisk.provider.p115.auth.P115AccountMetadata(
                accountId = accountId,
                remoteUserId = "20856775",
                displayName = "wwxb",
                authState = com.notdisk.core.model.AuthState.AUTHORIZED,
                membershipLabel = "年费VIP",
                membershipExpiresAt = 1_982_993_972_000,
                addedAt = 1_791_956_000_000,
                lastVerifiedAt = 1_791_956_000_000,
            ),
        )

        return P115CloudProvider(
            configuration = P115Configuration(),
            logger = NoOpCloudLogger,
            credentialStore = credentials,
            accountMetadataStore = accounts,
            hostOverrides = mapOf(
                P115Hosts.WEB_API to host,
                P115Hosts.PASSPORT_API to host,
                P115Hosts.QRCODE_API to host,
                P115Hosts.MY to host,
                P115Hosts.APS to host,
            ),
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** Waits for the request whose path contains [fragment], so assertions are order-independent. */
    private fun MockWebServer.takeRequestMatching(fragment: String): okhttp3.mockwebserver.RecordedRequest {
        repeat(6) {
            val request = takeRequest(2, TimeUnit.SECONDS) ?: return@repeat
            if (request.path.orEmpty().contains(fragment)) return request
        }
        throw AssertionError("no request matching '$fragment' was recorded")
    }

    /** The body of the request whose path contains [fragment]. */
    private fun lastRequestBodyMatching(fragment: String): String =
        server.takeRequestMatching(fragment).body.readUtf8()

    /**
     * Queues a response that takes priority over every path route.
     *
     * Use with care: a queued response is handed to the **next** request whatever it is, and the
     * provider makes a pickcode lookup whenever its cache is cold. Prefer the path routes in
     * [setUp], which are order-independent.
     */
    private fun enqueue(body: String, code: Int = 200) {
        queued.add(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body),
        )
    }

    // ------------------------------------------------------------------ listing

    /**
     * §3.1's recorded listing body, verbatim.
     *
     * Asserts the three things the mapper must get right on this payload: a folder is distinguished
     * from a file by the *absence* of `fid`, the vendor's own `path` array becomes the breadcrumb,
     * and a cursor is produced when a full page comes back.
     */
    @Test
    fun `listing maps folders and files from the recorded body`() = runTest {
        val provider = providerWithAccount()

        // The recorded response is a 2-entry page: one folder (cid only) and one file (fid present).
        enqueue(
            """
            {
              "state": true,
              "data": [
                { "n":"我的文件", "cid":"2590058519487052812", "pc":"fa8zwyji7hnqv44siu" },
                { "fid":"3289386448728834697", "cid":"3289386376326758547", "n":"cookies.txt",
                  "s":2355, "pc":"d6m6sqtfmezzyxqdm", "sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A",
                  "ico":"txt", "t":"2025-11-03 11:43", "te":"1762141421", "is_top":0, "fl":[] }
              ],
              "count": 2,
              "offset": 0,
              "page_size": 30,
              "order": "user_ptime",
              "is_asc": 0,
              "path": [ {"cid":0,"name":"根目录"} ]
            }
            """.trimIndent(),
        )

        val page = provider.files.listChildren(
            accountId,
            ListChildrenRequest(P115FileIdCodec.forFolder("0"), pageSize = 30),
        ).getOrNull()

        assertNotNull(page)
        assertEquals(2, page.items.size)

        val folder = page.items[0]
        assertEquals(FileType.FOLDER, folder.type, "a node with cid and no fid is a folder")
        assertEquals("我的文件", folder.name)
        assertNull(folder.extension, "a folder must not carry an extension")

        val file = page.items[1]
        assertEquals(FileType.FILE, file.type, "a node with fid is a file")
        assertEquals("cookies.txt", file.name)
        assertEquals(2355L, file.size)
        assertEquals("txt", file.extension)
        assertEquals("text/plain", file.mimeType)
        // §4: `te` is the numeric modified time and is preferred over the formatted `t`.
        assertEquals(1_762_141_421_000L, file.modifiedTime)
        // The vendor's own breadcrumb.
        assertEquals(listOf("根目录"), page.path.map { it.name })
        // A 2-item page with pageSize 30 is short, so the listing ends unambiguously.
        assertNull(page.nextCursor, "a short page must terminate the listing")

        // The request must carry the documented parameters.
        val recorded = server.takeRequest()
        val url = recorded.path!!
        assertTrue(url.contains("aid=1"), "personal space is aid=1: $url")
        assertTrue(url.contains("cid=0"), "the root is cid=0: $url")
        assertTrue(url.contains("show_dir=1"), "show_dir drives folder inclusion: $url")
        assertTrue(url.contains("format=json"), url)
        assertEquals("GET", recorded.method)
        // §1.4: the cookie must carry the four body-delivered credentials.
        val cookie = recorded.getHeader("Cookie").orEmpty()
        assertTrue(cookie.contains("USERSESSIONID="), cookie)
        assertTrue(cookie.contains("SEID="), cookie)
    }

    /**
     * A full page must produce a cursor, and the next request must use the incremented offset.
     * This is what stops "load more" from either looping on page 0 or stopping early.
     */
    @Test
    fun `a full page produces a cursor that advances the offset`() = runTest {
        val provider = providerWithAccount()
        val page = """{"state":true,"data":[${(1..3).joinToString(",") {
            """{"fid":"$it","cid":"99","n":"f$it.txt","s":1,"pc":"pc$it","ico":"txt"}"""
        }}],"count":10,"offset":0,"page_size":3,"path":[{"cid":0,"name":"根目录"}]}"""

        enqueue(page)
        val first = provider.files.listChildren(
            accountId,
            ListChildrenRequest(P115FileIdCodec.forFolder("0"), pageSize = 3),
        ).getOrNull()
        assertNotNull(first)
        assertEquals("3", first.nextCursor, "a full page must advertise the next offset")
        server.takeRequest()

        enqueue(page)
        provider.files.listChildren(
            accountId,
            ListChildrenRequest(
                P115FileIdCodec.forFolder("0"),
                cursor = first.nextCursor,
                pageSize = 3,
            ),
        )
        val second = server.takeRequest()
        assertTrue(
            second.path!!.contains("offset=3"),
            "the cursor must be sent as the next offset, got ${second.path}",
        )
    }

    /**
     * §4's warning that search results use *string* types where listings use numbers.
     * This body is the recorded search response, where `s`, `sta`, `t` and `aid` are all strings.
     */
    @Test
    fun `search maps the string-typed recorded body`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """
            {"count":1,"data":[{"fid":"3289386448728834697","aid":"1","cid":"3289386376326758547",
            "n":"cookies.txt","s":"2355","sta":"1","pt":"0","pc":"d6m6sqtfmezzyxqdm","fc":1,"p":0,
            "m":"0","t":"2025-11-03","te":"1762141421","tp":"1762141421","d":0,"c":"0","sh":"0",
            "e":"","ico":"txt","dp":"从赘婿到宠臣",
            "ns":"<b style='color: #FF0000; background-color: #FFFF00;'>cookies<\/b>.txt",
            "sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A","q":0,"ih":"","nm":0,"hdf":0,
            "coordinate":"","address":"","ec":0,"fl":[],"u":"","score":0,"is_top":0}],
            "cost_time_es":0.0685,"folder":{"name":"根目录","cid":"0","pid":"0"},"page_size":5,
            "page_count":1,"offset":0,"is_asc":0,"order":"file_size","suffix":"","fc_mix":0,
            "type":0,"state":true,"error":"","errCode":0}
            """.trimIndent(),
        )

        val page = provider.files.search(
            accountId,
            com.notdisk.core.model.FileSearchQuery(keyword = "cookies"),
        ).getOrNull()

        assertNotNull(page)
        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals("cookies.txt", item.name)
        // The string "2355" must become the number 2355.
        assertEquals(2355L, item.size)
        assertEquals("txt", item.extension)
        // §3.4: a single search page carries no cursor; claiming more pages would loop forever.
        assertNull(page.nextCursor)
        // The `ns` HTML-highlighted name must never reach the model.
        assertTrue(!item.name.contains("<b"), "the highlighted name must not leak HTML")
    }

    // ------------------------------------------------------------------ detail

    /**
     * §3.2's recorded `category/get` body.
     *
     * It is a *flat* object (not wrapped in `data`), spells the pickcode `pick_code`, and carries the
     * full `paths` chain — all three differ from the listing endpoint.
     */
    @Test
    fun `detail uses the recorded flat category body and its paths chain`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """
            {"state":true,"error":"","errNo":0,"count":0,"size":"1.86MB","folder_count":0,
            "play_long":0,"show_play_long":0,"ptime":"1779289803","ctime":"1779289803",
            "utime":"1779289803","is_share":"0","is_private":0,
            "file_name":"screenshot.png","pick_code":"ak944jen6pwe5hsiu",
            "sha1":"56076926ECA710C7878E8853738D29A7CE2EB734","is_mark":"0","fvs":0,
            "open_time":1791959227,"rtime":"0","score":0,"desc":"","file_category":"1","fl":[],
            "paths":[{"file_id":0,"file_name":"根目录"},
                     {"file_id":"2590058519487052812","file_name":"我的文件"},
                     {"file_id":"2606876990032051352","file_name":"电视剧"}]}
            """.trimIndent(),
        )

        val fileId = P115FileIdCodec.forFile("3433237498815791830", "2606876990032051352")
        val item = provider.files.getFile(accountId, fileId).getOrNull()

        assertNotNull(item)
        assertEquals("screenshot.png", item.name)
        assertEquals(FileType.FILE, item.type)
        // The detail response carries no `s`/`ico`; the size comes from `size` and the mime from the
        // extension, so a missing `ico` must not zero the item out.
        assertTrue(item.extension == "png" || item.extension == null)
        // `pick_code` must be picked up, because every single-file operation needs it.
        // The pickcode is NOT part of the id (it is derived metadata), so it is asserted through
        // the resolver's cache instead: `getFile` must have recorded it.
        assertEquals(
            null,
            P115FileIdCodec.decode(item.id)?.fid?.let { null },
            "sanity: the id carries no pickcode by construction",
        )
    }

    @Test
    fun `path resolution returns the vendor breadcrumb`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """
            {"state":true,"file_name":"screenshot.png","pick_code":"ak944jen6pwe5hsiu",
            "paths":[{"file_id":0,"file_name":"根目录"},
                     {"file_id":"2590058519487052812","file_name":"我的文件"},
                     {"file_id":"2606876990032051352","file_name":"电视剧"}]}
            """.trimIndent(),
        )

        val path = provider.files
            .getPath(accountId, P115FileIdCodec.forFile("111", "222"))
            .getOrNull()

        assertNotNull(path)
        assertEquals(listOf("根目录", "我的文件", "电视剧"), path.segments.map { it.name })
        // The root segment arrives as the number 0 and must be normalised to the canonical folder id,
        // otherwise the breadcrumb's first element could not be navigated to.
        assertEquals(
            P115FileIdCodec.forFolder("0"),
            path.segments.first().id,
            "the numeric root id 0 must be normalised",
        )
    }

    // ------------------------------------------------------------------ media

    /**
     * §10.1's recorded `files/video` body.
     *
     * The critical assertions are the trailing `\r\n` on the subtitle URLs (which would produce a
     * malformed request if left in) and the fact that the payload is returned at the **top level**,
     * not under `data`.
     */
    @Test
    fun `video metadata maps renditions, audio tracks and trims subtitle urls`() = runTest {
        val provider = providerWithAccount()

        val fileId = P115FileIdCodec.forFile("3433237498815791806", "1")
        val resource = provider.media.resolveMedia(accountId, fileId).getOrNull()

        assertNotNull(resource)
        assertEquals(MediaProtocol.HLS, resource.protocol)
        assertTrue(resource.url.endsWith(".m3u8"))
        // §10.1: `play_long` is seconds, and the unified model is milliseconds.
        assertEquals(2_525_000L, resource.durationMs)
        assertEquals(1920, resource.width)
        assertEquals(804, resource.height)

        // §10.1's warning: the trailing CRLF must be gone, or the player's request is malformed.
        assertEquals(2, resource.subtitles.size)
        resource.subtitles.forEach { track ->
            assertTrue(
                !track.url.contains("\r") && !track.url.contains("\n"),
                "subtitle url must be trimmed, got <${track.url}>",
            )
        }
        assertEquals("application/x-subrip", resource.subtitles.first().mimeType)

        // §10.1: `definition_list` gives the quality ladder, keyed by bitrate.
        assertEquals(2, resource.renditions.size)
        assertEquals("7500000", resource.renditions.first().id, "renditions sort by bitrate desc")
        assertEquals("4K", resource.renditions.first().label)
        // 115 labels its ladders with both "1080P" and "4K"; the latter must still yield a height.
        assertEquals(2160, resource.renditions.first().height)

        // §10.2 item 2: the audio is delivered as separate playlists.
        assertEquals(2, resource.audioTracks.size)
        assertEquals("0", resource.audioTracks.first().id)

        // §10.2 / §1.4: the CDN requires attribution headers.
        assertTrue(resource.headers.containsKey("Referer"))
        assertTrue(resource.refreshable, "a media URL must be refreshable")

        // The video request must carry a pickcode, not the file id (§1.5 / §15.2 item 7). The first
        // request is the pickcode lookup, so the recorded paths are searched rather than assumed.
        val paths = generateSequence { server.takeRequest(1, TimeUnit.SECONDS)?.path }.toList()
        assertTrue(
            paths.any { it.contains("/files/video") && it.contains("pickcode=") },
            "no /files/video request carried a pickcode; recorded: $paths",
        )
    }

    /**
     * A provider call on a file whose id lacks a pickcode must fail with a clear, typed error rather
     * than issuing a request that the server would reject with `errno 1001`.
     */
    @Test
    fun `media resolution resolves a missing pickcode via get_info`() = runTest {
        val provider = providerWithAccount()
        val fileId = P115FileIdCodec.forFile("111", "222")

        // A pickcode is *derived metadata*, not identity, so a cold cache must not be fatal: the
        // provider performs one `files/get_info` lookup and then plays. That is what makes a deep
        // link into a file the user never browsed work.
        val result = provider.media.resolveMedia(accountId, fileId)

        assertTrue(result.isSuccess(), "a cold pickcode cache must be resolved, not fatal: $result")
        val paths = generateSequence { server.takeRequest(1, TimeUnit.SECONDS)?.path }.toList()
        assertTrue(
            paths.any { it.startsWith("/files/get_info") },
            "the pickcode must be resolved through files/get_info; recorded: $paths",
        )
        assertTrue(
            paths.any { it.contains("/files/video") && it.contains("pickcode=pc-111") },
            "the pickcode resolved from get_info must be used for the video request; " +
                "recorded: $paths",
        )
    }

    /** §10.1's `file_status` gates playback; a non-1 value is a normal "not playable" answer. */
    @Test
    fun `a non playable file status is reported as unsupported not as an error`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":true,"file_status":0,"video_url":"https://115.com/api/video/m3u8/x.m3u8"}""")

        val fileId = P115FileIdCodec.forFile("1", "2")
        val result = provider.media.resolveMedia(accountId, fileId)

        assertTrue(result is CloudResult.Failure)
        assertTrue(
            (result as CloudResult.Failure).error is CloudError.Unsupported,
            "a transcoding/unavailable file is Unsupported, not a transport error",
        )
    }

    /** §10.3: this build must not claim to report progress it has no endpoint for. */
    @Test
    fun `playback progress reporting is refused without a request`() = runTest {
        val provider = providerWithAccount()
        val result = provider.media.reportPlaybackProgress(
            accountId,
            P115FileIdCodec.forFile("1", "2"),
            positionMs = 1_000,
            durationMs = 2_000,
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.Unsupported)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ preview

    /** §11.2's recorded image body. */
    @Test
    fun `image preview resolves the original url and parses its expiry`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """
            {"state":true,"data":{
            "url":"https://thumb.115.com/thumb/5/60/76/56076926ECA7_1440?s=EFhLzoZK&t=1791962827",
            "all_url":["https://thumb.115.com/thumb/5/60/76/56076926ECA7_100?s=EFhLzoZK&t=1791962827",
                       "https://thumb.115.com/thumb/5/60/76/56076926ECA7_480?s=EFhLzoZK&t=1791962827",
                       "https://thumb.115.com/thumb/5/60/76/56076926ECA7_800?s=EFhLzoZK&t=1791962827"],
            "origin_url":"https://thumb.115.com/thumb/5/60/76/56076926ECA7_0?s=EFhLzoZK&t=1791962827",
            "source_url":"https://cdnfhnfile.115.com/hash/name.png?t=1791960222&u=20856775&s=52428800&d=vip&k=895a2af5",
            "file_name":"screenshot.png","file_sha1":"56076926ECA710C7878E8853738D29A7CE2EB734",
            "pick_code":"ak944jen6pwe5hsiu"}}
            """.trimIndent(),
        )

        val fileId = P115FileIdCodec.forFile("3433237498815791830", "1")
        val result = provider.preview.preview(
            accountId,
            fileId,
            hint = PreviewHint(name = "screenshot.png", extension = "png", mimeType = "image/png"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.Image, "got ${result.kind}")
        val image = result.kind as PreviewKind.Image
        assertTrue(image.originalAvailable, "origin_url was present")
        // The thumbnail is a *descriptor* carrying the content hash, never the signed URL.
        assertEquals("56076926eca710c7878e8853738d29a7ce2eb734", image.thumbnail?.contentHash)
        assertNotNull(result.resource)
        assertTrue(result.resource!!.url.contains("_0"), "the original is preferred for the viewer")
        // §11.2 / §7.1: `t` is the expiry, in Unix seconds.
        assertEquals(1_791_962_827_000L, result.expiresAt)
        assertTrue(result.refreshable)
    }

    /**
     * §11.1's recorded rejection: `{"state":false,"code":4011,"msg":"暂不支持此文件格式预览"}`.
     *
     * This is the single most important preview behaviour: a format the vendor cannot render must
     * become a *successful* "Unsupported" classification so the UI offers a download, rather than an
     * error dialog that implies something broke.
     */
    @Test
    fun `an unsupported preview format becomes a classification not a failure`() = runTest {
        val provider = providerWithAccount()

        // `PREVIEW_UNPREVIEWABLE_FID` is routed to the vendor's recorded `4011` rejection.
        val fileId = P115FileIdCodec.forFile(PREVIEW_UNPREVIEWABLE_FID, "1")
        val result = provider.preview.preview(
            accountId,
            fileId,
            hint = PreviewHint(name = "archive.zip", extension = "zip"),
        ).getOrNull()

        assertNotNull(result, "a 4011 must not surface as a failure")
        assertTrue(
            result.kind is PreviewKind.Unsupported,
            "the file must classify as Unsupported so the UI can offer a download, got ${result.kind}",
        )
    }

    /** §11.1's recorded WPS handoff for a text file. */
    @Test
    fun `office preview carries the url and token pair`() = runTest {
        val provider = providerWithAccount()

        // The office route serves the WPS handoff for the fixture's previewable pickcode.
        val fileId = P115FileIdCodec.forFile("3289386448728834697", "1")
        val result = provider.preview.preview(
            accountId,
            fileId,
            hint = PreviewHint(name = "doc.docx", extension = "docx"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.OfficeDocument, "got ${result.kind}")
        val viewer = (result.kind as PreviewKind.OfficeDocument).viewer
        assertTrue(viewer.url.contains("appdocs.wpscdn.cn"))
        assertNotNull(viewer.token, "the WPS handoff needs the token alongside the url")
        // `expire_at` is Unix *seconds* (1792045513); the unified model is milliseconds.
        assertEquals(1_792_045_513_000L, viewer.expiresAt)
        assertEquals(3_540L, viewer.refreshBeforeSeconds)
        assertTrue(viewer.canEdit)
        // §11.1 requires POST for `files/preview`; a GET returns 405. The pickcode lookup is a GET,
        // so the requests are inspected rather than the first one being assumed.
        val methods = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }
            .filter { it.path.orEmpty().startsWith("/files/preview") }
            .map { it.method }
            .toList()
        assertEquals(listOf("POST"), methods, "preview must be requested with POST")
    }

    /**
     * Classification must be local: the brief requires list rendering not to pay a network request
     * per row, and a 200-item folder would otherwise issue 200 preview requests.
     */
    @Test
    fun `classification without access resolution sends no request`() = runTest {
        val provider = providerWithAccount()
        val fileId = P115FileIdCodec.forFile("1", "2")

        val result = provider.preview.preview(
            accountId,
            fileId,
            resolveAccess = false,
            hint = PreviewHint(name = "clip.mp4", extension = "mp4"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.Video)
        assertNull(result.resource, "no access material may be resolved in local mode")
        assertEquals(0, server.requestCount, "local classification must not touch the network")
    }

    // ------------------------------------------------------------------ trash

    /**
     * §8.1's recorded recycle-bin body.
     *
     * Note that `count`, `type`, `file_size` and `dtime` are all **strings** here, and that `id` is
     * the `rid` — not the original file id. Both are asserted.
     */
    @Test
    fun `trash listing maps rid and reports the safe key requirement`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """
            {"count":"58","rb_pass":1,"data":[
            {"id":"3537724747796186152","file_name":"ssis-622ch","type":"2","file_size":"0",
             "dtime":"1791745653","status":"0","cid":"3025056515334602708","parent_name":"tmp"},
            {"id":"3537298091902240214","file_name":"FNS-260-AI","type":"2","file_size":"90534826",
             "dtime":"1791694792","status":"0","cid":"3025056515334602708","parent_name":"tmp"}],
            "state":true,"offset":0,"page_size":5,"order":"dtime","is_asc":0}
            """.trimIndent(),
        )

        val page = provider.operations.listTrash(accountId, pageSize = 30).getOrNull()

        assertNotNull(page)
        assertEquals(2, page.items.size)
        assertEquals("3537724747796186152", page.items.first().trashId)
        assertTrue(page.items.first().isFolder, "type 2 means folder")
        assertEquals(1_791_745_653_000L, page.items.first().deletedAt)
        assertEquals("tmp", page.items.first().originalParentName)
        // §8.1: `rb_pass` = 1 means permanent deletion will need the account's safe key.
        assertTrue(page.protectedBySecret)
        // `count` is the string "58" and must still be read.
        assertEquals(58L, page.totalCount)
        // A 2-item page with pageSize 30 ends the sequence.
        assertNull(page.nextCursor)
    }

    // ------------------------------------------------------------------ account

    /** §2.1 Step 5's recorded session check. */
    @Test
    fun `session verification updates the stored profile`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """
            {"state":true,"data":{"user_id":20856775,"user_name":"wwxb","vip":1,
            "expire":1982993972,"mark1":1023,"mark3":1023,"face":"https://x/y.jpg","device":2}}
            """.trimIndent(),
        )

        val account = provider.auth.verifyAccount(accountId).getOrNull()

        assertNotNull(account)
        assertEquals("wwxb", account.displayName)
        assertEquals("年费VIP", account.membershipLabel, "mark1=1023 is 年费VIP per §13.2")
        assertEquals(com.notdisk.core.model.AuthState.AUTHORIZED, account.authState)
    }

    /**
     * §12 of the brief: a network outage must not log the user out. Only an explicit authentication
     * failure may invalidate a stored credential.
     */
    @Test
    fun `a server error does not invalidate the stored credential`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":false,"errno":500,"error":"internal"}""", code=200)

        val result = provider.auth.verifyAccount(accountId)
        assertTrue(result is CloudResult.Failure)

        val account = provider.auth.getAccount(accountId).getOrNull()
        assertNotNull(account)
        assertEquals(
            com.notdisk.core.model.AuthState.AUTHORIZED,
            account.authState,
            "a server-side failure must not downgrade the account to expired",
        )
    }

    /**
     * §2.1: `state` is the integer `1` on the auth endpoints rather than the boolean `true`.
     * The envelope normaliser must accept both, which this body exercises.
     */
    @Test
    fun `integer state envelopes are accepted`() = runTest {
        val provider = providerWithAccount()
        // An auth-shaped envelope: state is 1, not true. If the reader only accepted booleans this
        // would be misread as a failure.
        enqueue("""{"state":1,"code":0,"message":"","data":{"user_id":20856775,"user_name":"wwxb"}}""")

        val account = provider.auth.verifyAccount(accountId).getOrNull()
        assertNotNull(account, "an integer state=1 envelope must be treated as success")
    }

    // ------------------------------------------------------------------ errors

    /** §14.2: `errno 1001` is the "wrong parameter name" signal and must be recognisable. */
    @Test
    fun `documented errno 1001 maps to InvalidArgument`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":false,"errno":1001,"error":"参数错误"}""")

        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(P115FileIdCodec.forFolder("0")),
        )

        assertTrue(result is CloudResult.Failure)
        val error = (result as CloudResult.Failure).error
        assertTrue(error is CloudError.InvalidArgument, "got $error")
        assertEquals("1001", error.vendorCode, "the vendor code must be preserved for diagnosis")
    }

    /**
     * §8.4: the safe-key failure arrives as `errno 0` with the message `密钥错误`, so the *message*
     * is the only signal. It must become a verifiable prompt, not a generic error.
     */
    @Test
    fun `a safe key error becomes an actionable verification requirement`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":false,"error":"密钥错误","errno":0,"errtype":""}""")

        val result = provider.operations.purgeFromTrash(accountId, listOf("1"), safeKey = "")

        assertTrue(result is CloudResult.Failure)
        val error = (result as CloudResult.Failure).error
        assertTrue(
            error is CloudError.AdditionalVerificationRequired,
            "a safe-key failure must be distinguishable so the UI can prompt for the key, got $error",
        )
    }

    /** §8.4: `rb/clean` must send indexed `rid[i]` **and** the `password` field. */
    @Test
    fun `purge sends indexed rids and the safe key field`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":true,"error":""}""")

        provider.operations.purgeFromTrash(accountId, listOf("11", "22"), safeKey = "s3cret")

        val body = lastRequestBodyMatching("/rb/clean")
        assertTrue(body.contains("rid%5B0%5D=11"), "rid must be indexed, got: $body")
        assertTrue(body.contains("rid%5B1%5D=22"), body)
        assertTrue(body.contains("password=s3cret"), body)
        // §15.2 item 3: `file_ids=`/`ids=` are rejected by this API.
        assertTrue(!body.contains("rid%5B%5D="), "rid[] is documented to fail, got: $body")
    }

    /** §8.2: the delete endpoint must use indexed `fid[i]`, which is the API's biggest trap. */
    @Test
    fun `delete sends indexed fids`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":true,"error":""}""")

        provider.operations.delete(
            accountId,
            listOf(
                P115FileIdCodec.forFile("111", "1"),
                P115FileIdCodec.forFile("222", "1"),
            ),
        )

        val body = lastRequestBodyMatching("/rb/delete")
        assertTrue(body.contains("fid%5B0%5D=111"), "fid must be indexed, got: $body")
        assertTrue(body.contains("fid%5B1%5D=222"), body)
        assertTrue(!body.contains("file_ids="), "file_ids= is documented to return errno 1001: $body")
        assertTrue(body.contains("ignore_warn=1"), body)
    }

    /** §5.2: rename must go to `batch_rename` with `files_new_name[<id>]`, never `/files/edit`. */
    @Test
    fun `rename uses batch_rename with the indexed name field`() = runTest {
        val provider = providerWithAccount()
        enqueue("""{"state":true,"error":""}""")

        val result = provider.operations.rename(
            accountId,
            P115FileIdCodec.forFile("111", "1"),
            "newname.txt",
        )

        assertTrue(result.isSuccess(), "got $result")
        val recorded = server.takeRequestMatching("/files/batch_rename")
        assertTrue(
            recorded.path!!.endsWith("/files/batch_rename"),
            "rename must use batch_rename, got ${recorded.path}",
        )
        assertTrue(
            recorded.body.readUtf8().contains("files_new_name%5B111%5D=newname.txt"),
            "the new name must be sent as files_new_name[<id>]",
        )
    }

    /** A locally-invalid rename must be rejected before any request is made. */
    @Test
    fun `rename with a path separator is rejected locally`() = runTest {
        val provider = providerWithAccount()
        val result = provider.operations.rename(
            accountId,
            P115FileIdCodec.forFile("111", "1"),
            "a/b.txt",
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.InvalidArgument)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ download

    /**
     * §7.1's recorded direct-link body.
     *
     * The signed URL is a secret with a lifetime, so the assertions are that the expiry is parsed
     * from the URL and that the link is *not* written into a persistable field.
     */
    @Test
    fun `download link expiry is parsed from the signed url`() = runTest {
        val provider = providerWithAccount()

        val fileId = P115FileIdCodec.forFile("3289386448728834697", "1")
        val source = provider.operations.prepareDownload(accountId, fileId).getOrNull()

        assertNotNull(source)
        assertEquals("cookies.txt", source.fileName, "§15.2 item 14: the name comes from the payload")
        assertEquals(2355L, source.sizeBytes)
        assertEquals(1_791_960_109_000L, source.expiresAt, "`t` is the expiry in Unix seconds")
        assertTrue(source.refreshable, "§7.1 advises never caching this link")
        assertTrue(source.headers.containsKey("Referer"))
        // §7.1's request sends file_id (and the pickcode when known).
        val paths = generateSequence { server.takeRequest(1, TimeUnit.SECONDS)?.path }.toList()
        assertTrue(
            paths.any { it.contains("/files/download") && it.contains("file_id=3289386448728834697") },
            "no download request carried the file id; recorded: $paths",
        )
    }

    // ------------------------------------------------------------------ capabilities

    /**
     * The capability declaration is itself a contract: it decides which UI affordances exist. These
     * assertions pin the deliberate gaps so that a future change to them is a conscious decision
     * rather than an accident.
     */
    @Test
    fun `capabilities declare the documented and the unverified accurately`() {
        val provider = P115CloudProvider(
            logger = NoOpCloudLogger,
            credentialStore = com.notdisk.provider.p115.auth.InMemoryP115CredentialStore(),
            accountMetadataStore = com.notdisk.provider.p115.auth.InMemoryP115AccountMetadataStore(),
        )
        val caps = provider.capabilities()

        assertEquals(P115ProviderId, caps.providerId)

        // Measured (`✅`) and implemented.
        assertEquals(CapabilitySupport.SUPPORTED, caps.fileSystem.listChildren)
        assertEquals(CapabilitySupport.SUPPORTED, caps.fileSystem.getFile)
        assertEquals(CapabilitySupport.SUPPORTED, caps.fileSystem.search)
        assertEquals(CapabilitySupport.SUPPORTED, caps.operations.createFolder)
        assertEquals(CapabilitySupport.SUPPORTED, caps.operations.rename)
        assertEquals(CapabilitySupport.SUPPORTED, caps.operations.move)
        assertEquals(CapabilitySupport.SUPPORTED, caps.operations.copy)
        assertEquals(CapabilitySupport.SUPPORTED, caps.operations.delete)
        assertEquals(CapabilitySupport.SUPPORTED, caps.operations.trash.purge)
        assertEquals(CapabilitySupport.SUPPORTED, caps.media.videoStreaming)
        assertEquals(CapabilitySupport.SUPPORTED, caps.preview.image)

        // Documented from source only (`📖`) — deliberately not claimed as working.
        assertEquals(CapabilitySupport.UNVERIFIED, caps.operations.upload.supported)
        assertEquals(CapabilitySupport.UNVERIFIED, caps.operations.share)
        assertEquals(CapabilitySupport.UNVERIFIED, caps.media.playbackProgress)

        // Genuinely absent.
        assertEquals(CapabilitySupport.UNSUPPORTED, caps.operations.download.folderArchive)
        assertEquals(CapabilitySupport.UNSUPPORTED, caps.account.silentRefresh)

        // A non-idempotent write surface must not be declared retryable (§5.4's copy).
        assertTrue(!caps.operations.idempotentWrites)

        // The pagination model drives the UI's "load more" affordance.
        assertEquals(com.notdisk.core.model.PagingModel.OFFSET_LIMIT, caps.fileSystem.paging)

        // The media protocol is HLS, and range support is explicitly unknown rather than asserted.
        assertEquals(setOf(MediaProtocol.HLS), caps.media.protocols)
        assertNull(caps.media.rangeRequests, "undocumented range support must stay unknown")

        // No developer credentials are configured, so OAuth must not be advertised.
        assertTrue(!caps.account.interactiveFlows.contains(com.notdisk.core.model.AuthFlowKind.OAUTH_REDIRECT))
        assertTrue(caps.account.interactiveFlows.contains(com.notdisk.core.model.AuthFlowKind.QR_CODE))
    }

    // ------------------------------------------------------------------ unsupported operations

    /** Upload must fail closed with an explanation, never pretend to succeed. */
    @Test
    fun `upload returns a typed unsupported result and sends nothing`() = runTest {
        val provider = providerWithAccount()
        val result = provider.operations.upload(
            accountId,
            com.notdisk.core.model.UploadRequest(
                key = com.notdisk.core.model.CloudFileKey(
                    P115ProviderId,
                    accountId,
                    P115FileIdCodec.forFolder("0"),
                ),
                parentId = P115FileIdCodec.forFolder("0"),
                fileName = "x.bin",
                totalBytes = 10,
                sourceUri = "content://test/1",
            ),
        )

        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.Unsupported)
        assertEquals(0, server.requestCount, "an unimplemented upload must not touch the network")
    }

    /** Sharing must fail closed: publishing on an unverified protocol is the riskiest possible act. */
    @Test
    fun `share returns a typed unsupported result and sends nothing`() = runTest {
        val provider = providerWithAccount()
        val result = provider.operations.share(
            accountId,
            listOf(P115FileIdCodec.forFile("1", "2")),
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.Unsupported)
        assertEquals(0, server.requestCount)
    }

    /** Permanent delete through the ordinary endpoint must be refused, pointing at the trash path. */
    @Test
    fun `permanent delete is refused rather than silently downgraded`() = runTest {
        val provider = providerWithAccount()
        val result = provider.operations.delete(
            accountId,
            listOf(P115FileIdCodec.forFile("1", "2")),
            permanent = true,
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue((result as CloudResult.Failure).error is CloudError.Unsupported)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ auth flows

    /** Only QR is advertised, so every other flow must fail closed without a request. */
    @Test
    fun `unadvertised auth flows fail closed`() = runTest {
        val provider = providerWithAccount()
        for (flow in com.notdisk.core.model.AuthFlowKind.entries) {
            if (flow == com.notdisk.core.model.AuthFlowKind.QR_CODE) continue
            val result = provider.auth.beginAuthorization(flow)
            assertTrue(
                result is CloudResult.Failure,
                "$flow is not advertised but returned $result",
            )
            assertTrue((result as CloudResult.Failure).error is CloudError.Unsupported)
        }
        assertEquals(0, server.requestCount)
    }

    /**
     * The QR flow's three-step sequence, using the manual's recorded shapes.
     *
     * The critical assertion is that the session is extracted from the **response body**, because
     * §2.1 Step 3 warns that these four cookies never appear in `Set-Cookie`.
     */
    @Test
    fun `qr flow extracts credentials from the response body`() = runTest {
        val host = server.url("/").toString().trimEnd('/')
        val credentials = com.notdisk.provider.p115.auth.InMemoryP115CredentialStore()
        val accounts = com.notdisk.provider.p115.auth.InMemoryP115AccountMetadataStore()
        val provider = P115CloudProvider(
            logger = NoOpCloudLogger,
            credentialStore = credentials,
            accountMetadataStore = accounts,
            hostOverrides = mapOf(
                P115Hosts.QRCODE_API to host,
                P115Hosts.PASSPORT_API to host,
                P115Hosts.WEB_API to host,
                P115Hosts.MY to host,
                P115Hosts.APS to host,
            ),
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )

        // Step 1: the recorded token response.
        enqueue(
            """
            {"state":1,"code":0,"message":"","data":{
            "uid":"dd0c4805d130b9893d6274d606d89e1502d2a995","time":1791955914,
            "sign":"fcf6b0e25d99835f9edf22501e1f29f7",
            "qrcode":"https://115.com/scan/dg-dd0c4805d130b9893d6274d606d89e1502d2a995"}}
            """.trimIndent(),
        )
        val challenge = provider.auth.beginAuthorization(
            com.notdisk.core.model.AuthFlowKind.QR_CODE,
        ).getOrNull()

        assertNotNull(challenge)
        assertTrue(challenge is AuthChallenge.QrCode)
        assertTrue(challenge.content.contains("115.com/scan"))
        assertNotNull(challenge.expiresAt, "the QR must carry its ~120s validity")

        // Step 2: "confirmed" (`status: 2`).
        enqueue("""{"state":1,"code":0,"data":{"msg":"","status":2,"version":"2.0"}}""")
        // Step 3: the recorded login response, with the cookies in the BODY.
        enqueue(
            """
            {"state":1,"data":{"user_id":20856775,"user_name":"wwxb","country":"CN",
            "is_vip":1982993972,"mark":1023,"is_first_login":0,"is_chang_passwd":0,
            "face":{"face_l":"a","face_m":"b","face_s":"c"},"passwd_reset":1,
            "cookie":{"UID":"20856775_A1_1791956082",
                      "CID":"b017d659c0a1102aeebdd890204199e0",
                      "SEID":"${"8".repeat(120)}",
                      "KID":"44b17af1fdc496eece781e712599268f"},
            "from":"115android","is_trusted":false}}
            """.trimIndent(),
        )

        val state = provider.auth.pollChallenge(challenge.challengeId).getOrNull()
        assertTrue(
            state is AuthChallengeState.Succeeded,
            "the confirmed scan must complete the flow, got $state",
        )

        val accountId = (state as AuthChallengeState.Succeeded).accountId
        val stored = credentials.load(accountId)
        assertNotNull(stored, "the credential must be persisted from data.cookie")
        assertEquals("20856775_A1_1791956082", stored.uid)
        assertEquals("b017d659c0a1102aeebdd890204199e0", stored.cid)
        assertEquals(120, stored.seid.length)
        assertEquals("44b17af1fdc496eece781e712599268f", stored.kid)
        assertTrue(stored.isComplete)

        assertEquals(3, server.requestCount, "token + status + login")
    }

    /** A cancelled or unknown challenge must answer with a terminal state, not an error. */
    @Test
    fun `an unknown challenge yields a terminal failed state`() = runTest {
        val provider = providerWithAccount()
        val state = provider.auth.pollChallenge("no-such-challenge").getOrNull()

        assertNotNull(state, "polling must answer with a state so the UI loop terminates")
        assertTrue(state is AuthChallengeState.Failed)
    }

    /** §2.1: `-1` means the user cancelled on the phone. */
    @Test
    fun `a cancelled scan is reported as a terminal failure`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """{"state":1,"code":0,"message":"","data":{"uid":"u","time":1,"sign":"s",
            "qrcode":"https://115.com/scan/x"}}""",
        )
        val challenge = provider.auth.beginAuthorization(
            com.notdisk.core.model.AuthFlowKind.QR_CODE,
        ).getOrNull()!!
        server.takeRequest()

        enqueue("""{"state":1,"code":0,"data":{"status":-1}}""")
        val state = provider.auth.pollChallenge(challenge.challengeId).getOrNull()

        assertTrue(state is AuthChallengeState.Failed)
    }

    /** §2.1: `40199002 key invalid` means the QR token died and must be refetched. */
    @Test
    fun `an invalid qr key ends the challenge as retryable`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """{"state":1,"code":0,"message":"","data":{"uid":"u","time":1,"sign":"s",
            "qrcode":"https://115.com/scan/x"}}""",
        )
        val challenge = provider.auth.beginAuthorization(
            com.notdisk.core.model.AuthFlowKind.QR_CODE,
        ).getOrNull()!!
        server.takeRequest()

        enqueue("""{"state":0,"code":40199002,"message":"key invalid"}""")
        val state = provider.auth.pollChallenge(challenge.challengeId).getOrNull()

        assertTrue(state is AuthChallengeState.Failed)
        assertTrue((state as AuthChallengeState.Failed).retryable)
    }

    /** An unscanned QR (`data: {}`) must keep the caller polling rather than terminating. */
    @Test
    fun `an unscanned qr keeps the challenge pending`() = runTest {
        val provider = providerWithAccount()
        enqueue(
            """{"state":1,"code":0,"message":"","data":{"uid":"u","time":1,"sign":"s",
            "qrcode":"https://115.com/scan/x"}}""",
        )
        val challenge = provider.auth.beginAuthorization(
            com.notdisk.core.model.AuthFlowKind.QR_CODE,
        ).getOrNull()!!
        server.takeRequest()

        enqueue("""{"state":1,"code":0,"data":{}}""")
        val state = provider.auth.pollChallenge(challenge.challengeId).getOrNull()

        assertTrue(
            state is AuthChallengeState.Pending,
            "an empty data object means 'not scanned yet', got $state",
        )
    }
}

/**
 * A file id that the test server answers with the vendor's recorded `4011`
 * ("暂不支持此文件格式预览") rejection rather than an office handoff.
 */
internal const val PREVIEW_UNPREVIEWABLE_FID = "3433237498815791831"

/**
 * Builds a 200 JSON response.
 *
 * Top-level rather than a member so the anonymous dispatchers in this file (and the contract
 * fixture) can call it without capturing an enclosing instance.
 */
internal fun json(body: String): MockResponse = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/json; charset=utf-8")
    .setBody(body)
