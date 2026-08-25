import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名凭据从项目根目录的 keystore.properties 读取（该文件已被 .gitignore 忽略，不会入库）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.loyea"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.loyea"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.5.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

/**
 * 宿主核心依赖方向架构门禁（D4：物理拆分收官）。
 *
 * 目标：Android 宿主核心（提示词组装、LLM 客户端、请求规范化、记忆/输出处理等中性逻辑）
 * 只能依赖 :plugin-api 契约（PersonaProjection / GenerationPatch / PromptPatch / 冻结回合）
 * 与 :knowledge-core 中立类型（WorldInfo*）等稳定边界，不得直接 import 具体的 Tavern
 * 实现包（:plugins:tavern-core / tavern-storage / tavern-ui）。
 *
 * 允许直接引用具体 Tavern 实现包的只有“组合根 / 适配器 / UI / 迁移桥 / 存储适配”文件，
 * 与 tavern_plugin_refactor.md 第 62 行“Android 宿主仅可在 composition root 和 Tavern
 * 适配器中引用具体插件类型”的许可边界一致。白名单之外的任何宿主核心文件一旦引入具体
 * Tavern 实现包，本任务即失败，从而防止依赖方向在未来被回退。
 */
val verifyHostCoreBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies host core only depends on the plugin-api contract, never on concrete Tavern implementation packages."

    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "App host has no Kotlin sources" }

        // 允许 import 具体 Tavern 实现包的宿主文件白名单（相对 src/main/java 的路径）。
        // 这些文件均为组合根 / 适配器 / UI 渲染面 / 迁移桥 / 存储适配，属允许的边界例外。
        val boundaryWhitelist = setOf(
            // 组合根 / 插件注册装配
            "com/loyea/LoyeaApplication.kt",
            // 后台主动问候：需在请求租约内调用插件回合工厂
            "com/loyea/worker/GreetingWorker.kt",
            // UI 渲染与控制面
            "com/loyea/ui/chat/ChatScreen.kt",
            "com/loyea/ui/chat/ChatViewModel.kt",
            "com/loyea/ui/chat/TavernScreen.kt",
            "com/loyea/ui/settings/WorldInfoSettings.kt",
            // 适配器 / 迁移桥（文档允许的具体插件类型引用边界）
            "com/loyea/ui/chat/TavernCardParser.kt",
            "com/loyea/ui/chat/TavernCharacterCardAdapter.kt",
            "com/loyea/ui/chat/TavernCardPresetAdapter.kt",
            "com/loyea/ui/chat/TavernCardRegexAdapter.kt",
            "com/loyea/ui/chat/TavernCardResourceBindings.kt",
            "com/loyea/ui/chat/TavernCardDownloader.kt",
            "com/loyea/ui/chat/TavernChatSessionCodec.kt",
            "com/loyea/ui/chat/TavernGroupReplyCoordinator.kt",
            "com/loyea/ui/chat/LegacyTavernTurnAdapter.kt",
            "com/loyea/ui/chat/AppTavernPersonaRepository.kt",
            "com/loyea/ui/chat/CharacterPersonaOwnership.kt",
            "com/loyea/ui/chat/TavernFieldDropMigration.kt",
            // 迁移期间仍暂留 :app 的 World Info 配置 / 提示词模板桥
            "com/loyea/ui/chat/WorldInfoConfig.kt",
            "com/loyea/ui/chat/PromptAssembler.kt",
            // 宿主持久化适配：会话/消息与 ST 格式、插件存储之间的桥
            "com/loyea/ui/chat/ChatStorageManager.kt"
        )

        // 具体 Tavern 实现包的 import 前缀（同时覆盖 core / storage / ui 三个包子包）。
        val forbiddenImportPrefix = "import com.loyea.plugins.tavern."
        kotlinSources.forEach { source ->
            val relativePath = source
                .relativeTo(sourceRoot.asFile)
                .invariantSeparatorsPath
            if (relativePath in boundaryWhitelist) return@forEach

            val forbiddenImport = source
                .readLines()
                .map(String::trim)
                .firstOrNull { it.startsWith(forbiddenImportPrefix) }
            check(forbiddenImport == null) {
                "Host core file must not import a concrete Tavern implementation package: " +
                    "$relativePath ($forbiddenImport)"
            }
        }
    }
}

// 挂到宿主单测门禁：验收命令 :app:testDebugUnitTest 会先执行本依赖方向检查。
// AGP 的 testDebugUnitTest 任务采用延迟注册，故用 matching+configureEach 确保不因时序而失败。
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(verifyHostCoreBoundaries)
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugin-host"))
    implementation(project(":knowledge-core"))
    implementation(project(":plugins:tavern-core"))
    implementation(project(":plugins:tavern-storage"))
    implementation(project(":plugins:tavern-ui"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // Play Services Location (for Activity Recognition)
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
