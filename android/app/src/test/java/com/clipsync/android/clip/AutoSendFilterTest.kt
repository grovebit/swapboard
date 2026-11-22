package com.clipsync.android.clip

import com.clipsync.android.clip.AutoSendFilter.Decision
import com.clipsync.android.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSendFilterTest {
    private var now = 1_000_000L
    private val echoGuard = EchoGuard(clock = { now })
    private val filter = AutoSendFilter(echoGuard, clock = { now })

    @Test
    fun sendsOrdinaryCopies() {
        assertEquals(Decision.SEND, filter.check("hello", sensitive = false, label = "Chrome"))
        assertEquals(Decision.SEND, filter.check("world", sensitive = false, label = null))
    }

    @Test
    fun skipsOwnWritesSensitiveEmptyAndLargeClips() {
        assertEquals(Decision.OWN_WRITE, filter.check("from mac", false, ClipboardWriter.LABEL))
        assertEquals(Decision.SENSITIVE, filter.check("hunter2", true, "Bitwarden"))
        assertEquals(Decision.EMPTY, filter.check("", false, null))
        assertEquals(Decision.TOO_LARGE, filter.check("x".repeat(Protocol.MAX_TEXT_BYTES + 1), false, null))
    }

    @Test
    fun skipsEchoOfMacClipEvenWithoutOurLabel() {
        echoGuard.recordIncoming("copied on mac")
        assertEquals(Decision.ECHO, filter.check("copied on mac", false, "Samsung Keyboard"))
    }

    @Test
    fun skipsImmediateRepeatsButNotAfterTheWindow() {
        assertEquals(Decision.SEND, filter.check("same", false, null))
        assertEquals(Decision.REPEAT, filter.check("same", false, null))
        assertEquals(Decision.SEND, filter.check("other", false, null))
        assertEquals(Decision.SEND, filter.check("same", false, null))
        now += 11 * 60 * 1000L
        assertEquals(Decision.SEND, filter.check("same", false, null))
    }
}
