// Codex Terminal Companion 的多模块工程与构建插件仓库。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "codex-idea-companion"
include("bridge-contract", "bridge-client", "idea-plugin")
