pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Loyea"
include(":app")
include(":character-core")

// 调制器插件（docs/loyea_modulator Spec v1.0）：非侵入式独立模块，
// 不被 app / character-core 反向依赖；接入与验收前不进入聊天链路。
// web 调试页：gradlew :loyea-modulator-core:webDemo 后打开 http://127.0.0.1:8628
include(":loyea-modulator-core")
project(":loyea-modulator-core").projectDir = file("plugins/modulator/core")
