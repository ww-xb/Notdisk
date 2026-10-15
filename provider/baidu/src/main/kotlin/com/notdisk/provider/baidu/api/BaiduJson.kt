package com.notdisk.provider.baidu.api

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant reader for Baidu Netdisk's response envelopes.
 *
 * ### Why not just `@Serializable` data classes
 * The reference material documents five independent inconsistencies in the *same* API, each of which
 * would break a strict deserializer on a valid production response:
 *
 *  1. **The success field is not always `errno`.** `/rest/2.0/membership/…` uses `error_code`
 *     (§9.3). The vendor's own interceptor reads
 *     `e.data.errno !== undefined ? e.data.errno : e.data.error_code`.
 *  2. **A missing or non-numeric code means failure, not success.** The interceptor coerces with
 *     `Number(n.errno); if (isNaN(i)) i = -1`. Treating an absent code as `0` would turn every
 *     malformed response into a silent success.
 *  3. **`errno` is sometimes the empty string.** `"errno":""` occurs in real bodies, and `Number("")
 *     === 0`, so it must be read as "absent", not as success-by-accident.
 *  4. **64-bit integers are sent as bare JSON numbers.** `fs_id`, `task_id`, `request_id` and
 *     `shareId` routinely exceed 2^53; the vendor's own front end re-extracts `task_id` from the raw
 *     text with a regex for exactly this reason (§0.3). Kotlin's `Long` is 64-bit so it can hold the
 *     value, but the reader must accept **both** a number and a numeric string for the same field,
 *     because endpoints disagree.
 *  5. **The error message field has three spellings.** `show_msg` is the user-facing one; `errmsg`
 *     and `error_msg` appear elsewhere; the interceptor only ever consumed `show_msg`.
 *
 * ### Naming
 * The accessors are named `…Of` rather than `…OrNull` on purpose: `kotlinx.serialization` already
 * defines `longOrNull`/`booleanOrNull` as properties on `JsonPrimitive`, and an extension function
 * with the same name on `JsonObject` is silently shadowed by them — producing a confusing "cannot
 * invoke as a function" error rather than a clean clash. (This mirrors the same trap the 115
 * provider documents.)
 */
internal object BaiduJson {

    /**
     * `ignoreUnknownKeys` is essential rather than cosmetic: `/api/list` alone returns ~30 fields per
     * entry (§3.2) whose set varies by endpoint, and the vendor adds fields without notice.
     */
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    /**
     * Normalizes the envelope into a single structure.
     *
     * The three facts a caller needs are: did it succeed, what is the payload, and what is the error.
     * The vendor's own A/B/C response shapes are a documentation device, not something to branch on.
     */
    fun parseEnvelope(
        body: String,
        httpStatus: Int,
        family: BaiduErrorFamily = BaiduErrorFamily.GENERAL,
        /**
         * Business codes that mean "some elements failed" rather than "the call failed".
         *
         * `/api/filemetas` is a **multi-target** endpoint and §3.5 measures exactly this shape:
         * `{"errno": 12, "info": [{"errno": -9}]}` — the outer code says a partial failure occurred
         * while each element carries its own. Treating the outer code as fatal would discard the
         * per-element detail and report one blanket error for a batch where most entries succeeded.
         *
         * Callers that pass a code here take on the obligation to inspect every element.
         */
        partialFailureCodes: Set<Int> = emptySet(),
    ): CloudResult<BaiduEnvelope> {
        val root = try {
            lenient.parseToJsonElement(body) as? JsonObject
                ?: return CloudResult.Failure(
                    CloudError.ParseError(message = "百度网盘响应不是 JSON 对象（HTTP $httpStatus）"),
                )
        } catch (t: Throwable) {
            return CloudResult.Failure(
                CloudError.ParseError(message = "百度网盘响应解析失败（HTTP $httpStatus）", cause = t),
            )
        }

        // Rule 1: `errno` first, then `error_code`. Rule 3: only a *present, numeric* value counts.
        val errno = root["errno"].asIntOrNull()
        val errorCode = root["error_code"].asIntOrNull()
        val code = errno ?: errorCode

        // Rule 5: `show_msg` is the user-facing field the vendor's interceptor consumes; the others
        // are fallbacks so a failure is never reported with no explanation at all.
        val errorMessage = root.stringOf("show_msg", "errmsg", "error_msg", "error", "msg")

        // Rule 2: absent or non-numeric collapses to -1, exactly as the vendor's interceptor does
        // (`var i = Number(n.errno); if (isNaN(i)) i = -1;`). It is tempting to fall back to the HTTP
        // status here, but that would *invent* a success for a malformed body — and the whole point of
        // this envelope is that HTTP status is not a failure signal for this API
        // (`validateStatus: () => true`), so it cannot be a success signal either.
        val effectiveCode = code ?: -1

        val succeeded = (effectiveCode == BaiduErrorCodes.OK ||
            effectiveCode in partialFailureCodes) && httpStatus in 200..299

        if (!succeeded) {
            return CloudResult.Failure(
                mapBaiduError(
                    code = effectiveCode,
                    message = errorMessage,
                    httpStatus = httpStatus,
                    family = family,
                ),
            )
        }

        return CloudResult.Success(
            BaiduEnvelope(
                root = root,
                code = effectiveCode,
                message = errorMessage,
                httpStatus = httpStatus,
            ),
        )
    }

    /**
     * Parses a body that is a **bare JSON array**, which `/api/recycle/…` and some `/rest/2.0/…`
     * endpoints return rather than an object.
     *
     * A missing envelope is not automatically a failure: the caller decides. When the body is not
     * valid JSON at all, that *is* a parse failure.
     */
    fun parseRoot(body: String, httpStatus: Int): CloudResult<JsonElement> {
        val root = try {
            lenient.parseToJsonElement(body)
        } catch (t: Throwable) {
            return CloudResult.Failure(
                CloudError.ParseError(message = "百度网盘响应解析失败（HTTP $httpStatus）", cause = t),
            )
        }
        return CloudResult.Success(root)
    }
}

// ------------------------------------------------------------------ primitive coercion

/**
 * Reads a long from a number **or** a numeric string.
 *
 * Required because endpoints disagree: `/api/list` sends `fs_id` as a number (§3.2) while
 * `/api/categorylist` and search results have been observed to send the same field as a string
 * (§3.4's "type changes between endpoints" note). `task_id` in particular is documented as a 64-bit
 * value that must never round-trip through a float.
 */
internal fun JsonElement?.asLongOrNull(): Long? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    primitive.longOrNull?.let { return it }
    val text = primitive.content.trim()
    if (text.isEmpty()) return null
    return text.toLongOrNull()
}

internal fun JsonElement?.asIntOrNull(): Int? =
    asLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonElement?.asDoubleOrNull(): Double? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.trim().toDoubleOrNull()
}

/** Reads a string, treating a JSON number as its textual form (ids are often numeric). */
internal fun JsonElement?.asStringOrNull(): String? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/** Reads a string, mapping blank to `null` — the vendor uses `""` liberally to mean "absent". */
internal fun JsonElement?.asNonBlankStringOrNull(): String? =
    asStringOrNull()?.takeIf { it.isNotBlank() }

/**
 * Reads a boolean from any of the shapes this API actually uses.
 *
 * `isdir`, `share`, `wpfile`, `unlist` and friends are documented as `0`/`1` integers, but the same
 * endpoints also emit real booleans depending on the field, so both are accepted.
 */
internal fun JsonElement?.asBoolOrNull(): Boolean? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.content.trim().lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no", "" -> false
        else -> null
    }
}

// ------------------------------------------------------------------ JsonObject field readers

/** First non-blank string among [keys]; `null` when none is present or all are blank. */
internal fun JsonObject.stringOf(vararg keys: String): String? {
    for (key in keys) {
        this[key].asNonBlankStringOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.longOf(vararg keys: String): Long? {
    for (key in keys) {
        this[key].asLongOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.intOf(vararg keys: String): Int? {
    for (key in keys) {
        this[key].asIntOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.boolOf(vararg keys: String): Boolean? {
    for (key in keys) {
        this[key].asBoolOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.objOf(vararg keys: String): JsonObject? {
    for (key in keys) {
        (this[key] as? JsonObject)?.let { return it }
    }
    return null
}

internal fun JsonObject.arrayOf(vararg keys: String): JsonArray? {
    for (key in keys) {
        (this[key] as? JsonArray)?.let { return it }
    }
    return null
}

/**
 * A successfully-normalized Baidu Netdisk response envelope.
 *
 * [root] is the whole object so endpoint-specific fields can be read without re-parsing. The payload
 * is deliberately **not** normalized into a `data` property: this API puts its payload at the top
 * level (`list[]`, `info[]`, `dlink`, `result{…}`), not under a uniform `data` key, and inventing
 * one would hide which endpoints deviate.
 */
internal data class BaiduEnvelope(
    val root: JsonObject,
    val code: Int,
    val message: String?,
    val httpStatus: Int,
) {
    /**
     * The list payload, accepting either spelling.
     *
     * §3.3 is explicit that `/api/categorylist` returns `info[]` **instead of** `list[]`, and that
     * the vendor's own front end normalizes it with `c.info && (c.list = c.info)`. Doing the same
     * here means no caller has to remember which endpoint it is talking to.
     */
    val entries: List<JsonObject>
        get() = arrayOf("list", "info")
            .asSequence()
            .mapNotNull { root.arrayOf(it) }
            .firstOrNull()
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    /** The `result` payload used by `/api/gettemplatevariable`. */
    val result: JsonObject? get() = root.objOf("result")

    fun string(vararg keys: String): String? = root.stringOf(*keys)
    fun long(vararg keys: String): Long? = root.longOf(*keys)
    fun int(vararg keys: String): Int? = root.intOf(*keys)
    fun bool(vararg keys: String): Boolean? = root.boolOf(*keys)
    fun array(vararg keys: String): JsonArray? = root.arrayOf(*keys)
    fun obj(vararg keys: String): JsonObject? = root.objOf(*keys)
}
