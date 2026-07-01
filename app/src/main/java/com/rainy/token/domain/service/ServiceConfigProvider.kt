package com.rainy.token.domain.service

/**
 * 服务的获取方式。新增服务时只在这里枚举即可，UI 层会根据它切换对应流程。
 */
enum class FetchMethod {
    /** 公开 REST API（如 DeepSeek） */
    REST_API,

    /** WebView 登录后抓取 HTML/内部 JSON API */
    WEBVIEW_SCRAPER,

    /** 完全手动输入（已降级） */
    MANUAL
}

/**
 * 单个服务的完整配置。新增/修改服务只动这一个文件。
 */
data class ServiceConfig(
    val type: ServiceType,
    val method: FetchMethod,
    /** 登录入口 URL（WebView 模式用到） */
    val loginUrl: String,
    /** 余额/配额的展示单位（如 "¥" / "$" / "Credits" / "requests"） */
    val displayUnit: String,
    /** 触发降级的连续失败次数阈值（计划 5.4 / 6.3） */
    val degradationFailureThreshold: Int = 3,
    /** Cookie 有效期 < 该值则触发降级（小时） */
    val minCookieTtlHours: Long = 1
)

/**
 * 集中配置入口。新增服务时在这里加一条即可。
 */
object ServiceConfigProvider {

    private val configs: Map<ServiceType, ServiceConfig> = mapOf(
        ServiceType.DEEPSEEK to ServiceConfig(
            type = ServiceType.DEEPSEEK,
            method = FetchMethod.REST_API,
            loginUrl = "", // REST 模式不需要登录 URL
            displayUnit = "¥"
        ),
        ServiceType.OPENCODE_GO to ServiceConfig(
            type = ServiceType.OPENCODE_GO,
            // 用 OkHttp + Cookie 抓 dashboard，枚举值借用 WEBVIEW_SCRAPER 表示"页面抓取"统称
            method = FetchMethod.WEBVIEW_SCRAPER,
            loginUrl = "https://opencode.ai/auth",
            displayUnit = "%"
        ),
        ServiceType.COMMANDCODE_GO to ServiceConfig(
            type = ServiceType.COMMANDCODE_GO,
            method = FetchMethod.REST_API,
            loginUrl = "", // API Key 模式不需要登录 URL
            displayUnit = "$"
        ),
        ServiceType.CODEX to ServiceConfig(
            type = ServiceType.CODEX,
            method = FetchMethod.REST_API,
            loginUrl = "",
            displayUnit = "%"
        )
    )

    fun get(type: ServiceType): ServiceConfig =
        configs.getValue(type)

    fun all(): List<ServiceConfig> = ServiceType.entries.map { get(it) }
}