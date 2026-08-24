plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

val verifyKnowledgeCoreBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies the neutral knowledge core has no Android or Tavern plugin dependency."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "Knowledge core has no Kotlin sources" }

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.",
            "import com.loyea.plugins.tavern.",
            "import com.loyea.ui.",
            "import com.loyea.worker.",
            "import com.loyea.perception.",
        )
        kotlinSources.forEach { source ->
            val relativePath = source
                .relativeTo(sourceRoot.asFile)
                .invariantSeparatorsPath
            check(relativePath.startsWith("com/loyea/context/core/")) {
                "Knowledge core source escaped its neutral namespace: $relativePath"
            }

            val content = source.readText()
            val packageDeclaration = content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
            check(
                packageDeclaration == "package com.loyea.context.core" ||
                    packageDeclaration?.startsWith("package com.loyea.context.core.") == true
            ) {
                "Knowledge core source uses a non-neutral package: $relativePath ($packageDeclaration)"
            }
            forbiddenImports.firstOrNull(content::contains)?.let { forbiddenImport ->
                error("Knowledge core source imports a host/plugin implementation: $relativePath ($forbiddenImport)")
            }
        }
    }
}

tasks.test {
    dependsOn(verifyKnowledgeCoreBoundaries)
    useJUnit()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
