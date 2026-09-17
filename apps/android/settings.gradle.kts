pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Mural"
include(":app")
// Local voice native layer (whisper.cpp STT): used by the remote conversation
// engines (deepseek/glm), which run STT/TTS on device and the LLM remotely.
// The on-device llama.cpp LLM engine was removed on 2026-09-17.
include(":core:engine-local")
