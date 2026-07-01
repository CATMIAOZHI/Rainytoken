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
- ✅ Codex / ChatGPT Plus — 粘贴完整 auth.json（含 refresh_token），调 `chatgpt.com/backend-api/wham/usage`；token 过期前 60 分钟自动刷新
- ✅ 文案统一：所有服务标签均使用中文（"每周"统一代替 "weekly"/"Weekly"/"weekly"）
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
> **自适应断点**：容器宽度 > 600dp 时卡片双列（`BoxWithConstraints`），≤600dp 时单列。
> 
> **长按拖拽排序**：基于 Compose 原生 `detectDragGesturesAfterLongPress`，不引入第三方库。
> 核心设计：拖动中不修改真实布局顺序（手势节点不动），其它卡片用 `displacementFor()` 计算的 `offset` 做视觉让位。
> 换位判断采用"拖拽卡片中心点命中目标格"模型——累计手指偏移计算拖拽中心在窗口的坐标，
> 与冻结的格子中心表 `itemCenterById` 比对，进入目标格激活区域（卡片短边的 45%）后才切换 `dragTargetIndex`。
> 自动滚动时对浮动卡片和格子中心表同步做 `y - consumed` 补偿，防止飞走。
> 松手后通过 `settleDraggedItem()` 落位并持久化到 SharedPreferences（`dashboard_card_order`）。
> 
> 关键状态拆分：
> - `visualDragOffsetX/Y` —— 视觉跟手偏移，持续累计
> - `dragStartCenterX/YInWindow` —— 拖动开始时卡片中心在窗口位置，作为偏移基准
> - `dragFromIndex` / `dragTargetIndex` —— 真实 index 和目标 index，不触发重组
> - `itemCenterById` —— 拖动中冻结的格子中心坐标表
> - `displayOrder` —— 真实布局顺序，拖动中不改
> - `cardOrder`（外层 SharedPreferences）—— 持久化的用户偏好顺序

**图表自适应**：

> `UsageDetailScreen` / `UsageChartScreen` 通过 `BoxWithConstraints` 判断容器宽度 > 700dp 时图表并排（前两张 50/50，第三张独占一行），反之纵向堆叠。阈值 700dp 高于 Dashboard 的 600dp，因为图表卡片需要更多空间。

**页面导航**：
```
Compact（手机）：
  Dashboard → OCGO: UsageDetail（图表） → UsageOverview（总统计）
                            ↘ UsageData（原始数据）
            → CCGO: CCGO_USAGE_DETAIL（图表） → CCGO_USAGE_OVERVIEW（总统计）
                                          ↘ CCGO_USAGE_DATA（原始数据）

  返回用 guardedPop()（200ms 时间戳围栏，PopGuard 非 State 对象）+ popExit=fadeOut(1ms)+popEnter=None。
围栏防同一帧/连续帧的第二次 pop；popExit=fadeOut(1ms) 确保旧 composable 正确从 layout 树移除
（ExitTransition.None 是字面零帧，连续 pop 时退出 composable 可能未清除 → 幽灵残影卡在上层），
popEnter=None 消除进入动画与连点 pop 的竞态（根因：默认 popEnter fadeIn(700ms) 时，
第二次 pop 中断进入动画导致 AnimatedContent 状态不一致 → 空白页）。
enter/exit 保留默认动画（仅影响前进导航）。
PopGuard 额外检查 previousBackStackEntry != null，且 popBackStack() 返回 false 时 reset 围栏。

✅ 已修复：快速连点左上角返回 → 空页面 / 旧页面残影卡在上层。
根因：① mutableStateOf 写入触发 NavHost 重组，干扰 AnimatedContent 过渡状态机；
      ② popExitTransition=None 字面零帧，连续 pop 时退出 composable 未从 layout 树清除；
      ③ popExitTransition=None + 默认 popEnterTransition(700ms) 不匹配，第二次 pop
        中断进入动画导致 AnimatedContent 状态不一致。
修复：① PopGuard 用普通对象替代 mutableStateOf，不触发重组；
      ② popExitTransition=fadeOut(tween(1))，极短非零帧确保 composable 正确移除；
      ③ popEnterTransition=EnterTransition.None，返回导航瞬时完成；
      ④ previousBackStackEntry 空值检查 + popBackStack() 返回值检查。

Expanded（平板，≥840dp）：
  ┌─ 左侧 35%: Dashboard（固定） ─┐  ┌─ 右侧 65%: when(pane) 原子切换 ─────┐
  │                                │  │  ServiceDetail / OCGOUsage / CCGOUsage │
  │                                │  │  Settings（内嵌 NavHost）              │
  └────────────────────────────────┘  └────────────────────────────────────────┘
  右侧用量详情内部子路由：图表 → 总览 / 原始数据（OCGO/CCGO/Settings 各自用局部 NavHost）
  面板切换用 when(pane) 分支（同一帧原子重组，零穿透），子路由由局部 NavHost 的 popBackStack() 内置防护。
```

**桌面小组件（Widget）**：
- 显示当前选中服务的用量+DeepSeek 余额
- 支持三服务切换：OCGO / CCGO / Codex（右上角 ↻ 按钮旁的切换按钮循环切换）
- 右上角 ↻ 手动刷新按钮（后台广播 → `WidgetRefreshReceiver` → EntryPoints 获取 `RefreshBalanceUseCase`）
- 刷新逻辑：只刷新当前选中服务 + DeepSeek，不再串行刷全部服务；25s 超时保护；`isRefreshing` 互斥锁防连续点击
- 点击刷新后立即更新 Widget 右上角时间为"刷新中..."（`showRefreshing()`），再后台请求网络
- 进度条颜色按百分比动态变化（<50% 草莓粉 / 50-80% 暖橙 / >80% 玫红）
- **MIUI Widget 适配**：`miuiWidget` 标识 → 可拖入负一屏；`miui.appwidget.action.APPWIDGET_UPDATE` 曝光刷新（划到即触发，20s 冷却）；`@android:id/background` 根布局 ID（系统统一裁切圆角）
- **自动刷新**：`onUpdate()` 内缓存为空或超过 5 分钟冷却时自动发送 `WidgetRefreshReceiver` 广播
- **一键添桌面**：Dashboard 顶部栏 + 按钮 → `requestPinAppWidget`（有 fallback 到 `ACTION_APPWIDGET_PICK`）
- 服务切换状态持久化到 SharedPreferences（`widget_auto_refresh` 中的 `display_service` key），切换后立即调用 `notifyDataChanged` 触发 `onUpdate()` 渲染

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
    → CommandCodeGoRepository.fetchBalance() / CodexRepository.fetchBalance()
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