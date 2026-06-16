package com.rainy.token.domain.model

import com.rainy.token.domain.service.ServiceType
import kotlinx.serialization.Serializable

/**
 * 统一的余额/配额数据模型。所有服务的 Repository 最终都把这个对象返回给上层。
 */
@Serializable
data class ServiceBalance(
    val service: ServiceType,
    /** 余额数字（如 5.20 表示 5.20 元/美元/Credits） */
    val amount: Double,
    /** 单位（¥ / $ / Credits / requests） */
    val unit: String,
    /** 服务是否可用（DeepSeek 的 is_available；其他服务为 true） */
    val isAvailable: Boolean = true,
    /** 本月消费（如有） */
    val monthlySpent: Double? = null,
    /** 总额度（订阅型服务如 Go） */
    val totalQuota: Double? = null,
    /** 配额周期下次重置时间（订阅型服务，epoch millis） */
    val nextResetAt: Long? = null,
    /** 服务特定的附加信息（如 Zen 充值 $20 起，Go 5h 配额） */
    val extras: Map<String, String> = emptyMap()
)