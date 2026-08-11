package com.i5autolock.data.bluelink.eu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuAuthTest {

    @Test
    fun extractsCodeFromFullRedirectUrl() {
        val url = "https://prd.eu-ccapi.hyundai.com:8080/api/v1/user/oauth2/redirect?code=ABC123&state=ccsp"
        assertEquals("ABC123", EuAuth.extractAuthCodeLoose(url))
    }

    @Test
    fun extractsCodeFromQueryFragment() {
        assertEquals("XYZ789", EuAuth.extractAuthCodeLoose("code=XYZ789&foo=bar"))
    }

    @Test
    fun acceptsBareCode() {
        assertEquals("TOKEN-42", EuAuth.extractAuthCodeLoose("  TOKEN-42  "))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(EuAuth.extractAuthCodeLoose(""))
        assertNull(EuAuth.extractAuthCodeLoose("just some words here"))
    }
}
