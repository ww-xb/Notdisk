package com.notdisk.core.network

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The single JSON configuration used by every provider in this app, plus the two decode helpers that
 * turn a raw response body into either a value or a typed [CloudError.ParseError].
 *
 * ### Why one shared instance instead of `Json { }` at each call site
 * A `Json` instance is immutable and caches a schema per serializer, so sharing it is both cheaper
 * and — more importantly — the only way to guarantee that two providers cannot disagree about how
 * leniently they parse. The three deviations from the library defaults are each a direct response to
 * an observed vendor behaviour:
 *
 *  - **`ignoreUnknownKeys = true`** — vendors add fields without notice. A strict decoder turns
 *    "115 started returning `sha1` in listings" into a total outage of file browsing, which is a
 *    catastrophic failure for a purely additive server change.
 *  - **`isLenient = true`** — several providers wrap numbers in quotes inconsistently *between
 *    endpoints* (115 returns `count` as a string in one listing and as a number in another), and
 *    some emit unquoted keys. Leniency here means a mapper does not have to declare every numeric
 *    field as `String` just to survive the vendor's inconsistency.
 *  - **`explicitNulls = false`** — when encoding (request bodies, cache snapshots) an absent value
 *    must be *absent*, not `null`. Providers distinguish "field omitted" from "field present but
 *    null", and sending an explicit `null` has been observed to mean "clear this field" on some
 *    write endpoints.
 *
 * `coerceInputValues` is intentionally left off: it silently substitutes a default for a *wrong
 * type*, which would let a malformed response look like a successful one. A wrong type is a real
 * signal (the vendor changed the contract) and must surface as a [CloudError.ParseError].
 *
 * ### What is deliberately NOT here
 * No `SerializersModule` and no polymorphic registration: a shared module would couple every
 * provider's class hierarchy to this module, and two providers can legitimately use the same
 * discriminator value for different types. Providers that need polymorphism build a `Json` from
 * [JsonCodec.json] with `Json(from = json) { serializersModule = … }`, which keeps this default
 * safe for everyone else.
 */
object JsonCodec {

    /**
     * The shared, configured instance.
     *
     * `@JvmField` is avoided on purpose: exposing it as a property keeps the Kotlin accessor in
     * charge, so a future migration to a lazy or context-aware instance does not break binary
     * compatibility for the providers that are compiled against it.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        // Pretty printing stays off: it would inflate cached payloads and make byte-for-byte
        // comparisons in tests noisy without adding anything a log line does not already give.
        prettyPrint = false
        // A trailing comma appears in hand-edited fixtures; tolerating it costs nothing at runtime
        // and removes a class of "works against the live server, fails against the fixture" bugs.
        allowTrailingComma = true
    }

    /**
     * Parses [body] into [T], or returns `null` when the body is not a valid [T].
     *
     * ### Why a `null`-returning variant exists next to [decode]'s `CloudResult`
     * Not every JSON-shaped failure is an error. Providers routinely probe a response for an
     * optional wrapper — is the payload at the root or under `data`? is this a login page rather
     * than an API answer? — and those probes are *expected* to fail most of the time. Forcing them
     * through [CloudResult] would mean constructing and discarding a `CloudError` per attempt, and
     * would tempt callers into matching on error strings. `null` is the honest signal for "this
     * shape did not match", and callers that need a reason must use [decode].
     *
     * Cancellation is not a concern here: this function is pure and never suspends. It does not
     * catch `Throwable`, only [SerializationException] and [IllegalArgumentException] (thrown for
     * structurally invalid input such as a non-JSON body), so a genuine programming error still
     * propagates.
     */
    inline fun <reified T> decodeOrNull(body: String): T? = try {
        json.decodeFromString<T>(body)
    } catch (serialization: SerializationException) {
        null
    } catch (argument: IllegalArgumentException) {
        null
    }

    /**
     * Parses [body] into [T], mapping any failure to [CloudError.ParseError].
     *
     * ### Why ParseError carries the cause but not the body
     * The body is exactly what must not be logged: it is where 115 puts session credentials, and it
     * is where a CDN error page can echo a signed URL. The [CloudError.ParseError] therefore carries
     * the decoder's exception (which names the offending field and offset — enough to fix a mapper)
     * while the body itself stays with the caller, which is the only place that knows whether it may
     * be retained. [com.notdisk.core.common.logging.LogRedaction] is the sanctioned way to put any
     * of it into a log line.
     */
    inline fun <reified T> decode(body: String): CloudResult<T> = try {
        CloudResult.Success(json.decodeFromString<T>(body))
    } catch (serialization: SerializationException) {
        CloudResult.Failure(
            CloudError.ParseError(
                message = "响应解析失败: ${serialization.message?.take(MAX_REASON_LENGTH) ?: "格式不匹配"}",
                cause = serialization,
            ),
        )
    } catch (argument: IllegalArgumentException) {
        CloudResult.Failure(
            CloudError.ParseError(
                message = "响应解析失败: ${argument.message?.take(MAX_REASON_LENGTH) ?: "输入不是合法 JSON"}",
                cause = argument,
            ),
        )
    }

    /**
     * Encodes [value] to a JSON string, or returns `null` when it cannot be encoded.
     *
     * Used for request bodies and for local cache snapshots, where a failure must not crash the
     * caller. Returning `null` rather than an empty string is deliberate: an empty string is a valid
     * (and dangerous) request body, so conflating the two would send a truncated payload to a write
     * endpoint.
     */
    inline fun <reified T> encodeOrNull(value: T): String? = try {
        json.encodeToString(serializer<T>(), value)
    } catch (serialization: SerializationException) {
        null
    }

    /** The raw, unparsed tree, or `null` when [body] is not JSON at all. */
    fun parseTreeOrNull(body: String): JsonElement? = try {
        json.parseToJsonElement(body)
    } catch (serialization: SerializationException) {
        null
    } catch (argument: IllegalArgumentException) {
        null
    }

    /**
     * Reads a vendor envelope flag that may legitimately be `true`, `"true"`, `1` or `"1"`.
     *
     * ### Why this is in the shared codec rather than in each provider
     * The boolean-rendered-three-ways problem is not specific to one vendor: 115 uses `state` as a
     * JSON boolean on one endpoint and `1`/`0` on another, and a second provider may use `"true"`.
     * Every provider writing its own `when` over these shapes guarantees that one of them forgets a
     * case and silently reads a failure as a success. One implementation, tested once, is the only
     * defensible option.
     *
     * Returns `null` when [container] has no such key, or when the value is not one of the
     * recognised truthy/falsy renderings — absence of an answer is not the same as `false`.
     */
    fun readBooleanFlag(container: JsonObject, key: String): Boolean? {
        val primitive = container[key] as? JsonPrimitive ?: return null
        // `content` gives the literal text for both a boolean and a string primitive, which is what
        // lets the same branch table handle `true`, `"true"` and `"1"`.
        return when (primitive.content.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    /**
     * Reads an integer that the vendor may render as a number or as a quoted string.
     *
     * The same rationale as [readBooleanFlag]: 115 renders `count` and file sizes inconsistently
     * between endpoints, so the conversion has to be written once. Returns `null` for a missing or
     * non-numeric value instead of defaulting to `0`, because a defaulted `0` in a file listing
     * silently claims the folder is empty.
     */
    fun readLongOrNull(container: JsonObject, key: String): Long? {
        val primitive = container[key] as? JsonPrimitive ?: return null
        if (primitive.isString && primitive.content.isBlank()) return null
        return primitive.content.toLongOrNull()
    }

    /**
     * A cap on the length of a vendor-supplied reason copied into a [CloudError] message.
     *
     * Decoder messages embed the offending input, and a vendor body can be hundreds of kilobytes. A
     * truncated reason keeps the error small enough to travel through a `StateFlow` and into a crash
     * report without turning into a payload of its own.
     */
    const val MAX_REASON_LENGTH = 200
}
