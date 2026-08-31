package com.i5autolock.data.bluelink.eu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuAuthTest {

    @Test
    fun extractsCodeFromFullRedirectUrl() {
        val url = "https://oneapp.hyundai.com/redirect?code=ABC123&state=ccsp"
        assertEquals("ABC123", EuAuth.extractAuthCode(url))
    }

    @Test
    fun extractsCodeWhenNotFirstParam() {
        val url = "https://oneapp.hyundai.com/redirect?state=ccsp&code=XYZ789"
        assertEquals("XYZ789", EuAuth.extractAuthCode(url))
    }

    @Test
    fun returnsNullWhenNoCode() {
        assertNull(EuAuth.extractAuthCode("https://oneapp.hyundai.com/redirect?error=access_denied"))
        assertNull(EuAuth.extractAuthCode("https://oneapp.hyundai.com/redirect"))
    }
}
