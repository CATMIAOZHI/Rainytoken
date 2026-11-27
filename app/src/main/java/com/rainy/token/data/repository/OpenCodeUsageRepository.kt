package com.rainy.token.data.repository

import com.rainy.token.data.local.OPEN_CODE_LOGS_SCOPE_PREFIX
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OpenCode Console's bearer-authenticated request logs (one row per inference request). */
@Singleton
class OpenCodeUsageRepository(
    okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository
) {
    // Even BASIC logs the query string. The cursor and response must stay out of logs and HTTP caches.
    private val requestLogsClient = okHttpClient.newBuilder().apply {
        interceptors().removeAll { it is HttpLoggingInterceptor }
        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        cache(null)
        followRedirects(false)
    }.build()

    internal suspend fun openSession(scope: String): RequestLogSession {
        val access = credentialRepository.openCodeUsageAccess(scope)
        val key = apiKey(access.credential) ?: throw RepositoryError.CredentialChanged()
        return RequestLogSession(access, key)
    }

    internal inner class RequestLogSession(
        private val access: CredentialRepository.OpenCodeUsageAccess,
        private val key: String
    ) {
        suspend fun fetchPage(since: Long, until: Long, cursor: String?): OpenCodeRequestLogs.Page {
            ensureCurrent()
            val page = try {
                val request = pageRequest(key, since, until, cursor)
                requestLogsClient.newCall(request).awaitPage(access.scope)
            } catch (e: RepositoryError) {
                // Don't surface errors for a key that is no longer active either.
                ensureCurrent()
                throw e
            }
            ensureCurrent()
            return page
        }

        suspend fun ensureCurrent() = credentialRepository.withCurrentOpenCodeUsageAccess(access) {}

        suspend fun saveIfCurrent(save: () -> Unit) =
            credentialRepository.withCurrentOpenCodeUsageAccess(access, save)
    }

    private suspend fun Call.awaitPage(scope: String): OpenCodeRequestLogs.Page =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // IOException messages may contain the full request URL.
                    continuation.resumeWithException(RepositoryError.Network())
                }

                override fun onResponse(call: Call, response: Response) {
                    val page = try {
                        response.use {
                            if (!it.isSuccessful) throw httpError(it)
                            val text = it.body?.string()?.takeUnless(String::isBlank)
                                ?: throw RepositoryError.ParseError(
                                    RepositoryError.ParseErrorReason.EMPTY_BODY, "Empty OpenCode request logs"
                                )
                            OpenCodeRequestLogs.parse(text, scope)
                        }
                    } catch (e: RepositoryError) {
                        continuation.resumeWithException(e)
                        return
                    } catch (_: IOException) {
                        continuation.resumeWithException(RepositoryError.Network())
                        return
                    } catch (_: Exception) {
                        continuation.resumeWithException(RepositoryError.Unknown())
                        return
                    }
                    continuation.resume(page)
                }
            })
        }

    companion object {
        private val REQUEST_LOGS_API = "https://opencode.ai/console/api/request-logs".toHttpUrl()

        /** Isolate saved history by bearer-key digest; old export and HTML data have other scopes. */
        internal fun cacheScope(credential: Credential?): String? = apiKey(credential)?.let(::scopeForKey)

        private fun apiKey(credential: Credential?): String? =
            (credential as? Credential.SessionCredential)?.takeIf { it.service == ServiceType.OPENCODE_GO }
                ?.apiKey?.trim()?.takeIf { it.isNotEmpty() }

        private fun scopeForKey(key: String): String = OPEN_CODE_LOGS_SCOPE_PREFIX +
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun pageRequest(key: String, since: Long, until: Long, cursor: String?): Request {
            val url = REQUEST_LOGS_API.newBuilder()
                .addQueryParameter("since", since.toString())
                .addQueryParameter("until", until.toString())
                .addQueryParameter("category", "inference")
                .addQueryParameter("limit", "100")
                .apply { cursor?.let { addQueryParameter("cursor", it) } }
                .build()
            return Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
        }

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
            // A bare Forbidden gets guidance for both possible causes.
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
