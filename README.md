# 🌧️ 雨晴Token (RainyToken)

> *"AI 用量，尽在掌握"* 🐱✨

[![Release](https://github.com/CATMIAOZHI/Rainytoken/actions/workflows/release.yml/badge.svg)](https://github.com/CATMIAOZHI/Rainytoken/actions)
[![Version](https://img.shields.io/github/v/release/CATMIAOZHI/Rainytoken?color=ff85a2)](https://github.com/CATMIAOZHI/Rainytoken/releases)

Android AI 余额查询 APP —— 统一查看 DeepSeek 和 OpenCode Go 的余额/配额。

## 功能

- 📊 仪表盘：DeepSeek 余额（¥）+ OpenCode Go 用量（5h/本周/本月）+ 下拉全局刷新
- 📈 用量图表：消耗金额 / API 请求次数 / Token 消耗（3 张 Canvas 图表，支持多粒度筛选）
- 📋 详细数据：原始记录分页浏览，时间+模型筛选，原始数据弹窗
- 🧩 桌面小组件：不打开 APP 也能看用量
- 🔄 自动同步：首页下拉自动同步用量；无缓存时启动自动全量同步
- 🎀 雨晴粉色调 UI

## 构建

```bash
cd Rainytoken
export ANDROID_HOME=$HOME/Android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 技术栈

Kotlin + Jetpack Compose + Material 3 · Hilt + KSP · Retrofit + OkHttp · DataStore · minSdk=31

## 项目结构

```
com.rainy.token/
├── data/
│   ├── cache/          # BalanceCache（DataStore）
│   ├── local/          # UsageCache（DataStore + 内存缓存）、UsageRecord、ChartBucket
│   ├── remote/         # DeepSeekApi（Retrofit）+ OpenCodeGo 抓取 + OpenCodeUsageRepository
│   └── repository/     # DeepSeekRepository / OpenCodeGoRepository / CredentialRepository
├── domain/
│   ├── model/          # ServiceBalance、Credential 等
│   ├── service/        # ServiceType 枚举
│   └── usecase/        # RefreshBalanceUseCase / SyncUsageUseCase
├── ui/
│   ├── dashboard/      # DashboardScreen、UsageDetailScreen（图表）、UsageOverviewScreen（总统计）、UsageDataScreen（原始数据）
│   ├── widget/         # 桌面小组件（OpenCodeGoWidgetProvider）
│   └── theme/          # 雨晴粉主题
└── di/                 # Hilt 模块
```