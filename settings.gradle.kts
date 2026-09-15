pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "simple-assistant"

include(":assistant-core")
include(":assistant-compose")
include(":provider-ollama")
include(":provider-simpleai")

listOf("assistant-core", "assistant-compose", "provider-ollama", "provider-simpleai").forEach {
    project(":$it").projectDir = file("android/$it")
}
