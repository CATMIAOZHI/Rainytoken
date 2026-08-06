package com.rainy.token.ui.heatmap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.ui.theme.inkMuted

/**
 * Token 活动热力图页面。
 *
 * Scaffold + TopAppBar 布局，包含视图切换器（每日/每周/累计）和 HeatmapCanvas。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(
    onBack: () -> Unit,
    viewModel: HeatmapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.heatmap_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── 视图切换器 ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 4.dp, bottom = 12.dp)
            ) {
                HeatmapViewMode.entries.forEach { mode ->
                    val label = when (mode) {
                        HeatmapViewMode.DAILY -> stringResource(R.string.heatmap_view_daily)
                        HeatmapViewMode.WEEKLY -> stringResource(R.string.heatmap_view_weekly)
                        HeatmapViewMode.CUMULATIVE -> stringResource(R.string.heatmap_view_cumulative)
                    }
                    val isSelected = state.viewMode == mode
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else inkMuted(),
                        modifier = Modifier.clickable { viewModel.setViewMode(mode) }
                    )
                }
            }

            // ── 热力图 Canvas ──
            HeatmapCanvas(
                state = state,
                onDayClick = { dayData ->
                    // Phase 4: 显示浮层
                },
                onWeekClick = { weekData ->
                    // Phase 4: 显示浮层
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}