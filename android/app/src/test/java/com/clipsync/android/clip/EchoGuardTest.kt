package com.clipsync.android.clip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {
    @Test
    fun detectsRecentIncomingTextOnly() {
        var now = 1_000L
        val guard = EchoGuard(clock = { now }, windowMs = 10_000)
        assertFalse(guard.isEcho("hello"))
        guard.recordIncoming("hello")
        assertTrue(guard.isEcho("hello"))
        assertFalse(guard.isEcho("hello!"))
        now += 10_001
        assertFalse(guard.isEcho("hello"))
    }
}
