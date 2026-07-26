package com.rainy.token.data.repository

import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RefreshWriteSessionTest {

    @Test
    fun `rotated Codex token is not overwritten by later stale verification copy`() {
        val original = Credential.CodexCredential(
            service = ServiceType.CODEX,
            accessToken = "access-old",
            refreshToken = "refresh-old",
            accountId = "account-1",
            expiresAt = 1000L,
            lastVerifiedAt = 10L
        )
        val snapshot = CredentialRepository.CredentialSnapshot(
            credential = original,
            revision = 7L,
            fingerprint = CredentialRepository.credentialFingerprint(original)!!
        )
        val session = RefreshWriteSession(snapshot)

        val rotated = original.copy(
            accessToken = "access-new",
            refreshToken = "refresh-new",
            expiresAt = 2000L,
            lastVerifiedAt = 100L
        )
        session.stageCredential(rotated)
        session.stageCredential(original.copy(lastVerifiedAt = 200L))

        val staged = session.stagedCredential() as Credential.CodexCredential
        assertEquals("access-new", staged.accessToken)
        assertEquals("refresh-new", staged.refreshToken)
        assertEquals(2000L, staged.expiresAt)
        assertEquals(200L, staged.lastVerifiedAt)
    }

    @Test
    fun `latest changed credential wins when token rotates more than once`() {
        val original = Credential.CodexCredential(
            service = ServiceType.CODEX,
            accessToken = "access-0",
            refreshToken = "refresh-0",
            accountId = "account-1",
            expiresAt = 1000L
        )
        val session = RefreshWriteSession(
            CredentialRepository.CredentialSnapshot(
                credential = original,
                revision = 1L,
                fingerprint = CredentialRepository.credentialFingerprint(original)!!
            )
        )

        session.stageCredential(original.copy(accessToken = "access-1", refreshToken = "refresh-1"))
        session.stageCredential(original.copy(accessToken = "access-2", refreshToken = "refresh-2"))

        val staged = session.stagedCredential() as Credential.CodexCredential
        assertEquals("access-2", staged.accessToken)
        assertEquals("refresh-2", staged.refreshToken)
    }

    @Test
    fun `balance write is staged for atomic commit`() {
        val credential = Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "sk-test")
        val session = RefreshWriteSession(
            CredentialRepository.CredentialSnapshot(
                credential = credential,
                revision = 0L,
                fingerprint = CredentialRepository.credentialFingerprint(credential)!!
            )
        )
        val balance = ServiceBalance(
            service = ServiceType.DEEPSEEK,
            amount = 12.34,
            unit = "¥"
        )

        session.stageBalance(ServiceType.DEEPSEEK, balance)

        assertNotNull(session.stagedBalance())
        assertEquals(balance, session.stagedBalance())
    }

    @Test
    fun `session reads staged rotated credential on retry`() {
        val original = Credential.CodexCredential(
            service = ServiceType.CODEX,
            accessToken = "access-old",
            refreshToken = "refresh-old",
            accountId = "account-1",
            expiresAt = 1000L
        )
        val session = RefreshWriteSession(
            CredentialRepository.CredentialSnapshot(
                credential = original,
                revision = 2L,
                fingerprint = CredentialRepository.credentialFingerprint(original)!!
            )
        )
        val rotated = original.copy(
            accessToken = "access-new",
            refreshToken = "refresh-new"
        )

        session.stageCredential(rotated)

        assertEquals(rotated, session.credentialForRead())
    }

    @Test
    fun `snapshot must match both revision and fingerprint`() {
        assertEquals(
            true,
            CredentialRepository.snapshotMatches(3L, "fp-a", 3L, "fp-a")
        )
        assertEquals(
            false,
            CredentialRepository.snapshotMatches(3L, "fp-a", 4L, "fp-a")
        )
        assertEquals(
            false,
            CredentialRepository.snapshotMatches(3L, "fp-a", 3L, "fp-b")
        )
    }

    @Test
    fun `Codex token rotation changes auth fingerprint but keeps cache identity`() {
        val original = Credential.CodexCredential(
            service = ServiceType.CODEX,
            accessToken = "access-old",
            refreshToken = "refresh-old",
            accountId = "account-1",
            expiresAt = 1000L
        )
        val rotated = original.copy(
            accessToken = "access-new",
            refreshToken = "refresh-new",
            expiresAt = 2000L
        )

        assertNotEquals(
            CredentialRepository.credentialFingerprint(original),
            CredentialRepository.credentialFingerprint(rotated)
        )
        assertEquals(
            CredentialRepository.cacheIdentityFingerprint(original),
            CredentialRepository.cacheIdentityFingerprint(rotated)
        )
    }

    @Test
    fun `changing balance account invalidates cache identity`() {
        val oldCredential = Credential.ApiKeyCredential(ServiceType.DEEPSEEK, "key-a")
        val newCredential = oldCredential.copy(key = "key-b")

        assertNotEquals(
            CredentialRepository.cacheIdentityFingerprint(oldCredential),
            CredentialRepository.cacheIdentityFingerprint(newCredential)
        )
    }
}
