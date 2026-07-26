package com.rainy.token.ui.servicedetail

import com.rainy.token.domain.model.CookieEntry
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests credential fingerprint computation and credential change classification.
 */
class ServiceDetailViewModelTest {

    @Test
    fun `null credential returns null fingerprint`() {
        assertNull(ServiceDetailViewModel.credentialFingerprint(null))
    }

    @Test
    fun `same ApiKeyCredential ignores lastVerifiedAt`() {
        val cred = Credential.ApiKeyCredential(
            service = ServiceType.DEEPSEEK,
            key = "sk-abc123",
            lastVerifiedAt = 1000L
        )
        val fp1 = ServiceDetailViewModel.credentialFingerprint(cred)
        val fp2 = ServiceDetailViewModel.credentialFingerprint(cred.copy(lastVerifiedAt = 2000L))
        assertEquals(fp1, fp2)
        assertEquals(64, fp1?.length)
        assertFalse(fp1.orEmpty().contains(cred.key))
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
    fun `Codex fingerprint varies with access token`() {
        val cred1 = codexCredential()
        val cred2 = cred1.copy(accessToken = "at-BBB")
        assertNotEquals(
            ServiceDetailViewModel.credentialFingerprint(cred1),
            ServiceDetailViewModel.credentialFingerprint(cred2)
        )
    }

    @Test
    fun `Codex fingerprint varies when only refresh token is rotated`() {
        val cred1 = codexCredential()
        val cred2 = cred1.copy(refreshToken = "rt-ROTATED")
        assertNotEquals(
            ServiceDetailViewModel.credentialFingerprint(cred1),
            ServiceDetailViewModel.credentialFingerprint(cred2)
        )
    }

    @Test
    fun `Session fingerprint varies with authCookie`() {
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

    @Test
    fun `Session fingerprint includes Cookie list and is order independent`() {
        val cookieA = CookieEntry(name = "a", value = "1", domain = "example.com")
        val cookieB = CookieEntry(name = "b", value = "2", domain = "example.com")
        val cred1 = Credential.SessionCredential(
            service = ServiceType.OLLAMA,
            cookies = listOf(cookieA, cookieB)
        )
        val reordered = cred1.copy(cookies = listOf(cookieB, cookieA))
        val changed = cred1.copy(cookies = listOf(cookieA, cookieB.copy(value = "3")))

        assertEquals(
            ServiceDetailViewModel.credentialFingerprint(cred1),
            ServiceDetailViewModel.credentialFingerprint(reordered)
        )
        assertNotEquals(
            ServiceDetailViewModel.credentialFingerprint(cred1),
            ServiceDetailViewModel.credentialFingerprint(changed)
        )
    }

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
            ServiceDetailViewModel.classifyCredentialChange(null, "fp-1")
        )
    }

    @Test
    fun `fingerprint to null is DELETED`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.DELETED,
            ServiceDetailViewModel.classifyCredentialChange("fp-1", null)
        )
    }

    @Test
    fun `same fingerprint is UNCHANGED`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.UNCHANGED,
            ServiceDetailViewModel.classifyCredentialChange("fp-1", "fp-1")
        )
    }

    @Test
    fun `different fingerprints is REPLACED`() {
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.REPLACED,
            ServiceDetailViewModel.classifyCredentialChange("fp-1", "fp-2")
        )
    }

    @Test
    fun `invalid credential replaced by valid credential is REPLACED`() {
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
    fun `account A replaced by account B is REPLACED`() {
        val accountA = ServiceDetailViewModel.credentialFingerprint(codexCredential())
        val accountB = ServiceDetailViewModel.credentialFingerprint(
            codexCredential().copy(
                accessToken = "token-B",
                refreshToken = "rt-B",
                accountId = "acc-B"
            )
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.REPLACED,
            ServiceDetailViewModel.classifyCredentialChange(accountA, accountB)
        )
    }

    @Test
    fun `credential deleted during refresh is DELETED`() {
        val oldFp = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "key-1")
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.DELETED,
            ServiceDetailViewModel.classifyCredentialChange(oldFp, null)
        )
    }

    @Test
    fun `normal resume with unchanged credential is UNCHANGED`() {
        val fp = ServiceDetailViewModel.credentialFingerprint(
            Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "same-key")
        )
        assertEquals(
            ServiceDetailViewModel.Companion.CredentialChange.UNCHANGED,
            ServiceDetailViewModel.classifyCredentialChange(fp, fp)
        )
    }

    private fun codexCredential(): Credential.CodexCredential =
        Credential.CodexCredential(
            service = ServiceType.CODEX,
            accessToken = "at-AAA",
            refreshToken = "rt-AAA",
            accountId = "acc-1",
            expiresAt = 1000L
        )
}
