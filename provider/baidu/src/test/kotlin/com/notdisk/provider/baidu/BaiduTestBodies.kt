package com.notdisk.provider.baidu

/**
 * Response bodies shared by the Baidu Netdisk tests.
 *
 * ### Provenance
 * Every body below is a **verbatim copy** of a response recorded in the reference material
 * (`/data/workspace/baidu_reverse/docs/`), with only unstable identifiers substituted. The section
 * marker above each one names where it came from, so a reviewer can check that the shape the provider
 * was written against is the shape the vendor really returns.
 *
 * Nothing here is invented. Where the reference material records that a response was **never**
 * observed — the recycle bin with content, the share-create success — no body is fabricated here
 * either; those cases are covered by tests that assert the *honest refusal* instead.
 */
internal object BaiduTestBodies {

    /** §3.2 `GET /api/list` — a folder plus a file, with the vendor's ~30-field entry shape. */
    val LISTING = """
        {"errno":0,"guid_info":"",
         "list":[
           {"tkbind_id":0,"server_filename":"我的文件","category":6,"unlist":0,"isdir":1,
            "dir_empty":0,"oper_id":1714888383,"wpfile":0,"local_mtime":1716023471,"share":0,"pl":2,
            "local_ctime":1716023471,"empty":0,"real_category":"","black_tag":0,"extent_int2":0,
            "server_ctime":1716023471,"extent_tinyint7":0,"owner_id":0,"size":0,"extent_int8":0,
            "path":"/我的文件","from_type":0,"fs_id":29770847494518,"owner_type":0,"server_atime":0,
            "server_mtime":1781004024,"is_scene":0},
           {"tkbind_id":0,"server_filename":"favorites.txt","category":4,"unlist":0,"isdir":0,
            "oper_id":1714888383,"wpfile":0,"local_mtime":1746792006,"share":0,"pl":2,
            "local_ctime":1746792006,"real_category":"","black_tag":0,
            "server_ctime":1746792011,"owner_id":0,"size":277,
            "path":"/我的文件/favorites.txt","from_type":1,"fs_id":761224233514659,"owner_type":0,
            "server_atime":0,"server_mtime":1746792011,"is_scene":0}
         ],
         "request_id":8956370037768280427}
    """.trimIndent()

    /**
     * §3.3 `GET /api/categorylist` — the category payload.
     *
     * Note it returns **`info[]`, not `list[]`**, which the reference material flags as the single
     * most important shape difference between the two listing endpoints. The envelope normalizes it.
     * The `thumbs` object and the signed `docpreview`/`lodocpreview` URLs are reproduced verbatim
     * because their *presence* is what the mapper reads (never their values).
     */
    val CATEGORY_LIST = """
        {"errno":0,"guid_info":"",
         "info":[
           {"server_mtime":1746792011,"category":4,
            "object_key":"E-pSs1dI3iTda9I51Ji4QI2LtHqBI36JlscI4Rq31kI4B",
            "fs_id":761224233514659,"black_tag":0,"oper_id":1714888383,
            "server_ctime":1746792011,"extent_int8":0,"wpfile":0,"local_mtime":1746792006,
            "size":277,"isdir":0,"server_filename":"favorites.txt","share":0,
            "path":"/backup/Via/favorites.txt","from_type":1,
            "docpreview":"https://pcsdata.baidu.com/doc/f892ac729o62544a1c8ed52779e84d72?fid=1714888383-250528-761224233514659&time=1791972823&rt=pr&sign=FDTAER-DCb740ccc5511e5e8fedcff06b081203-pv7LIF80vJ10xXNDYkR8LlctBc4%3d&expires=8h&chkbd=0&chkv=0&dp-logid=8956605781754175892&dp-callid=0",
            "local_ctime":1746792006,"md5":"f892ac729o62544a1c8ed52779e84d72","owner_id":0,
            "lodocpreview":"https://pcsdata.baidu.com/doc/f892ac729o62544a1c8ed52779e84d72?fid=1714888383-250528-761224233514659&time=1791972823&rt=pr&sign=x&type=pdf&from=lo",
            "thumbs":{"url3":"https://thumbnail0.baidupcs.com/thumbnail/f892ac729o62544a1c8ed52779e84d72?fid=1714888383-250528-761224233514659&rt=pr&sign=SIG&size=c850_u580&quality=100",
                      "url2":"https://thumbnail0.baidupcs.com/thumbnail/f892ac729o62544a1c8ed52779e84d72?size=c360_u270&quality=100",
                      "url1":"https://thumbnail0.baidupcs.com/thumbnail/f892ac729o62544a1c8ed52779e84d72?size=c140_u90&quality=100"}}],
         "request_id":8956605781754175892}
    """.trimIndent()

    /**
     * §3.4 `GET /api/search` — a hit, including the fields unique to search.
     *
     * Also the shape that carries `thumbs: {}` — an **empty** object meaning "no thumbnail" — which is
     * why the mapper reads the presence of a URL rather than the presence of the object.
     */
    val SEARCH = """
        {"errno":0,
         "list":[
           {"category":6,"fs_id":29770847494518,"isdir":1,
            "local_ctime":1716023471,"local_mtime":1716023471,"md5":"",
            "oper_id":1714888383,"owner_id":861955664,"path":"/backup",
            "server_ctime":1716023471,"server_filename":"backup","server_mtime":1781004024,
            "share":0,"size":0,"wpfile":0,"delete_type":0,"from_type":0,
            "thumbs":{},"docpreview":"","ptype":"","fold":0,
            "score":1,"relevance_level":1,"recent_tag":0,"extent_int8":0}],
         "request_id":8956382838230001159,"has_more":0,"display_count":0,
         "need_ai_search":false}
    """.trimIndent()

    /** §3.4 with a keyword that matches nothing: a successful, empty result. */
    val SEARCH_EMPTY = """
        {"errno":0,"list":[],"request_id":8956382838230001160,"has_more":0,"display_count":0}
    """.trimIndent()

    /**
     * §3.5 `POST /api/filemetas` — a video, with the full media metadata.
     *
     * Reproduced because it is the payload that proves `md5` and `file_size`-as-string handling, and
     * because the embedded `extra_info` JSON is what a future progress/duration feature would read.
     */
    val FILEMETAS_VIDEO = """
        {"errno":0,
         "info":[{
           "server_filename":"59丨HPACK中整型数字的编码.mp4","category":1,
           "fs_id":171394449149326,"videotag":0,
           "play_source":0,"file_size":"84127739",
           "extra_info":"{\"audio_bitrate\":128002,\"audio_codec_name\":\"aac\",\"audio_duration\":351786,\"dar_h\":9,\"dar_w\":16,\"format_name\":\"mov,mp4,m4a,3gp,3g2,mj2\",\"max_audio_ts_gap\":23,\"max_video_ts_gap\":125,\"pix_fmt\":\"yuv420p\",\"video_bitrate\":1778641,\"video_codec_name\":\"mpeg4\",\"video_duration\":351917}\n",
           "sample_rate":44100,"height":1080,"src_location":"yangquan",
           "path":"/学习资料/59丨HPACK中整型数字的编码.mp4",
           "file_key":"E-pSsvwIpTtZDI4raOlRI2x81CwI1jKKXwIObwP8I6tsNZ",
           "meta_info":"{\"date_taken\":0,\"encoder\":\"Lavf56.15.100\",\"has_thumbnail\":true,\"mediaType\":\"video\"}\n",
           "server_mtime":1599048973,"size":84127739,"use_segment":1,
           "md5":"78688b049h4fd9eccf3b78fa7f1bbba5",
           "thumbs":{
             "icon":"https://thumbnail0.baidupcs.com/thumbnail/78688b049h4fd9eccf3b78fa7f1bbba5?size=c60_u60&quality=100&vuk=1714888383&ft=video",
             "url3":"https://thumbnail0.baidupcs.com/thumbnail/78688b049h4fd9eccf3b78fa7f1bbba5?size=c850_u580&quality=100&vuk=1714888383&ft=video",
             "url2":"https://thumbnail0.baidupcs.com/thumbnail/78688b049h4fd9eccf3b78fa7f1bbba5?size=c360_u270&quality=100&vuk=1714888383&ft=video"}}],
         "request_id":8956495075706973695}
    """.trimIndent()

    /**
     * §3.5's `text=1` variant — the text/code reader's access material.
     *
     * `picdocpreview` is the URL this build hands to the text preview.
     */
    val FILEMETAS_TEXT = """
        {"errno":0,
         "info":[{"errno":0,
           "picdocpreview":"https://pcsdata.baidu.com/rest/2.0/docview/text?object=f892ac729o62544a1c8ed52779e84d72&expires=24h&dp_logid=8956613585231222007&rt=pr&sign=FOTRE-DCb740ccc5511e5e8fedcff06b081203-d1H3BY%252Fwrvdvk7Pt7d5qB3H2wkg%253D&file_size=277&timestamp=1791972851&method=info&fid=1714888383-250528-761224233514659&client_type=web&file_type=txt"}],
         "request_id":8956613585231222007}
    """.trimIndent()

    /**
     * §3.5's **partial-failure** shape: the outer envelope reports `errno 12` while the element
     * carries `-9` ("文件不存在"). This is the payload that makes the element-level check necessary.
     */
    val FILEMETAS_PARTIAL_FAILURE = """
        {"errno":12,"info":[{"errno":-9}],"request_id":8956482164189282574}
    """.trimIndent()

    /** §3.5's response for a file that does not exist at all: an empty `info` array. */
    val FILEMETAS_EMPTY = """{"errno":0,"info":[],"request_id":8956482164189282575}"""

    /** §3.7 `POST /api/create` — the measured response. */
    val CREATE_FOLDER = """{"errno":0,"name":"新建文件夹","path":"/我的文件/新建文件夹"}"""

    /** §3.6 `POST /api/filemanager` — a plain success. */
    val FILEMANAGER_OK = """{"errno":0,"request_id":8956596186756250000}"""

    /**
     * §3.6 — an **asynchronous** acceptance carrying a 64-bit `taskid`.
     *
     * The value exceeds 2^53, which is exactly why the reader must accept a bare JSON number without
     * routing it through a float.
     */
    val FILEMANAGER_ASYNC = """{"errno":0,"taskid":8956596186756250123,"request_id":8956596186756250001}"""

    /**
     * §3.6 — the **measured risk-control** response for this account.
     *
     * `errno 132` with an `authwidget` is the payload that proves this API distinguishes "needs an
     * interactive identity check" from "permission denied".
     */
    val FILEMANAGER_VERIFY_REQUIRED = """
        {"errno":132,"info":[],"request_id":8956596186756250000,"verify_scene":1,
         "authwidget":{"saferand":"hjy1791972786","safesign":"dabab2e3c8142280fcf06ce69dbd15a9",
                       "safetpl":"filemanager"}}
    """.trimIndent()

    /** §3.6 — a partial failure with a per-item code. */
    val FILEMANAGER_PARTIAL = """
        {"errno":0,"info":[{"errno":-8,"path":"/我的文件/existing.txt"}],"show_msg":"",
         "request_id":8956596186756250002}
    """.trimIndent()

    /**
     * §3.10 `GET /api/recycle/list/` — the **measured** empty bin.
     *
     * The reference material records that this account's bin was empty, so the element-level fields
     * were never observed. The empty shape is reproduced exactly rather than filled in with an
     * invented entry.
     */
    val RECYCLE_EMPTY = """{"errno":0,"list":[],"request_id":8956426766020409851,"timestamp":1791972155}"""

    /** §3.10 — a successful restore/delete. */
    val RECYCLE_OK = """{"errno":0,"taskid":8956426766020409852,"request_id":8956426766020409853}"""

    /** §3.10 — a partial operation reporting `faillist`. */
    val RECYCLE_PARTIAL = """
        {"errno":0,"faillist":[29770847494518],"request_id":8956426766020409854}
    """.trimIndent()

    /** §2.4 `GET /api/gettemplatevariable` — the measured token response. */
    val TEMPLATE_VARIABLE = """
        {"errno":0,
         "result":{"bdstoken":"2d8dd749ef5bfde3fa2a86446a1ffa08",
                   "uk":1714888383,"isdocuser":1,"servertime":1791971680},
         "request_id":8956299286468383436}
    """.trimIndent()

    /** §2.4 `GET /api/loginStatus` — a live session. */
    val LOGIN_STATUS_OK = """
        {"errno":0,"uk":1714888383,"isLogin":1,"vip":1,"svip10":"","csrf":"TCcADPMU-lxM",
         "username":"愤怒de小熊丶","request_id":8956299742969817514}
    """.trimIndent()

    /**
     * §2.4 — the anonymous shape.
     *
     * This is the response that proves the pre-persist check cannot rely on `errno` alone: it is a
     * successful envelope reporting `isLogin: 0`.
     */
    val LOGIN_STATUS_ANONYMOUS = """{"errno":0,"isLogin":0,"request_id":8956299742969817515}"""

    /** §9.1 `GET /api/user/getinfo` — the measured profile. */
    val USER_GETINFO = """
        {"errmsg":"","errno":0,"newno":"",
         "records":[{"avatar_url":"https://himg.bdimg.com/sys/portrait/item/public.1.5d338d98.cvK8ZfCykmbz45IDKGnI-Q.jpg",
           "ban_info":{"ban_grade":-1,"ban_msg":""},"birthday":"","black_flag":0,
           "board_info":{"is_whitelist":1},"certuser_data":{"cancel_status":0,"cert_status":0},
           "display_name":"","follow_flag":0,"intro":"","is_able_set_birthday":0,
           "nick_name":"五碳糖","priority_name":"五碳糖","remark":"","svip10_id":"",
           "uk":1714888383,"uname":"愤怒de小熊丶","user_label":0,"vip_level":1,"vip_point":10,
           "vip_type":0}],
         "request_id":8956299742969817514,"server_time":1791971682}
    """.trimIndent()

    /** §9.2 `GET /api/quota` — the measured quota. */
    val QUOTA = """
        {"errmsg":"","errno":0,"expire":false,"free":0,"is_show_window":0,"recmd_vip":"",
         "recyclestatus":0,"sbox_total":0,"sbox_used":0,"server_time":1791971682,
         "total":2206539448320,"used":1322146849400}
    """.trimIndent()

    /**
     * §7.3 — phase one of `/api/streaming`: the **ad gate**.
     *
     * `errno 133` is a documented handshake step, not a failure. The token is what phase two needs.
     */
    val STREAMING_AD_GATE = """
        {"errno":133,"request_id":8956495137075703169,"adTime":8,
         "adToken":"7xr7XRsSOxiMCDdy3YHzUiS6G3oqhqsePqOYSqZT6lLsta26C5wsKCl/2HHQ4+jIpYenN9jZ2hhMsaFq9SZo+2Bg5x+FacQ0Si8dsMLNzWmd6oX8+fBhAmxsYUEUVhG8OgXF3Hx8rZiRXcpidc6H6VAYHuJDc3q/1KrZNJipoUE=",
         "ltime":5}
    """.trimIndent()

    /**
     * §7.4.1 — phase two: the **measured HLS master playlist**.
     *
     * Reproduced with the real structure: many `#EXTINF:2,` entries pointing at the *same* container
     * with different `range` parameters, which is what makes this "range-sliced HLS" rather than DASH.
     */
    val STREAMING_PLAYLIST = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXT-X-DISCONTINUITY
        #EXTINF:2,
        https://gzct09.baidupcs.com/video/netdisk-videotran-baoding/78688b049h4fd9eccf3b78fa7f1bbba5_1074_1_ts/1aed0f7782dae94571c1c481bbdcf691?ts_size=6062248&app_id=250528&clienttype=0&csl=300&fn=test.mp4&range=0-43991&len=43992&sign=SIG&etag=1aed0f77&by=my-streaming
        #EXTINF:2,
        https://gzct09.baidupcs.com/video/netdisk-videotran-baoding/78688b049h4fd9eccf3b78fa7f1bbba5_1074_1_ts/1aed0f7782dae94571c1c481bbdcf691?ts_size=6062248&app_id=250528&clienttype=0&csl=300&fn=test.mp4&range=43992-88547&len=44556&sign=SIG&etag=1aed0f77&by=my-streaming
        #EXT-X-ENDLIST
    """.trimIndent()

    /** §7.3 — the measured "this rendition is unavailable" answer. */
    val STREAMING_RENDITION_UNAVAILABLE = """{"errno":2,"request_id":8956495137075703170}"""

    /** §7.3 — the measured "unsupported `type`" answer, which arrives with **HTTP 400**. */
    val STREAMING_BAD_TYPE = """{"error_code":31023,"errno":31023}"""

    /** §7.5 — the measured AI-subtitle status. */
    val AUTOSUBTITLE_STATUS = """
        {"errno":0,"extra_res":[],"request_id":8956497481060418564,
         "res":{"method":"status","roi_status":1,
                "subtitle_status":{"current_status":0,"extra_status":0,"has_record":0,
                                   "residue_count":1},
                "user_type":0},
         "show_msg":""}
    """.trimIndent()

    /** §6.1 — the **measured** share-creation refusal (the account has no bound phone). */
    val SHARE_PSET_PHONE_REQUIRED = """
        {"errno":2,"newno":"","request_id":2625535841497193704,
         "show_msg":"为保障您的账号安全，使用分享功能前需完成手机号绑定"}
    """.trimIndent()

    /** §5.3 — the measured "signature expired" answer for a download attempt. */
    val DOWNLOAD_SIGN_EXPIRED = """{"errno":113,"request_id":8956609949814496001}"""

    /** §1.3 — the session-expired envelope, which every endpoint can return. */
    val NOT_LOGGED_IN = """{"errno":-6,"request_id":8956609949814496002,"show_msg":"请重新登录"}"""

    /** §1.4 — the measured captcha requirement. */
    val CAPTCHA_REQUIRED = """{"errno":-20,"request_id":8956609949814496003}"""

    /** §1.2 — a syntax-error response (a non-JSON body), used by the parse-failure test. */
    const val MALFORMED = "<html><body>502 Bad Gateway</body></html>"
}
