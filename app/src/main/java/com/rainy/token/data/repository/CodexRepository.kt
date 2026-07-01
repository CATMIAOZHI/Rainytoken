package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Singleton

@Singleton
class CodexRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val WHAM_USAGE = "https://chatgpt.com/backend-api/wham/usage"
        private const val OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val REFRESH_BUFFER_MS = 60L * 60 * 1000
    }

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.CODEX)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())
        if (credential !is Credential.CodexCredential)
            return@withContext Result.failure(RepositoryError.InvalidCredential())

        val effectiveCred = if (tokenNeedsRefresh(credential)) {
            val refreshed = refreshToken(credential)
            if (refreshed != null) { credentialRepository.save(refreshed); refreshed } else credential
        } else credential

        val usageResult = try {
            fetchJson(WHAM_USAGE, effectiveCred.accessToken)
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: RepositoryError) {
            if (e is RepositoryError.InvalidCredential && effectiveCred == credential) {
                val retry = refreshToken(credential)
                if (retry != null) {
                    credentialRepository.save(retry)
                    try { fetchJson(WHAM_USAGE, retry.accessToken) }
                    catch (e2: RepositoryError) { return@withContext Result.failure(e2) }
                    catch (e2: IOException) { return@withContext Result.failure(RepositoryError.Network(e2)) }
                    catch (e2: Throwable) { return@withContext Result.failure(RepositoryError.Unknown(e2)) }
                } else return@withContext Result.failure(e)
            } else return@withContext Result.failure(e)
        } catch (e: Throwable) { return@withContext Result.failure(RepositoryError.Unknown(e)) }

        val windows = parseUsageWindows(usageResult)
        if (windows.isEmpty()) return@withContext Result.failure(RepositoryError.ParseError("未找到 Codex 用量窗口数据"))

        val config = ServiceConfigProvider.get(ServiceType.CODEX)
        val primary = windows.firstOrNull { it.label.contains("h") } ?: windows.first()
        val extras = buildMap {
            windows.forEachIndexed { i, w ->
                put("window_$i.label", w.label)
                put("window_$i.remainingPct", w.remainingPct.toString())
                put("window_$i.resetAt", w.resetAt?.toString() ?: "")
            }
            usageResult["plan_type"]?.jsonPrimitive?.content?.let { put("plan", it) }
            usageResult["credits"]?.jsonObject?.let { c ->
                c["balance"]?.jsonPrimitive?.floatOrNull?.let { put("usageCredits", it.toString()) }
            }
        }
        val balance = ServiceBalance(ServiceType.CODEX, primary.remainingPct.toDouble(), config.displayUnit, true, null, null, primary.resetAt, extras)
        balanceCache.put(ServiceType.CODEX, balance)
        credentialRepository.save(effectiveCred.copy(lastVerifiedAt = System.currentTimeMillis()))
        Result.success(balance)
    }

    private fun tokenNeedsRefresh(cred: Credential.CodexCredential): Boolean =
        System.currentTimeMillis() >= cred.expiresAt - REFRESH_BUFFER_MS

    private fun refreshToken(cred: Credential.CodexCredential): Credential.CodexCredential? {
        val bodyStr = json.encodeToString(OAuthRefreshRequest.serializer(),
            OAuthRefreshRequest("refresh_token", cred.refreshToken, CLIENT_ID, "openid profile email"))
        val request = Request.Builder().url(OAUTH_TOKEN_URL)
            .header("Content-Type", "application/json")
            .post(bodyStr.toRequestBody("application/json".toMediaType())).build()
        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val tr = json.decodeFromString(OAuthRefreshResponse.serializer(), resp.body?.string() ?: return@use null)
                cred.copy(accessToken = tr.accessToken, refreshToken = tr.refreshToken,
                    expiresAt = System.currentTimeMillis() + tr.expiresIn * 1000L, lastVerifiedAt = System.currentTimeMillis())
            }
        } catch (e: Exception) { null }
    }

    private fun fetchJson(url: String, token: String): JsonObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("OpenAI-Beta", "codex-1")
            .header("OAI-Language", "en")
            .header("User-Agent", "codex-reset-tracker/0.1")
            .header("originator", "codex-reset-tracker")
            .header("Authorization", "Bearer $token").get().build()
        val resp = okHttpClient.newCall(request).execute()
        resp.use {
            if (!it.isSuccessful) throw if (it.code in listOf(401, 403)) RepositoryError.InvalidCredential() else RepositoryError.ServerError(it.code)
            return json.parseToJsonElement(it.body?.string() ?: throw RepositoryError.ParseError("响应体为空")).jsonObject
        }
    }

    private data class UsageWindow(val label: String, val remainingPct: Int, val resetAt: Long?)

    private fun parseUsageWindows(data: JsonObject): List<UsageWindow> {
        val result = mutableListOf<UsageWindow>()
        fun addWindows(rl: JsonObject?) {
            if (rl == null) return
            for (key in listOf("primary_window", "secondary_window")) {
                val w = rl[key]?.jsonObject ?: continue
                val usedPct = w["used_percent"]?.jsonPrimitive?.floatOrNull ?: continue
                val remaining = (100 - usedPct).toInt().coerceIn(0, 100)
                result.add(UsageWindow(durationLabel(w["limit_window_seconds"]?.jsonPrimitive?.longOrNull), remaining, w["reset_at"]?.jsonPrimitive?.longOrNull?.times(1000L)))
            }
        }
        addWindows(data["rate_limit"]?.jsonObject)
        (data["additional_rate_limits"] as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
            if (item is JsonObject) addWindows(item["rate_limit"]?.jsonObject)
        }
        return result
    }

    private fun durationLabel(seconds: Long?): String = when { seconds == null -> "Usage"; seconds / 60.0 >= 10079 -> "Weekly"; seconds / 60.0 >= 1439 -> "${(seconds / 86400).toInt()}d"; seconds / 60.0 >= 60 -> "${(seconds / 3600).toInt()}h"; else -> "${maxOf(1, (seconds / 60).toInt())}m" }

    @Serializable data class OAuthRefreshRequest(@kotlinx.serialization.SerialName("grant_type") val grantType: String, @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String, @kotlinx.serialization.SerialName("client_id") val clientId: String, val scope: String)
    @Serializable data class OAuthRefreshResponse(@kotlinx.serialization.SerialName("access_token") val accessToken: String, @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String, @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long, @kotlinx.serialization.SerialName("token_type") val tokenType: String? = null)
}