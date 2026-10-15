package com.notdisk.core.data.di

import android.util.Log
import com.notdisk.core.common.logging.CloudLogger

/**
 * The Android sink.
 *
 * ### Tag discipline
 * Every line is prefixed with a constant tag so `adb logcat -s NotDisk:*` shows exactly this app's
 * output. The caller passes its own tag as the `tag` argument, and it is appended rather than used as
 * the Logcat tag — a per-class Logcat tag would be truncated at 23 characters on older platforms and
 * make filtering unreliable.
 *
 * ### Release behaviour
 * `debug` and `info` are suppressed outside debug builds. This is not cosmetic: even redacted lines
 * reveal *which* files a user browsed and *when*, so a release build keeps only warnings and errors.
 * The in-memory ring buffer (see `LoggingModule`) still receives everything, because it never leaves
 * the device and is what the in-app diagnostics screen reads.
 */
internal class LogcatCloudLogger(
    private val isDebugBuild: Boolean = runCatching {
        // `BuildConfig.DEBUG` is not available in this module because build config generation is off
        // project-wide. The `Log.isLoggable` check is the platform's own supported equivalent and
        // needs no generated class.
        Log.isLoggable("NotDisk", Log.DEBUG)
    }.getOrDefault(false),
) : CloudLogger {

    override fun d(tag: String, message: String) {
        if (isDebugBuild) Log.d(LOG_TAG, "[$tag] $message")
    }

    override fun i(tag: String, message: String) {
        if (isDebugBuild) Log.i(LOG_TAG, "[$tag] $message")
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        // Warnings are always logged: they indicate something the user may need to act on.
        if (throwable == null) {
            Log.w(LOG_TAG, "[$tag] $message")
        } else {
            Log.w(LOG_TAG, "[$tag] $message", throwable)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        // Errors are always logged, with the stack trace when available.
        if (throwable == null) {
            Log.e(LOG_TAG, "[$tag] $message")
        } else {
            Log.e(LOG_TAG, "[$tag] $message", throwable)
        }
    }

    private companion object {
        const val LOG_TAG = "NotDisk"
    }
}
