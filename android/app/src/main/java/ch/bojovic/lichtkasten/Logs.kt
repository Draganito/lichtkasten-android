package ch.bojovic.lichtkasten

import android.util.Log

/**
 * The only way the app writes to logcat. Release builds never log a file
 * name, a URI or an exception message: logcat is readable by system apps
 * and over adb, and none of that belongs there. Debug builds get the detail.
 */
object Logs {
    const val TAG = "lichtkasten"

    /** Anything that names a file. Compiled out of release builds. */
    inline fun file(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, msg())
    }

    /** A failure worth seeing in release: fixed text plus the exception class, nothing else. */
    fun w(what: String, e: Throwable? = null) {
        val detail = when {
            e == null -> ""
            BuildConfig.DEBUG -> ": ${e.javaClass.simpleName}: ${e.message}"
            else -> ": ${e.javaClass.simpleName}"
        }
        Log.w(TAG, what + detail)
    }
}
