package com.rainy.token.ui.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rainy.token.R
import com.rainy.token.ui.theme.inkMuted
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Usage prices are already in USD. Do not round a nonzero sub-cent charge to zero. */
internal fun formatUsageUsd(usd: Double): String {
    val amount = abs(usd)
    val digits = if (amount >= 0.01 || amount == 0.0) 4 else 10
    val rounded = BigDecimal.valueOf(amount).setScale(digits, RoundingMode.HALF_UP)
    if (amount > 0.0 && rounded.signum() == 0) return if (usd < 0.0) ">−\$0.0000000001" else "<\$0.0000000001"
    val compact = rounded.stripTrailingZeros()
    return (if (usd < 0.0) "−\$" else "\$") + compact.setScale(maxOf(2, compact.scale())).toPlainString()
}

@Composable
internal fun formatKnownUsageUsd(usd: Double, pricedRequests: Long): String =
    if (pricedRequests > 0L) formatUsageUsd(usd) else stringResource(R.string.common_unknown)

@Composable
internal fun UsageCostCoverage() {
    Text(stringResource(R.string.go_usage_partial_cost), style = MaterialTheme.typography.bodySmall, color = inkMuted())
}

@Composable
internal fun UsageCostChart(state: ChartUiState, models: List<String>, onDetail: () -> Unit) {
    val priced = state.buckets.sumOf { it.pricedRequests }
    val requests = state.buckets.sumOf { it.totalRequests }
    val unknown = stringResource(R.string.common_unknown)
    val costModels = models.mapIndexedNotNull { idx, model ->
        if (state.buckets.any { (it.byModel[model]?.pricedRequests ?: 0L) > 0L }) {
            model to modelColors[idx % modelColors.size]
        } else null
    }
    ChartCard(
        stringResource(R.string.chart_cost),
        if (requests > 0L && priced == 0L) unknown else formatUsageUsd(state.buckets.sumOf { it.totalCost }),
        if (requests > 0L) onDetail else null
    ) {
        if (priced < requests) UsageCostCoverage()
        if (priced > 0L || requests == 0L) {
            StackedBarChart(
                buckets = state.buckets,
                valueSelector = { it.totalCost },
                stackSelector = { bucket ->
                    costModels.mapNotNull { (model, color) ->
                        bucket.byModel[model]?.takeIf { it.pricedRequests > 0L }?.let { it.cost to color }
                    }
                },
                stackLabels = { bucket -> costModels.mapNotNull { (model, _) -> model.takeIf { (bucket.byModel[model]?.pricedRequests ?: 0L) > 0L } } },
                formatValue = ::formatUsageUsd,
                granularity = state.granularity,
                legendItems = costModels,
                useUtc8 = state.useUtc8,
                formatBucketTooltip = { bucket ->
                    if (bucket.totalRequests > 0L && bucket.pricedRequests == 0L) unknown else formatUsageUsd(bucket.totalCost)
                }
            )
        }
    }
}

@Composable
internal fun UsageCostByModel(state: ChartUiState, models: List<String>) {
    if (state.buckets.sumOf { it.pricedRequests } < state.buckets.sumOf { it.totalRequests }) UsageCostCoverage()
    models.forEach { model ->
        val stats = state.buckets.mapNotNull { it.byModel[model] }
        if (stats.isNotEmpty()) DetailRow(model, formatKnownUsageUsd(stats.sumOf { it.cost }, stats.sumOf { it.pricedRequests }))
    }
}
