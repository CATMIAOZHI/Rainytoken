package com.rainy.token.data.repository

import com.rainy.token.data.local.UsageRecord
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Singleton

/** The documented Console CSV export contains workspace-wide daily summaries, not request logs. */
@Singleton
class OpenCodeUsageRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository
) {
    internal data class Export(
        val cacheScope: String,
        val records: List<UsageRecord>,
        val fromTs: Long,
        val toTs: Long
    )

    internal suspend fun fetchExport(expectedScope: String): Result<Export> = withContext(Dispatchers.IO) {
        try {
            val credential = credentialRepository.get(ServiceType.OPENCODE_GO)
            val key = apiKey(credential) ?: throw RepositoryError.CredentialChanged()
            if (scopeForKey(key) != expectedScope) throw RepositoryError.CredentialChanged()
            val request = Request.Builder()
                .url(EXPORT_API)
                .header("Accept", "text/csv")
                .header("Authorization", "Bearer $key")
                .build()
            val startedAt = Instant.now()
            val result = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(httpError(response))
                } else {
                    val body = response.body ?: throw RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.EMPTY_BODY, "Empty OpenCode usage export"
                    )
                    // The response Date uses the server's clock; requested range counts UTC days.
                    val today = (response.headers.getDate("Date")?.toInstant() ?: startedAt)
                        .atOffset(ZoneOffset.UTC).toLocalDate()
                    val fromTs = today.minusDays(EXPORT_DAYS - 1).toEpochDay() * MILLIS_PER_DAY
                    val toTs = today.toEpochDay() * MILLIS_PER_DAY
                    val records = OpenCodeUsageCsv.parse(body.charStream(), expectedScope)
                    if (records.any { it.timeCreated !in fromTs..toTs }) {
                        throw RepositoryError.ParseError(
                            RepositoryError.ParseErrorReason.MALFORMED_RESPONSE, "Export dates fall outside the requested range"
                        )
                    }
                    Result.success(Export(expectedScope, records, fromTs, toTs))
                }
            }
            if (cacheScope(credentialRepository.get(ServiceType.OPENCODE_GO)) != expectedScope) {
                Result.failure(RepositoryError.CredentialChanged())
            } else result
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepositoryError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(RepositoryError.Network(e))
        } catch (e: Exception) {
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    companion object {
        private const val EXPORT_DAYS = 90L
        private const val EXPORT_API = "https://opencode.ai/console/api/v2/usage/export?range=90d"
        internal const val MILLIS_PER_DAY = 86_400_000L

        /** No workspace ID is exposed by this API. Isolate local history by bearer-key digest. */
        internal fun cacheScope(credential: Credential?): String? = apiKey(credential)?.let(::scopeForKey)

        private fun apiKey(credential: Credential?): String? =
            (credential as? Credential.SessionCredential)?.takeIf { it.service == ServiceType.OPENCODE_GO }?.apiKey?.trim()?.takeIf { it.isNotEmpty() }

        private fun scopeForKey(key: String): String = "opencode-csv:" + MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun httpError(response: Response): RepositoryError = when (response.code) {
            400 -> RepositoryError.OpenCodeUsageBadRequest()
            401 -> RepositoryError.InvalidCredential()
            403 -> RepositoryError.OpenCodeUsageForbidden(forbiddenReason(response.peekBody(4096).string()))
            429 -> RepositoryError.RateLimited(response.header("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 })
            503 -> RepositoryError.OpenCodeUsageSummaryUnavailable()
            else -> RepositoryError.ServerError(response.code)
        }

        private val USAGE_PERMISSION_HINT = Regex("\\busage\\s*[:›>./_-]?\\s*read\\b", RegexOption.IGNORE_CASE)
        private val WORKSPACE_ROLE_HINT = Regex("\\bowner\\s+or\\s+admin(?:istrator)?\\b", RegexOption.IGNORE_CASE)

        private fun forbiddenReason(body: String): RepositoryError.OpenCodeUsageForbiddenReason {
            // The docs specify two causes but do not promise distinct response codes or payloads.
            // Only disambiguate when the server provides a hint; bare Forbidden gets guidance for both.
            val missingPermission = USAGE_PERMISSION_HINT.containsMatchIn(body)
            val missingRole = WORKSPACE_ROLE_HINT.containsMatchIn(body)
            return when {
                missingPermission == missingRole -> RepositoryError.OpenCodeUsageForbiddenReason.UNSPECIFIED
                missingPermission -> RepositoryError.OpenCodeUsageForbiddenReason.MISSING_PERMISSION
                else -> RepositoryError.OpenCodeUsageForbiddenReason.NOT_OWNER_OR_ADMIN
            }
        }
    }
}
