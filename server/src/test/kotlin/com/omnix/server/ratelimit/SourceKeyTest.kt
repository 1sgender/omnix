package com.omnix.server.ratelimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceKeyTest {

    @Test
    fun `ipv4 is used as is`() {
        assertEquals("203.0.113.7", SourceKey.of("203.0.113.7"))
    }

    @Test
    fun `addresses inside one ipv6 slash 64 share a key`() {
        val a = SourceKey.of("2001:db8:1:2:0:0:0:1")
        val b = SourceKey.of("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        val compressed = SourceKey.of("2001:db8:1:2::9")
        assertEquals(a, b)
        assertEquals(a, compressed)
        assertEquals("2001:0db8:0001:0002::/64", a)
    }

    @Test
    fun `different ipv6 slash 64 prefixes get different keys`() {
        assertNotEquals(
            SourceKey.of("2001:db8:1:2::1"),
            SourceKey.of("2001:db8:1:3::1")
        )
    }

    @Test
    fun `missing or blank address maps to a single unknown bucket`() {
        assertEquals("unknown", SourceKey.of(null))
        assertEquals("unknown", SourceKey.of("   "))
    }

    @Test
    fun `scope id is ignored and unparseable text is kept verbatim`() {
        assertEquals(SourceKey.of("fe80::1"), SourceKey.of("fe80::1%eth0"))
        assertEquals("not:an:address:literal!", SourceKey.of("not:an:address:literal!"))
    }
}
