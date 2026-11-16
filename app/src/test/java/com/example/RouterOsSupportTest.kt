package com.example

import com.example.data.repository.RouterOsSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterOsSupportTest {
    @Test
    fun `accepts any RouterOS v6 release and the validated target`() {
        assertTrue(RouterOsSupport.isSupported("6.49.21"))
        assertTrue(RouterOsSupport.isSupported("6.49.21 (stable)"))
        assertTrue(RouterOsSupport.isSupported("6.49.20"))
        assertTrue(RouterOsSupport.isSupported("6.48.7"))
        assertTrue(RouterOsSupport.isSupported("6.43.16"))
        assertTrue(RouterOsSupport.isExactTarget("6.49.21 (long-term)"))
        assertFalse(RouterOsSupport.isExactTarget("6.49.20"))
    }

    @Test
    fun `rejects v7 and unknown versions`() {
        assertFalse(RouterOsSupport.isSupported("7.14.3"))
        assertFalse(RouterOsSupport.isSupported("7.0"))
        assertFalse(RouterOsSupport.isSupported(""))
        assertFalse(RouterOsSupport.isSupported("unknown"))
    }

    @Test
    fun `unsupported message states that only v6 is supported`() {
        val message = RouterOsSupport.unsupportedMessage("7.14.3")
        assertTrue(message.contains("7.14.3"))
        assertTrue(message.contains("v6"))
        assertTrue(message.contains("6.49.21"))
    }
}
