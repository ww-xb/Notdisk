package com.notdisk.provider.p115

import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.FileId
import com.notdisk.core.provider.testing.ProviderContractFixture
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.P115Hosts
import com.notdisk.provider.p115.auth.InMemoryP115AccountMetadataStore
import com.notdisk.provider.p115.auth.InMemoryP115CredentialStore
import com.notdisk.provider.p115.auth.P115AccountMetadata
import com.notdisk.provider.p115.auth.P115Credential
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The [ProviderContractFixture] the shared contract suite runs against for 115.
 *
 * ### Why a fixture needs its own HTTP server
 * The contract suite is *offline by design*: it must be runnable in CI without an account, without
 * network access and without rate limits. For the Mock provider that is free. For a real provider it
 * means the fixture has to supply a server.
 *
 * Rather than a hand-written stub, this fixture runs a [MockWebServer] that answers with the payloads
 * recorded in `api_bodies.json`. That way the contract suite exercises the same DTO mapping the
 * production path uses — a stubbed-out mapper would validate nothing.
 *
 * ### Interpreting a result from this class
 * A pass means "given 115's documented payloads, this provider honours the shared contract". A
 * failure is a real architectural defect. Neither says anything about whether 115's service behaves
 * as documented today — see `docs/api-mapping.md`.
 */
internal class P115ContractFixture private constructor(
    override val provider: P115CloudProvider,
    private val server: MockWebServer,
) : ProviderContractFixture {

    override val accountId: AccountId = AccountId("p115-contract-account")

    /** A folder that lists successfully. */
    override val knownFolderId: FileId = P115FileIdCodec.forFolder("2590058519487052812")

    /** An id that must never resolve. The fixture answers `category/get` with an empty payload. */
    override val nonExistentFileId: FileId =
        P115FileIdCodec.forFile("9999999999999999999", "1")

    override val searchKeywordThatMatchesSomething: String = "cookies"

    override val searchKeywordThatMatchesNothing: String = "zzz-no-such-file-zzz"

    override val paginationPageSize: Int = 2

    override val maxPaginationPages: Int = 20

    override val readableContentUriOrNull: String? = "content://com.notdisk.test/document/1"

    override val knownVideoFileId: FileId =
        P115FileIdCodec.forFile("3433237498815791806", "1")

    override val knownImageFileId: FileId =
        P115FileIdCodec.forFile("3433237498815791830", "1")

    override val knownNonMediaFileId: FileId =
        P115FileIdCodec.forFile("3289386448728834697", "1")

    override val knownUnpreviewableFileId: FileId =
        P115FileIdCodec.forFile("3433237498815791831", "1")

    /** Recorded responses consumed in order by the dispatcher. */
    private val responses = ConcurrentLinkedQueue<MockResponse>()

    private fun enqueue(body: String, code: Int = 200) {
        responses.add(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json; charset=utf-8")
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
                recordRequest(path, request)

                // A queue entry always wins, so an individual test can override any route.
                responses.poll()?.let { return it }

                return when {
                    path.startsWith("/files/search") -> searchResponse(path)
                    path.startsWith("/files/getid") -> getidResponse(path)
                    path.startsWith("/category/get") -> categoryResponse(path)
                    path.startsWith("/files/get_info") -> getInfoResponse(path)
                    path.startsWith("/files/download") -> downloadResponse()
                    path.startsWith("/files/video") -> videoResponse()
                    path.startsWith("/files/image") -> imageResponse()
                    path.startsWith("/files/preview") -> previewResponse()
                    path.startsWith("/files/index_info") -> indexInfoResponse()
                    path.startsWith("/rb") -> trashResponse()
                    path.startsWith("/files") -> listingResponse(path)
                    // `my.115.com/?ct=ajax&ac=nav` — the session check.
                    path.contains("ac=nav") -> navResponse()
                    else -> json("""{"state":false,"errno":-1,"error":"unrouted path: $path"}""")
                }
            }
        }
    }

    /**
     * Requests observed by the fixture.
     *
     * Kept because several contract rules are *about the request shape* rather than the response —
     * e.g. "a blank rename must be rejected without contacting the vendor" is only provable by
     * showing that no request arrived.
     */
    val recordedPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun recordRequest(path: String, request: RecordedRequest) {
        recordedPaths += "${request.method} $path"
    }

    // ------------------------------------------------------------------ recorded responses

    /** §3.1's listing shape. Answers 2 items so a small page size exercises pagination. */
    private fun listingResponse(path: String): MockResponse {
        val offset = path.substringAfter("offset=", "0").substringBefore('&').toIntOrNull() ?: 0
        val limit = path.substringAfter("limit=", "40").substringBefore('&').toIntOrNull() ?: 40

        val all = (1..6).map { index ->
            """{"fid":"32893864487288346$index","cid":"2590058519487052812","n":"file-$index.txt",
               "s":${index * 100},"pc":"pickcode$index","sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A",
               "ico":"txt","te":"176214142$index","tp":"1762141420","is_top":0,"fl":[]}"""
        }
        val page = all.drop(offset).take(limit)

        return json(
            """
            {"state":true,"data":[${page.joinToString(",")}],"count":${all.size},
             "offset":$offset,"page_size":$limit,"order":"user_ptime","is_asc":0,
             "path":[{"cid":0,"name":"根目录"},{"cid":"2590058519487052812","name":"我的文件"}]}
            """.trimIndent(),
        )
    }

    /** §3.4's search shape, where `s`/`sta`/`t` are strings. */
    private fun searchResponse(path: String): MockResponse {
        val keyword = path.substringAfter("search_value=", "").substringBefore('&')
            .let { java.net.URLDecoder.decode(it, "UTF-8") }

        if (keyword.contains("zzz-no-such-file")) {
            return json("""{"count":0,"data":[],"page_size":5,"page_count":0,"offset":0,"state":true,"error":""}""")
        }

        return json(
            """
            {"count":1,"data":[{"fid":"3289386448728834697","aid":"1","cid":"3289386376326758547",
            "n":"cookies.txt","s":"2355","sta":"1","pt":"0","pc":"d6m6sqtfmezzyxqdm","fc":1,"p":0,
            "m":"0","t":"2025-11-03","te":"1762141421","tp":"1762141421","d":0,"c":"0","sh":"0",
            "e":"","ico":"txt","dp":"从赘婿到宠臣",
            "ns":"<b style='color: #FF0000;'>cookies<\/b>.txt",
            "sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A","q":0,"is_top":0}],
            "cost_time_es":0.068,"folder":{"name":"根目录","cid":"0","pid":"0"},"page_size":5,
            "page_count":1,"offset":0,"is_asc":0,"order":"file_size","state":true,"error":""}
            """.trimIndent(),
        )
    }

    /** §3.5: `id` at the top level; `0` means "not found". */
    private fun getidResponse(path: String): MockResponse {
        val wanted = java.net.URLDecoder.decode(
            path.substringAfter("path=", "").substringBefore('&'),
            "UTF-8",
        )
        return if (wanted.contains("不存在") || wanted.contains("zzz")) {
            json("""{"state":true,"error":"","errno":0,"id":"0","is_private":"0"}""")
        } else {
            json("""{"state":true,"error":"","errno":0,"id":"2590058519487052812","is_private":"0"}""")
        }
    }

    /**
     * §3.2: a flat payload with a `paths` chain.
     *
     * ### Why the name is derived rather than fixed
     * The shared suite lists a folder and then calls `getFile` on an item from that page, asserting
     * that the two agree on the name and type. That is a genuine invariant the UI relies on (a row
     * must not change identity when opened), so this route has to answer *about the id it was asked
     * for*. A hard-coded name — as an earlier version used — made the invariant untestable and
     * reported a false failure.
     */
    private fun categoryResponse(path: String): MockResponse {
        val cid = path.substringAfter("cid=", "").substringBefore('&')

        // The suite probes a non-existent id; a payload with no `file_name` must produce a typed
        // `NotFound` rather than a crash or a fabricated item.
        if (cid.contains("9999999999999999999")) {
            return json("""{"state":true,"error":"","errNo":0,"file_name":""}""")
        }

        // `listChildren` generates `file-<n>.txt` with fids of the form `32893864487288346<n>`, so a
        // detail request for one of those must describe the same file.
        val index = cid.removePrefix("32893864487288346").toIntOrNull()
        val name = if (index != null) "file-$index.txt" else "我的文件"
        val isFolder = index == null

        return json(
            """
            {"state":true,"error":"","errNo":0,"count":0,"size":"1.86MB","folder_count":0,
            "play_long":0,"ptime":"1779289803","ctime":"1779289803","utime":"1779289803",
            "file_name":"$name","pick_code":"fa8zwyji7hnqv44siu",
            "sha1":"56076926ECA710C7878E8853738D29A7CE2EB734","is_mark":"0","fvs":0,
            "open_time":1791959227,"score":0,"desc":"","file_category":"1","fl":[],
            "paths":[{"file_id":0,"file_name":"根目录"},
                     {"file_id":"2590058519487052812","file_name":"我的文件"}]}
            """.trimIndent(),
        )
    }

    /** §3.3: `files/get_info` returns an array. */
    private fun getInfoResponse(path: String): MockResponse {
        val fid = path.substringAfter("file_id=", "").substringBefore('&')
        if (fid.contains("9999999999999999999")) {
            return json("""{"state":true,"data":[]}""")
        }
        // `pc` is what the pickcode resolver reads on a cache miss. Returning it here is what makes
        // "playback works even when the user never listed the folder" testable.
        return json(
            """
            {"state":true,"data":[{"fid":"$fid","uid":20856775,"aid":1,
            "cid":"2590058519487052812","n":"file.txt","s":2355,"sta":1,
            "pc":"resolvedpickcode","sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A","ico":"txt",
            "t":"2025-11-03 11:43","te":"1762141421","tp":"1762141420","is_top":0,"fl":[]}]}
            """.trimIndent(),
        )
    }

    /** §7.1's direct-link shape, with a real short-lived `t` parameter. */
    private fun downloadResponse(): MockResponse = json(
        """
        {"state":true,"msg":"","msg_code":0,"is_vip":1,"file_name":"cookies.txt","file_size":"2355",
        "pickcode":"d6m6sqtfmezzyxqdm","file_id":"3289386448728834697","user_id":20856775,
        "file_url":"https://cdnfhnfile.115.com/690824ec/cookies.txt?t=1791960109&u=20856775&s=52428800&d=vip-2006125250-d6m6sqtfmezzyxqdm-1-0&c=0&f=3&k=b70b07fdcd6d464f5359ede25d0ce11a&us=104857600&uc=8&v=1"}
        """.trimIndent(),
    )

    /** §10.1's video metadata, including the CRLF-suffixed subtitle URL. */
    private fun videoResponse(): MockResponse = json(
        """
        {"state":true,"file_status":1,"height":804,"width":1920,
        "video_url":"https://115.com/api/video/m3u8/cs4bbzk27i8khd9u3.m3u8",
        "video_url_demo":"https://115.com/api/video/m3u8/cs4bbzk27i8khd9u3.m3u8",
        "definition_list":{"3000000":"1080P","7500000":"4K"},
        "multitrack_list":[{"title":"立体声：中文"},{"title":"5.1环绕声：中文"}],
        "play_long":"2525","definition_index":{"3000000":4,"7500000":5},
        "subtitle_info":[{"url":"https://cpats01.115.com/a/b/c/subtitle/0.srt?u=&se=u&s=1&ck=\r\n","title":"subrip","sort":0}]}
        """.trimIndent(),
    )

    /** §11.2's image response with every size variant. */
    private fun imageResponse(): MockResponse = json(
        """
        {"state":true,"data":{
        "url":"https://thumb.115.com/thumb/5/60/76/56076926ECA7_1440?s=SIG&t=1791962827",
        "all_url":["https://thumb.115.com/thumb/5/60/76/56076926ECA7_100?s=SIG&t=1791962827",
                   "https://thumb.115.com/thumb/5/60/76/56076926ECA7_480?s=SIG&t=1791962827",
                   "https://thumb.115.com/thumb/5/60/76/56076926ECA7_800?s=SIG&t=1791962827"],
        "origin_url":"https://thumb.115.com/thumb/5/60/76/56076926ECA7_0?s=SIG&t=1791962827",
        "source_url":"https://cdnfhnfile.115.com/hash/name.png?t=1791960222&k=SIG",
        "file_name":"screenshot.png","file_sha1":"56076926ECA710C7878E8853738D29A7CE2EB734",
        "pick_code":"ak944jen6pwe5hsiu"}}
        """.trimIndent(),
    )

    /**
     * §11.1: distinguishes the two documented preview outcomes.
     *
     * The office handoff for the ids the suite uses as previewable, and the recorded
     * `code 4011` rejection for the id the suite designates as unpreviewable. That lets the shared
     * rule "an unpreviewable file classifies as `Unsupported` rather than failing the call" be
     * verified against the vendor's *real* rejection shape.
     */
    private fun previewResponse(): MockResponse {
        val body = currentRequestBody.orEmpty()
        return if (body.contains(PREVIEWABLE_PICKCODE)) {
            json(
                """
                {"state":true,"code":0,"msg":"","data":{"file_name":"doc.docx","file_size":"2355",
                "pickcode":"$PREVIEWABLE_PICKCODE",
                "sha1":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A","file_id":"3289386448728834697",
                "url":"https://appdocs.wpscdn.cn/office/w/viewer?_w_appid=AK20221021LNYAAV",
                "token":"Anonymous-contract-token","task_id":"open:abc",
                "expire_at":1792045513,"refresh_before":3540,"can_edit":0}}
                """.trimIndent(),
            )
        } else {
            json(
                """
                {"state":false,"code":4011,"msg":"暂不支持此文件格式预览",
                "data":{"user_id":20856775,"file_name":"data.bin","file_id":"3433237498815791831",
                "pickcode":"ak944jen6pwe5hsiu2","file_size":"1947182"}}
                """.trimIndent(),
            )
        }
    }

    /** §9.3's storage statistics, with the vendor's `pecent`/`type_summury` misspellings. */
    private fun indexInfoResponse(): MockResponse = json(
        """
        {"state":true,"data":{"space_info":{
        "all_total":{"size":20410453391910.89,"size_format":"18.56TB"},
        "all_remain":{"size":5193171217345.891,"size_format":"4.72TB"},
        "all_use":{"size":15217282174565.0,"size_format":"13.84TB"}},
        "count_space_nums":{"type_nums":{"PIC":{"count":13475},"AVI":{"count":8463}},
        "type_summury":{"AVI":{"count":8509,"size":14746848079592,"size_format":"13.41TB","pecent":0.97},
                        "PIC":{"count":13475,"size":1000,"size_format":"1KB","pecent":0.03}}}}}
        """.trimIndent(),
    )

    /** §8.1's recycle-bin shape, where every field is a string. */
    private fun trashResponse(): MockResponse = json(
        """
        {"count":"58","rb_pass":1,"state":true,"data":[
        {"id":"3537724747796186152","file_name":"ssis-622ch","type":"2","file_size":"0",
         "dtime":"1791745653","status":"0","cid":"3025056515334602708","parent_name":"tmp"}],
        "offset":0,"page_size":30,"order":"dtime","is_asc":0}
        """.trimIndent(),
    )

    /** §2.1 Step 5's session check. */
    private fun navResponse(): MockResponse = json(
        """{"state":true,"data":{"user_id":20856775,"user_name":"wwxb","vip":1,"expire":1982993972,"mark1":1023,"mark3":1023,"device":2}}""",
    )

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    /** The most recent request body, so a POST-routed response can branch on its content. */
    @Volatile
    private var currentRequestBody: String? = null

    fun shutdown() {
        server.shutdown()
    }

    companion object {
        private const val PREVIEWABLE_PICKCODE = "d6m6sqtfmezzyxqdm"

        /**
         * Builds a fully-wired provider whose every host points at a local recording server.
         *
         * Everything the suite needs to run offline is assembled here, which is what lets the *same*
         * `ProviderContractRules` class be reused unchanged for both the Mock and the real provider.
         */
        fun build(): P115ContractFixture {
            val server = MockWebServer().apply { start() }
            val host = server.url("/").toString().trimEnd('/')

            val credentials = InMemoryP115CredentialStore()
            val accounts = InMemoryP115AccountMetadataStore()
            val accountId = AccountId("p115-contract-account")

            runBlocking {
                credentials.save(
                    accountId,
                    P115Credential(
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
                    P115AccountMetadata(
                        accountId = accountId,
                        remoteUserId = "20856775",
                        displayName = "wwxb",
                        authState = AuthState.AUTHORIZED,
                        membershipLabel = "年费VIP",
                        membershipExpiresAt = 1_982_993_972_000,
                        addedAt = 1_791_956_000_000,
                        lastVerifiedAt = 1_791_956_000_000,
                    ),
                )
            }

            val provider = P115CloudProvider(
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

            val fixture = P115ContractFixture(provider, server)
            fixture.installDispatcher()
            return fixture
        }
    }
}
