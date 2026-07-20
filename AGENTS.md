# 雨晴Token — AI 余额查询 APP

## 项目概述

Android（Jetpack Compose + Kotlin）APP，统一查询 DeepSeek、OpenCode Go、CommandCode Go、Codex / ChatGPT Plus、Ollama Pro 的余额/配额。
DeepSeek 走 REST API，OpenCode Go 通过 OkHttp 抓取 dashboard HTML 解析 SSR hydration 数据。
CommandCode Go 走 JSON API 抓取用量数据，Codex / ChatGPT Plus 通过 auth.json 刷新 token 后查询 wham 用量。Ollama Pro 通过 Cookie 抓取 settings 页 HTML 解析用量百分比。APP 名为「雨晴Token」（粉色调品牌），配套桌面小组件。

## 技术栈

- Kotlin 100% · Jetpack Compose + Material 3
- MVVM + Repository + `RefreshBalanceUseCase`（单一 UseCase，按 ServiceType 分发）
- Hilt + KSP（DI）
- Retrofit 2 + OkHttp 4 + Kotlinx Serialization
- Room（用量数据，indexed on workspaceId+timeCreated）+ DataStore（余额缓存/图表偏好）+ Android Keystore（凭据加密，AES-256 GCM）
- `minSdk=31`（Android 12+）
- `material3-window-size-class` — 平板自适应布局
- WorkManager（计划中）

## 当前实现状态

**服务**：
- ✅ DeepSeek — REST API `GET /user/balance`，API Key 认证
- ✅ OpenCode Go — OkHttp 抓 dashboard HTML，解析 `rollingUsage`/`weeklyUsage`/`monthlyUsage`。**一键激活用量**：OCGO 详情页可向 `opencode.ai/zen/v1/chat/completions` 发送简短请求（`content:hello`）触发用量统计，API Key 从设置页手动填写（存 `SessionCredential.apiKey`），模型列表从 `models.dev/api.json`（provider=`opencode`）动态获取，用户选择持久化到 `SharedPreferences("ocgo_trigger_prefs")`，响应经 `parseChatResponse()` 提取回复文本+用量统计后弹窗展示。
- ✅ CommandCode Go — JSON API 抓取用量数据，`CommandCodeUsageRepository` 解析（workspaceId = `"commandcode"`）
- ✅ Codex / ChatGPT Plus — 支持 **OAuth PKCE 登录（无头模式）** 或粘贴完整 auth.json（含 refresh_token），调 `chatgpt.com/backend-api/wham/usage`；token 过期前 60 分钟自动刷新。OpenAI 采用 refresh_token 单次轮换机制，被外部工具使用后旧 token 立即失效，需重新 OAuth 登录或导入新 auth.json。**5h 窗口可能被 OpenAI 临时关闭**（`primary_window` 返回 `null`），UI 保留空 5h 槽位 + 主标签动态化（`extras["primary.label"]`），恢复后自动填回。**一键激活用量**：Codex 详情页可向 `chatgpt.com/backend-api/codex/responses` 发送简短请求（`input:hello`）触发用量统计，模型列表从 `models.dev/api.json` 动态获取（无需认证），用户选择持久化到 `SharedPreferences("codex_trigger_prefs")`，请求需 `stream:true`+`store:false`+`ChatGPT-Account-Id` 头（从 access_token JWT 解析），SSE 响应经 `parseSseResponse()` 提取回复文本+用量统计后弹窗展示。
- ✅ Ollama Pro — Cookie 认证，OkHttp 抓 `ollama.com/settings` HTML，正则解析 plan/session(5h)/weekly 百分比 + `data-time` 重置时间 + `data-model` 模型级请求次数；无官方 API（ollama/ollama#12532）。**一键激活用量**：Ollama 详情页可向 `ollama.com/v1/chat/completions` 发送简短请求（`content:hello`）触发用量统计，API Key 从设置页手动填写（存 `SessionCredential.apiKey`），模型列表从 `models.dev/api.json`（provider=`ollama-cloud`）动态获取，用户选择持久化到 `SharedPreferences("ollama_trigger_prefs")`，响应经 `parseOllamaChatResponse()` 提取回复文本+用量统计后弹窗展示。
- ✅ 文案统一：所有服务标签均使用中文（"每周"统一代替 "weekly"/"Weekly"/"weekly"）
- ❌ OpenCode Zen / 小米 MiMo — 未实现

**用量统计系统**：
- ✅ `UsageCache`（Room，indexed on workspaceId+timeCreated）— DAO 查询替代全量 JSON 序列化；首次启动自动从旧 DataStore JSON 迁移
- ✅ `SyncUsageUseCase`（OCGO）/ `SyncCommandCodeUsageUseCase`（CCGO） — 首次全量同步（cursor 翻页）、增量同步（逐页比对本地 ID 集合，按 `getIdsByWorkspace(wid)` 过滤避免跨 workspace 碰撞）
- ✅ `UsageViewModel` — `loadStatsInternal()` 单次 `getRecords()`→ 内存聚合 Overview/ModelStats/DailyStats，所有重操作包在 `withContext(Dispatchers.Default)` 避免主线程卡顿
- ✅ `UsageChartViewModel` — 图表粒度（5h/**12h(10min)**/24h/今天/昨天/7天/当月/自定义日/月/范围），模型多选，3 张 Canvas 图表；支持 **UTC+0/UTC+8 时区切换**（桶边界+标签双感知）；自定义日/月/范围保存 `LocalDate` 语义，切换 UTC 偏好时重新计算边界；**自动降级**（5h无数据→12h→7天→当月）
- ✅ `ChartSettingsStore`（DataStore Preferences + StateFlow）— 持久化 UTC 偏好，`useUtc8Flow` 异步读取（已移除 runBlocking）
- ✅ `UsageDataViewModel` — 原始记录分页浏览（20条/页），支持时间+模型筛选，页码输入跳转
- ✅ 全局刷新绑定 — Dashboard 下拉刷新 → `DashboardViewModel.refresh()` → `UsageViewModel.sync()`（增量）

**调试与错误诊断**：
- ✅ `DebugLog` — 内存 ring buffer（200 条，线程安全），所有 Repository 关键路径（网络请求、Token 刷新、解析错误）均写入日志；设置页提供「调试日志」入口，无需连电脑即可在 APP 内查看 ERROR/WARN/INFO 三级日志。
- ✅ `RepositoryError.InvalidCredential` 支持自定义 `detail`，Codex `RefreshResult` 密封类明确区分刷新成功/失败原因，错误信息可透传到 Dashboard 卡片与调试日志。

**JsonNull 安全红线**：

> ⚠️ kotlinx.serialization 中 `?.jsonObject` / `?.jsonPrimitive` 对 JSON 显式 `null` 值无效——返回的是 `JsonNull` 对象（非 Kotlin null），扩展函数内部类型检查会抛 `IllegalArgumentException` 导致闪退。
> **必须用 `as? JsonObject` / `as? JsonPrimitive` 替代**，安全转换能正确处理 `JsonNull`（返回 null）。
> `parseUsageWindows()` 等解析方法外部必须包 `try/catch` → `RepositoryError.ParseError`，防止 API 响应结构变化导致异常逃逸。

**凭据回显红线**：

> ⚠️ `CredentialEditViewModel.load()` 首次加载已有凭据时，需针对每种 `Credential` 子类显式编写回显分支。
> 当前覆盖：`ApiKeyCredential`（API Key 输入框）、`SessionCredential`（Cookie 输入框 / `ollamaCookie` 字段 / `apiKey` 字段 → `triggerApiKey`）、`CodexCredential`（auth.json 输入框）。
> 新增凭据类型（如 OpenCode Zen / MiMo）时必须同步添加对应的 `load()` 回显分支，否则用户保存后看不到已存内容。

**ViewModel 加载机制红线**：

> ⚠️ 三个 ViewModel 的 `init` 块**已移除**，不再自动加载。数据加载由 Composable 层的 `LaunchedEffect(Unit)` 显式触发：
> - OCGO 页面：`LaunchedEffect(Unit) { viewModel.load() / loadStats() / loadData() }`
> - CCGO 页面：`LaunchedEffect(Unit) { viewModel.setWorkspace(wid) }`（`setWorkspace` 内部调 `load()`）
> - CCGO 页面通过 `autoLoad = false` 参数跳过 Screen 内的 `LaunchedEffect` 重复 load
>
> 原因：`init` 自动加载时 `workspaceIdOverride` 为 null，协程读到 OCGO 凭据，导致 CCGO 页面闪现 OCGO 数据。

**DashboardViewModel 刷新竞态红线**：

> ⚠️ `DashboardViewModel.init` 必须在**同一协程内串行**调用 `loadFromCache()` → `refresh()`，不能拆成两个独立协程。
> 原因：并行时 `loadFromCache()` 读到的旧缓存快照可能在 `refresh()` 写入新数据后才 `_uiState.update`，导致用量数据闪回旧值（如 Codex 5h 61%→2%）。
> `refresh()` 内部用 `Mutex.tryLock()` 防并发——下拉刷新与 init 的 refresh 并发时后者直接跳过，避免交错覆盖。

**hiltViewModel key 红线**：

> ⚠️ `hiltViewModel(key = key)` 的 key 在 ViewModelStore 内全局唯一、不区分类型。
> CCGO 路由中 `UsageChartViewModel` 和 `UsageViewModel` 用相同 key 会导致类型碰撞、加载失败。
> 当前方案：`chartVm` 用 `"ccgo_chart_$wid"`，`usageVm` 用 `"ccgo_$wid"`（与 Dashboard 首页 `CommandCodeUsageStatsCard` 共享实例）。

**ARM64 Proot Release 构建红线**：

> ⚠️ AGP 9.0 的 `optimizeReleaseResources` 在 ARM64 Proot 下传入 `--resource-path-shortening-map=<path>`（等号形式），ARM64 AAPT2 不接受此语法（只接受空格分隔），exit code=1 但 AGP `invokeAapt()` 只调 `rethrowFailure()` 不调 `assertNormalExitValue()`，失败被静默吞掉。导致 optimized `.ap_` 缺失，`packageRelease` 生成无 `AndroidManifest.xml` / `resources.arsc` / `res/` 的残缺 APK（BUILD SUCCESSFUL 但 APK 不可用）。
>
> 修复链（不可删除任一环节）：
> 1. `~/.gradle/gradle.properties`（全局，不提交项目仓库）→ `android.aapt2FromMavenOverride` 指向 `~/.local/lib/android-aapt2-wrapper/aapt2` wrapper 脚本，将 `--resource-path-shortening-map=<path>` 拆分为 `--resource-path-shortening-map` `<path>` 两个独立 argv
> 2. `app/build.gradle.kts` 中 `guardReleaseResources` 任务：`optimizeReleaseResources` 之后验证 optimized `.ap_` 完整性（manifest + arsc + res/），缺失则复制 linked `.ap_` 作为 fallback；`packageRelease` 依赖此任务
> 3. `app/build.gradle.kts` 中 `resolutionStrategy` 强制 `aapt2:linux-aarch64` classifier
>
> CI（GitHub Actions x86_64）不受影响——全局 `~/.gradle/gradle.properties` 不在项目仓库中。Debug 不经过 `optimizeReleaseResources`，无此问题。

**首页布局**：

> Dashboard 使用 `PullToRefreshBox` → `Column` + `verticalScroll`（非 `LazyColumn`）。
> 页面仅 8 个 item，`LazyColumn` 的 dispose/recompose 会导致用量卡片的 `LaunchedEffect` 反复触发，产生卡顿。
> 
> **自适应断点**：容器宽度 > 600dp 时卡片双列（`BoxWithConstraints`），≤600dp 时单列。
> OCGO / CCGO 服务余额卡底部均提供「查看用量详情」入口，未配置凭据时不显示。
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
> - `itemCenterById` —— 拖动中冻结的格子中心坐标表；使用普通 `HashMap`，不要改回 Compose StateMap（滚动/返回动画期间 `onGloballyPositioned` 高频写入会触发重组卡顿）
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
            → Settings → Tips（使用小技巧）

  返回用 guardedPop()（200ms 时间戳围栏，PopGuard 非 State 对象）+ Android predictive back。
Manifest 开启 `android:enableOnBackInvokedCallback="true"`；`navigation-compose` 保持 2.9.x 以上，使用后续 predictive back 修复。
Compact 根 `NavHost` 必须显式配置 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition` 四项：
前进统一左滑，返回统一右滑，避免只配置 pop 时短时间返回混入默认淡入淡出。
当前页面背景/渐变层不适合 `scaleOut` 或长透明淡出类返回动画，容易出现透明背景和文字叠影；若要改动画，先处理 destination 的不透明背景层。
PopGuard 额外检查 previousBackStackEntry != null，且 popBackStack() 返回 false 时 reset 围栏。

Expanded（平板，≥840dp）：
  ┌─ 左侧 35%: Dashboard（固定） ─┐  ┌─ 右侧 65%: when(pane) 原子切换 ─────┐
  │                                │  │  ServiceDetail / OCGOUsage / CCGOUsage │
  │                                │  │  Settings（内嵌 NavHost → Tips）      │
  └────────────────────────────────┘  └────────────────────────────────────────┘
  右侧用量详情内部子路由：图表 → 总览 / 原始数据（OCGO/CCGO/Settings 各自用局部 NavHost）
  面板切换用 when(pane) 分支（同一帧原子重组，零穿透），子路由由局部 NavHost 的 popBackStack() 内置防护。
```

**桌面小组件（Widget）**：
- 显示当前选中服务的用量+DeepSeek 余额
- 支持四服务切换：OCGO / CCGO / Codex / Ollama（右上角 ↻ 按钮旁的切换按钮循环切换）
- 右上角 ↻ 手动刷新按钮（后台广播 → `WidgetRefreshReceiver` → EntryPoints 获取 `RefreshBalanceUseCase`）
- 刷新逻辑：只刷新当前选中服务 + DeepSeek，不再串行刷全部服务；25s 超时保护；`isRefreshing` 互斥锁防连续点击
- 点击刷新后立即更新 Widget 右上角时间为"刷新中..."（`showRefreshing()`），再后台请求网络
- 进度条颜色按百分比动态变化（<50% 草莓粉 / 50-80% 暖橙 / >80% 玫红）
- **MIUI Widget 适配**：`miuiWidget` 标识 → 可拖入负一屏；`miui.appwidget.action.APPWIDGET_UPDATE` 曝光刷新（划到即触发，20s 冷却）；`@android:id/background` 根布局 ID（系统统一裁切圆角）
- **自动刷新**：`onUpdate()` 内缓存为空或超过 5 分钟冷却时自动发送 `WidgetRefreshReceiver` 广播
- **一键添桌面**：Dashboard 顶部栏 + 按钮 → 二次确认弹窗 → 权限检测（Manifest `INSTALL_SHORTCUT` + MIUI AppOps `android:install_shortcut`）→ `requestPinAppWidget`（有 fallback 到 `ACTION_APPWIDGET_PICK`）
- 服务切换状态持久化到 SharedPreferences（`widget_auto_refresh` 中的 `display_service` key），切换后立即调用 `notifyDataChanged` 触发 `onUpdate()` 渲染

**小组件点击绑定**：

> `widget_wordmark` + `widget_open_hint`（`>` 箭头）→ 打开 APP；`widget_content` / `widget_switch` / `widget_service_title` → 切换服务广播 `ACTION_SWITCH_SERVICE`；`widget_refresh` → 刷新广播。
> 切换 requestCode=2，刷新 requestCode=1。左上角 `>` 箭头是可点击进 APP 的视觉提示（ASCII `>` 替代 Unicode `›`，确保全 ROM 兼容）。

**使用小技巧系统**：

> - `AppTips`（`ui/components/AppTips.kt`）集中管理 13 条技巧，每条含 `title` / `hint`（一句话）/ `detail`（详细说明）
> - Dashboard 卡片上方每次启动随机显示一条 `hint`（`remember { AppTips.randomHint() }`，不自动轮换）
> - 设置页 `💡 使用小技巧` 卡片 → `TipsScreen` 独立页面（Route `tips`），LazyColumn 逐条展示
> - 首次进入 Dashboard 显示一次性"长按卡片可拖拽排序"提示横幅，SharedPreferences `dashboard_ui_hints` 的 `drag_hint_shown` 标记

**图表默认 tooltip**：

> `StackedBarChart` / `LineChart` 的 `tooltipBucket` 初始值为 `buckets.lastOrNull()`，进入图表页即可看到最新时段数值详情，暗示图表可交互。

**测试体系**：

- 纯 JVM 单元测试（`testDebugUnitTest`），无 Android 框架依赖，无网络
- 5 个测试文件，78 个用例：
  - `OllamaRepositoryTest`（16）— HTML 解析：plan/百分比/时间戳/模型次数/空输入
  - `OpenCodeGoRepositoryTest`（10）— SSR hydration 解析：三窗口/嵌套/缺失字段
  - `FormatUtilsTest`（18）— `formatAmount`/`formatResetInSec`/`formatResetForWidget`/`normalizeWindowLabel`
  - `FormatCodexPrimaryLabelTest`（9）— `formatCodexPrimaryLabel`：null fallback/大小写/未知值
  - `CodexRepositoryTest`（25）— `durationLabel` 阈值 + `parseUsageWindows` JsonNull 安全 + `parseSseResponse` SSE 解析
- CodexRepository 中 `parseUsageWindows`/`durationLabel`/`UsageWindow` 为 `internal`（companion object），`parseSseResponse` 为 `internal` 顶层函数——不使用实例状态，便于测试直接调用

**CI workflow（`.github/workflows/ci.yml`）**：

- 触发：push main / 所有 PR
- 3 个 job 串行（test 通过后才构建）：
  1. `test`：`testDebugUnitTest` + `lintDebug`，上传 XML 测试报告 + lint 报告 artifact
  2. `build-debug`：`assembleDebug`，上传 Debug APK artifact
  3. `build-release`：`assembleRelease` + APK 完整性验证（`AndroidManifest.xml` + `resources.arsc` + `res/`），上传 Release APK artifact
- Release 签名 fallback：CI 无 `release.jks`，`build.gradle.kts` 自动 fallback 到 `~/.android/debug.keystore`；CI 额外步骤确保 debug keystore 存在
- artifact 保留 14 天

**重大修改 PR 审计红线**：

> ⚠️ 重大修改（构建配置、签名、CI、架构变更、新服务接入）必须通过 PR 提交，不能直接 push main。
> PR 触发 CI 三 job 全部通过后才可合并：单元测试 + lint + Debug/Release APK 构建验证。
> Release APK 完整性验证（manifest + arsc + res/）是防止 ARM64 Proot 构建问题再次发生的最后防线。

## RemoteViews 兼容性红线

以下元素在 Widget 布局中**不可用**，会导致「载入出现问题」：

| ❌ 不可用 | ✅ 替代方案 |
|-----------|------------|
| `<Space>` | 透明 ProgressBar（`0dp + weight=1`） |
| `<View>` | ProgressBar 或 TextView |
| `<ImageView>` + 矢量 drawable | PNG（`drawable-nodpi`）。Widget logo 必须用 `_widget` 后缀 PNG（`ic_opencode_go_logo_widget.png`、`ic_codex_logo_widget.png`）；DeepSeek / Ollama 已有 PNG（`ic_deepseek_logo.png`、`ic_ollama_logo.png`）。Compose 层（`ServiceIcon.kt`）继续用 VectorDrawable XML。 |
| `<TextView>` `0dp+weight=1` 空串 spacer | ProgressBar spacer |
| `<TextView>` 固定 dp 宽度 + `gravity` | 仅固定 dp，不加 gravity |

## 关键命令

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/Rainytoken
export ANDROID_HOME=$HOME/Android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64

./gradlew testDebugUnitTest
# 78 个单元测试，纯 JVM，无设备依赖

./gradlew assembleDebug
# Debug APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# Release APK: app/build/outputs/apk/release/app-release.apk
# ARM64 Proot 需 ~/.gradle/gradle.properties 配置 aapt2FromMavenOverride（见红线）
# CI x86_64 无需额外配置
```

## 数据流

```
DashboardViewModel.refresh()
  → RefreshBalanceUseCase(service) （retryOnTransientError: Network/5xx 指数退避重试 2 次）
    → DeepSeekRepository.fetchBalance()    / OpenCodeGoRepository.fetchBalance()
    → CommandCodeGoRepository.fetchBalance() / CodexRepository.fetchBalance()
    → OllamaRepository.fetchBalance()
  → BalanceCache.put(service, result) — put() 在 dataStore.edit {} 互斥锁内做 read-modify-write，避免并发覆盖
  → OpenCodeGoWidgetProvider.notifyDataChanged(context)

Dashboard 下拉刷新 → usageSyncTrigger++ → UsageViewModel.sync()
  → OCGO: SyncUsageUseCase.fullSync() / incrementalSync()
    → OpenCodeUsageRepository.fetchPage(cursor) 逐页抓取
    → UsageCache.insertAll() → Room DAO insert（IGNORE 策略，去重）
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
    → EntryPoints → RefreshBalanceUseCase(selectedService + DEEPSEEK)
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