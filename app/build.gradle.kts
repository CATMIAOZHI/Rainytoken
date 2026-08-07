import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.rainy.token"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rainy.token"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // CI 环境没有 release.jks，自动 fallback 到 debug keystore
            // Release workflow 通过 Secret 注入 release.jks
            val keystoreFile = rootProject.file("release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                // 凭据必须通过环境变量注入；缺失或为空时直接报错，不提供任何隐式 fallback
                // 注意：GitHub Actions 中未设置的 secret 会被替换为空字符串（非 null），
                // 因此用 takeIf { isNotBlank() } 同时防御 null 和空字符串
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PASSWORD env var not set or empty — cannot sign release")
                keyAlias = System.getenv("KEYSTORE_ALIAS")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_ALIAS env var not set or empty — cannot sign release")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEY_PASSWORD env var not set or empty — cannot sign release")
            } else {
                // 仅在 CI 环境中 fallback 到 debug keystore（用于编译/资源完整性验证）
                // 本地构建缺少 release.jks 时直接报错，避免静默生成 debug 签名的 Release APK
                if (System.getenv("CI") != null) {
                    val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                } else {
                    throw GradleException(
                        "release.jks 不存在，且当前不是 CI 环境。\n" +
                        "正式 Release 构建需要 release.jks 密钥库文件。\n" +
                        "如需本地验证编译，请设置环境变量 CI=true 或使用 assembleDebug。"
                    )
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // noCompress workaround removed — no longer needed
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Android 13+ 应用级语言设置：根据 values-* 目录自动生成 locales_config
        generateLocaleConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ═══════════════════════════════════════════════════════
// AGP 9.0 ARM64 Proot: Release resource guard + fallback
//
// 根因：AGP 9.0 的 optimizeReleaseResources 传入 --resource-path-shortening-map=<path>
// 等号形式，ARM64 AAPT2 不接受此语法（只接受空格分隔），exit code=1 但 AGP 不检查，
// 导致 optimized .ap_ 缺失，packageRelease 生成无资源残缺 APK。
//
// 修复链：
// 1. ~/.gradle/gradle.properties → android.aapt2FromMavenOverride 指向 wrapper
//    wrapper 将 --resource-path-shortening-map=<path> 拆分为两个独立 argv
// 2. 本任务作为构建防护：optimizeReleaseResources 之后验证 optimized .ap_ 完整性，
//    若缺失则复制 linked .ap_ 作为 fallback（功能正确，仅跳过 path shortening）
// 3. packageRelease 依赖此任务，确保消费到完整资源
// ═══════════════════════════════════════════════════════
if (System.getProperty("os.arch") == "aarch64") {
    tasks.register("guardReleaseResources") {
        dependsOn("optimizeReleaseResources")
        doLast {
            val linkedAp = layout.buildDirectory
                .file("intermediates/linked_resources_binary_format/release/processReleaseResources/linked-resources-binary-format-release.ap_")
                .get().asFile
            val optimizedDir = layout.buildDirectory
                .dir("intermediates/optimized_processed_res/release/optimizeReleaseResources")
                .get().asFile
            val optimizedAp = File(optimizedDir, "resources-release-optimize.ap_")

            if (optimizedAp.exists() && optimizedAp.length() > 0) {
                // optimized .ap_ 存在，验证内容完整性
                val zipList = providers.exec {
                    commandLine("unzip", "-l", optimizedAp.absolutePath)
                    isIgnoreExitValue = true
                }.standardOutput.asText.get()

                val hasManifest = zipList.contains("AndroidManifest.xml")
                val hasArsc = zipList.contains("resources.arsc")
                val hasRes = zipList.contains("res/")

                if (hasManifest && hasArsc && hasRes) {
                    logger.lifecycle("GuardReleaseResources: optimized .ap_ OK (${optimizedAp.length()} bytes)")
                    return@doLast
                }
                logger.warn("GuardReleaseResources: optimized .ap_ incomplete (manifest=$hasManifest arsc=$hasArsc res=$hasRes)")
            } else {
                logger.warn("GuardReleaseResources: optimized .ap_ missing or empty")
            }

            // Fallback: copy linked .ap_ → optimized .ap_
            if (!linkedAp.exists() || linkedAp.length() == 0L) {
                throw GradleException("GuardReleaseResources: linked .ap_ also missing or empty — cannot recover")
            }

            optimizedDir.mkdirs()
            linkedAp.copyTo(optimizedAp, overwrite = true)

            // Verify the copy
            val verifyList = providers.exec {
                commandLine("unzip", "-l", optimizedAp.absolutePath)
                isIgnoreExitValue = true
            }.standardOutput.asText.get()

            val vManifest = verifyList.contains("AndroidManifest.xml")
            val vArsc = verifyList.contains("resources.arsc")
            val vRes = verifyList.contains("res/")

            if (!vManifest || !vArsc || !vRes) {
                throw GradleException("GuardReleaseResources: fallback copy verification failed (manifest=$vManifest arsc=$vArsc res=$vRes)")
            }

            logger.lifecycle("GuardReleaseResources: fallback — copied linked .ap_ → optimized .ap_ (${optimizedAp.length()} bytes, path shortening skipped)")
        }
    }

    // packageRelease 必须依赖此防护任务
    project.tasks.matching { it.name == "packageRelease" }.configureEach {
        dependsOn("guardReleaseResources")
    }
}

// Force ARM64 AAPT2 in Proot environment (local only; GitHub Actions x86_64 uses default)
if (System.getProperty("os.arch") == "aarch64") {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
                useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
            }
        }
    }
}

dependencies {

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Network
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization.converter)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WebKit
    implementation(libs.androidx.webkit)

    // DI (Hilt + KSP)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
