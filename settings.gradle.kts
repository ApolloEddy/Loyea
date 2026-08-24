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
include(":plugin-api")
include(":plugin-host")
include(":knowledge-core")
include(":plugins:tavern-core")
include(":plugins:tavern-storage")
include(":plugins:tavern-ui")
