package com.w6tech.infinassistant.core.common

import android.util.Log

/**
 * Small logging facade so core modules can emit structured logs without leaking secrets.
 */
interface Logger {
    fun d(tag: String, msg: String, tr: Throwable? = null)
    fun i(tag: String, msg: String, tr: Throwable? = null)
    fun w(tag: String, msg: String, tr: Throwable? = null)
    fun e(tag: String, msg: String, tr: Throwable? = null)
}

class AndroidLogger(
    private val minPriority: Int = Log.DEBUG,
) : Logger {
    override fun d(tag: String, msg: String, tr: Throwable?) {
        if (minPriority <= Log.DEBUG) Log.d(tag, msg, tr)
    }

    override fun i(tag: String, msg: String, tr: Throwable?) {
        if (minPriority <= Log.INFO) Log.i(tag, msg, tr)
    }

    override fun w(tag: String, msg: String, tr: Throwable?) {
        if (minPriority <= Log.WARN) Log.w(tag, msg, tr)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        if (minPriority <= Log.ERROR) Log.e(tag, msg, tr)
    }
}
