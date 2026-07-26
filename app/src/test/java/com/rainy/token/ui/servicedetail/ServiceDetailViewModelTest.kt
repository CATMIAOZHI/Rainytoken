package com.rainy.token.ui.servicedetail

import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ServiceDetailViewModel] companion object pure functions.
 *
 * Tests credential fingerprint computation and credential change classification
 * — the core logic for detecting whether a credential was replaced (not just
 * toggled between configured/unconfigured) when returning from the credential
 * edit page.
 *
 * Regression for Codex review P2: "更换凭据后继续显示旧错误或旧账户数据".
 */
class ServiceDetailViewModelTest {

    // ── credentialFingerprint ──

    @Test
    fun `null credential returns null fingerprint`() {
        assertNull(ServiceDetailViewModel.credentialFingerprint(null))
    }

    @Test
    fun `same ApiKeyCredential produces same fingerprint`() {
        val cred = Credential.ApiKeyCredential(
            service = ServiceType.DEEPSEEK,
            key = "sk-abc123",
            lastVerifiedAt = 1000L
        )
        val fp1 = ServiceDetailViewModel.credentialFingerprint(cred)
        val fp2 = ServiceDetailViewModel.credentialFingerprint(cred.copy(lastVerifiedAt = 2000L))
        assertEquals(fp1, fp2)
    }

    @Test
    fun `different ApiKeyCredential keys produce different fingerprints`() {
        val fp1 = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "key-A")
        )
        val fp2 = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "key-B")
        )
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `CodexCredential fingerprint includes accessToken and accountId`() {
        val cred1 = Credential.CodexCredential(
            service = ServiceType.CODEX,
            accessToken = "at-AAA",
            refreshToken = "rt-AAA",
            accountId = "acc-1",
            expiresAt = 1000L
        )
        val cred2 = cred1.copy(accessToken = "at-BBB") // different token
        assertNotEquals(
            ServiceDetailViewModel.credentialFingerprint(cred1),
            ServiceDetailViewModel.credentialFingerprint(cred2)
        )
    }

    @Test
    fun `SessionCredential fingerprint varies with authCookie`() {
        val cred1 = Credential.SessionCredential(
            service = ServiceType.OPENCODE_GO,
            authCookie = "cookie-AAA"
        )
        val cred2 = cred1.copy(authCookie = "cookie-BBB")
        assertNotEquals(
            ServiceDetailViewModel.credentialFingerprint(cred1),
            ServiceDetailViewModel.credentialFingerprint(cred2)
        )
    }

    // ── classifyCredentialChange ──

    @Test
    fun `null to null is NONE_TO_NONE`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.NONE_TO_NONE,
            ServiceDetailViewModel.classifyCredentialChange(null, null)
        )
    }

    @Test
    fun `null to fingerprint is NEW`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.NEW,
            ServiceDetailViewModel.classifyCredentialChange(null, "ak:key-1")
        )
    }

    @Test
    fun `fingerprint to null is DELETED`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.DELETED,
            ServiceDetailViewModel.classifyCredentialChange("ak:key-1", null)
        )
    }

    @Test
    fun `same fingerprint is UNCHANGED`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.UNCHANGED,
            ServiceDetailViewModel.classifyCredentialChange("ak:key-1", "ak:key-1")
        )
    }

    @Test
    fun `different fingerprints is REPLACED`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.REPLACED,
            ServiceDetailViewModel.classifyCredentialChange("ak:key-1", "ak:key-2")
        )
    }

    // ── Scenario coverage (matches Codex review requirements) ──

    @Test
    fun `scenario 1 - invalid credential Error then replaced with valid credential triggers REPLACED`() {
        val oldFp = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "invalid-key")
        )
        val newFp = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "valid-key")
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.REPLACED,
            ServiceDetailViewModel.classifyCredentialChange(oldFp, newFp)
        )
    }

    @Test
    fun `scenario 2 - account A Fresh replaced by account B triggers REPLACED`() {
        val accountA = ServiceDetailViewModel.credentialFingerprint(
            Credential.CodexCredential(
                service = ServiceType.CODEX,
                accessToken = "token-A",
                refreshToken = "rt-A",
                accountId = "acc-A",
                expiresAt = 0L
            )
        )
        val accountB = ServiceDetailViewModel.credentialFingerprint(
            Credential.CodexCredential(
                service = ServiceType.CODEX,
                accessToken = "token-B",
                refreshToken = "rt-B",
                accountId = "acc-B",
                expiresAt = 0L
            )
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.REPLACED,
            ServiceDetailViewModel.classifyCredentialChange(accountA, accountB)
        )
    }

    @Test
    fun `scenario 3 - credential deleted during refresh triggers DELETED`() {
        val oldFp = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "key-1")
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.DELETED,
            ServiceDetailViewModel.classifyCredentialChange(oldFp, null)
        )
    }

    @Test
    fun `scenario 4 - normal resume with unchanged credential triggers UNCHANGED`() {
        val fp = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "same-key")
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.UNCHANGED,
            ServiceDetailViewModel.classifyCredentialChange(fp, fp)
        )
    }
}