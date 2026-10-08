// 统一桥接模块的 Java 编译级别、测试及可复现归档。
plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0" apply false
}

allprojects {
    group = "dev.local.icb"
    version = "1.0.0-dev.6"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // 独立桥接模块可由 Java 21 运行；IDEA 插件在其模块中使用 Java 25。
        options.release.set(21)
    }
    tasks.withType<Test>().configureEach {
        useJUnit()
        testLogging {
            events("failed", "skipped")
        }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Jar>().configureEach {
        // 项目许可与依赖声明随每个模块发布，桥接程序单独安装后也能查看。
        from(rootProject.file("LICENSE")) {
            into("META-INF")
            rename { "LICENSE-Codex-Terminal-Companion.txt" }
        }
        from(rootProject.file("docs/THIRD_PARTY_NOTICES.txt")) {
            into("META-INF")
        }
        from(rootProject.file("docs/licenses")) {
            into("META-INF/licenses")
        }
    }
    dependencies {
        "testImplementation"("junit:junit:4.13.2")
    }
    dependencyLocking {
        lockAllConfigurations()
    }
}
