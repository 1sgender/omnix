package com.omnix.assistant.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyPolicyLinkTest {

    @Test
    fun `url is built from the flavor base url without hardcoded host`() {
        assertEquals(
            "https://api.example.test/privacy?lang=ru",
            privacyPolicyUrl("https://api.example.test/", "ru-RU")
        )
    }

    @Test
    fun `russian reading regional languages get the russian text`() {
        for (tag in listOf("ru", "tk", "kk-KZ", "uz")) {
            assertEquals("lang for $tag", "https://h/privacy?lang=ru", privacyPolicyUrl("https://h", tag))
        }
    }

    @Test
    fun `other languages get the english text`() {
        for (tag in listOf("en", "en-US", "tr", "de")) {
            assertEquals("lang for $tag", "https://h/privacy?lang=en", privacyPolicyUrl("https://h", tag))
        }
    }
}
