package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.remote.DeepSeekApi
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek 余额仓库。计划 3.2：
 *  - 读 SecureStorage 里的 API Key
 *  - 调用 DeepSeekApi.getBalance
 *  - 错误分级（401 / 429 / 网络 / 5xx）
 *  - 同步写 BalanceCache
 */
@Singleton
class DeepSeekRepository @Inject constructor(
    private val deepSeekApi: DeepSeekApi,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.DEEPSEEK)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.ApiKeyCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val response = try {
            deepSeekApi.getBalance(authorization = "Bearer ${credential.key}")
        } catch (e: HttpException) {
            return@withContext Result.failure(mapHttpError(e))
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        // 优先匹配 CNY 的余额项；匹配不到时取第一个非空 total_balance 的；都没有就拿列表头
        val info = response.balanceInfos.firstOrNull { it.currency.equals("CNY", ignoreCase = true) && it.totalBalance.isNotEmpty() }
            ?: response.balanceInfos.firstOrNull { it.totalBalance.isNotEmpty() }
            ?: response.balanceInfos.firstOrNull()
        val amount = info?.totalBalance?.toDoubleOrNull() ?: 0.0

        val config = ServiceConfigProvider.get(ServiceType.DEEPSEEK)
        val balance = ServiceBalance(
            service = ServiceType.DEEPSEEK,
            amount = amount,
            unit = config.displayUnit,
            isAvailable = response.isAvailable,
            // 把赠送 / 充值明细也带进 extras，详情页能展示拆分
            extras = buildMap {
                info?.let { i ->
                    put("totalBalance", i.totalBalance)
                    put("grantedBalance", i.grantedBalance)
                    put("toppedUpBalance", i.toppedUpBalance)
                }
            }
        )

        // 同步写入本地缓存
        balanceCache.put(ServiceType.DEEPSEEK, balance)
        // 更新凭据的 lastVerifiedAt
        credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

        Result.success(balance)
    }

    private fun mapHttpError(e: HttpException): RepositoryError = when (e.code()) {
        401, 403 -> RepositoryError.InvalidCredential(e)
        429 -> {
            val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
            RepositoryError.RateLimited(retryAfter)
        }
        in 500..599 -> RepositoryError.ServerError(e.code(), e)
        else -> RepositoryError.Unknown(e)
    }
}