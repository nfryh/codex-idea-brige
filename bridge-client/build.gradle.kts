// 独立 Java 21 桥接程序，随插件携带完整运行依赖。
plugins {
    java
}

dependencies {
    implementation(project(":bridge-contract"))
}

tasks.jar {
    dependsOn(":bridge-contract:jar")
    archiveFileName.set("bridge-client.jar")
    manifest {
        attributes["Main-Class"] = "dev.local.icb.client.BridgeClientMain"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
}

// 独立进程测试使用真实可运行桥接包。
tasks.test {
    dependsOn(tasks.jar)
    systemProperty("bridge.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
