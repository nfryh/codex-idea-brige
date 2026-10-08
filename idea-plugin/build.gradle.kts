// 复用本机 IDEA 作为开发平台；发布包不限制到某一个补丁构建号。
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 开发者可以通过 ideaHome 属性指定已安装的 IDEA；不会下载或复制另一套开发环境。
val installedIdea = providers.gradleProperty("ideaHome").orElse(providers.environmentVariable("IDEA_HOME"))
    .orElse("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app")

dependencies {
    implementation(project(":bridge-contract"))
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation(project(":bridge-client"))
    intellijPlatform {
        local(installedIdea)
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    dependsOn(":bridge-client:jar")
    from(project(":bridge-client").tasks.named("jar")) {
        into("bridge")
    }
}

intellijPlatform {
    pluginConfiguration {
        name.set("Codex Terminal Companion")
        version.set(project.version.toString())
        ideaVersion {
            sinceBuild.set("262")
            untilBuild.set(provider { null })
        }
    }
    pluginVerification {
        ides {
            local(file(installedIdea.get()))
        }
        // 内部接口报告不隐藏；任务末尾只允许终端适配器的实际远程产品模式判断，其余内部接口仍失败。
        failureLevel.set(VerifyPluginTask.FailureLevel.values().filterNot {
            it.name in setOf("ALL", "INTERNAL_API_USAGES", "EXPERIMENTAL_API_USAGES", "DEPRECATED_API_USAGES")
        })
    }
}

// 对实际验证平台核查已登记的内部接口，报告结构变化也要求人工重新审核。
tasks.named<VerifyPluginTask>("verifyPlugin") {
    // 使用完整的本机安装离线核查，禁止验证器另外下载 IDE 和远程依赖。
    offline.set(true)
    doLast {
        val reports = fileTree(layout.buildDirectory.dir("reports/pluginVerifier")) {
            include("**/internal-api-usages.txt")
        }
        check(!reports.isEmpty) { "插件验证器没有生成内部接口报告，不能认定兼容检查通过。" }
        for (report in reports) {
            val entries = report.readLines().filter { it.isNotBlank() }
            val allowed = listOf(
                "Internal class com.intellij.idea.AppMode is referenced in dev.local.icb.terminal262.CompanionShellCustomizer.",
                "Internal method com.intellij.idea.AppMode.isRemoteDevHost() : boolean is invoked in dev.local.icb.terminal262.CompanionShellCustomizer."
            )
            check(entries.size == 2 && entries.all { entry -> allowed.any { entry.startsWith(it) } }) {
                "发现未登记的内部接口或报告格式变化，必须手工审核后才能交付：${report.absolutePath}"
            }
        }
    }
}

// 对外安装包使用产品名称；许可声明保留在插件目录根部。
tasks.named<BuildPluginTask>("buildPlugin") {
    archiveFileName.set("codex-terminal-companion-${project.version}.zip")
    from(listOf(rootProject.file("LICENSE"), rootProject.file("docs/THIRD_PARTY_NOTICES.txt")))
    from(rootProject.file("docs/licenses")) {
        into("licenses")
    }
}

// 导出已构建的安装包和文件摘要，避免维护者手工复制时取错版本。
tasks.register<Copy>("prepareDistribution") {
    group = "distribution"
    description = "生成可安装的插件压缩包与 SHA-256 文件摘要。"
    val pluginArchive = tasks.named<BuildPluginTask>("buildPlugin").flatMap { it.archiveFile }
    dependsOn(pluginArchive)
    from(pluginArchive)
    into(rootProject.layout.projectDirectory.dir("dist"))
    outputs.file(rootProject.layout.projectDirectory.file("dist/codex-terminal-companion-${project.version}.zip.sha256"))
    doLast {
        val archive = destinationDir.resolve(pluginArchive.get().asFile.name)
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
        val checksum = HexFormat.of().formatHex(digest)
        archive.resolveSibling("${archive.name}.sha256").writeText("$checksum  ${archive.name}\n")
    }
}

// 性能报告只含样本数量和时长，不保存正文、请求或凭证。
tasks.test {
    systemProperty("icb.performanceReport", layout.buildDirectory.file("reports/local-performance.json").get().asFile.absolutePath)
    systemProperty("icb.nativeTerminalReport", layout.buildDirectory.file("reports/native-terminal-roundtrip.json").get().asFile.absolutePath)
}
