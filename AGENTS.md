# 雨晴Token — AI 余额查询 APP

## 项目概述

Android（Jetpack Compose + Kotlin）APP，统一查询 DeepSeek、OpenCode Go 两项服务的余额/配额。
DeepSeek 走 REST API，OpenCode Go 通过 OkHttp 抓取 dashboard HTML 解析 SSR hydration 数据。
APP 名为「雨晴Token」（粉色调品牌），配套桌面小组件。

## 技术栈

- Kotlin 100% · Jetpack Compose + Material 3
- MVVM + Repository + `RefreshBalanceUseCase`（单一 UseCase，按 ServiceType 分发）
- Hilt + KSP（DI）
- Retrofit 2 + OkHttp 4 + Kotlinx Serialization
- DataStore（本地缓存）+ Android Keystore（凭据加密，AES-256 GCM）
- `minSdk=31`（Android 12+）
- WorkManager（计划中）

## 当前实现状态

**服务**：
- ✅ DeepSeek — REST API `GET /user/balance`，API Key 认证
- ✅ OpenCode Go — OkHttp 抓 dashboard HTML，解析 `rollingUsage`/`weeklyUsage`/`monthlyUsage`
- ❌ OpenCode Zen / 小米 MiMo — 未实现

**用量统计系统**：
- ✅ `UsageCache`（DataStore，~3700 条记录）— 全量 JSON 序列化 + 内存缓存（`@Volatile cachedAll`），仅在写入后失效
- ✅ `SyncUsageUseCase` — 首次全量同步（cursor 翻页）、增量同步（逐页比对本地 ID 集合）
- ✅ `UsageViewModel` — `loadStatsInternal()` 单次 `getRecords()`→ 内存聚合 Overview/ModelStats/DailyStats，所有重操作包在 `withContext(Dispatchers.Default)` 避免主线程卡顿
- ✅ `UsageChartViewModel` — 图表粒度（5h/24h/今天/昨天/7天/当月/自定义日/月/范围），模型多选，3 张 Canvas 图表
- ✅ `UsageDataViewModel` — 原始记录分页浏览（20条/页），支持时间+模型筛选，页码输入跳转
- ✅ 全局刷新绑定 — Dashboard 下拉刷新 → `DashboardViewModel.refresh()` → `UsageViewModel.sync()`（增量）

**页面导航**：
```
Dashboard → UsageDetail（图表） → UsageOverview（总统计）
                 ↘ UsageData（原始数据）
```

**桌面小组件（Widget）**：
- 显示 OpenCode Go 三个用量窗口（5h/本周/本月）+ 进度条 + 重置时间 + DeepSeek 余额
- 右上角刷新按钮（后台广播 → `WidgetRefreshReceiver` → EntryPoints 获取 `RefreshBalanceUseCase`）
- 进度条颜色按百分比动态变化（<50% 草莓粉 / 50-80% 暖橙 / >80% 玫红）

## RemoteViews 兼容性红线

以下元素在 Widget 布局中**不可用**，会导致「载入出现问题」：

| ❌ 不可用 | ✅ 替代方案 |
|-----------|------------|
| `<Space>` | 透明 ProgressBar（`0dp + weight=1`） |
| `<View>` | ProgressBar 或 TextView |
| `<ImageView>` + 矢量 drawable | PNG（`drawable-nodpi`） |
| `<TextView>` `0dp+weight=1` 空串 spacer | ProgressBar spacer |
| `<TextView>` 固定 dp 宽度 + `gravity` | 仅固定 dp，不加 gravity |

## 关键命令

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/Rainytoken
export ANDROID_HOME=$HOME/Android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 数据流

```
DashboardViewModel.refresh()
  → RefreshBalanceUseCase(service)
    → DeepSeekRepository.fetchBalance()    / OpenCodeGoRepository.fetchBalance()
  → BalanceCache.put(service, result)
  → OpenCodeGoWidgetProvider.notifyDataChanged(context)

Dashboard 下拉刷新 → usageSyncTrigger++ → UsageViewModel.sync()
  → SyncUsageUseCase.fullSync() / incrementalSync()
    → OpenCodeUsageRepository.fetchPage(cursor) 逐页抓取
    → UsageCache.insertAll() → persist() → invalidateCache()
  → UsageViewModel.loadStats() → getRecords() → 内存聚合

Widget 刷新按钮：
  ↻ → PendingIntent.getBroadcast() → WidgetRefreshReceiver
    → EntryPoints → RefreshBalanceUseCase(DEEPSEEK + OPENCODE_GO)
    → notifyDataChanged()
```

## 品牌色

| 用途 | 色值 |
|------|------|
| 主品牌草莓粉 | `#FF85A2` |
| 樱粉背景/点缀 | `#FFD1DC` |
| 浅粉背景 | `#FFF0F5` |
| 深暖文字 | `#3D2C35` |
| 暖灰辅助 | `#8A7A82` |
| 玫红（>80% 警示） | `#E91E63` |
| 暖橙（50-80%） | `#FFA726` |