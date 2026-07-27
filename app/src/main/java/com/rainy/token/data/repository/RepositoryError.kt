package com.rainy.token.data.repository

/**
 * Repository 层的统一错误类型。阶段 3.2 规定的错误分级。
 */
sealed class RepositoryError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 凭据无效（如 401 Unauthorized），detail 为具体原因 */
    class InvalidCredential(detail: String? = null, cause: Throwable? = null) :
        RepositoryError("凭据无效" + (detail?.let { ": $it" } ?: ""), cause)

    /** 请求期间凭据被保存、替换或删除；旧结果已安全丢弃。 */
    class CredentialChanged : RepositoryError("凭据已变更，本次请求结果已丢弃")

    /** 限流（429 Too Many Requests） */
    class RateLimited(val retryAfterSeconds: Long? = null) :
        RepositoryError("请求过于频繁${retryAfterSeconds?.let { "，请 ${it} 秒后重试" } ?: ""}")

    /** 网络问题（超时、IO 等）—— 不标记凭据失效 */
    class Network(cause: Throwable? = null) : RepositoryError("网络异常", cause)

    /** 服务端问题（5xx）—— 不标记凭据失效 */
    class ServerError(val code: Int, cause: Throwable? = null) :
        RepositoryError("服务端异常 (HTTP $code)", cause)

    /** 解析失败（HTML 解析结果为空 / JSON 反序列化失败） */
    class ParseError(message: String, cause: Throwable? = null) :
        RepositoryError("解析失败: $message", cause)

    /** 未知错误 */
    class Unknown(cause: Throwable? = null) : RepositoryError(
        "未知错误" + (cause?.let { ": ${it::class.simpleName}: ${it.message ?: "(no message)"}" } ?: ""),
        cause
    )
}
