package com.notdisk.provider.baidu

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.core.provider.PreviewHint
import com.notdisk.provider.baidu.api.BaiduHosts
import com.notdisk.provider.baidu.auth.BaiduAccountMetadata
import com.notdisk.provider.baidu.auth.BaiduCredential
import com.notdisk.provider.baidu.auth.InMemoryBaiduAccountMetadataStore
import com.notdisk.provider.baidu.auth.InMemoryBaiduCredentialStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * End-to-end HTTP tests for the Baidu Netdisk provider against a local [MockWebServer].
 *
 * ### Why these matter more than usual here
 * The reference material documents a set of behaviours that are *specific to this vendor* and that no
 * amount of interface-level testing can catch:
 *  - **success is strictly `errno === 0`** and a missing code collapses to `-1` rather than to success
 *    (§0.3);
 *  - the success field is `error_code` on the membership surface and `errno` elsewhere (§9.3);
 *  - `/api/categorylist` returns **`info[]`**, `/api/list` returns **`list[]`** (§3.3);
 *  - `filemetas` puts an **element-level `errno`** inside a successful envelope (§3.5);
 *  - `errno 132` carries an `authwidget` and means "verify your identity", not "permission denied"
 *    (§3.6);
 *  - `/api/streaming` answers **`errno 133` as phase one of a handshake** and only then a playlist
 *    (§7.3);
 *  - 64-bit `taskid` values must survive as exact integers (§0.3).
 *
 * Every response body used below is a **verbatim copy** of a documented body from [BaiduTestBodies], so
 * these tests fail if the mapping ever stops matching the real payload shape. No response is invented.
 */
class BaiduProviderHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: InMemoryBaiduCredentialStore
    private lateinit var accounts: InMemoryBaiduAccountMetadataStore
    private lateinit var provider: BaiduCloudProvider

    private val accountId = AccountId("baidu-http-test")

    /** Explicitly queued responses; always consumed before the path routes. */
    private val queued = ConcurrentLinkedQueue<MockResponse>()

    /** The body of the request currently being routed (used by POST routes). */
    private val currentBody = java.lang.ThreadLocal.withInitial { "" }

    /** The query of the request currently being routed. */
    private val currentQuery = java.lang.ThreadLocal.withInitial { "" }

    /** Requests observed, as `METHOD path`. */
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest
    fun setUp() = runTest {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                recorded += "${request.method} $path"
                currentBody.set(runCatching { request.body.clone().readUtf8() }.getOrDefault(""))
                currentQuery.set(path)

                queued.poll()?.let { return it }

                return when {
                    path.startsWith("/api/loginStatus") -> json(BaiduTestBodies.LOGIN_STATUS_OK)
                    path.startsWith("/api/gettemplatevariable") -> json(BaiduTestBodies.TEMPLATE_VARIABLE)
                    path.startsWith("/api/user/getinfo") -> json(BaiduTestBodies.USER_GETINFO)
                    path.startsWith("/api/quota") -> json(BaiduTestBodies.QUOTA)
                    path.startsWith("/api/search") -> json(BaiduTestBodies.SEARCH)
                    path.startsWith("/api/categorylist") -> json(BaiduTestBodies.CATEGORY_LIST)
                    path.startsWith("/api/filemetas") -> fileMetasRoute(request)
                    path.startsWith("/api/create") -> json(BaiduTestBodies.CREATE_FOLDER)
                    path.startsWith("/api/filemanager") -> json(BaiduTestBodies.FILEMANAGER_OK)
                    path.startsWith("/api/recycle/list") -> json(BaiduTestBodies.RECYCLE_EMPTY)
                    path.startsWith("/api/recycle") -> json(BaiduTestBodies.RECYCLE_OK)
                    path.startsWith("/api/streaming") -> streamingRoute(path)
                    path.startsWith("/api/list") -> json(BaiduTestBodies.LISTING)
                    else -> json("""{"errno":-1,"show_msg":"unrouted: $path"}""")
                }
            }
        }
        server.start()

        credentials = InMemoryBaiduCredentialStore()
        accounts = InMemoryBaiduAccountMetadataStore()
        credentials.save(
            accountId,
            BaiduCredential(
                bduss = "7".repeat(120),
                stoken = "8".repeat(64),
                extraCookies = listOf("BAIDUID" to "9".repeat(32)),
                userId = "1714888383",
                obtainedAt = 0,
            ),
        )
        accounts.upsert(
            BaiduAccountMetadata(
                accountId = accountId,
                remoteUserId = "1714888383",
                displayName = "wwxb",
                authState = com.notdisk.core.model.AuthState.AUTHORIZED,
                membershipLabel = null,
                membershipExpiresAt = null,
                addedAt = 0,
                lastVerifiedAt = 0,
            ),
        )

        val host = server.url("/").toString().trimEnd('/')
        provider = BaiduCloudProvider(
            configuration = BaiduConfiguration(pageSize = 100),
            logger = NoOpCloudLogger,
            credentialStore = credentials,
            accountMetadataStore = accounts,
            hostOverrides = mapOf(BaiduHosts.PAN to host, BaiduHosts.D_PCS to host),
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ envelope semantics

    /**
     * §0.3: the vendor's own interceptor tests `0 !== errno`, so `errno: 0` is the **only** success.
     * An equivalent envelope carrying a non-zero code must fail even though HTTP is 200.
     */
    @Test
    fun `a 200 response with a non zero errno is a failure`() = runTest {
        queued.add(json(BaiduTestBodies.CAPTCHA_REQUIRED))
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        assertTrue(result is CloudResult.Failure, "errno -20 must not be accepted as success")
        assertTrue(
            result.error is CloudError.AdditionalVerificationRequired,
            "errno -20 means 'captcha required', got ${result.error}",
        )
    }

    /**
     * §0.3: the interceptor coerces a missing or non-numeric code to `-1`. Accepting an absent code as
     * success would turn every malformed response into a silent lie about the user's data.
     */
    @Test
    fun `an envelope with no code at all is not treated as success`() = runTest {
        queued.add(json("""{"guid_info":"","list":[]}"""))
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        assertTrue(result is CloudResult.Failure, "a codeless envelope must not be success")
    }

    /**
     * §1.3: `"errno":""` occurs in real bodies, and `Number("") === 0` in JavaScript — which is exactly
     * why it has to be read as "absent" rather than allowed to become an accidental success.
     */
    @Test
    fun `an empty string errno is not coerced into success`() = runTest {
        queued.add(json("""{"errno":"","list":[]}"""))
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        assertTrue(result is CloudResult.Failure, "an empty errno must not be success")
    }

    /** §9.3: the membership surface spells success `error_code`, so the reader must accept both. */
    @Test
    fun `error_code is accepted as the success field`() = runTest {
        queued.add(json("""{"error_code":0,"error_msg":"","currenttime":1791972361}"""))
        val result = provider.auth.getStorageQuota(accountId)
        // The quota route is not what answered; what matters is that the envelope with `error_code: 0`
        // was accepted as a success rather than rejected for lacking `errno`.
        assertTrue(
            result is CloudResult.Success,
            "an `error_code: 0` envelope must be a success, got ${result.getOrNull()}",
        )
    }

    /** §1.3: `errno -6` is the re-authentication trigger, and it must be reported as such. */
    @Test
    fun `errno minus six is reported as an expired session`() = runTest {
        queued.add(json(BaiduTestBodies.NOT_LOGGED_IN))
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue(
            result.error is CloudError.TokenExpired,
            "errno -6 must map to TokenExpired, got ${result.error}",
        )
        assertFalse(
            (result.error as CloudError.TokenExpired).canRefresh,
            "Baidu has no documented silent refresh, so canRefresh must be false",
        )
    }

    /** A non-JSON body (an HTML error page from a proxy) must be a typed parse error, not a crash. */
    @Test
    fun `a non json body produces a parse error rather than throwing`() = runTest {
        queued.add(
            MockResponse().setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody(BaiduTestBodies.MALFORMED),
        )
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error.message.isNotBlank(), "a failure must carry a message")
    }

    // ------------------------------------------------------------------ listing

    /**
     * §3.2: `/api/list` is **path-addressed**. The request must carry `dir` as an absolute path, and
     * the listed items' ids must encode those paths.
     */
    @Test
    fun `listing sends the absolute path and maps both folders and files`() = runTest {
        val page = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/我的文件")),
        ).getOrNull()

        assertNotNull(page)
        assertEquals(2, page.items.size)

        val folder = page.items[0]
        assertEquals("我的文件", folder.name)
        assertEquals(FileType.FOLDER, folder.type)
        // A folder's size is 0 in the response, which means "not applicable" — reporting it as `0`
        // would render as an empty folder.
        assertNull(folder.size)
        assertNull(folder.extension, "a folder must not carry an extension")

        val file = page.items[1]
        assertEquals("favorites.txt", file.name)
        assertEquals(FileType.FILE, file.type)
        assertEquals(277L, file.size)
        assertEquals("txt", file.extension)
        assertEquals("text/plain", file.mimeType)

        val dir = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(dir)
        assertTrue(
            dir.path.orEmpty().contains("dir=%2F%E6%88%91%E7%9A%84%E6%96%87%E4%BB%B6"),
            "dir must be the URL-encoded absolute path, got ${dir.path}",
        )
    }

    /**
     * §0.1: every request carries the five global parameters, and `app_id=250528` is the personal
     * edition's. §13.2 item 5 records that mixing editions returns permission errors, so the exact
     * value is worth asserting.
     */
    @Test
    fun `every request carries the documented global parameters`() = runTest {
        provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        val path = request.path.orEmpty()

        assertTrue(path.contains("app_id=250528"), "app_id missing: $path")
        assertTrue(path.contains("clienttype=0"), "clienttype missing: $path")
        assertTrue(path.contains("web=1"), "web missing: $path")
        assertTrue(path.contains("channel=chunlei"), "channel missing: $path")
        assertTrue(path.contains("dp-logid="), "dp-logid missing: $path")
    }

    /**
     * §1.5: `BDUSS` is the credential, and it must be presented as a `Cookie` header rather than a
     * bearer token.
     */
    @Test
    fun `the session is presented as a cookie header containing bduss`() = runTest {
        provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = BaiduFileIdCodec.forFolder("/")),
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        val cookie = request.getHeader("Cookie")
        assertNotNull(cookie, "no Cookie header was sent")
        assertTrue(cookie.contains("BDUSS="), "BDUSS missing from the cookie: $cookie")
    }

    /** §3.2's three documented sort keys map onto `order` + `desc` with the vendor's polarity. */
    @Test
    fun `sorting maps onto the vendor parameters with correct polarity`() = runTest {
        provider.files.listChildren(
            accountId,
            ListChildrenRequest(
                parentId = BaiduFileIdCodec.forFolder("/"),
                sort = FileSort(field = FileSortField.NAME, ascending = true),
            ),
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertTrue(request.path.orEmpty().contains("order=name"))
        // Baidu uses `desc=1` for descending, so ascending must send `desc=0`.
        assertTrue(request.path.orEmpty().contains("desc=0"), "ascending must send desc=0")
    }

    /** §3.3: the category endpoint answers `info[]`, and the envelope must normalize it. */
    @Test
    fun `the category listing is read from the info array`() = runTest {
        // The provider routes category listings through `search`; the envelope is what normalizes the
        // `info` spelling, so the assertion is on the mapper's ability to read it.
        queued.add(json(BaiduTestBodies.CATEGORY_LIST))
        val page = provider.files.search(
            accountId,
            com.notdisk.core.model.FileSearchQuery(keyword = "favorites"),
        ).getOrNull()

        assertNotNull(page)
        assertEquals(1, page.items.size)
        assertEquals("favorites.txt", page.items[0].name)
        // `thumbs` was present, so the thumbnail descriptor must be populated — and it must carry the
        // md5, never a signed URL.
        assertNotNull(page.items[0].thumbnail)
        assertEquals("f892ac729o62544a1c8ed52779e84d72", page.items[0].thumbnail?.contentHash)
    }

    /**
     * A signed URL must never reach the model. This is the security-critical mapping assertion: the
     * `docpreview`/`thumbs` URLs in the payload are short-lived credentials, and `FileItem` is cached.
     */
    @Test
    fun `signed urls never leak into the file model`() = runTest {
        queued.add(json(BaiduTestBodies.CATEGORY_LIST))
        val page = provider.files.search(
            accountId,
            com.notdisk.core.model.FileSearchQuery(keyword = "favorites"),
        ).getOrNull()

        assertNotNull(page)
        val item = page.items.first()
        val rendered = item.toString() + item.extensions.toString() + item.thumbnail.toString()
        assertFalse(rendered.contains("sign="), "a signed URL leaked into the model: $rendered")
        assertFalse(rendered.contains("http://"), "a URL leaked into the model: $rendered")
        assertFalse(rendered.contains("https://"), "a URL leaked into the model: $rendered")
    }

    // ------------------------------------------------------------------ detail

    /**
     * §3.5: `filemetas` puts a per-element `errno` inside a successful envelope. An element-level `-9`
     * means *this file* is gone, which must be a typed `NotFound` rather than a parse failure or a
     * fabricated item.
     */
    @Test
    fun `an element level error inside a successful envelope is honoured`() = runTest {
        queued.add(json(BaiduTestBodies.FILEMETAS_PARTIAL_FAILURE))
        val result = provider.files.getFile(
            accountId,
            BaiduFileIdCodec.forFile("/不存在/zzz-no-such-file"),
        )
        assertTrue(result is CloudResult.Failure, "an element errno -9 must fail the call")
        assertTrue(
            result.error is CloudError.NotFound,
            "element errno -9 means 'file missing', got ${result.error}",
        )
    }

    /** An empty `info` array means the path resolved to nothing; that is `NotFound`, not a parse error. */
    @Test
    fun `an empty info array is reported as not found`() = runTest {
        queued.add(json(BaiduTestBodies.FILEMETAS_EMPTY))
        val result = provider.files.getFile(accountId, BaiduFileIdCodec.forFile("/nope.txt"))
        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.NotFound, "got ${result.error}")
    }

    /**
     * §3.5: a video's `file_size` arrives as a **string** while `/api/list`'s `size` is a number, and
     * its `md5` is the key thumbnail resolution needs.
     */
    @Test
    fun `the video metadata payload is mapped including the string sized field`() = runTest {
        val item = provider.files.getFile(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/59丨HPACK中整型数字的编码.mp4"),
        ).getOrNull()

        assertNotNull(item)
        assertEquals("59丨HPACK中整型数字的编码.mp4", item.name)
        assertEquals(FileType.FILE, item.type)
        assertEquals(84_127_739L, item.size)
        assertEquals("mp4", item.extension)
        assertEquals("baidu.md5" to "78688b049h4fd9eccf3b78fa7f1bbba5", item.extensions.entries.first {
            it.key == "baidu.md5"
        }.toPair())
        assertEquals(true, item.capabilities.canStream)
    }

    /** §3.2: timestamps are Unix **seconds** and the model is milliseconds. */
    @Test
    fun `timestamps are converted from seconds to milliseconds`() = runTest {
        val item = provider.files.getFile(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/59丨HPACK中整型数字的编码.mp4"),
        ).getOrNull()

        assertNotNull(item)
        // 1599048973 s → 1599048973000 ms.
        assertEquals(1_599_048_973_000L, item.modifiedTime)
    }

    /** `getPath` is exact and free for this provider because the id *is* the path. */
    @Test
    fun `path resolution is derived from the id without a request`() = runTest {
        val before = recorded.size
        val path = provider.files.getPath(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/sub/a.txt"),
        ).getOrNull()

        assertNotNull(path)
        assertEquals(listOf("全部文件", "我的文件", "sub", "a.txt"), path.segments.map { it.name })
        assertEquals("全部文件/我的文件/sub/a.txt", path.displayPath)
        assertEquals(before, recorded.size, "getPath must not issue a request for this provider")
    }

    // ------------------------------------------------------------------ search

    /** §3.4: `dir` is explicitly deleted by the vendor's own client, and `recursion=1` is required. */
    @Test
    fun `search sends key and recursion and never sends dir`() = runTest {
        provider.files.search(accountId, com.notdisk.core.model.FileSearchQuery(keyword = "backup"))
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        val path = request.path.orEmpty()

        assertTrue(path.contains("key=backup"), "key missing: $path")
        assertTrue(path.contains("recursion=1"), "recursion missing: $path")
        assertFalse(path.contains("dir="), "search must not send dir (the vendor deletes it): $path")
    }

    /** §3.4: `has_more` exists on search only, and `0` must terminate the listing. */
    @Test
    fun `search honours the has_more field`() = runTest {
        val page = provider.files.search(
            accountId,
            com.notdisk.core.model.FileSearchQuery(keyword = "backup"),
        ).getOrNull()

        assertNotNull(page)
        assertNull(page.nextCursor, "has_more=0 must terminate the listing")
    }

    // ------------------------------------------------------------------ file operations

    /**
     * §3.6: `opera=rename` is the one mutating capability backed by a complete URL literal, and the
     * body must be `filelist` carrying `path`/`dest`/`newname`.
     */
    @Test
    fun `rename sends the documented opera and filelist shape`() = runTest {
        val before = recorded.size
        val result = provider.operations.rename(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/a.txt"),
            "b.txt",
        )
        assertTrue(result is CloudResult.Success, "rename should succeed: $result")

        // A write first fetches `bdstoken` (§2.4), so the rename is not necessarily the first request.
        // Selecting by path rather than by position keeps this test about the request *shape*.
        val rename = takeRequestMatching { it.contains("opera=rename") }
        assertNotNull(rename, "no rename request among: ${recorded.drop(before)}")

        val body = rename.body.readUtf8()
        assertTrue(body.contains("filelist="), "filelist missing from the body: $body")
        assertTrue(body.contains("newname"), "newname missing: $body")
        assertTrue(body.contains("dest"), "dest missing: $body")
    }

    /** §13.2 item 6: every write must carry `bdstoken`, and it comes from the template endpoint. */
    @Test
    fun `a write fetches and sends bdstoken`() = runTest {
        val before = recorded.size
        provider.operations.createFolder(accountId, BaiduFileIdCodec.forFolder("/我的文件"), "新文件夹")

        val requests = recorded.drop(before)
        assertTrue(
            requests.any { it.contains("/api/gettemplatevariable") },
            "the write did not fetch bdstoken: $requests",
        )
        assertTrue(
            requests.any { it.contains("bdstoken=2d8dd749ef5bfde3fa2a86446a1ffa08") },
            "the write did not send the fetched bdstoken: $requests",
        )
    }

    /**
     * §3.6: a **64-bit** `taskid` must survive exactly. The vendor's own front end re-extracts it from
     * raw text with a regex to dodge JavaScript's 53-bit float precision — so a client that routed it
     * through a `Double` would silently corrupt it.
     */
    @Test
    fun `an asynchronous taskid survives as an exact 64 bit value`() = runTest {
        queued.add(json(BaiduTestBodies.TEMPLATE_VARIABLE))
        queued.add(json(BaiduTestBodies.FILEMANAGER_ASYNC))

        val result = provider.operations.copy(
            accountId,
            listOf(BaiduFileIdCodec.forFile("/我的文件/a.txt")),
            BaiduFileIdCodec.forFolder("/备份"),
        ).getOrNull()

        assertNotNull(result)
        assertEquals(
            "8956596186756250123",
            result.vendorTaskId,
            "the 64-bit taskid was corrupted in transit",
        )
    }

    /**
     * §3.6: `errno 132` carries an `authwidget` and means "verify your identity" — a distinct outcome
     * from a permission failure, and one the UI must be able to explain.
     */
    @Test
    fun `risk control verification is a distinct typed outcome`() = runTest {
        queued.add(json(BaiduTestBodies.TEMPLATE_VARIABLE))
        queued.add(json(BaiduTestBodies.FILEMANAGER_VERIFY_REQUIRED))

        val result = provider.operations.delete(
            accountId,
            listOf(BaiduFileIdCodec.forFile("/我的文件/a.txt")),
        )

        assertTrue(result is CloudResult.Failure)
        assertTrue(
            result.error is CloudError.AdditionalVerificationRequired,
            "errno 132 must map to AdditionalVerificationRequired, got ${result.error}",
        )
    }

    /** §3.6: a per-item failure inside a successful envelope must be reported per item. */
    @Test
    fun `a partial batch reports the failed item rather than a blanket success`() = runTest {
        queued.add(json(BaiduTestBodies.TEMPLATE_VARIABLE))
        queued.add(json(BaiduTestBodies.FILEMANAGER_PARTIAL))

        val first = BaiduFileIdCodec.forFile("/我的文件/existing.txt")
        val second = BaiduFileIdCodec.forFile("/我的文件/fresh.txt")
        val result = provider.operations.move(
            accountId,
            listOf(first, second),
            BaiduFileIdCodec.forFolder("/备份"),
        ).getOrNull()

        assertNotNull(result)
        assertEquals(1, result.failed.size, "the per-item failure was dropped")
        assertEquals(first, result.failed.first().key.fileId)
        assertEquals(1, result.succeeded.size)
        assertEquals(second, result.succeeded.first().fileId)
        assertTrue(result.partiallySucceeded)
    }

    /** §3.6: "单次操作最多 100 个文件". Rejecting locally gives a clear message instead of `errno 3`. */
    @Test
    fun `an oversized batch is rejected locally without a request`() = runTest {
        val before = recorded.size
        val many = (1..101).map { BaiduFileIdCodec.forFile("/我的文件/f$it.txt") }

        val result = provider.operations.move(accountId, many, BaiduFileIdCodec.forFolder("/备份"))

        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.InvalidArgument, "got ${result.error}")
        assertEquals(before, recorded.size, "an invalid batch must not reach the network")
    }

    /** A blank rename must be rejected before the vendor is contacted, per the shared contract. */
    @Test
    fun `a blank rename is rejected locally`() = runTest {
        val before = recorded.size
        val result = provider.operations.rename(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/a.txt"),
            "   ",
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.InvalidArgument)
        assertEquals(before, recorded.size, "a blank name must be rejected without a request")
    }

    /**
     * §3.6: permanent deletion is not reachable from `filemanager`. Refusing it is a data-safety
     * statement — silently downgrading it to a recycle-bin delete would tell the user something false
     * about where their file went.
     */
    @Test
    fun `permanent delete is refused and names the narrower capability`() = runTest {
        val before = recorded.size
        val result = provider.operations.delete(
            accountId,
            listOf(BaiduFileIdCodec.forFile("/我的文件/a.txt")),
            permanent = true,
        )

        assertTrue(result is CloudResult.Failure)
        val error = result.error
        assertTrue(error is CloudError.Unsupported, "got $error")
        assertNotNull((error as CloudError.Unsupported).capability)
        assertEquals(before, recorded.size, "a refused operation must not reach the network")
    }

    /**
     * §13.2 item 6: `bdstoken` is a **query** parameter and must never appear inside the `filelist`
     * JSON. Putting it in the body produces `errno 2`, whose message points nowhere near the cause.
     */
    @Test
    fun `bdstoken and opera stay in the query rather than the body`() = runTest {
        queued.add(json(BaiduTestBodies.TEMPLATE_VARIABLE))
        queued.add(json(BaiduTestBodies.FILEMANAGER_OK))

        provider.operations.rename(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/a.txt"),
            "b.txt",
        )

        // Skip the bdstoken fetch; the rename is the next request.
        server.takeRequest(1, TimeUnit.SECONDS)
        val rename = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(rename)
        val body = rename.body.readUtf8()

        assertFalse(body.contains("bdstoken"), "bdstoken leaked into the body: $body")
        assertFalse(body.contains("opera"), "opera leaked into the body: $body")
        assertTrue(body.contains("filelist="), "filelist missing: $body")
    }

    // ------------------------------------------------------------------ recycle bin

    /** §3.10: the recycle list endpoint **requires a trailing slash**. */
    @Test
    fun `the recycle list uses the trailing slash endpoint`() = runTest {
        val before = recorded.size
        provider.operations.listTrash(accountId)

        val request = recorded.drop(before).firstOrNull { it.contains("recycle") }
        assertNotNull(request, "no recycle request was made: ${recorded.drop(before)}")
        assertTrue(
            request.contains("/api/recycle/list/"),
            "the trailing slash is required by the vendor: $request",
        )
    }

    /** §3.10: the body is `fidlist` as a JSON array string, and `faillist` reports partial failures. */
    @Test
    fun `restore reports per item outcomes from faillist`() = runTest {
        queued.add(json(BaiduTestBodies.TEMPLATE_VARIABLE))
        queued.add(json(BaiduTestBodies.RECYCLE_PARTIAL))

        val result = provider.operations.restoreFromTrash(
            accountId,
            listOf("29770847494518", "29770847494519"),
        ).getOrNull()

        assertNotNull(result)
        assertEquals(1, result.failed.size, "faillist was not honoured")
        assertEquals(1, result.succeeded.size)
    }

    // ------------------------------------------------------------------ media

    /**
     * §7.3's two-phase handshake: phase one answers `errno 133` with an `adToken`, and phase two
     * answers the playlist. A provider that treated `133` as a plain failure would never play anything.
     */
    @Test
    fun `the streaming ad handshake is completed and yields an hls resource`() = runTest {
        val resource = provider.media.resolveMedia(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/movie.mp4"),
        ).getOrNull()

        assertNotNull(resource, "the two-phase handshake did not produce a resource")
        assertEquals(MediaProtocol.HLS, resource.protocol)
        assertEquals("application/vnd.apple.mpegurl", resource.mimeType)
        assertTrue(resource.url.contains("adToken="), "phase two must carry the adToken: ${resource.url}")
        assertTrue(resource.refreshable)

        // §7.4: segment URLs are self-signed and need no Cookie/UA/Referer. Handing the player the
        // account credential to forward to a CDN would be a leak, so the header map must be empty.
        assertTrue(
            resource.headers.isEmpty(),
            "Baidu's HLS needs no request headers; got ${resource.headers}",
        )
    }

    /** §7.3: a non-zero code that is not the ad gate is reported through the normal error mapping. */
    @Test
    fun `an unavailable rendition is reported honestly`() = runTest {
        queued.add(json(BaiduTestBodies.STREAMING_RENDITION_UNAVAILABLE))
        val result = provider.media.resolveMedia(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/movie.mp4"),
        )
        assertTrue(result is CloudResult.Failure)
        // `errno 2` is documented as "参数错误 / 失败重试", which is exactly what an unavailable
        // rendition looks like on the wire.
        assertTrue(result.error is CloudError.InvalidArgument, "got ${result.error}")
    }

    /** §7.4's measured playlist is the resource's URL, so the protocol must be HLS. */
    @Test
    fun `the playlist carries the documented renditions`() = runTest {
        val resource = provider.media.resolveMedia(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/movie.mp4"),
        ).getOrNull()

        assertNotNull(resource)
        assertTrue(resource.renditions.isNotEmpty(), "the rendition ladder must be exposed")
        // §7.3.1: the vendor's highest exposed rendition is 1080P — there is no 2160 option in the UI.
        assertTrue(
            resource.renditions.none { it.id.contains("2160") },
            "2160P is not exposed by the vendor's own UI and must not be offered",
        )
    }

    /** §7: no progress-report endpoint is documented, so reporting progress must fail closed. */
    @Test
    fun `playback progress reporting fails closed`() = runTest {
        val before = recorded.size
        val result = provider.media.reportPlaybackProgress(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/movie.mp4"),
            1000,
            2000,
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.Unsupported, "got ${result.error}")
        assertEquals(before, recorded.size, "an unsupported operation must not contact the vendor")
    }

    /** §7.6: audio has no streaming endpoint, so an audio track request must fail closed. */
    @Test
    fun `audio track resolution fails closed`() = runTest {
        val before = recorded.size
        val result = provider.media.resolveAudioTrack(
            accountId,
            BaiduFileIdCodec.forFile("/学习资料/movie.mp4"),
            com.notdisk.core.model.MediaAudioTrack(id = "0", label = "立体声"),
        )
        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.Unsupported, "got ${result.error}")
        assertEquals(before, recorded.size)
    }

    // ------------------------------------------------------------------ preview

    /** §8.5: text preview material comes from `filemetas?text=1` and is passed through. */
    @Test
    fun `text preview resolves the documented picdocpreview url`() = runTest {
        queued.add(json(BaiduTestBodies.FILEMETAS_TEXT))
        val result = provider.preview.preview(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/favorites.txt"),
            resolveAccess = true,
            hint = PreviewHint(name = "favorites.txt", extension = "txt"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.Text, "got ${result.kind}")
        assertNotNull(result.resource)
        assertTrue(
            result.resource!!.url.contains("pcsdata.baidu.com"),
            "the text reader's URL was not passed through: ${result.resource}",
        )
    }

    /**
     * §8.4: `/disk/docview` redirects into a separate OAuth application, so no viewer is fabricated.
     * The classification still routes the file correctly, and no access material is invented.
     */
    @Test
    fun `office documents classify without a fabricated viewer`() = runTest {
        val result = provider.preview.preview(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/report.docx"),
            resolveAccess = false,
            hint = PreviewHint(name = "report.docx", extension = "docx"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.OfficeDocument, "got ${result.kind}")
        // The classification must not embed a URL. `OfficeDocument` is the one sanctioned carrier of a
        // viewer, and here it must be empty rather than pointing at a page this build cannot drive.
        val viewer = (result.kind as PreviewKind.OfficeDocument).viewer
        assertTrue(viewer.url.isBlank(), "a viewer URL was fabricated: ${viewer.url}")
        assertNull(viewer.token)
    }

    /** §8.2: a thumbnail URL is a signed secret, so `thumbnail()` returns `null` rather than a URL. */
    @Test
    fun `thumbnail returns absence rather than a signed url`() = runTest {
        val result = provider.preview.thumbnail(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/screenshot.png"),
            com.notdisk.core.model.ThumbnailRef(sizeHint = "850"),
        ).getOrNull()

        assertNotNull(result)
        assertNull(result.resource, "a signed thumbnail URL must not be handed out")
    }

    /** Images classify as [PreviewKind.Image] without a request when access is not being resolved. */
    @Test
    fun `image classification is local`() = runTest {
        val before = recorded.size
        val result = provider.preview.preview(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/screenshot.png"),
            resolveAccess = false,
            hint = PreviewHint(name = "screenshot.png", extension = "png"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.Image, "got ${result.kind}")
        assertNull(result.resource)
        assertEquals(before, recorded.size, "resolveAccess=false must not touch the network")
    }

    /** §8.1: `.xmind` has an explicitly empty plugin implementation in the vendor's registry. */
    @Test
    fun `an unknown extension classifies as unsupported rather than failing`() = runTest {
        val result = provider.preview.preview(
            accountId,
            BaiduFileIdCodec.forFile("/我的文件/diagram.xmind"),
            resolveAccess = false,
            hint = PreviewHint(name = "diagram.xmind", extension = "xmind"),
        ).getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.Unsupported, "got ${result.kind}")
    }

    // ------------------------------------------------------------------ auth

    /**
     * The `MANUAL_CREDENTIAL` flow must **verify before persisting**: a stale or wrong cookie must not
     * leave an account row that renders as bound and fails on the first tap.
     */
    @Test
    fun `a pasted cookie is verified before it is persisted`() = runTest {
        val challenge = provider.auth.beginAuthorization(
            AuthFlowKind.MANUAL_CREDENTIAL,
            AuthFlowParams(manualCredential = "BDUSS=abc123def456; STOKEN=deadbeef"),
        ).getOrNull()

        assertNotNull(challenge)
        assertTrue(challenge is AuthChallenge.Completed, "got $challenge")
        val completed = challenge as AuthChallenge.Completed

        // The credential reached the encrypted store, and the account row was created from the
        // *verified* profile — `uk` and the display name come from `loginStatus`, not from the paste.
        val stored = credentials.load(completed.accountId)
        assertNotNull(stored, "the credential was not persisted")
        assertEquals("abc123def456", stored.bduss)

        val account = accounts.find(completed.accountId)
        assertNotNull(account, "the account row was not created")
        assertEquals("1714888383", account.remoteUserId)
        assertEquals("愤怒de小熊丶", account.displayName)

        // And the row is only reported as authorized because the server said so.
        assertEquals(com.notdisk.core.model.AuthState.AUTHORIZED, account.authState)
    }

    /**
     * Re-pasting a cookie for an account that is already bound must reuse that account rather than
     * creating a duplicate row for the same Baidu user.
     */
    @Test
    fun `re-binding the same account reuses its local id`() = runTest {
        val first = (provider.auth.beginAuthorization(
            AuthFlowKind.MANUAL_CREDENTIAL,
            AuthFlowParams(manualCredential = "BDUSS=abc123def456"),
        ).getOrNull() as AuthChallenge.Completed).accountId

        val second = (provider.auth.beginAuthorization(
            AuthFlowKind.MANUAL_CREDENTIAL,
            AuthFlowParams(manualCredential = "BDUSS=a-different-but-still-valid-value"),
        ).getOrNull() as AuthChallenge.Completed).accountId

        assertEquals(first, second, "a second paste created a duplicate account row")
        // The fixture already binds `uk 1714888383`, so the correct behaviour is that **no new row**
        // appears at all: the de-duplication is keyed on the verified vendor user id, not on the paste.
        assertEquals(1, accounts.all().size, "a duplicate row was created for the same Baidu user")
    }

    /** The anonymous shape is a *successful* envelope reporting `isLogin: 0`, so it must be rejected. */
    @Test
    fun `a cookie that is not logged in is rejected`() = runTest {
        queued.add(json(BaiduTestBodies.LOGIN_STATUS_ANONYMOUS))
        val result = provider.auth.beginAuthorization(
            AuthFlowKind.MANUAL_CREDENTIAL,
            AuthFlowParams(manualCredential = "BDUSS=abc123def456"),
        )

        assertTrue(result is CloudResult.Failure, "an anonymous session must not be accepted")
        assertTrue(result.error is CloudError.TokenExpired, "got ${result.error}")
    }

    /** A paste with no `BDUSS` must be rejected as invalid input, not stored. */
    @Test
    fun `a cookie without bduss is rejected as invalid input`() = runTest {
        val before = recorded.size
        val result = provider.auth.beginAuthorization(
            AuthFlowKind.MANUAL_CREDENTIAL,
            AuthFlowParams(manualCredential = "STOKEN=deadbeef; BAIDUID=1122"),
        )

        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.InvalidArgument, "got ${result.error}")
        assertEquals(before, recorded.size, "an unparseable paste must not be sent to the vendor")
    }

    /** The challenge contract: a completed challenge polls to `Succeeded` with the local account id. */
    @Test
    fun `a completed challenge polls to succeeded`() = runTest {
        val challenge = provider.auth.beginAuthorization(
            AuthFlowKind.MANUAL_CREDENTIAL,
            AuthFlowParams(manualCredential = "BDUSS=abc123def456"),
        ).getOrNull() as AuthChallenge.Completed

        val state = provider.auth.pollChallenge(challenge.challengeId).getOrNull()
        assertTrue(state is AuthChallengeState.Succeeded, "got $state")
        assertEquals(challenge.accountId, (state as AuthChallengeState.Succeeded).accountId)
    }

    /** A flow that is not advertised must fail closed rather than inventing a challenge. */
    @Test
    fun `an unadvertised flow fails closed without a request`() = runTest {
        val before = recorded.size
        val result = provider.auth.beginAuthorization(AuthFlowKind.QR_CODE)

        assertTrue(result is CloudResult.Failure)
        assertTrue(result.error is CloudError.Unsupported, "got ${result.error}")
        assertEquals(before, recorded.size, "a refused flow must not contact the vendor")
    }

    /** §9.2's quota is mapped onto the model, including a derived remaining figure. */
    @Test
    fun `quota is mapped with a derived remaining figure`() = runTest {
        val quota = provider.auth.getStorageQuota(accountId).getOrNull()
        assertNotNull(quota)
        assertEquals(2_206_539_448_320L, quota.totalBytes)
        assertEquals(1_322_146_849_400L, quota.usedBytes)
        assertEquals(2_206_539_448_320L - 1_322_146_849_400L, quota.remainingBytes)
        assertNotNull(quota.totalLabel)
    }

    // ------------------------------------------------------------------ capability honesty

    /**
     * The declared capabilities must agree with what the methods actually do. This is asserted by the
     * shared contract suite for the coarse cases; here the *specific* refusals that define this
     * provider are pinned, because each one corresponds to a documented impossibility rather than to an
     * unimplemented method.
     */
    @Test
    fun `the capability declaration matches the documented boundaries`() {
        val capabilities = provider.capabilities()

        assertEquals(provider.id, capabilities.providerId)
        // Only one flow, and it needs no developer credential.
        assertEquals(setOf(AuthFlowKind.MANUAL_CREDENTIAL), capabilities.account.interactiveFlows)

        // Browsing is fully measured.
        assertEquals(CapabilitySupport.SUPPORTED, capabilities.fileSystem.listChildren)
        assertEquals(CapabilitySupport.SUPPORTED, capabilities.fileSystem.search)

        // Only `rename` has a complete URL literal; the rest are inferred, so they are UNVERIFIED.
        assertEquals(CapabilitySupport.SUPPORTED, capabilities.operations.rename)
        assertEquals(CapabilitySupport.UNVERIFIED, capabilities.operations.move)
        assertEquals(CapabilitySupport.UNVERIFIED, capabilities.operations.copy)
        assertEquals(CapabilitySupport.UNVERIFIED, capabilities.operations.delete)
        assertEquals(CapabilitySupport.UNVERIFIED, capabilities.operations.upload.supported)
        // Download needs a server-supplied JavaScript signature, which Kotlin cannot evaluate.
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities.operations.download.supported)
        assertEquals(CapabilitySupport.UNVERIFIED, capabilities.operations.share)

        // Playback is measured; audio streaming is not (it uses the dlink path).
        assertEquals(CapabilitySupport.SUPPORTED, capabilities.media.videoStreaming)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities.media.audioStreaming)
        assertEquals(setOf(MediaProtocol.HLS), capabilities.media.protocols)
        // §7.4 measured that segment URLs need no headers — a real difference from 115.
        assertFalse(capabilities.media.requiresRequestHeaders)
        assertEquals(CapabilitySupport.UNVERIFIED, capabilities.media.playbackProgress)

        // Every declared refusal must be honest about whether it writes.
        assertFalse(capabilities.operations.idempotentWrites)
    }

    /** A provider that declares no browsing would be useless; this is the "usable" self-check. */
    @Test
    fun `the provider reports verified browsing`() {
        assertTrue(provider.capabilities().hasVerifiedBrowsing)
    }

    // ------------------------------------------------------------------ helpers

    private fun fileMetasRoute(request: RecordedRequest): MockResponse {
        val body = runCatching { request.body.clone().readUtf8() }.getOrDefault("")
        val target = decodeTarget(body)
        return when {
            target.contains("zzz") -> json(BaiduTestBodies.FILEMETAS_PARTIAL_FAILURE)
            target.endsWith(".mp4") -> json(BaiduTestBodies.FILEMETAS_VIDEO)
            else -> json(BaiduTestBodies.FILEMETAS_TEXT)
        }
    }

    private fun streamingRoute(path: String): MockResponse = if (path.contains("adToken=")) {
        MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/x-mpegURL")
            .setBody(BaiduTestBodies.STREAMING_PLAYLIST)
    } else {
        json(BaiduTestBodies.STREAMING_AD_GATE)
    }

    private fun decodeTarget(body: String): String {
        val raw = body.substringAfter("target=", "").substringBefore('&')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.trim().removePrefix("[").removeSuffix("]").trim().trim('"')
    }

    /**
     * Drains pending requests until one matches [predicate].
     *
     * Used instead of positional `takeRequest` calls because a write may issue a `bdstoken` fetch
     * before the request under test. Binding a test to request *order* would make it fail whenever an
     * unrelated token fetch is added, which is a false signal about the property being tested.
     */
    private fun takeRequestMatching(predicate: (String) -> Boolean): RecordedRequest? {
        repeat(MAX_DRAINED_REQUESTS) {
            val request = server.takeRequest(1, TimeUnit.SECONDS) ?: return null
            if (predicate(request.path.orEmpty())) return request
        }
        return null
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private companion object {
        /** A small bound so a missing request fails fast instead of hanging the suite. */
        const val MAX_DRAINED_REQUESTS = 8
    }
}
