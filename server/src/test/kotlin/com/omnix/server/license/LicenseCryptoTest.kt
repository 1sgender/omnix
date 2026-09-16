package com.omnix.server.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseCryptoTest {

    private val crypto = LicenseCrypto(
        pepper = "test-pepper-with-at-least-32-bytes!!"
    )

    @Test
    fun `canonical omx code normalizes to itself`() {
        assertEquals(
            "OMX-J6P2F-RGTJ6-A63JK-5LYXS",
            crypto.normalizeLicenseCode("OMX-J6P2F-RGTJ6-A63JK-5LYXS")
        )
    }

    @Test
    fun `legacy jrv code stays valid after rebrand`() {
        assertEquals(
            "JRV-J6P2F-RGTJ6-A63JK-5LYXS",
            crypto.normalizeLicenseCode("JRV-J6P2F-RGTJ6-A63JK-5LYXS")
        )
    }

    @Test
    fun `legacy code without dashes is reformatted with legacy prefix`() {
        assertEquals(
            "JRV-J6P2F-RGTJ6-A63JK-5LYXS",
            crypto.normalizeLicenseCode("JRVJ6P2FRGTJ6A63JK5LYXS")
        )
    }

    @Test
    fun `codes are case-insensitive and whitespace-tolerant`() {
        assertEquals(
            "OMX-J6P2F-RGTJ6-A63JK-5LYXS",
            crypto.normalizeLicenseCode("  omx-j6p2f-rgtj6-a63jk-5lyxs\n")
        )
        assertEquals(
            "JRV-J6P2F-RGTJ6-A63JK-5LYXS",
            crypto.normalizeLicenseCode("jrv-j6p2f-rgtj6-a63jk-5lyxs")
        )
    }

    @Test
    fun `unknown prefixes are rejected`() {
        assertNull(crypto.normalizeLicenseCode("XXX-J6P2F-RGTJ6-A63JK-5LYXS"))
        assertNull(crypto.normalizeLicenseCode("JARVIS-1"))
        assertNull(crypto.normalizeLicenseCode(""))
    }

    @Test
    fun `generated codes carry the omx prefix`() {
        repeat(50) {
            assertTrue(crypto.generateLicenseCode().startsWith("OMX-"))
        }
    }
}
