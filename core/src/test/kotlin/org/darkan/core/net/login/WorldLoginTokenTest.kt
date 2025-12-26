package org.darkan.core.net.login

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldLoginTokenTest {

    @Test
    fun `token roundtrip verifies and exposes fields`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            displayName = "Test User",
            rights = 2,
            nowMs = now,
            ttlMs = 60_000L,
            secret = secret,
        )

        val verified = LoginToken.verify(token, nowMs = now + 1, secret = secret)
        assertNotNull(verified)
        assertTrue(verified.username == "test_user")
        assertTrue(verified.displayName == "Test User")
        assertTrue(verified.rights == 2)
        assertTrue(verified.expiresAtMs > now)
    }

    @Test
    fun `token tamper fails`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            displayName = "Test User",
            rights = 2,
            nowMs = now,
            ttlMs = 60_000L,
            secret = secret,
        )

        val tampered = token.replace("test_user", "evil_user")
        val verified = LoginToken.verify(tampered, nowMs = now + 1, secret = secret)
        assertNull(verified)
    }

    @Test
    fun `expired token fails`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            displayName = "Test User",
            rights = 2,
            nowMs = now,
            ttlMs = 10L,
            secret = secret,
        )

        val verified = LoginToken.verify(token, nowMs = now + 11, secret = secret)
        assertNull(verified)
    }
}


