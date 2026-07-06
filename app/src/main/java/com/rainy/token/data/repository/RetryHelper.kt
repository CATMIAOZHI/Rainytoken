package com.rainy.token.data.repository

import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * 通用重试包装。
 *
 * 对 [RepositoryError.Network] 和 [RepositoryError.ServerError] 执行指数退避重试。
 * 401/403（InvalidCredential）、429（RateLimited）、ParseError 等不重试。
 *
 * @param maxRetries 最大重试次数（不含首次），默认 2
 * @param baseDelayMs 退避基数，默认 1000ms（第二次 = 2000ms）
 * @param block 实际请求逻辑
 */
suspend fun <T> retryOnTransientError(
    maxRetries: Int = 2,
    baseDelayMs: Long = 1000L,
    block: suspend () -> Result<T>
): Result<T> {
    var lastResult = block()
    if (lastResult.isSuccess) return lastResult

    repeat(maxRetries) { attempt ->
        val error = lastResult.exceptionOrNull()
        val shouldRetry = when (error) {
            is RepositoryError.Network -> true
            is RepositoryError.ServerError -> error.code in 500..599
            else -> false
        }
        if (!shouldRetry) return lastResult

        val backoffMs = baseDelayMs * (2.0.pow(attempt).toLong())
        delay(backoffMs)
        lastResult = block()
        if (lastResult.isSuccess) return lastResult
    }
    return lastResult
}