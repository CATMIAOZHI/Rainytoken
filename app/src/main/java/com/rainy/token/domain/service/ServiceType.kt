package com.rainy.token.domain.service

import kotlinx.serialization.Serializable

/**
 * 服务类型枚举。集中管理当前支持的服务。
 *
 * 实际服务配置（登录 URL、API 端点、CSS 选择器、降级条件等）见
 * [ServiceConfigProvider]，本枚举只保留身份标识和展示信息。
 */
@Serializable
enum class ServiceType(
    val displayName: String,
    /** 用于在 SecureStorage 中索引的稳定 key（与枚举名解耦，避免重命名丢数据） */
    val storageKey: String
) {
    OPENCODE_GO("OpenCode Go", "opencode_go"),
    COMMANDCODE_GO("CommandCode Go", "commandcode_go"),
    DEEPSEEK("DeepSeek", "deepseek");

    companion object {
        fun fromStorageKey(key: String): ServiceType? =
            entries.firstOrNull { it.storageKey == key }
    }
}