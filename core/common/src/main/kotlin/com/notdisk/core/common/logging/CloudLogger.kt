package com.notdisk.core.common.logging

/**
 * A logging facade that is *safe by construction*: it exists so that provider code has an
 * obvious, reviewable path for diagnostics without ever printing credentials.
 *
 * Rules enforced by convention and reviewed in `docs/security.md`:
 *  - Never log tokens, cookies, `pickcode`/`sign`/`k` URL parameters, or full signed URLs.
 *  - Log identifiers (account id, file id) in a hashed/shortened form via [redactId].
 *  - Signed URLs are logged through [redactUrl] which strips the query string entirely.
 */
interface CloudLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** Discards everything. Used in tests and as the default in pure-JVM modules. */
object NoOpCloudLogger : CloudLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

/**
 * In-memory ring buffer used by the in-app diagnostics screen.
 *
 * It is deliberately fed with already-redacted strings: the redaction helpers below are the
 * only sanctioned way to build log lines that contain remote identifiers.
 */
class InMemoryCloudLogger(private val capacity: Int = 300) : CloudLogger {

    data class Entry(val level: Char, val tag: String, val message: String, val timestamp: Long)

    private val buffer = ArrayDeque<Entry>(capacity)
    private val lock = Any()

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    private fun add(level: Char, tag: String, message: String) {
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(Entry(level, tag, message, System.currentTimeMillis()))
        }
    }

    override fun d(tag: String, message: String) = add('D', tag, message)
    override fun i(tag: String, message: String) = add('I', tag, message)
    override fun w(tag: String, message: String, throwable: Throwable?) =
        add('W', tag, if (throwable == null) message else "$message | ${throwable.javaClass.simpleName}")

    override fun e(tag: String, message: String, throwable: Throwable?) =
        add('E', tag, if (throwable == null) message else "$message | ${throwable.javaClass.simpleName}")
}

/** Fans out to several loggers (e.g. ring buffer + logcat). */
class CompositeCloudLogger(private val delegates: List<CloudLogger>) : CloudLogger {
    override fun d(tag: String, message: String) = delegates.forEach { it.d(tag, message) }
    override fun i(tag: String, message: String) = delegates.forEach { it.i(tag, message) }
    override fun w(tag: String, message: String, throwable: Throwable?) =
        delegates.forEach { it.w(tag, message, throwable) }

    override fun e(tag: String, message: String, throwable: Throwable?) =
        delegates.forEach { it.e(tag, message, throwable) }
}

object LogRedaction {

    /** Keeps the first 6 chars of a stable id so log lines remain correlatable but not usable. */
    fun redactId(id: String?): String {
        if (id.isNullOrEmpty()) return "<none>"
        return if (id.length <= 6) "***" else "${id.take(6)}…(${id.length})"
    }

    /**
     * Strips the query string and userinfo from a URL. Signed download/stream URLs carry the
     * credential material in the query, so it must never be logged.
     */
    fun redactUrl(url: String?): String {
        if (url.isNullOrEmpty()) return "<none>"
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val schemeEnd = withoutQuery.indexOf("://")
        val redactedUserInfo = if (schemeEnd >= 0) {
            val rest = withoutQuery.substring(schemeEnd + 3)
            val at = rest.indexOf('@')
            if (at >= 0) withoutQuery.substring(0, schemeEnd + 3) + "***@" + rest.substring(at + 1)
            else withoutQuery
        } else {
            withoutQuery
        }
        return if (withoutQuery.length != url.length) "$redactedUserInfo?<redacted>" else redactedUserInfo
    }
}
