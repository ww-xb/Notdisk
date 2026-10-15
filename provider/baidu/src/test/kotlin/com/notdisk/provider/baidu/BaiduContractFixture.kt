package com.notdisk.provider.baidu

import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.FileId
import com.notdisk.core.provider.testing.ProviderContractFixture
import com.notdisk.provider.baidu.api.BaiduHosts
import com.notdisk.provider.baidu.auth.BaiduAccountMetadata
import com.notdisk.provider.baidu.auth.BaiduCredential
import com.notdisk.provider.baidu.auth.InMemoryBaiduAccountMetadataStore
import com.notdisk.provider.baidu.auth.InMemoryBaiduCredentialStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * The [ProviderContractFixture] the shared contract suite runs against for Baidu Netdisk.
 *
 * ### Why a fixture needs its own HTTP server
 * The contract suite is *offline by design*: it must run in CI without an account, without network
 * access and without rate limits. For a real provider that means the fixture has to supply a server.
 *
 * Rather than a hand-written stub, this fixture runs a [MockWebServer] that answers with the payloads
 * recorded in [BaiduTestBodies]. That way the contract suite exercises the same envelope parsing and
 * DTO mapping the production path uses — a stubbed-out mapper would validate nothing.
 *
 * ### Interpreting a result from this class
 * A pass means "given Baidu's documented payloads, this provider honours the shared contract". A
 * failure is a real architectural defect. Neither says anything about whether Baidu's service behaves
 * as documented today — see `docs/api-mapping.md`.
 */
internal class BaiduContractFixture private constructor(
    override val provider: BaiduCloudProvider,
    private val server: MockWebServer,
) : ProviderContractFixture {

    override val accountId: AccountId = AccountId("baidu-contract-account")

    /** A folder that lists successfully. */
    override val knownFolderId: FileId = BaiduFileIdCodec.forFolder("/我的文件")

    /** An id that must never resolve. The fixture answers `filemetas` with an element-level `-9`. */
    override val nonExistentFileId: FileId = BaiduFileIdCodec.forFile("/不存在/zzz-no-such-file")

    override val searchKeywordThatMatchesSomething: String = "backup"

    override val searchKeywordThatMatchesNothing: String = "zzz-no-such-file-zzz"

    override val paginationPageSize: Int = 1

    override val maxPaginationPages: Int = 20

    /**
     * `null`, and that is a factual statement: this provider declares `upload` UNVERIFIED and its
     * `upload` method returns `Unsupported` **without opening a stream**, so there is no readable URI
     * for the fixture to supply.
     */
    override val readableContentUriOrNull: String? = null

    override val knownVideoFileId: FileId =
        BaiduFileIdCodec.forFile("/学习资料/59丨HPACK中整型数字的编码.mp4")

    override val knownImageFileId: FileId = BaiduFileIdCodec.forFile("/我的文件/screenshot.png")

    override val knownNonMediaFileId: FileId = BaiduFileIdCodec.forFile("/我的文件/favorites.txt")

    override val knownUnpreviewableFileId: FileId =
        BaiduFileIdCodec.forFile("/我的文件/archive.7z")

    /** Requests observed by the fixture; several contract rules are *about* the request shape. */
    val recordedPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Explicitly queued responses; always consumed before the path routes. */
    private val responses = ConcurrentLinkedQueue<MockResponse>()

    fun enqueue(body: String, code: Int = 200, contentType: String = "application/json; charset=utf-8") {
        responses.add(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", contentType)
                .setBody(body),
        )
    }

    /**
     * Answers each request according to its **path**, so the fixture is order-independent.
     *
     * An order-dependent stub would make the suite fragile: a rule that happens to probe capabilities
     * in a different order would consume the wrong response and fail for a reason unrelated to the
     * property under test.
     */
    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                recordedPaths += "${request.method} $path"

                responses.poll()?.let { return it }

                return when {
                    path.startsWith("/api/loginStatus") -> json(BaiduTestBodies.LOGIN_STATUS_OK)
                    path.startsWith("/api/gettemplatevariable") -> json(BaiduTestBodies.TEMPLATE_VARIABLE)
                    path.startsWith("/api/user/getinfo") -> json(BaiduTestBodies.USER_GETINFO)
                    path.startsWith("/api/quota") -> json(BaiduTestBodies.QUOTA)
                    path.startsWith("/api/search") -> searchResponse(path)
                    path.startsWith("/api/categorylist") -> json(BaiduTestBodies.CATEGORY_LIST)
                    path.startsWith("/api/filemetas") -> fileMetasResponse(request)
                    path.startsWith("/api/create") -> json(BaiduTestBodies.CREATE_FOLDER)
                    path.startsWith("/api/filemanager") -> json(BaiduTestBodies.FILEMANAGER_OK)
                    path.startsWith("/api/recycle/list") -> json(BaiduTestBodies.RECYCLE_EMPTY)
                    path.startsWith("/api/recycle") -> json(BaiduTestBodies.RECYCLE_OK)
                    path.startsWith("/api/streaming") -> streamingResponse(path)
                    path.startsWith("/api/list") -> listingResponse(path)
                    else -> json("""{"errno":-1,"show_msg":"unrouted path: $path"}""")
                }
            }
        }
    }

    // ------------------------------------------------------------------ recorded responses

    /**
     * §3.2's listing shape, paginated by `page`/`num`.
     *
     * The fixture holds six files and answers `num` entries per page, which is what lets the shared
     * suite verify that the cursor advances and that pagination terminates. Baidu documents **no**
     * "has more" field, so the provider's terminating rule is "a short page ends the list" — and this
     * route is what proves it works.
     */
    private fun listingResponse(path: String): MockResponse {
        val query = path.substringAfter('?', "")
        val page = query.substringAfter("page=", "1").substringBefore('&').toIntOrNull() ?: 1
        val num = query.substringAfter("num=", "100").substringBefore('&').toIntOrNull() ?: 100

        val all = (1..6).map { index ->
            """{"server_filename":"file-$index.txt","category":4,"isdir":0,"size":${index * 100},
                "path":"/我的文件/file-$index.txt","fs_id":76122423351465$index,
                "md5":"f892ac729o62544a1c8ed52779e84d7$index","black_tag":0,
                "server_ctime":1746792011,"server_mtime":${1746792011 + index},
                "local_ctime":1746792006,"local_mtime":1746792006,
                "thumbs":{"url1":"https://thumbnail0.baidupcs.com/thumbnail/x?size=c140_u90"}}"""
        }
        val start = (page - 1) * num
        val pageItems = all.drop(start).take(num)

        return json(
            """
            {"errno":0,"guid_info":"","list":[${pageItems.joinToString(",")}],
             "request_id":8956370037768280427}
            """.trimIndent(),
        )
    }

    /** §3.4's search shape; a keyword containing `zzz` matches nothing. */
    private fun searchResponse(path: String): MockResponse {
        val keyword = java.net.URLDecoder.decode(
            path.substringAfter("key=", "").substringBefore('&'),
            "UTF-8",
        )
        return if (keyword.contains("zzz-no-such-file")) {
            json(BaiduTestBodies.SEARCH_EMPTY)
        } else {
            json(BaiduTestBodies.SEARCH)
        }
    }

    /**
     * §3.5's metadata route, branched on the requested `target`.
     *
     * The route has to answer *about the path it was asked for*, because the shared suite calls
     * `getFile` on an item it just listed and asserts the two agree on the name and type. A hard-coded
     * name would make that invariant untestable and report a false failure.
     */
    private fun fileMetasResponse(request: RecordedRequest): MockResponse {
        val body = runCatching { request.body.clone().readUtf8() }.getOrDefault("")
        val target = decodeTarget(body)

        return when {
            // The suite's designated non-existent id. An element-level `-9` is the documented shape.
            target.contains("zzz-no-such-file") -> json(BaiduTestBodies.FILEMETAS_PARTIAL_FAILURE)

            // The video id, which the suite uses for the media probe.
            target.endsWith(".mp4") -> json(BaiduTestBodies.FILEMETAS_VIDEO)

            // A text request (`text=1`) returns the text reader's material.
            request.path.orEmpty().contains("text=1") -> json(BaiduTestBodies.FILEMETAS_TEXT)

            // Anything else: answer about the requested path so `getFile` agrees with the listing.
            else -> json(metaForPath(target))
        }
    }

    /** Builds a `filemetas` answer that describes exactly the requested path. */
    private fun metaForPath(target: String): String {
        val name = target.substringAfterLast('/').ifEmpty { "root" }
        val isFolder = !name.contains('.')
        return """
            {"errno":0,"info":[
              {"errno":0,"server_filename":"$name","isdir":${if (isFolder) 1 else 0},
               "category":${if (isFolder) 6 else 4},
               "size":${if (isFolder) 0 else 277},
               "path":"$target","fs_id":761224233514659,
               "md5":"f892ac729o62544a1c8ed52779e84d72","black_tag":0,
               "server_ctime":1746792011,"server_mtime":1746792011,
               "local_ctime":1746792006,"local_mtime":1746792006,
               "thumbs":{}}],
             "request_id":8956495075706973695}
        """.trimIndent()
    }

    /** Pulls the single path out of the JSON-array `target` body field. */
    private fun decodeTarget(body: String): String {
        val raw = body.substringAfter("target=", "").substringBefore('&')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        // The value is a JSON array string such as `["/a/b.txt"]`; strip the wrapper.
        return decoded.trim().removePrefix("[").removeSuffix("]").trim().trim('"')
    }

    /**
     * §7.3's two-phase streaming route.
     *
     * Phase one (no `adToken`) answers the ad gate; phase two answers the playlist. That is the
     * documented handshake, and reproducing both phases is what lets the suite verify that the
     * provider completes it rather than reporting a failure.
     */
    private fun streamingResponse(path: String): MockResponse {
        val query = path.substringAfter('?', "")
        return if (query.contains("adToken=")) {
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-mpegURL")
                .setBody(BaiduTestBodies.STREAMING_PLAYLIST)
        } else {
            json(BaiduTestBodies.STREAMING_AD_GATE)
        }
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    fun shutdown() {
        server.shutdown()
    }

    companion object {
        /**
         * Builds a fully-wired provider whose every host points at a local recording server.
         *
         * Everything the suite needs to run offline is assembled here, which is what lets the *same*
         * `ProviderContractRules` class be reused unchanged for both providers.
         */
        fun build(): BaiduContractFixture {
            val server = MockWebServer().apply { start() }
            val host = server.url("/").toString().trimEnd('/')

            val credentials = InMemoryBaiduCredentialStore()
            val accounts = InMemoryBaiduAccountMetadataStore()
            val accountId = AccountId("baidu-contract-account")

            runBlocking {
                credentials.save(
                    accountId,
                    BaiduCredential(
                        // A structurally-complete credential. The value is not a real session: it is a
                        // placeholder of the documented length, because the fixture never reaches the
                        // real service.
                        bduss = "7".repeat(120),
                        stoken = "8".repeat(64),
                        extraCookies = listOf(
                            "BAIDUID" to "9".repeat(32),
                            "BIDUPSID" to "A".repeat(32),
                            "PSTM" to "1791956082",
                        ),
                        userId = "1714888383",
                        obtainedAt = 1_791_956_000_000,
                    ),
                )
                accounts.upsert(
                    BaiduAccountMetadata(
                        accountId = accountId,
                        remoteUserId = "1714888383",
                        displayName = "wwxb",
                        authState = AuthState.AUTHORIZED,
                        membershipLabel = null,
                        membershipExpiresAt = null,
                        addedAt = 1_791_956_000_000,
                        lastVerifiedAt = 1_791_956_000_000,
                    ),
                )
            }

            val provider = BaiduCloudProvider(
                configuration = BaiduConfiguration(pageSize = 100),
                logger = NoOpCloudLogger,
                credentialStore = credentials,
                accountMetadataStore = accounts,
                hostOverrides = mapOf(
                    BaiduHosts.PAN to host,
                    BaiduHosts.D_PCS to host,
                ),
                okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build(),
            )

            val fixture = BaiduContractFixture(provider, server)
            fixture.installDispatcher()
            return fixture
        }
    }
}
