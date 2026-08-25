plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // D3：URL/表单 JSON 校验（isOptionalJsonObjectValid）需要轻量 JSON 解析器。
    // 仅此一个生产依赖，仍保持纯 JVM、不依赖 Android/Compose/宿主类型。
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
}

val verifyTavernUiBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies Tavern UI state stays platform-neutral and isolated from host UI code."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "Tavern UI state has no Kotlin sources" }

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.",
            "import com.loyea.ui.",
            "import com.loyea.worker.",
            "import com.loyea.perception.",
        )
        kotlinSources.forEach { source ->
            val relativePath = source
                .relativeTo(sourceRoot.asFile)
                .invariantSeparatorsPath
            check(relativePath.startsWith("com/loyea/plugins/tavern/ui/")) {
                "Tavern UI source escaped the plugin namespace path: $relativePath"
            }

            val content = source.readText()
            val packageDeclaration = content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
            check(
                packageDeclaration == "package com.loyea.plugins.tavern.ui" ||
                    packageDeclaration?.startsWith("package com.loyea.plugins.tavern.ui.") == true
            ) {
                "Tavern UI source uses a host package: $relativePath ($packageDeclaration)"
            }
            forbiddenImports.firstOrNull(content::contains)?.let { forbiddenImport ->
                error("Tavern UI source imports platform or host code: $relativePath ($forbiddenImport)")
            }
        }
    }
}

tasks.test {
    dependsOn(verifyTavernUiBoundaries)
    useJUnit()
}
