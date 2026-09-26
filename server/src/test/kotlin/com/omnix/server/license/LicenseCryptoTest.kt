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
    fun `box code from the clip card normalizes to itself`() {
        assertEquals("A7B2C3", crypto.normalizeLicenseCode("A7B2C3"))
    }

    @Test
    fun `box code is case-insensitive, whitespace and dash tolerant`() {
        assertEquals("A7B2C3", crypto.normalizeLicenseCode(" a7b2c3 "))
        assertEquals("A7B2C3", crypto.normalizeLicenseCode("a7b2-c3"))
    }

    @Test
    fun `box codes never contain ambiguous characters`() {
        repeat(200) {
            val code = crypto.generateBoxCode()
            assertEquals(LicenseCrypto.BOX_CODE_LENGTH, code.length)
            assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
            assertEquals(code, crypto.normalizeLicenseCode(code))
        }
    }

    @Test
    fun `seven characters or digits zero and one are not box codes`() {
        assertNull(crypto.normalizeLicenseCode("A7B2C3D"))
        assertNull(crypto.normalizeLicenseCode("ABC01E"))
        // Формат мягкий (как у длинного кодека): I/O проходят normalize и
        // попадают в not-found по хэшу — на карточке их не печатают.
        assertEquals("ABCDIO", crypto.normalizeLicenseCode("ABCDIO"))
    }

    @Test
    fun `long omx codes still normalize after the box-code change`() {
        assertEquals(
            "OMX-J6P2F-RGTJ6-A63JK-5LYXS",
            crypto.normalizeLicenseCode("omx-j6p2f-rgtj6-a63jk-5lyxs")
        )
    }

    @Test
    fun `code hint exposes less of a short code than of a long one`() {
        assertEquals("2C3", crypto.codeHint("A7B2C3"))
        assertEquals("5LYXS", crypto.codeHint("OMX-J6P2F-RGTJ6-A63JK-5LYXS"))
    }

    @Test
    fun `generated codes carry the omx prefix`() {
        repeat(50) {
            assertTrue(crypto.generateLicenseCode().startsWith("OMX-"))
        }
    }
}
