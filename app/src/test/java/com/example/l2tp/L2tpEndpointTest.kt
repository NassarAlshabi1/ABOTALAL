package com.example.l2tp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class L2tpEndpointTest {

    @Test
    fun bareHostUsesFallbackPort() {
        val endpoint = parseL2tpEndpoint("ath.vpnbersama.us", 1701)!!
        assertEquals("ath.vpnbersama.us", endpoint.host)
        assertEquals(1701, endpoint.port)
    }

    @Test
    fun explicitPortWins() {
        val endpoint = parseL2tpEndpoint("ath.vpnbersama.us:13196")!!
        assertEquals("ath.vpnbersama.us", endpoint.host)
        assertEquals(13196, endpoint.port)
    }

    @Test
    fun schemeAndPathAreStripped() {
        val endpoint = parseL2tpEndpoint("l2tp://vpn.example.net:1701/profiles")!!
        assertEquals("vpn.example.net", endpoint.host)
        assertEquals(1701, endpoint.port)
    }

    @Test
    fun bracketedIpv6KeepsAddressAndPort() {
        val endpoint = parseL2tpEndpoint("[2001:db8::1]:1701")!!
        assertEquals("2001:db8::1", endpoint.host)
        assertEquals(1701, endpoint.port)
    }

    @Test
    fun bareIpv6KeepsAddressAndFallbackPort() {
        val endpoint = parseL2tpEndpoint("2001:db8::1", 1701)!!
        assertEquals("2001:db8::1", endpoint.host)
        assertEquals(1701, endpoint.port)
    }

    @Test
    fun blankInputIsRejected() {
        assertNull(parseL2tpEndpoint("   "))
        assertNull(parseL2tpEndpoint(""))
    }

    @Test
    fun outOfRangePortIsRejected() {
        assertNull(parseL2tpEndpoint("vpn.example.net:70000"))
        assertNull(parseL2tpEndpoint("vpn.example.net:0"))
    }

    @Test
    fun nonNumericPortIsRejected() {
        assertNull(parseL2tpEndpoint("vpn.example.net:l2tp"))
    }
}
