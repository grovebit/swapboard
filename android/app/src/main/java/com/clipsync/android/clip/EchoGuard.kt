package com.clipsync.android.clip

import java.security.MessageDigest

/**
 * Remembers (a hash of) the last text that arrived from the Mac, so a one-tap "send clipboard"
 * right after doesn't bounce the same text back. Explicit share/selection sends skip this check.
 */
class EchoGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 10 * 60 * 1000L,
) {
    @Volatile
    private var last: Pair<String, Long>? = null

    fun recordIncoming(text: String) {
        last = hash(text) to clock()
    }

    fun isEcho(text: String): Boolean {
        val (h, at) = last ?: return false
        return clock() - at <= windowMs && h == hash(text)
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
