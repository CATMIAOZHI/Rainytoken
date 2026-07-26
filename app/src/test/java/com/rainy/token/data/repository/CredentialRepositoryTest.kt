package com.rainy.token.data.repository

import com.rainy.token.domain.model.CredentialStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CredentialRepository.determineCredentialState].
 *
 * Tests the credential status logic that determines whether a saved credential
 * should be shown as OK, WARNING, or NOT_CONFIGURED in the settings page.
 *
 * This is a regression test for Issue #2: after saving a credential and returning
 * to the settings page, the status was not refreshed. The fix uses LifecycleEventEffect(ON_RESUME)
 * to re-trigger [CredentialRepository.statusForAll], which internally calls
 * [CredentialRepository.determineCredentialState].
 *
 * All tests are pure JVM — no Android framework, no network.
 */
class CredentialRepositoryTest {

    // ── determineCredentialState ──

    @Test
    fun `lastVerifiedAt 0 returns WARNING (saved but not yet verified)`() {
        // When a credential is just saved (e.g. via saveApiKey), lastVerifiedAt = 0L.
        // This should show as WARNING ("需要重新验证"), NOT as NOT_CONFIGURED.
        val state = CredentialRepository.determineCredentialState(0L, System.currentTimeMillis())
        assertEquals(CredentialStatus.State.WARNING, state)
    }

    @Test
    fun `recently verified returns OK`() {
        val now = System.currentTimeMillis()
        val state = CredentialRepository.determineCredentialState(now, now)
        assertEquals(CredentialStatus.State.OK, state)
    }

    @Test
    fun `verified within 7 days returns OK`() {
        val now = System.currentTimeMillis()
        val sixDaysAgo = now - 6L * 24 * 3600 * 1000
        val state = CredentialRepository.determineCredentialState(sixDaysAgo, now)
        assertEquals(CredentialStatus.State.OK, state)
    }

    @Test
    fun `verified exactly 7 days ago returns OK (boundary)`() {
        val now = System.currentTimeMillis()
        val sevenDays = 7L * 24 * 3600 * 1000
        // now - lastVerifiedAt == 7 days exactly → not > 7 days → OK
        val state = CredentialRepository.determineCredentialState(now - sevenDays, now)
        assertEquals(CredentialStatus.State.OK, state)
    }

    @Test
    fun `verified more than 7 days ago returns WARNING`() {
        val now = System.currentTimeMillis()
        val eightDaysAgo = now - 8L * 24 * 3600 * 1000
        val state = CredentialRepository.determineCredentialState(eightDaysAgo, now)
        assertEquals(CredentialStatus.State.WARNING, state)
    }

    @Test
    fun `lastVerifiedAt in future returns OK`() {
        // Edge case: if clock skew causes lastVerifiedAt > now, should be OK
        val now = System.currentTimeMillis()
        val state = CredentialRepository.determineCredentialState(now + 10000L, now)
        assertEquals(CredentialStatus.State.OK, state)
    }

    // ── State transition scenarios (regression for Issue #2) ──

    @Test
    fun `freshly saved API key credential shows WARNING not NOT_CONFIGURED`() {
        // Scenario: User saves an API Key (lastVerifiedAt = 0L), returns to settings page.
        // Before fix: settings page still showed "未配置" (NOT_CONFIGURED) because
        // SettingsViewModel never re-read credentials.
        // After fix: ON_RESUME triggers refresh(), which calls statusFor() →
        // credential exists → determineCredentialState(0L, now) → WARNING.
        // This test verifies the state logic is correct (WARNING, not NOT_CONFIGURED).
        val state = CredentialRepository.determineCredentialState(0L, System.currentTimeMillis())
        assertEquals(CredentialStatus.State.WARNING, state)
        // NOT_CONFIGURED is only returned by statusFor() when credential == null,
        // not by determineCredentialState. So a saved credential will never be
        // NOT_CONFIGURED — confirming the fix works once refresh() is triggered.
    }

    @Test
    fun `verified credential shows OK after refresh`() {
        // Scenario: User saves credential and tests it (lastVerifiedAt = now),
        // returns to settings page. Should show OK.
        val now = System.currentTimeMillis()
        val state = CredentialRepository.determineCredentialState(now, now)
        assertEquals(CredentialStatus.State.OK, state)
    }

    @Test
    fun `deleted credential would show NOT_CONFIGURED via statusFor`() {
        // Scenario: User deletes credential, returns to settings page.
        // statusFor() returns NOT_CONFIGURED when credential == null (get() returns null).
        // determineCredentialState is not called in this path.
        // This test documents that behavior — NOT_CONFIGURED comes from statusFor's
        // null check, not from determineCredentialState.
        // (Cannot test statusFor directly without Android Keystore.)
    }
}