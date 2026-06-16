package com.rainy.token.domain.model

import com.rainy.token.domain.service.ServiceType

/**
 * 凭据配置状态。用于凭据管理页展示 + 控制是否允许跳转到服务详情。
 */
data class CredentialStatus(
    val service: ServiceType,
    val state: State,
    val lastVerifiedAt: Long
) {
    enum class State {
        /** 尚未配置任何凭据 */
        NOT_CONFIGURED,

        /** 已配置且最近一次校验通过 */
        OK,

        /** 已配置但最近一次校验失败（如 401），需重新配置 */
        EXPIRED,

        /** 凭据疑似有问题但未确认（连续失败但还没达到 EXPIRED 阈值） */
        WARNING
    }
}