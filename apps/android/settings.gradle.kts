pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Mural"
include(":app")
// On-device conversation engine (llama.cpp). Only exercises native code when selected via
// mural.conversationEngine=local; the default "openai" build ships it unused.
include(":core:engine-local")
