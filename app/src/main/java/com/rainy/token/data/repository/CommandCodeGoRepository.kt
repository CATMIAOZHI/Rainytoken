package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Singleton

/**
 * CommandCode Go 余额仓库。
 *
 * 调 JSON API 获取月度配额余额 + 用量窗口信息：
 *   GET https://api.commandcode.ai/alpha/billing/credits
 *   Authorization: Bearer <API Key>
 *
 * 调 subscription 端点获取计划信息（用来算已用/总量百分比）：
 *   GET https://api.commandcode.ai/alpha/billing/subscriptions
 *   Authorization: Bearer <API Key>
 */
@Singleton
class CommandCodeGoRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiBase = "https://api.commandcode.ai"

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.COMMANDCODE_GO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.ApiKeyCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val apiKey = credential.key
        if (apiKey.isBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        // 并行拉取 credits + subscriptions
        val creditsResult = runCatching { fetchCredits(apiKey) }
        val subResult = runCatching { fetchSubscription(apiKey) }

        val creditsPayload = creditsResult.getOrElse { e ->
            return@withContext Result.failure(
                if (e is IOException) RepositoryError.Network(e)
                else RepositoryError.Unknown(e)
            )
        }

        // 从订阅信息拿计划名称，查 plan catalog 拿总量
        val monthlyTotal = subResult.getOrNull()?.let { sub ->
            PLANS[sub.planId.lowercase()]
        }
        val billingPeriodEndMillis = subResult.getOrNull()?.let { parseIsoToEpoch(it.currentPeriodEnd) }

        val config = ServiceConfigProvider.get(ServiceType.COMMANDCODE_GO)

        val used = monthlyTotal?.let { total ->
            maxOf(0.0, total - creditsPayload.monthlyCredits)
        }

        val extras = buildMap {
            put("monthlyRemaining", creditsPayload.monthlyCredits.toString())
            put("purchasedCredits", creditsPayload.purchasedCredits.toString())
            put("freeCredits", creditsPayload.freeCredits.toString())
            monthlyTotal?.let { put("monthlyTotal", it.toString()) }
            used?.let { put("monthlyUsed", it.toString()) }
            creditsPayload.fiveHourUsed?.let { put("fiveHour.used", it.toString()) }
            creditsPayload.fiveHourCap?.let { put("fiveHour.cap", it.toString()) }
            creditsPayload.fiveHourResetAt?.let { put("fiveHour.resetInSec", epochToRemainingSec(it).toString()) }
            creditsPayload.weeklyUsed?.let { put("weekly.used", it.toString()) }
            creditsPayload.weeklyCap?.let { put("weekly.cap", it.toString()) }
            creditsPayload.weeklyResetAt?.let { put("weekly.resetInSec", epochToRemainingSec(it).toString()) }
            billingPeriodEndMillis?.let { 
                put("billingPeriodEnd", it.toString())
                put("monthly.resetInSec", epochToRemainingSec(it).toString())
            }
            subResult.getOrNull()?.planId?.let { put("planId", it) }
            subResult.getOrNull()?.planId?.let { put("planName", planDisplayName(it)) }
        }

        val balance = ServiceBalance(
            service = ServiceType.COMMANDCODE_GO,
            amount = creditsPayload.monthlyCredits,
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = used,
            totalQuota = monthlyTotal,
            nextResetAt = billingPeriodEndMillis,
            extras = extras
        )

        balanceCache.put(ServiceType.COMMANDCODE_GO, balance)
        credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

        Result.success(balance)
    }

    private fun fetchCredits(apiKey: String): CreditsPayload {
        val request = Request.Builder()
            .url("$apiBase/alpha/billing/credits")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
        }

        val body = response.body?.string() ?: throw IOException("空响应体")
        val root = json.parseToJsonElement(body).jsonObject
        val credits = root["credits"]?.jsonObject ?: throw IOException("缺少 credits 字段")

        fun getDouble(obj: JsonObject, key: String): Double =
            obj[key]?.jsonPrimitive?.double ?: 0.0

        fun getLong(obj: JsonObject, key: String): Long? =
            obj[key]?.jsonPrimitive?.long

        val fiveHour = root["windowLimits"]?.jsonObject?.let { limits ->
            if (limits["limited"]?.jsonPrimitive?.content == "true") {
                limits["fiveHour"]?.jsonObject
            } else null
        }
        val weekly = root["windowLimits"]?.jsonObject?.let { limits ->
            if (limits["limited"]?.jsonPrimitive?.content == "true") {
                limits["weekly"]?.jsonObject
            } else null
        }

        return CreditsPayload(
            monthlyCredits = getDouble(credits, "monthlyCredits"),
            purchasedCredits = getDouble(credits, "purchasedCredits"),
            freeCredits = getDouble(credits, "freeCredits"),
            fiveHourUsed = fiveHour?.let { getDouble(it, "used") },
            fiveHourCap = fiveHour?.let { getDouble(it, "cap") },
            fiveHourResetAt = fiveHour?.let { getLong(it, "resetAt") },
            weeklyUsed = weekly?.let { getDouble(it, "used") },
            weeklyCap = weekly?.let { getDouble(it, "cap") },
            weeklyResetAt = weekly?.let { getLong(it, "resetAt") }
        )
    }

    private fun fetchSubscription(apiKey: String): SubscriptionPayload? {
        val request = Request.Builder()
            .url("$apiBase/alpha/billing/subscriptions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Exception) {
            return null
        }

        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null

        return try {
            val root = json.parseToJsonElement(body).jsonObject
            if (root["success"]?.jsonPrimitive?.content != "true") return null
            val data = root["data"]?.jsonObject ?: return null
            SubscriptionPayload(
                planId = data["planId"]?.jsonPrimitive?.content.orEmpty(),
                currentPeriodEnd = data["currentPeriodEnd"]?.jsonPrimitive?.content
            )
        } catch (_: Exception) { null }
    }

    private fun planDisplayName(planId: String): String =
        PLAN_NAMES[planId.lowercase()] ?: planId

    companion object {
        private val PLANS = mapOf(
            "individual-go" to 10.0,
            "individual-pro" to 30.0,
            "individual-max" to 150.0,
            "individual-ultra" to 300.0
        )

        private val PLAN_NAMES = mapOf(
            "individual-go" to "Go",
            "individual-pro" to "Pro",
            "individual-max" to "Max",
            "individual-ultra" to "Ultra"
        )

        /** API 返回的是 epoch millis，转为距现在的剩余秒数 */
        private fun epochToRemainingSec(epochMillis: Long): Long =
            maxOf(0L, (epochMillis - System.currentTimeMillis()) / 1000)

        private fun parseIsoToEpoch(isoStr: String?): Long? {
            if (isoStr == null) return null
            return try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(isoStr.take(19))?.time
            } catch (_: Exception) { null }
        }
    }

    private data class CreditsPayload(
        val monthlyCredits: Double,
        val purchasedCredits: Double,
        val freeCredits: Double,
        val fiveHourUsed: Double?,
        val fiveHourCap: Double?,
        val fiveHourResetAt: Long?,
        val weeklyUsed: Double?,
        val weeklyCap: Double?,
        val weeklyResetAt: Long?
    )

    private data class SubscriptionPayload(
        val planId: String,
        val currentPeriodEnd: String?
    )
}