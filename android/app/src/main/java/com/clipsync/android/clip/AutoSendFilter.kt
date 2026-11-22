package com.clipsync.android.clip

import com.clipsync.android.core.Protocol

/**
 * Decides whether a clipboard change seen by automatic sync should go to the Mac.
 * Mirrors the Mac's rules: never send our own writes or echoes of Mac clips, never auto-send
 * items marked sensitive, and don't resend the same text twice in a row.
 */
class AutoSendFilter(
    private val echoGuard: EchoGuard,
    private val clock: () -> Long = System::currentTimeMillis,
    private val repeatWindowMs: Long = 10 * 60 * 1000L,
) {
    enum class Decision { SEND, EMPTY, OWN_WRITE, SENSITIVE, TOO_LARGE, ECHO, REPEAT }

    @Volatile
    private var last: Pair<String, Long>? = null

    @Synchronized
    fun check(text: String, sensitive: Boolean, label: String?): Decision {
        if (text.isEmpty()) return Decision.EMPTY
        if (label == ClipboardWriter.LABEL) return Decision.OWN_WRITE
        if (sensitive) return Decision.SENSITIVE
        if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT_BYTES) return Decision.TOO_LARGE
        if (echoGuard.isEcho(text)) return Decision.ECHO
        val now = clock()
        val previous = last
        if (previous != null && previous.first == text && now - previous.second <= repeatWindowMs) {
            return Decision.REPEAT
        }
        last = text to now
        return Decision.SEND
    }
}
