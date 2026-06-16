package com.rainy.token.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * DeepSeek 用户余额 API。
 *
 * 端点：`GET https://api.deepseek.com/user/balance`
 * 认证：Authorization: Bearer <API_KEY>
 *
 * 参考：阶段 3.1。
 */
interface DeepSeekApi {

    @GET("user/balance")
    suspend fun getBalance(
        @Header("Authorization") authorization: String
    ): DeepSeekBalanceResponse
}

/**
 * DeepSeek 余额接口的原始响应。
 *
 * 实际 API 字段（2026-06 真实响应抓取）：
 *  - is_available: 服务是否可用
 *  - balance_infos: 余额明细，按币种分项
 *      - currency: 币种（CNY / USD 等）
 *      - total_balance: 账户总余额（最重要——用户最关心的数字）
 *      - granted_balance: 赠送余额
 *      - topped_up_balance: 自费充值余额
 *
 * 注意：早期文档/SDK 假设字段叫 `amount`，实际服务端从 2024 年某个版本起
 * 已经拆成 `total_balance` / `granted_balance` / `topped_up_balance`。
 * 直接用 `amount` 会抛 MissingFieldException。
 */
@Serializable
data class DeepSeekBalanceResponse(
    @SerialName("is_available")
    val isAvailable: Boolean = true,
    @SerialName("balance_infos")
    val balanceInfos: List<DeepSeekBalanceInfo> = emptyList()
)

@Serializable
data class DeepSeekBalanceInfo(
    val currency: String,
    @SerialName("total_balance")
    val totalBalance: String = "0",
    @SerialName("granted_balance")
    val grantedBalance: String = "0",
    @SerialName("topped_up_balance")
    val toppedUpBalance: String = "0"
)