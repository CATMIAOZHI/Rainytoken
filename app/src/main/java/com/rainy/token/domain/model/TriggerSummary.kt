package com.rainy.token.domain.model

/**
 * 一键激活用量的结构化响应摘要。
 *
 * Repository 只负责从响应中提取数据，不拼接展示文案；
 * UI 层按当前语言环境用 stringResource 组装本地化文本。
 */
data class TriggerSummary(
    /** 发起请求时使用的模型 */
    val model: String,
    /** 模型回复文本；null 表示响应中不存在回复内容 */
    val reply: String?,
    /** 输入 token 数（OCGO/Ollama 为 prompt_tokens，Codex 为 input_tokens） */
    val inputTokens: String? = null,
    /** 输出 token 数（OCGO/Ollama 为 completion_tokens，Codex 为 output_tokens） */
    val outputTokens: String? = null,
    /** 总 token 数（OCGO/Ollama 的 total_tokens；Codex 无此字段） */
    val totalTokens: String? = null,
    /** Codex 响应 ID */
    val responseId: String? = null,
    /** 响应解析失败，但请求已成功发出 */
    val parseFailed: Boolean = false
)
