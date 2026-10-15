package com.notdisk.provider.p115.api

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant reader for 115's response envelopes.
 *
 * ### Why not just `@Serializable` data classes
 * `API_REFERENCE.md` §1.3 and §15.2 document four independent inconsistencies in the *same* API:
 *
 *  1. `state` is `true/false` on file endpoints and `1/0` on auth endpoints — the manual's explicit
 *     advice is to normalise with `!!state`.
 *  2. The error code arrives as `errno` on some endpoints and `code` on others, as a number on some
 *     and a string on others (`"errno":""` appears in real bodies).
 *  3. Fields change *type* between endpoints: §4 notes that in search results `aid`/`s`/`sta`/`t`
 *     are strings, whereas in list responses they are numbers.
 *  4. Booleans arrive as `0`/`1` integers, `"0"`/`"1"` strings, and real booleans.
 *
 * A strict `@Serializable` model would fail to deserialise valid production responses. So the
 * envelope is normalised once, here, into [P115Envelope], and the DTO mapping layer only ever sees
 * normalised primitives.
 *
 * ### Naming
 * The accessors are named `…Of` rather than `…OrNull` on purpose: `kotlinx.serialization` already
 * defines `longOrNull`/`intOrNull`/`doubleOrNull`/`booleanOrNull` as properties on `JsonPrimitive`,
 * and an extension function with the same name on `JsonObject` is silently shadowed by them —
 * producing a confusing "cannot invoke as a function" error rather than a clean clash.
 */
internal object P115Json {

    /**
     * `ignoreUnknownKeys` is essential: the manual documents 80+ field `user/setting` responses and
     * list items whose field set varies by endpoint, so the client must not break when 115 adds a
     * field.
     */
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    // ------------------------------------------------------------------ envelope

    /**
     * Normalises the three documented envelope shapes (A/B/C) into one structure.
     *
     * The A/B/C split is a *documentation* device, not something the client should branch on: the
     * client needs exactly three facts — did it succeed, what is the payload, and what is the error.
     */
    fun parseEnvelope(body: String, httpStatus: Int): CloudResult<P115Envelope> {
        val root = try {
            lenient.parseToJsonElement(body) as? JsonObject
                ?: return CloudResult.Failure(
                    CloudError.ParseError(message = "115 响应不是 JSON 对象（HTTP $httpStatus）"),
                )
        } catch (t: Throwable) {
            return CloudResult.Failure(
                CloudError.ParseError(message = "115 响应解析失败（HTTP $httpStatus）", cause = t),
            )
        }

        val state = root["state"].asBoolOrNull()
        val errno = root["errno"].asIntOrNull()
        val code = root["code"].asIntOrNull()
        // `errno` is the file-API spelling, `code` the auth-API spelling (§1.3, §14).
        val errorCode = errno ?: code
        // `error`, `message` and `msg` are used interchangeably across endpoints (§1.3, §14).
        val errorMessage = root.stringOf("error", "message", "msg", "errtype")

        val succeeded = when {
            // An explicit falsy state is authoritative.
            state == false -> false
            // A non-zero code is a failure even when `state` is absent or truthy: the manual shows
            // `{"state":false,"code":4011,…}` and `{"state":true,…,"errno":""}` both occurring.
            errorCode != null && errorCode != 0 -> false
            // `errno` legitimately arrives as the empty string, which means "no error".
            httpStatus in 200..299 -> true
            else -> false
        }

        if (!succeeded) {
            return CloudResult.Failure(
                mapP115Error(code = errorCode, message = errorMessage, httpStatus = httpStatus),
            )
        }

        return CloudResult.Success(
            P115Envelope(
                root = root,
                state = state,
                code = errorCode,
                message = errorMessage,
                httpStatus = httpStatus,
            ),
        )
    }
}

// ------------------------------------------------------------------ primitive coercion

/**
 * Reads a boolean from any of the shapes 115 actually uses: `true`, `1`, `"1"`, `"true"`.
 * Per the manual, `state` must be treated as `!!state`.
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

/**
 * Reads a long from a number *or* a numeric string.
 *
 * Required because §4 documents `s` (size) arriving as a string in search results while being a
 * number in list results, and because 115 sends 64-bit ids as strings to avoid JS precision loss
 * (`"fid":"3289386448728834697"`).
 */
internal fun JsonElement?.asLongOrNull(): Long? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    primitive.longOrNull?.let { return it }
    val text = primitive.content.trim()
    if (text.isEmpty()) return null
    return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
}

internal fun JsonElement?.asIntOrNull(): Int? =
    asLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonElement?.asDoubleOrNull(): Double? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    primitive.doubleOrNull?.let { return it }
    return primitive.content.trim().toDoubleOrNull()
}

/** Reads a string, treating a JSON number as its textual form (ids are often numeric). */
internal fun JsonElement?.asStringOrNull(): String? {
    val primitive = (this as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/** Reads a string, mapping blank to `null` — 115 uses `""` liberally to mean "absent". */
internal fun JsonElement?.asNonBlankStringOrNull(): String? =
    asStringOrNull()?.takeIf { it.isNotBlank() }

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

internal fun JsonObject.doubleOf(vararg keys: String): Double? {
    for (key in keys) {
        this[key].asDoubleOrNull()?.let { return it }
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

/** A successfully-normalised 115 response envelope. */
internal data class P115Envelope(
    val root: JsonObject,
    val state: Boolean?,
    val code: Int?,
    val message: String?,
    val httpStatus: Int,
) {
    /** The `data` payload, when present. */
    val data: JsonElement? get() = root["data"]

    val dataObject: JsonObject? get() = data as? JsonObject

    val dataArray: JsonArray? get() = data as? JsonArray

    /** `data` coerced to a list, accepting either a JSON array or a single object. */
    val dataAsArray: JsonArray
        get() = when (val payload = data) {
            is JsonArray -> payload
            is JsonObject -> JsonArray(listOf(payload))
            else -> JsonArray(emptyList())
        }

    fun int(vararg keys: String): Int? = root.intOf(*keys)
    fun long(vararg keys: String): Long? = root.longOf(*keys)
    fun string(vararg keys: String): String? = root.stringOf(*keys)
    fun bool(vararg keys: String): Boolean? = root.boolOf(*keys)
    fun array(vararg keys: String): JsonArray? = root.arrayOf(*keys)
    fun obj(vararg keys: String): JsonObject? = root.objOf(*keys)

    fun dataString(vararg keys: String): String? =
        dataObject?.stringOf(*keys)

    fun dataLong(vararg keys: String): Long? =
        dataObject?.longOf(*keys)

    fun dataInt(vararg keys: String): Int? =
        dataObject?.intOf(*keys)

    fun dataBool(vararg keys: String): Boolean? =
        dataObject?.boolOf(*keys)

    fun dataObj(vararg keys: String): JsonObject? =
        dataObject?.objOf(*keys)

    fun dataArrayOf(vararg keys: String): JsonArray? =
        dataObject?.arrayOf(*keys)

    /** Maps this envelope to [R], failing with a typed parse error when the mapping yields `null`. */
    fun <R : Any> mapData(field: String, transform: (P115Envelope) -> R?): CloudResult<R> =
        transform(this)?.let { CloudResult.Success(it) }
            ?: CloudResult.Failure(CloudError.ParseError(message = "115 响应缺少字段 `$field`"))
}
