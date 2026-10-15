package com.notdisk.core.common.result

import com.notdisk.core.common.error.CloudError

/**
 * The single, uniform outcome type for every provider/domain operation in the app.
 *
 * Contract rules (enforced by convention and by the shared provider contract tests):
 *  - No provider method throws for an expected failure; it returns [Failure].
 *  - No provider method returns `null` to mean "failed"; absence of a *value* is a domain
 *    concept and must be modelled explicitly by the payload type.
 *  - Cancellation ([kotlinx.coroutines.CancellationException]) is **never** captured into a
 *    [Failure]; it always propagates so structured concurrency keeps working.
 */
sealed interface CloudResult<out T> {

    data class Success<out T>(val data: T) : CloudResult<T>

    data class Failure(val error: CloudError) : CloudResult<Nothing>
}

/** Returns the payload, or `null` when this is a [CloudResult.Failure]. */
fun <T> CloudResult<T>.getOrNull(): T? = when (this) {
    is CloudResult.Success -> data
    is CloudResult.Failure -> null
}

/** Returns the error, or `null` when this is a [CloudResult.Success]. */
fun CloudResult<*>.errorOrNull(): CloudError? = when (this) {
    is CloudResult.Success -> null
    is CloudResult.Failure -> error
}

fun <T> CloudResult<T>.getOrDefault(default: T): T = getOrNull() ?: default

fun <T> CloudResult<T>.isSuccess(): Boolean = this is CloudResult.Success

fun <T> CloudResult<T>.isFailure(): Boolean = this is CloudResult.Failure

inline fun <T, R> CloudResult<T>.map(transform: (T) -> R): CloudResult<R> = when (this) {
    is CloudResult.Success -> CloudResult.Success(transform(data))
    is CloudResult.Failure -> this
}

inline fun <T, R> CloudResult<T>.flatMap(transform: (T) -> CloudResult<R>): CloudResult<R> =
    when (this) {
        is CloudResult.Success -> transform(data)
        is CloudResult.Failure -> this
    }

inline fun <T> CloudResult<T>.onSuccess(action: (T) -> Unit): CloudResult<T> {
    if (this is CloudResult.Success) action(data)
    return this
}

inline fun <T> CloudResult<T>.onFailure(action: (CloudError) -> Unit): CloudResult<T> {
    if (this is CloudResult.Failure) action(error)
    return this
}

inline fun <T, R> CloudResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (CloudError) -> R,
): R = when (this) {
    is CloudResult.Success -> onSuccess(data)
    is CloudResult.Failure -> onFailure(error)
}

/**
 * Unwraps the payload or throws [CloudOperationException].
 *
 * Only call this from code that has already committed to an exception-based boundary
 * (for example a WorkManager worker or a UI event handler that logs and surfaces the error).
 * Provider implementations must return [CloudResult.Failure] instead of throwing.
 */
fun <T> CloudResult<T>.getOrThrow(): T = when (this) {
    is CloudResult.Success -> data
    is CloudResult.Failure -> throw CloudOperationException(error)
}

fun <T> T.asSuccess(): CloudResult<T> = CloudResult.Success(this)

fun CloudError.asFailure(): CloudResult<Nothing> = CloudResult.Failure(this)

/** Exception wrapper used only at explicit, exception-based boundaries. */
class CloudOperationException(val error: CloudError) : Exception(error.toString(), error.cause)

/** Runs [block], converting any non-cancellation throwable into a typed [CloudResult.Failure]. */
inline fun <T> cloudRunCatching(block: () -> T): CloudResult<T> =
    com.notdisk.core.common.error.runCatchingToCloud { block() }
