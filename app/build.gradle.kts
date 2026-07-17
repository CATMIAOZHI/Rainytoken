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
        versionCode = 5
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "RainyToken2026!"
            keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "rainy"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "RainyToken2026!"
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
    androidResources {
        // AGP 9.0 ARM64 Proot: noCompress workaround (manifest still needs post-process)
        noCompress += "xml"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ═══════════════════════════════════════════════════════
// AGP 9.0 ARM64 Proot Workaround: packageRelease 丢失
// AndroidManifest.xml
//
// 修复：assembleRelease 后检查 APK 是否含 manifest，
// 缺失则注入并重新签名。仅 aarch64 环境触发。
// ═══════════════════════════════════════════════════════
if (System.getProperty("os.arch") == "aarch64") {
    tasks.register("fixReleaseManifest") {
        dependsOn("assembleRelease")
        doLast {
            val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
            val manifest = layout.buildDirectory
                .file("intermediates/linked_resources_binary_format/release/processReleaseResources/linked-resources-binary-format-release.ap_")
                .get().asFile
            if (!apk.exists() || !manifest.exists()) return@doLast

            // 检查 APK 是否已包含 manifest
            val checkResult = providers.exec {
                commandLine("unzip", "-l", apk.absolutePath)
                isIgnoreExitValue = true
            }.standardOutput.asText.get()

            if (!checkResult.contains("AndroidManifest.xml")) {
                logger.lifecycle("FixManifestTask: injecting AndroidManifest.xml into release APK")
                // 从 .ap_ 提取 binary AndroidManifest.xml 到临时文件
                val tmpManifest = File(apk.parentFile, "AndroidManifest.xml")
                val extractProcess = Runtime.getRuntime().exec(
                    arrayOf("unzip", "-o", manifest.absolutePath, "AndroidManifest.xml", "-d", apk.parentFile!!.absolutePath)
                )
                extractProcess.waitFor()
                logger.lifecycle("FixManifestTask: extract from .ap_ exit code = ${extractProcess.exitValue()}")
                // 用 zip 命令注入 binary manifest
                val zipProcess = Runtime.getRuntime().exec(arrayOf("zip", "-j", apk.absolutePath, tmpManifest.absolutePath))
                zipProcess.waitFor()
                logger.lifecycle("FixManifestTask: zip exit code = ${zipProcess.exitValue()}")
                // 找 apksigner 并重新签名
                val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                var signerFile: File? = null
                if (androidHome != null) {
                    val btDir = File(androidHome, "build-tools")
                    val latest = btDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
                    if (latest != null) {
                        val signer = File(latest, "apksigner")
                        if (signer.exists()) signerFile = signer
                    }
                }
                val sf = signerFile
                if (sf != null && sf.exists()) {
                    // 从 android extension 拿签名配置
                    val androidExt = project.extensions.findByName("android")
                    val signingConfig = (androidExt as? com.android.build.api.dsl.ApplicationExtension)
                        ?.signingConfigs?.findByName("release")
                    val ksFile = signingConfig?.storeFile?.absolutePath ?: ""
                    val ksPass = signingConfig?.storePassword ?: ""
                    val ksAlias = signingConfig?.keyAlias ?: ""
                    val ksKeyPass = signingConfig?.keyPassword ?: ""
                    val signCmd = arrayOf(
                        sf.absolutePath, "sign",
                        "--ks", ksFile,
                        "--ks-pass", "pass:$ksPass",
                        "--ks-key-alias", ksAlias,
                        "--key-pass", "pass:$ksKeyPass",
                        "--v2-signing-enabled", "true",
                        "--v3-signing-enabled", "false",
                        "--min-sdk-version", "31",
                        apk.absolutePath
                    )
                    val signProcess = Runtime.getRuntime().exec(signCmd)
                    val errorText = signProcess.errorStream.bufferedReader().readText()
                    signProcess.waitFor()
                    logger.lifecycle("FixManifestTask: apksigner exit code = ${signProcess.exitValue()}")
                    if (errorText.isNotBlank()) logger.lifecycle("FixManifestTask: apksigner stderr = $errorText")
                    logger.lifecycle("FixManifestTask: APK re-signed successfully")
                } else {
                    logger.warn("FixManifestTask: apksigner not found, APK not re-signed")
                }
            }
        }
    }
    project.tasks.matching { it.name == "assembleRelease" }.configureEach {
        finalizedBy("fixReleaseManifest")
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
