package com.chamika.dashtune.signin

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialsFragmentTest {

    @Test
    fun `formatQuickConnectCode splits six digit code in the middle`() {
        assertEquals("123 456", CredentialsFragment.formatQuickConnectCode("123456"))
    }

    @Test
    fun `formatQuickConnectCode preserves leading zeros`() {
        assertEquals("012 345", CredentialsFragment.formatQuickConnectCode("012345"))
    }

    @Test
    fun `formatQuickConnectCode does not crash on short codes`() {
        assertEquals("12", CredentialsFragment.formatQuickConnectCode("12"))
        assertEquals("123", CredentialsFragment.formatQuickConnectCode("123"))
        assertEquals("", CredentialsFragment.formatQuickConnectCode(""))
    }

    @Test
    fun `formatQuickConnectCode handles odd length codes`() {
        assertEquals("12 345", CredentialsFragment.formatQuickConnectCode("12345"))
    }
}
