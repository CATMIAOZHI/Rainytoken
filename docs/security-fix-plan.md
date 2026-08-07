# 安全修复计划 — Codex Security Finding 修复

> 基于 `codex-security-findings-2026-08-04T22-22-52.609Z.csv` 报告核实结果制定
> 日期：2026-08-05
> 修订：v2 — 2026-08-05（纳入首轮独立审核反馈）

## 一、背景

Codex 安全扫描报告了一条 **high** 级别 finding，涉及 commit `5e25be23` (`ci: Release 自动构建 + 签名配置`)，相关文件为 `.github/workflows/release.yml` 和 `app/build.gradle.kts`。

核实结论：报告**半真半假**——action mutable reference 和硬编码 fallback 密码是真实风险，但"workflow 未导出 KEYSTORE_PASSWORD 等环境变量"是事实错误（代码第24-27行已明确导出）。

## 二、真实风险点（需要修复）

| # | 风险 | 严重度 | 涉及文件 |
|---|---|---|---|
| R1 | 所有 workflow 使用 mutable action tag 引用（`@v3`、`@v2`、`@v4`）而非 immutable commit SHA pin | medium | `release.yml`、`ci.yml` |
| R2 | `build.gradle.kts` 第35-37行硬编码了签名 fallback 密码（`"RainyToken2026!"`、`"rainy"`），公开可见 | medium | `app/build.gradle.kts` |
| R3 | `release.jks` 解码后留在 workspace，后续 step（含第三方 action）可读取 | low-medium | `release.yml` |
| R4 | `ci.yml` 缺少显式 `permissions` 声明，违反最小权限原则 | medium | `ci.yml` |

## 三、报告中的错误（不需要修复的部分）

| 描述 | 事实 |
|---|---|
| "workflow does not export KEYSTORE_PASSWORD, KEYSTORE_ALIAS, or KEY_PASSWORD" | **错误** — `release.yml` 第24-27行已通过 `env:` 导出全部三个变量 |

## 四、修复方案

### 修复 1：所有 action 引用改为 commit SHA pin

**原因**：Immutable SHA pin 可防止 action 被恶意 retag 后的供应链攻击。

**涉及文件**：`.github/workflows/release.yml`、`.github/workflows/ci.yml`

**SHA 映射**（通过 GitHub API 获取，2026-08-05 查询）：

| Action | Tag | Commit SHA | 备注 |
|---|---|---|---|
| `actions/checkout` | v4 | `11d5960a326750d5838078e36cf38b85af677262` | |
| `actions/setup-java` | v4 | `cf277c60eb25467037889841efdb72551f06f6c3` | v4 已弃用，后续可升级 v5（不阻塞本次修复） |
| `android-actions/setup-android` | v3 | `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407` | |
| `softprops/action-gh-release` | v2 | `3bb12739c298aeb8a4eeaf626c5b8d85266b0e65` | |
| `actions/upload-artifact` | v4 | `ea165f8d65b6e75b540449e92b4886f43607fa02` | |

**修改形式**（以 `android-actions/setup-android` 为例）：
```yaml
# Before
- uses: android-actions/setup-android@v3

# After
- uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407  # v3
```

**影响范围**：
- `release.yml`：4 处 action 引用（checkout、setup-java、setup-android、action-gh-release）
- `ci.yml`：4 种 action 引用（checkout、setup-java、setup-android、upload-artifact），共出现 13 次
  - test job：5 次（checkout、setup-java、setup-android、upload-artifact ×2）
  - build-debug job：4 次（checkout、setup-java、setup-android、upload-artifact）
  - build-release job：4 次（checkout、setup-java、setup-android、upload-artifact）

> **修订说明**：v1 计划中误写为 11 次，经逐行核实实际为 13 次，已更正。

### 修复 2：移除 build.gradle.kts 中的硬编码签名密码

**原因**：硬编码的明文密码公开暴露了签名凭据。应改为：env 变量缺失或为空时直接报错，不提供隐式 fallback。

**涉及文件**：`app/build.gradle.kts` 第33-37行

**关键设计点 — 空字符串防御**：

> **首轮审核发现的阻断项**：GitHub Actions 中 `${{ secrets.X }}` 在 secret 未设置时会被替换为**空字符串**（不是 null），经 `env:` 传递后 `System.getenv("X")` 返回空字符串 `""`。Kotlin 的 `?:` (Elvis operator) 只检查 `null`，不检查空字符串——空字符串会绕过 `?: throw`，被直接传给签名工具。

因此方案使用 `?.takeIf { it.isNotBlank() }` 同时防御 null 和空白字符串：

```kotlin
// Before (第33-37行)
if (keystoreFile.exists()) {
    storeFile = keystoreFile
    storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "RainyToken2026!"
    keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "rainy"
    keyPassword = System.getenv("KEY_PASSWORD") ?: "RainyToken2026!"

// After — 使用 takeIf 防御 null + 空字符串
if (keystoreFile.exists()) {
    storeFile = keystoreFile
    storePassword = System.getenv("KEYSTORE_PASSWORD")
        ?.takeIf { it.isNotBlank() }
        ?: throw GradleException("KEYSTORE_PASSWORD env var not set or empty — cannot sign release")
    keyAlias = System.getenv("KEYSTORE_ALIAS")
        ?.takeIf { it.isNotBlank() }
        ?: throw GradleException("KEYSTORE_ALIAS env var not set or empty — cannot sign release")
    keyPassword = System.getenv("KEY_PASSWORD")
        ?.takeIf { it.isNotBlank() }
        ?: throw GradleException("KEY_PASSWORD env var not set or empty — cannot sign release")
```

**兼容性分析**：
- Release workflow（`release.yml`）已通过 `env:` 导出这三个变量（secrets 已正确配置）→ ✅ 不受影响
- CI workflow（`ci.yml`）不涉及 `release.jks`，走 debug keystore fallback → ✅ 不受影响
- 本地构建：若开发者放置了 `release.jks` 但未设置 env 变量 → 会报错（这是正确行为，避免静默使用错误密码）

### 修复 3：构建完成后清理 release.jks

**原因**：减少 keystore 在 workspace 中的暴露窗口。虽然后续 step 不多，但作为深度防御措施值得添加。

**涉及文件**：`release.yml`

**修改方案**：在 "Build Release APK" step 之后、"Rename & Release" step 之前添加清理 step：
```yaml
      - name: Cleanup keystore (always run for security)
        run: rm -f release.jks
        if: always()
```

> **修订说明**：step name 加注 `(always run for security)` 说明意图。`if: always()` 确保即使构建失败也清理 keystore——这是正确的安全行为。后续的 `softprops/action-gh-release` 依赖已生成的 APK 文件（通过 `files:` 参数），不依赖 `release.jks`，清理不影响其运行。

### 修复 4：ci.yml 补充最小权限声明

**原因**：ci.yml 完全没有 `permissions` 块。CI job 只做编译、测试、上传 artifact，不需要 write 权限。显式声明 `contents: read` 遵循最小权限原则，尤其 `pull_request` 触发时（可能来自 fork）更应限制。

**涉及文件**：`.github/workflows/ci.yml`

**修改方案**：在 workflow 顶层添加 `permissions: contents: read`（覆盖所有 job）：
```yaml
on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  ...
```

**兼容性分析**：
- ci.yml 中的所有 job 只做 checkout、build、test、upload-artifact → 仅需 `contents: read`
- `actions/upload-artifact` 不需要 `contents: write`，artifact 上传使用独立权限 → ✅ 不受影响
- `pull_request` 触发时来自 fork 的 PR，GitHub 自动将 token 限制为 read-only → 显式声明与此一致，无冲突

## 五、不修改的部分

| 项 | 理由 |
|---|---|
| `release.yml` 的 `permissions: contents: write` | Release workflow 需要此权限创建 GitHub Release，无法移除 |
| `KEYSTORE_BASE64` secret 解码方式 | 标准做法，无更安全替代 |
| Debug keystore fallback 逻辑（CI 环境） | CI 中使用 debug keystore 只为验证编译/资源完整性，不用于正式发布 |
| `setup-java@v4` 升级到 v5 | v4 已被标记弃用，但 SHA pin 到 v4 当前 commit 仍是安全的。升级 v5 属后续改进项，不阻塞本次安全修复 |

## 六、修改顺序

1. **`app/build.gradle.kts`** — 移除硬编码密码，使用 `takeIf` 防御空字符串（修复 2）
2. **`.github/workflows/release.yml`** — SHA pin + 清理 keystore（修复 1 + 修复 3）
3. **`.github/workflows/ci.yml`** — SHA pin + 补充 `permissions: contents: read`（修复 1 + 修复 4）

## 七、验证方法

- 检查 `release.yml` 和 `ci.yml` 中所有 `uses:` 行均为 `@<40位SHA>  # vN` 格式，无 `@vN` 裸 tag
- 检查 `build.gradle.kts` 中 grep `"RainyToken2026!"` 和 `"rainy"` 返回空
- 检查 `build.gradle.kts` 中存在 `takeIf { it.isNotBlank() }` 模式
- 检查 `release.yml` 中存在 `rm -f release.jks` 清理 step，且 step name 含 `always run for security`
- 检查 `ci.yml` 中存在 `permissions: contents: read` 声明
- 确认 CI workflow 的 build-release job 不受 build.gradle.kts 修改影响（走 debug keystore fallback 路径）

## 八、修订日志

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v1 | 2026-08-05 | 初版计划 |
| v2 | 2026-08-05 | 纳入首轮独立审核反馈：<br>1. 修复 2：`?: throw` → `?.takeIf { it.isNotBlank() } ?: throw`，防御空字符串绕过（审核问题 #1，高严重度）<br>2. 新增修复 4：ci.yml 补充 `permissions: contents: read`（审核问题 #3，中严重度）<br>3. 修复 1：ci.yml action 计数从 11 更正为 13（审核问题 #4，低严重度）<br>4. 修复 3：step name 加注 `(always run for security)` 说明意图（审核问题 #5，建议性）<br>5. 新增"不修改的部分"中 `setup-java@v4` 弃用说明（审核问题 #6，信息性）<br>6. 新增 R4 风险点（ci.yml 缺少 permissions）<br>7. 新增本修订日志 |