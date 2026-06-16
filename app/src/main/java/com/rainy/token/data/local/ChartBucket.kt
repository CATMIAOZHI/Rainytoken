package com.rainy.token.data.local

/**
 * 图表数据点——按时间桶聚合后的单条数据。
 */
data class ChartBucket(
    val ts: Long,               // 时间桶起点 epoch ms（小时桶或天桶）
    val totalCost: Long,        // 总花费（原始值，÷1亿=美元）
    val totalRequests: Int,     // API 请求次数
    val inputTokens: Long,      // 未命中缓存的输入 token
    val cacheHitTokens: Long,   // 命中缓存的 token（cacheReadTokens）
    val outputTokens: Long,     // 输出 token
    /** 按模型分项 */
    val byModel: Map<String, ModelBucket> = emptyMap()
)

data class ModelBucket(
    val cost: Long,
    val requests: Int,
    val inputTokens: Long,
    val cacheHitTokens: Long,
    val outputTokens: Long
)

/** 时间粒度 */
enum class ChartGranularity(val label: String) {
    LAST_5H_HOURLY("最近5小时"),
    LAST_24H_HOURLY("最近24小时"),
    TODAY_HOURLY("今天"),
    YESTERDAY_HOURLY("昨天"),
    LAST_7D_DAILY("最近7天"),
    THIS_MONTH_DAILY("当月"),
    CUSTOM_DAY_HOURLY("自定义日"),
    CUSTOM_MONTH_DAILY("自定义月"),
    CUSTOM_RANGE_DAILY("自定义日期")
}

/** 聚合工具 */
object ChartAggregator {

    /**
     * 按小时聚合（用于5小时/当天）。
     * hourStart = timeCreated / 3600_000 * 3600_000
     */
    fun aggregateHourly(records: List<UsageRecord>): List<ChartBucket> {
        return records
            .groupBy { it.timeCreated / 3600_000L * 3600_000L }
            .map { (hourTs, recs) -> bucketOf(hourTs, recs) }
            .sortedBy { it.ts }
    }

    /**
     * 按天聚合（用于当月/自定义月）。
     * dayStart = timeCreated / 86400_000 * 86400_000
     */
    fun aggregateDaily(records: List<UsageRecord>): List<ChartBucket> {
        return records
            .groupBy { it.timeCreated / 86_400_000L * 86_400_000L }
            .map { (dayTs, recs) -> bucketOf(dayTs, recs) }
            .sortedBy { it.ts }
    }

    private fun bucketOf(ts: Long, records: List<UsageRecord>): ChartBucket {
        val byModel = records
            .groupBy { it.model }
            .mapValues { (_, recs) ->
                ModelBucket(
                    cost = recs.sumOf { it.cost },
                    requests = recs.size,
                    inputTokens = recs.sumOf { it.inputTokens },
                    cacheHitTokens = recs.sumOf { it.cacheReadTokens },
                    outputTokens = recs.sumOf { it.outputTokens }
                )
            }
        return ChartBucket(
            ts = ts,
            totalCost = records.sumOf { it.cost },
            totalRequests = records.size,
            inputTokens = records.sumOf { it.inputTokens },
            cacheHitTokens = records.sumOf { it.cacheReadTokens },
            outputTokens = records.sumOf { it.outputTokens },
            byModel = byModel
        )
    }
}