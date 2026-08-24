plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":plugin-api"))
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

val verifyTavernCoreBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies Tavern core stays in its plugin namespace and does not import host UI/runtime code."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "Tavern core has no Kotlin sources" }

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
            check(relativePath.startsWith("com/loyea/plugins/tavern/core/")) {
                "Tavern core source escaped the plugin namespace path: $relativePath"
            }

            val content = source.readText()
            val packageDeclaration = content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
            check(
                packageDeclaration == "package com.loyea.plugins.tavern.core" ||
                    packageDeclaration?.startsWith("package com.loyea.plugins.tavern.core.") == true
            ) {
                "Tavern core source uses a host package: $relativePath ($packageDeclaration)"
            }
            forbiddenImports.firstOrNull(content::contains)?.let { forbiddenImport ->
                error("Tavern core source imports host code: $relativePath ($forbiddenImport)")
            }
        }
    }
}

tasks.test {
    dependsOn(verifyTavernCoreBoundaries)
    useJUnit()
}
