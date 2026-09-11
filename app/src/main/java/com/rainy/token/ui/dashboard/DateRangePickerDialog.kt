package com.rainy.token.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rainy.token.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 日期范围选择弹窗 —— 单弹窗内一次选出起止日期（连续高亮）。
 *
 * - 状态通过 "if (show) Dialog(...)" 条件组合创建：每次打开都会用最新预填值重新初始化；
 * - 预填毫秒一律按 UTC 零点解释（与项目既有 UTC 语义一致）；
 * - 两端日期齐备前，确认按钮保持禁用（M3 范围选择：同一天点两次 = 单日区间）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangePickerDialog(
    title: String,
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    // 预填数据 sanitize：无起点时忽略终点；终点早于起点时忽略终点
    val sanitizedEnd = initialEnd?.takeIf { initialStart != null && !it.isBefore(initialStart) }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.toUtcMillis(),
        initialSelectedEndDateMillis = sanitizedEnd?.toUtcMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val complete = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null
            TextButton(
                onClick = {
                    val startMillis = state.selectedStartDateMillis
                    val endMillis = state.selectedEndDateMillis
                    if (startMillis != null && endMillis != null) {
                        onConfirm(startMillis.toUtcLocalDate(), endMillis.toUtcLocalDate())
                    }
                },
                enabled = complete
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().height(500.dp).padding(16.dp),
            title = { Text(title) },
            showModeToggle = false
        )
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
