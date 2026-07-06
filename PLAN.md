# RainyToken 改进计划

> 临时计划文件，执行完毕后删除。

---

## 1. 迁移 UsageCache 到 Room

**现状问题**：
- ~3700 条记录序列化为一个 JSON 字符串存入 DataStore 单 key
- 每次 `insertAll` → `loadAll()` 全量读取 → 追加 → `persist()` 全量序列化写回
- APP 冷启动时内存缓存丢失，需反序列化整个 JSON 字符串
- 数据只增不减，半年后可能上万条，写入和启动读取会线性恶化
- `getRecords(workspaceId, fromTs, toTs)` 是全量遍历后过滤，Room 用索引查询是 O(log n)

**目标**：
- 用 Room 替换 DataStore JSON 方案
- `UsageRecord` 作为 Entity，`workspaceId` + `timeCreated` 建索引
- `UsageCache` 接口不变（`getAll` / `insertAll` / `getRecords` / `getStatsByModel` / `getStatsByDay` / `getOverview` / `deleteByWorkspaceId` / `count` / `getDistinctModels` / `getLatest` / `getAllIds`），内部实现从 JSON 序列化改为 Room DAO 查询
- 保留内存缓存层（`@Volatile cachedAll`），但改为按需查询而非全量加载
- 数据迁移：首次启动时检查旧 DataStore 数据，一次性导入 Room，然后清理旧 key

**涉及文件**：
- 新建：`data/local/UsageDatabase.kt`（Room Database + DAO）
- 新建：`data/local/UsageRecordEntity.kt`（Entity 定义，从现有 `UsageRecord` 迁移）
- 修改：`data/local/UsageCache.kt`（内部实现改为 Room DAO 调用，接口不变）
- 修改：`di/NetworkModule.kt`（@Provides RoomDatabase 实例）
- 修改：`app/build.gradle.kts`（添加 Room 依赖）

**验证标准**：
- APP 启动后 OCGO / CCGO 用量数据正常显示
- 下拉刷新触发增量同步后新数据正确入库
- CCGO 清除按钮能正确删除并重新全量同步
- 图表、总览、原始数据页面正常渲染
- 冷启动不再有全量 JSON 反序列化卡顿

---

## 2. 加解析单元测试

**现状问题**：
- `OllamaRepository.parseUsage()` 和 `OpenCodeGoRepository.parseWindows()` 用正则解析 HTML
- 对方改一次页面结构就失效，且失效表现是静默的（显示 0% 而非报错）
- `CredentialEditViewModel.load()` 每次加新服务都要改回显分支，漏了用户看不到已存凭据

**目标**：
- 纯 JVM 单元测试，不需要 Android 设备
- 测试用例用之前抓到的真实 HTML（Ollama settings 页）作为 fixture

**涉及文件**：
- 新建：`app/src/test/java/com/rainy/token/data/repository/OllamaRepositoryTest.kt`
  - 测试 plan 解析（Pro / Max / Free）
  - 测试 session/weekly 百分比解析（含小数 35.1%）
  - 测试 data-time 重置时间戳解析
  - 测试 data-model + data-requests 模型级数据解析
  - 测试无效 Cookie / 空页面 / HTML 结构变更场景
- 新建：`app/src/test/java/com/rainy/token/data/repository/OpenCodeGoRepositoryTest.kt`
  - 测试 rollingUsage / weeklyUsage / monthlyUsage 解析
  - 测试嵌套对象的括号匹配
  - 测试 HTML 无目标数据时的 fallback

**验证标准**：
- `./gradlew test` 全部通过
- 测试覆盖正则解析的主要路径和边界情况

---

## 3. DashboardScreen 拆分

**现状问题**：
- DashboardScreen.kt 1121 行，包含主框架 + 所有服务卡片 + 拖拽逻辑 + 工具函数
- 加一个新服务要改这一个文件至少 3 处

**目标**：
- 拆成 3 个文件：
  - `DashboardScreen.kt` — 主框架 + Scaffold + PullToRefresh（~200 行）
  - `ServiceBalanceCards.kt` — 各服务的 MainBalance + UsageWindows Composable
  - `DashboardDragState.kt` — 拖拽状态管理类 + 工具函数

**涉及文件**：
- 修改：`ui/dashboard/DashboardScreen.kt`（大幅精简）
- 新建：`ui/dashboard/ServiceBalanceCards.kt`
- 新建：`ui/dashboard/DashboardDragState.kt`

**验证标准**：
- 编译通过
- Dashboard 功能不变（卡片显示、拖拽排序、下拉刷新、自适应布局）
- 新增服务时只需改 `ServiceBalanceCards.kt` + `secondaryLine`

---

## 4. 重复函数提取

**现状问题**：
- `formatAmount(Double)` — DashboardScreen.kt + ServiceDetailScreen.kt 各一份
- `normalizeWindowLabel(String)` — DashboardScreen.kt + WidgetProvider 各一份
- `formatResetInSec(Long)` / `formatReset(Long)` — DashboardScreen.kt + ServiceDetailScreen.kt + WidgetProvider 三份
- `parseCookieString(String)` — CredentialEditViewModel.kt 独一份，但应该在公共位置

**目标**：
- 抽取到 `ui/components/FormatUtils.kt`（UI 层共享）
- `parseCookieString` 抽到 `data/repository/CookieUtils.kt`

**涉及文件**：
- 新建：`ui/components/FormatUtils.kt`
- 新建：`data/repository/CookieUtils.kt`
- 修改：DashboardScreen.kt / ServiceDetailScreen.kt / OpenCodeGoWidgetProvider.kt / CredentialEditViewModel.kt（删除重复定义，改用 import）

**验证标准**：
- 编译通过，无行为变化

---

## 5. Repository 加重试

**现状问题**：
- 所有 Repository 的 `fetchBalance()` 只做一次请求，失败就返回 `Result.failure`
- 网络抖动时用户需手动下拉刷新

**目标**：
- 对 `RepositoryError.Network` 和 `RepositoryError.ServerError(5xx)` 加一次指数退避重试（最多 2 次）
- 401/403/429 不重试
- 可以做成 OkHttp Interceptor 或在 Repository 层包一层 `retryOnce`

**涉及文件**：
- 新建或修改：`data/repository/RetryHelper.kt`（通用重试包装）
- 修改：各 Repository 的 `fetchBalance()`（包一层 retry）

**验证标准**：
- 编译通过
- 网络抖动时自动重试一次而非直接失败
- 401/403 仍然立即失败不重试

---

## 6. CredentialEditViewModel 模板化

**现状问题**：
- `saveOpenCodeGoSession()` / `testAndSaveOpenCodeGo()` / `saveOllamaCredential()` / `testAndSaveOllama()` / `saveCommandCodeGoCredential()` / `testAndSaveCommandCodeGo()` 等方法成对出现，结构几乎一样

**目标**：
- 抽取通用的 `saveAndTestCredential(service, saveBlock, testBlock)` 模板方法
- 各服务只传不同的 save + test lambda

**涉及文件**：
- 修改：`ui/settings/CredentialEditViewModel.kt`

**验证标准**：
- 编译通过
- 各服务的保存/测试功能不变
- 新增服务时只需写 save + test 两个 lambda，不再写完整的成对方法

---

## 7. 删除 Jsoup 依赖

**现状问题**：
- `build.gradle.kts` 引入了 `org.jsoup:jsoup`，代码里 0 处 import

**涉及文件**：
- 修改：`app/build.gradle.kts`（删除 `implementation(libs.jsoup)`）

**验证标准**：
- 编译通过

---

## 8. ChartSettingsStore 改 Flow

**现状问题**：
- `ChartSettingsStore.getUseUtc8()` 用 `runBlocking` 同步读 DataStore，在 UI 线程调用

**目标**：
- 改成 `StateFlow` 暴露，Composable 层 `collectAsState`

**涉及文件**：
- 修改：`data/local/ChartSettingsStore.kt`
- 修改：调用 `getUseUtc8()` 的 Composable 层

**验证标准**：
- 编译通过
- 图表页 UTC 偏好切换正常
- 退出再进入能恢复上次偏好

---

## 执行顺序

1. 迁移 Room（基础设施，后续都依赖它）
2. 加解析单元测试（在改其他代码前先有回归保护）
3. DashboardScreen 拆分
4. 重复函数提取
5. Repository 加重试
6. CredentialEditViewModel 模板化
7. 删除 Jsoup 依赖
8. ChartSettingsStore 改 Flow

每步完成后 `./gradlew assembleDebug` 验证编译，全部完成后执行 `git commit` 并删除此计划文件。