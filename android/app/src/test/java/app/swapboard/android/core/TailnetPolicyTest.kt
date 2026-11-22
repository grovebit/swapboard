package app.swapboard.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetPolicyTest {
    @Test
    fun acceptsOnlyTheTailscaleRange() {
        assertTrue(TailnetPolicy.isTailscaleAddress("100.101.102.103"))
        assertTrue(TailnetPolicy.isTailscaleAddress("100.127.255.254"))
        assertFalse(TailnetPolicy.isTailscaleAddress("100.63.255.255"))
        assertFalse(TailnetPolicy.isTailscaleAddress("100.128.0.1"))
        assertFalse(TailnetPolicy.isTailscaleAddress("192.168.1.20"))
        assertFalse(TailnetPolicy.isTailscaleAddress("10.0.0.25"))
        assertFalse(TailnetPolicy.isTailscaleAddress("my-mac.local"))
        assertFalse(TailnetPolicy.isTailscaleAddress("100.64.0"))
        assertFalse(TailnetPolicy.isTailscaleAddress("100.64.0.256"))
        assertFalse(TailnetPolicy.isTailscaleAddress("fd7a:115c:a1e0::1"))
    }

    @Test
    fun filtersPairingHostsKeepingOrder() {
        val hosts = listOf("10.0.0.25", "100.101.102.103", "my-mac.local", "100.100.1.2", "100.101.102.103")
        assertEquals(listOf("100.101.102.103", "100.100.1.2"), TailnetPolicy.allowedHosts(hosts))
        assertEquals(emptyList<String>(), TailnetPolicy.allowedHosts(listOf("192.168.1.5", "mac.local")))
    }
}
