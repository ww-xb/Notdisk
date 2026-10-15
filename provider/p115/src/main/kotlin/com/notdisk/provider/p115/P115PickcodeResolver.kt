package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.provider.p115.api.P115Endpoints
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import com.notdisk.provider.p115.auth.P115Session
import kotlinx.serialization.json.JsonObject

/**
 * Resolves the vendor `pickcode` a file needs for its single-file endpoints.
 *
 * ### Why this is a separate collaborator
 * `files/video`, `files/preview` and `files/image` all key on a pickcode (§1.5). The value is
 * returned by `GET /files` as `pc` and by `category/get` as `pick_code`, but:
 *  - 115 omits it from some payloads, and
 *  - it is *not* part of a file's identity (see [P115FileIdCodec]), so it cannot live in the id.
 *
 * Three providers need it, and each would otherwise grow its own slightly-different fallback. One
 * resolver keeps the behaviour uniform and, more importantly, keeps the extra request it may cost
 * explicit and testable.
 *
 * ### Resolution order
 *  1. the in-memory cache, populated by any listing or detail call (the common case, zero requests);
 *  2. one `GET /files/get_info?file_id=<fid>` lookup, which returns the pickcode as `pick_code` and
 *     is the same endpoint the file-system provider already uses as its detail fallback.
 *
 * A miss is reported as `CloudError.InvalidArgument` with an actionable message rather than being
 * papered over: sending a request without a pickcode would earn `errno 1001`, which is far harder
 * for a user to act on.
 */
internal class P115PickcodeResolver(
    private val http: P115HttpClient,
    private val cache: P115PickcodeCache,
) {

    suspend fun resolve(
        accountId: AccountId,
        fileId: FileId,
        session: P115Session,
    ): CloudResult<String> {
        cache.get(accountId, fileId)?.let { return CloudResult.Success(it) }

        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )
        val fid = ref.fid
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "该标识不是文件: $fileId"),
            )

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_GET_INFO),
            query = mapOf("file_id" to fid, "this_path" to "1"),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.get_info.pickcode")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val node = firstEntry(result.data.dataAsArray.mapNotNull { it as? JsonObject })
                    ?: result.data.root.arrayOf("data")
                        ?.firstOrNull()
                        ?.let { it as? JsonObject }

                val pickcode = node?.stringOf("pc", "pick_code", "pickcode")
                if (pickcode.isNullOrBlank()) {
                    CloudResult.Failure(
                        CloudError.Unsupported(
                            message = "115 未返回该文件的 pickcode，无法获取播放/预览地址",
                            capability = "file.pickcode",
                        ),
                    )
                } else {
                    cache.put(accountId, fileId, pickcode)
                    CloudResult.Success(pickcode)
                }
            }
        }
    }

    private fun firstEntry(entries: List<JsonObject>): JsonObject? = entries.firstOrNull()
}
