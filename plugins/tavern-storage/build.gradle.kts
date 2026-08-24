plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

val verifyTavernStorageBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies Tavern storage stays a pure plugin-owned JVM module."

    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceRoot)

    doLast {
        val kotlinSources = sourceRoot.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(kotlinSources.isNotEmpty()) { "Tavern storage has no Kotlin sources" }

        val forbiddenImports = listOf(
            "import android.",
            "import androidx.",
            "import com.loyea.ui.",
            "import com.loyea.worker.",
            "import com.loyea.perception.",
            "import com.loyea.plugins.tavern.core.",
        )
        kotlinSources.forEach { source ->
            val relativePath = source
                .relativeTo(sourceRoot.asFile)
                .invariantSeparatorsPath
            check(relativePath.startsWith("com/loyea/plugins/tavern/storage/")) {
                "Tavern storage source escaped its namespace: $relativePath"
            }

            val content = source.readText()
            val packageDeclaration = content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
            check(
                packageDeclaration == "package com.loyea.plugins.tavern.storage" ||
                    packageDeclaration?.startsWith("package com.loyea.plugins.tavern.storage.") == true
            ) {
                "Tavern storage source uses an unexpected package: $relativePath ($packageDeclaration)"
            }
            forbiddenImports.firstOrNull(content::contains)?.let { forbiddenImport ->
                error("Tavern storage imports an Android/host/Tavern runtime implementation: $relativePath ($forbiddenImport)")
            }
        }
    }
}

tasks.test {
    dependsOn(verifyTavernStorageBoundaries)
    useJUnit()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
