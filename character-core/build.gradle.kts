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

// 边界门禁（Spec §3）：character-core 是纯 Kotlin/JVM 功能内核，
// 禁止依赖 Android、Compose、宿主实现或任何插件运行时。
val verifyCharacterCoreBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies character-core has no Android or host dependency."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "character-core has no Kotlin sources" }

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.",
            "import com.loyea.ui.",
            "import com.loyea.worker.",
            "import com.loyea.perception.",
            "import com.loyea.plugins.",
            "import com.loyea.plugin.",
            "import com.loyea.context.core."
        )
        kotlinSources.forEach { source ->
            val relativePath = source
                .relativeTo(sourceRoot.asFile)
                .invariantSeparatorsPath
            check(relativePath.startsWith("com/loyea/character/core/")) {
                "character-core source escaped its namespace: $relativePath"
            }

            val content = source.readText()
            val packageDeclaration = content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
            check(
                packageDeclaration == "package com.loyea.character.core" ||
                    packageDeclaration?.startsWith("package com.loyea.character.core.") == true
            ) {
                "character-core source uses a foreign package: $relativePath ($packageDeclaration)"
            }
            forbiddenImports.firstOrNull(content::contains)?.let { forbiddenImport ->
                error("character-core source imports a host/plugin/Android type: $relativePath ($forbiddenImport)")
            }
        }
    }
}

tasks.test {
    dependsOn(verifyCharacterCoreBoundaries)
    useJUnit()
}

dependencies {
    // 与 app 相同版本的 Gson（纯 JVM，MIT），供角色卡 codec 使用
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
