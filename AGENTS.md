# 雨晴Token — AI 余额查询 APP

## 项目概述

Android（Jetpack Compose + Kotlin）APP，统一查询 DeepSeek、OpenCode Go、CommandCode Go 三项服务的余额/配额。
DeepSeek 走 REST API，OpenCode Go 通过 OkHttp 抓取 dashboard HTML 解析 SSR hydration 数据。
CommandCode Go 走 JSON API 抓取用量数据。APP 名为「雨晴Token」（粉色调品牌），配套桌面小组件。

## 技术栈

- Kotlin 100% · Jetpack Compose + Material 3
- MVVM + Repository + `RefreshBalanceUseCase`（单一 UseCase，按 ServiceType 分发）
- Hilt + KSP（DI）
- Retrofit 2 + OkHttp 4 + Kotlinx Serialization
- DataStore（本地缓存）+ Android Keystore（凭据加密，AES-256 GCM）
- `minSdk=31`（Android 12+）
- `material3-window-size-class` — 平板自适应布局
- WorkManager（计划中）

## 当前实现状态

**服务**：
- ✅ DeepSeek — REST API `GET /user/balance`，API Key 认证
- ✅ OpenCode Go — OkHttp 抓 dashboard HTML，解析 `rollingUsage`/`weeklyUsage`/`monthlyUsage`
- ✅ CommandCode Go — JSON API 抓取用量数据，`CommandCodeUsageRepository` 解析（workspaceId = `"commandcode"`）
- ❌ OpenCode Zen / 小米 MiMo — 未实现

**用量统计系统**：
- ✅ `UsageCache`（DataStore，~3700 条记录）— 全量 JSON 序列化 + 内存缓存（`@Volatile cachedAll`），仅在写入后失效
- ✅ `SyncUsageUseCase`（OCGO）/ `SyncCommandCodeUsageUseCase`（CCGO） — 首次全量同步（cursor 翻页）、增量同步（逐页比对本地 ID 集合）
- ✅ `UsageViewModel` — `loadStatsInternal()` 单次 `getRecords()`→ 内存聚合 Overview/ModelStats/DailyStats，所有重操作包在 `withContext(Dispatchers.Default)` 避免主线程卡顿
- ✅ `UsageChartViewModel` — 图表粒度（5h/**12h(10min)**/24h/今天/昨天/7天/当月/自定义日/月/范围），模型多选，3 张 Canvas 图表；支持 **UTC+0/UTC+8 时区切换**（桶边界+标签双感知）；**自动降级**（5h无数据→12h→7天→当月）
- ✅ `ChartSettingsStore`（DataStore Preferences）— 持久化 UTC 偏好，下次进入自动恢复
- ✅ `UsageDataViewModel` — 原始记录分页浏览（20条/页），支持时间+模型筛选，页码输入跳转
- ✅ 全局刷新绑定 — Dashboard 下拉刷新 → `DashboardViewModel.refresh()` → `UsageViewModel.sync()`（增量）

**ViewModel 加载机制红线**：

> ⚠️ 三个 ViewModel 的 `init` 块**已移除**，不再自动加载。数据加载由 Composable 层的 `LaunchedEffect(Unit)` 显式触发：
> - OCGO 页面：`LaunchedEffect(Unit) { viewModel.load() / loadStats() / loadData() }`
> - CCGO 页面：`LaunchedEffect(Unit) { viewModel.setWorkspace(wid) }`（`setWorkspace` 内部调 `load()`）
> - CCGO 页面通过 `autoLoad = false` 参数跳过 Screen 内的 `LaunchedEffect` 重复 load
>
> 原因：`init` 自动加载时 `workspaceIdOverride` 为 null，协程读到 OCGO 凭据，导致 CCGO 页面闪现 OCGO 数据。

**hiltViewModel key 红线**：

> ⚠️ `hiltViewModel(key = key)` 的 key 在 ViewModelStore 内全局唯一、不区分类型。
> CCGO 路由中 `UsageChartViewModel` 和 `UsageViewModel` 用相同 key 会导致类型碰撞、加载失败。
> 当前方案：`chartVm` 用 `"ccgo_chart_$wid"`，`usageVm` 用 `"ccgo_$wid"`（与 Dashboard 首页 `CommandCodeUsageStatsCard` 共享实例）。

**首页布局**：

> Dashboard 使用 `PullToRefreshBox` → `Column` + `verticalScroll`（非 `LazyColumn`）。
> 页面仅 7 个 item，`LazyColumn` 的 dispose/recompose 会导致用量卡片的 `LaunchedEffect` 反复触发，产生卡顿。
> 
> **自适应断点**：容器宽度 > 600dp 时卡片双列（`FlowRow`），≤600dp 时单列。判断使用 `BoxWithConstraints` 而非全局 `WindowSizeClass`，避免嵌套面板误判。

**图表自适应**：

> `UsageDetailScreen` / `UsageChartScreen` 通过 `BoxWithConstraints` 判断容器宽度 > 700dp 时图表并排（前两张 50/50，第三张独占一行），反之纵向堆叠。阈值 700dp 高于 Dashboard 的 600dp，因为图表卡片需要更多空间。

**页面导航**：
```
Compact（手机）：
  Dashboard → OCGO: UsageDetail（图表） → UsageOverview（总统计）
                            ↘ UsageData（原始数据）
            → CCGO: CCGO_USAGE_DETAIL（图表） → CCGO_USAGE_OVERVIEW（总统计）
                                          ↘ CCGO_USAGE_DATA（原始数据）

  返回用 guardedPop()（150ms 时间戳围栏）+ popExitTransition=None。
  围栏防同一帧/连续帧的第二次 pop，popExitTransition 消旧 composable 残留窗口。
  其余 transition 保留默认动画（enter/exit/popEnter）。

⚠️ 已知未修复 Bug：快速连点左上角返回 → 仍然会返回到空页面。
  根因尚未定位（已排除：动画残留、回调竞态、目标路由 no-op）。
  待查方向：UsageDetailScreen 内部返回链路 + NavController backQueue 状态。

Expanded（平板，≥840dp）：
  ┌─ 左侧 35%: Dashboard（固定） ─┐  ┌─ 右侧 65%: when(pane) 原子切换 ─────┐
  │                                │  │  ServiceDetail / OCGOUsage / CCGOUsage │
  │                                │  │  Settings（内嵌 NavHost）              │
  └────────────────────────────────┘  └────────────────────────────────────────┘
  右侧用量详情内部子路由：图表 → 总览 / 原始数据（OCGO/CCGO/Settings 各自用局部 NavHost）
  面板切换用 when(pane) 分支（同一帧原子重组，零穿透），子路由由局部 NavHost 的 popBackStack() 内置防护。
```

**桌面小组件（Widget）**：
- 显示 OpenCode Go 三个用量窗口（5h/本周/本月）+ 进度条 + 重置时间 + DeepSeek 余额
- 右上角 ↻ 手动刷新按钮（后台广播 → `WidgetRefreshReceiver` → EntryPoints 获取 `RefreshBalanceUseCase`）
- 进度条颜色按百分比动态变化（<50% 草莓粉 / 50-80% 暖橙 / >80% 玫红）
- **MIUI Widget 适配**：`miuiWidget` 标识 → 可拖入负一屏；`miui.appwidget.action.APPWIDGET_UPDATE` 曝光刷新（划到即触发，20s 冷却）；`@android:id/background` 根布局 ID（系统统一裁切圆角）
- **自动刷新**：`onUpdate()` 内缓存为空或超过 5 分钟冷却时自动发送 `WidgetRefreshReceiver` 广播
- **一键添桌面**：Dashboard 顶部栏 + 按钮 → `requestPinAppWidget`（有 fallback 到 `ACTION_APPWIDGET_PICK`）

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
  → OCGO: SyncUsageUseCase.fullSync() / incrementalSync()
    → OpenCodeUsageRepository.fetchPage(cursor) 逐页抓取
    → UsageCache.insertAll() → persist() → invalidateCache()
  → CCGO: SyncCommandCodeUsageUseCase.fullSync() / incrementalSync()
    → CommandCodeUsageRepository.fetchPage(cursor) 逐页抓取
  → UsageViewModel.loadStats() → getRecords() → 内存聚合

CCGO 清除按钮（详情页顶栏）：
  点击 → AlertDialog 警告弹窗 → 3s 倒计时确认
  → UsageViewModel.clearAndResync()
    → UsageCache.deleteByWorkspaceId("commandcode")
    → SyncCommandCodeUsageUseCase.fullSync()
    → loadStats() → onBack()

Widget 刷新按钮：
  ↻ → PendingIntent.getBroadcast() → WidgetRefreshReceiver
    → EntryPoints → RefreshBalanceUseCase(DEEPSEEK + OPENCODE_GO)
    → notifyDataChanged()

MIUI 曝光刷新（用户划到负一屏/桌面）：
  → miui.appwidget.action.APPWIDGET_UPDATE → onReceive() → onUpdate()
    → 读缓存渲染
    → 缓存为空/过期? → sendBroadcast(WidgetRefreshReceiver)
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

深色模式下文字颜色由 `inkWarm()` / `inkMuted()` composable 自动切换（定义在 `Theme.kt`），静态资源通过 `drawable-night/` / `layout-night/` 适配。