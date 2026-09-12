plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// 边界门禁：调制器核心是纯 Kotlin/JVM 插件实现，禁止依赖 Android、宿主 app 或其他模块。
// 非侵入式约定见 plugins/modulator/README.md 与 docs/loyea_modulator Spec v1.0 §13。
val verifyModulatorBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies modulator core has no Android or host dependency."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "modulator core has no Kotlin sources" }

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.",
            "import com.loyea.ui.",
            "import com.loyea.worker.",
            "import com.loyea.perception.",
            "import com.loyea.character.core.",
            "import com.loyea.storage.",
            "import com.loyea.llm."
        )
        kotlinSources.forEach { source ->
            val relativePath = source
                .relativeTo(sourceRoot.asFile)
                .invariantSeparatorsPath
            check(relativePath.startsWith("com/loyea/plugin/modulator/")) {
                "modulator core source escaped its namespace: $relativePath"
            }

            val content = source.readText()
            val packageDeclaration = content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
            check(
                packageDeclaration == "package com.loyea.plugin.modulator" ||
                    packageDeclaration?.startsWith("package com.loyea.plugin.modulator.") == true
            ) {
                "modulator core source uses a foreign package: $relativePath ($packageDeclaration)"
            }
            forbiddenImports.firstOrNull(content::contains)?.let { forbiddenImport ->
                error("modulator core source imports an Android/host type: $relativePath ($forbiddenImport)")
            }
        }
    }
}

tasks.test {
    dependsOn(verifyModulatorBoundaries)
    useJUnit()
}

// web 调试页：JDK HttpServer 承载 plugins/modulator/web/index.html，
// 通过公共 API 驱动真模型（无状态检查点协议）。工作目录必须为仓库根。
val webDemo by tasks.registering(JavaExec::class) {
    group = "loyea-plugin"
    description = "启动调制器 web 调试页：http://127.0.0.1:8628"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.loyea.plugin.modulator.webdemo.WebDemoServerKt")
    workingDir = rootDir
}

dependencies {
    // 与 app / character-core 同版本锁定的 Gson（纯 JVM，MIT），供检查点与感知输入解析
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
