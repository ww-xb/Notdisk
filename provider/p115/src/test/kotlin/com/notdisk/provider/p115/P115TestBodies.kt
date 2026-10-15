package com.notdisk.provider.p115

/**
 * Response bodies shared by the 115 tests.
 *
 * ### Provenance
 * Every body here is a **verbatim copy** of a response recorded in
 * `/data/workspace/115_reverse/api_bodies.json` (captured from a real session on 2026-10-14), with
 * only unstable identifiers substituted. Keeping them in one file means a test cannot quietly
 * diverge from the payload shape the provider was written against, and it makes the evidence for
 * "this is what the vendor returns" reviewable in a single place.
 *
 * Nothing here is invented: if a field is present, the vendor sent it.
 */
internal object P115TestBodies {

    /** §3.1 `GET /files` — a 2-entry page with the vendor's own `path` breadcrumb. */
    val LISTING = """
        {"state":true,"data":[
          {"n":"我的文件","cid":"2590058519487052812","pc":"fa8zwyji7hnqv44siu"},
          {"fid":"3289386448728834697","cid":"3289386376326758547","n":"cookies.txt","s":2355,
           "pc":"d6m6sqtfmezzyxqdm","sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A","ico":"txt",
           "t":"2025-11-03 11:43","te":"1762141421","is_top":0,"fl":[]}],
         "count":2,"offset":0,"page_size":30,"order":"user_ptime","is_asc":0,
         "path":[{"cid":0,"name":"根目录"}]}
    """.trimIndent()

    /** §3.2 `GET /category/get` — flat payload with a full `paths` chain. */
    val CATEGORY = """
        {"state":true,"error":"","errNo":0,"count":0,"size":"1.86MB","folder_count":0,
         "play_long":0,"show_play_long":0,"ptime":"1779289803","ctime":"1779289803",
         "utime":"1779289803","is_share":"0","is_private":0,"file_name":"我的文件",
         "pick_code":"fa8zwyji7hnqv44siu","sha1":"56076926ECA710C7878E8853738D29A7CE2EB734",
         "is_mark":"0","fvs":0,"open_time":1791959227,"score":0,"desc":"","file_category":"1","fl":[],
         "paths":[{"file_id":0,"file_name":"根目录"},
                  {"file_id":"2590058519487052812","file_name":"我的文件"}]}
    """.trimIndent()

    /** §10.1 `GET /files/video` — top-level payload, `4K`/`1080P` ladder, CRLF-suffixed subtitles. */
    val VIDEO = """
        {"state":true,"inlay_power":0,"video_push_state":false,"download_url":[],
         "file_status":1,"thumb_url":"https://static.115.com/video/CC2655A4.jpg",
         "height":804,"width":1920,
         "video_url":"https://115.com/api/video/m3u8/cs4bbzk27i8khd9u3.m3u8",
         "video_url_demo":"https://115.com/api/video/m3u8/cs4bbzk27i8khd9u3.m3u8",
         "definition_list":{"3000000":"1080P","7500000":"4K"},
         "multitrack_list":[{"title":"5.1环绕声：中文：国语·杜比音效"},
                            {"title":"立体声：中文：国语·立体声"}],
         "play_long":"2525","definition_index":{"3000000":4,"7500000":5},
         "subtitle_info":[
           {"url":"https://cpats01.115.com/ad80/subtitle/0.srt?u=&se=u&s=209715200&ck=\r\n","title":"subrip","sort":0},
           {"url":"https://cpats01.115.com/cd5c/subtitle/1.srt?u=&se=u&s=209715200&ck=\r\n","title":"subrip","sort":0}]}
    """.trimIndent()

    /** §11.2 `GET /files/image` — every thumbnail size plus the signed origin. */
    val IMAGE = """
        {"state":true,"data":{
         "url":"https://thumb.115.com/thumb/5/60/76/56076926ECA7_1440?s=EFhLzoZK&t=1791962827",
         "all_url":["https://thumb.115.com/thumb/5/60/76/56076926ECA7_100?s=EFhLzoZK&t=1791962827",
                    "https://thumb.115.com/thumb/5/60/76/56076926ECA7_480?s=EFhLzoZK&t=1791962827",
                    "https://thumb.115.com/thumb/5/60/76/56076926ECA7_800?s=EFhLzoZK&t=1791962827"],
         "origin_url":"https://thumb.115.com/thumb/5/60/76/56076926ECA7_0?s=EFhLzoZK&t=1791962827",
         "source_url":"https://cdnfhnfile.115.com/6872412015530e844043050ae17649940cc07de3/screenshot.png?t=1791960222&u=20856775&s=52428800&d=vip-2006125250-ak944jen6pwe5hsiu-1-0&c=0&f=3&k=895a2af5d94bd81d7ebe509a9014d15b&us=104857600&uc=8&v=1",
         "file_name":"screenshot.png","file_sha1":"56076926ECA710C7878E8853738D29A7CE2EB734",
         "pick_code":"ak944jen6pwe5hsiu"}}
    """.trimIndent()

    /** §11.1 `POST /files/preview` — the measured WPS cloud-document handoff. */
    val PREVIEW_OFFICE = """
        {"state":true,"code":0,"msg":"","time":"2026-10-14 14:27:07","data":{
         "is_ipad":false,"file_name":"cookies.txt","file_size":"2355",
         "pickcode":"d6m6sqtfmezzyxqdm","sha1":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A",
         "file_id":"3289386448728834697",
         "url":"https://appdocs.wpscdn.cn/office/w/trqMf6v9iZb1Pp9W6soVmo2?_w_appid=AK20221021LNYAAV&_w_tokentype=1",
         "token":"Anonymous-xvjuapbhwmoggmgztdlxtyvtlajmgnfwcvubgglxhemxvfaj",
         "task_id":"open:vhhotwyvrlchutuxknjkdvsippjhxoa",
         "expire_at":1792045513,"refresh_before":3540,"gid":null,"id":4259860,"can_edit":1}}
    """.trimIndent()

    /** §11.1's measured rejection for a format the vendor cannot render. */
    val PREVIEW_UNSUPPORTED = """
        {"state":false,"code":4011,"msg":"暂不支持此文件格式预览","time":"2026-10-14 14:27:07",
         "data":{"user_id":20856775,"file_name":"data.bin",
                 "sha1":"56076926ECA710C7878E8853738D29A7CE2EB734","file_id":"3433237498815791831",
                 "pickcode":"ak944jen6pwe5hsiu","share_id":"","file_size":"1947182"}}
    """.trimIndent()

    /** §7.1 `GET /files/download` — a short-lived signed direct link. */
    val DOWNLOAD = """
        {"state":true,"msg":"","msg_code":0,"is_115chrome":0,"is_snap":0,"is_vip":1,
         "file_name":"cookies.txt","file_size":"2355","pickcode":"d6m6sqtfmezzyxqdm",
         "file_id":"3289386448728834697","user_id":20856775,
         "file_url":"https://cdnfhnfile.115.com/690824ec/cookies.txt?t=1791960109&u=20856775&s=52428800&d=vip-2006125250-d6m6sqtfmezzyxqdm-1-0&c=0&f=3&k=b70b07fdcd6d464f5359ede25d0ce11a&us=104857600&uc=8&v=1"}
    """.trimIndent()

    /** §8.1 `GET /rb` — every field is a string, and `id` is the `rid`. */
    val TRASH = """
        {"count":"58","rb_pass":1,"state":true,"data":[
         {"id":"3537724747796186152","file_name":"ssis-622ch","type":"2","file_size":"0",
          "dtime":"1791745653","status":"0","cid":"3025056515334602708","parent_name":"tmp"},
         {"id":"3537298091902240214","file_name":"FNS-260-AI","type":"2","file_size":"90534826",
          "dtime":"1791694792","status":"0","cid":"3025056515334602708","parent_name":"tmp"}],
         "offset":0,"page_size":5,"order":"dtime","is_asc":0}
    """.trimIndent()

    /** §3.4 `GET /files/search` — `s`/`sta`/`t` are strings, and `ns` carries highlight HTML. */
    val SEARCH = """
        {"count":1,"data":[{"fid":"3289386448728834697","aid":"1","cid":"3289386376326758547",
         "n":"cookies.txt","s":"2355","sta":"1","pt":"0","pc":"d6m6sqtfmezzyxqdm","fc":1,"p":0,
         "m":"0","t":"2025-11-03","te":"1762141421","tp":"1762141421","d":0,"c":"0","sh":"0",
         "e":"","ico":"txt","dp":"从赘婿到宠臣",
         "ns":"<b style='color: #FF0000; background-color: #FFFF00;'>cookies<\/b>.txt",
         "sha":"CB19773016AE5D9D372ECB3B08F9850823B1EC1A","q":0,"ih":"","nm":0,"hdf":0,
         "coordinate":"","address":"","ec":0,"fl":[],"u":"","score":0,"is_top":0}],
         "cost_time_es":0.06853795051574707,"folder":{"name":"根目录","cid":"0","pid":"0"},
         "page_size":5,"page_count":1,"offset":0,"is_asc":0,"order":"file_size","suffix":"",
         "fc_mix":0,"type":0,"state":true,"error":"","errCode":0}
    """.trimIndent()

    /** §9.3 `GET /files/index_info` — note the vendor's `pecent`/`type_summury` misspellings. */
    val INDEX_INFO = """
        {"state":true,"data":{"space_info":{
         "all_total":{"size":20410453391910.89,"size_format":"18.56TB"},
         "all_remain":{"size":5193171217345.891,"size_format":"4.72TB"},
         "all_use":{"size":15217282174565.0,"size_format":"13.84TB"}},
         "count_space_nums":{
          "type_nums":{"PIC":{"count":13475},"DOC":{"count":81},"AVI":{"count":8463}},
          "type_summury":{
           "AVI":{"count":8509,"size":14746848079592,"size_format":"13.41TB","pecent":0.97},
           "PIC":{"count":13475,"size":1000,"size_format":"1KB","pecent":0.03}}}}}
    """.trimIndent()

    /** §2.1 Step 1 `GET /token` — the QR token issuance response. */
    val QR_TOKEN = """
        {"state":1,"code":0,"message":"","data":{
         "uid":"dd0c4805d130b9893d6274d606d89e1502d2a995","time":1791955914,
         "sign":"fcf6b0e25d99835f9edf22501e1f29f7",
         "qrcode":"https://115.com/scan/dg-dd0c4805d130b9893d6274d606d89e1502d2a995"}}
    """.trimIndent()

    /** §2.1 Step 2 `GET /get/status` — "已扫码待确认" (`status: 1`). */
    val QR_STATUS_SCANNED = """
        {"state":1,"code":0,"data":{"msg":"扫描成功，请在手机点确认以登录","status":1,"version":"2.0"}}
    """.trimIndent()

    /** §2.1 Step 2 — "已确认" (`status: 2`). */
    val QR_STATUS_CONFIRMED = """
        {"state":1,"code":0,"data":{"msg":"","status":2,"version":"2.0"}}
    """.trimIndent()

    /** §2.1 Step 2 — not scanned yet: a present but empty `data` object. */
    val QR_STATUS_PENDING = """{"state":1,"code":0,"data":{}}"""

    /** §2.1 Step 3 `POST /login/qrcode` — the credentials arrive in `data.cookie`. */
    val QR_LOGIN_SUCCESS = """
        {"state":1,"data":{"user_id":20856775,"user_name":"wwxb","country":"CN",
         "is_vip":1982993972,"mark":1023,"is_first_login":0,"is_chang_passwd":0,
         "face":{"face_l":"https://x/l.jpg","face_m":"https://x/m.jpg","face_s":"https://x/s.jpg"},
         "passwd_reset":1,
         "cookie":{"UID":"20856775_A1_1791956082",
                   "CID":"b017d659c0a1102aeebdd890204199e0",
                   "SEID":"${"8".repeat(120)}",
                   "KID":"44b17af1fdc496eece781e712599268f"},
         "from":"115android","is_trusted":false}}
    """.trimIndent()
}
