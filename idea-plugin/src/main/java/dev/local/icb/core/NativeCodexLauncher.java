// 在 IDEA 私有运行目录准备原生命令适配，不改用户 Shell 配置、Codex 安装或登录。
package dev.local.icb.core;

import dev.local.icb.contract.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 通过公开帮助检查本机能力，用户仍然只输入 codex，原始参数按参数列表传递。 */
public final class NativeCodexLauncher {
    private NativeCodexLauncher() {}

    /**
     * 本次终端采用的原生命令适配。
     *
     * @param directory 仅本实例终端使用的私有命令入口目录
     * @param executable 用户已经安装并明确选中的原生 Codex 路径
     * @param directModeSupported true 表示本机支持原生进程内运行选项，false 表示未声明该选项并沿用原生方式
     */
    public record Prepared(Path directory, Path executable, boolean directModeSupported) {}

    /** 在终端后台启动阶段检查能力并写入私有启动入口，不启动 Codex 会话。 */
    public static Prepared prepare(Path directory, Path cli, Map<String, String> environment)
            throws IOException {
        if (!cli.isAbsolute() || !Files.isRegularFile(cli) || !Files.isExecutable(cli))
            throw new IOException("ICB_CLI_PATH_INVALID");
        var builder = new ProcessBuilder(cli.toString(), "--help").redirectErrorStream(true);
        builder.environment().putAll(environment);
        // npm 安装的原生命令依赖同目录的 Node.js，界面进程的 PATH 可能尚未包含该目录。
        builder.environment()
                .put(
                        "PATH",
                        cli.getParent()
                                + java.io.File.pathSeparator
                                + builder.environment().getOrDefault("PATH", ""));
        Process probe = builder.start();
        String help;
        try {
            if (!probe.waitFor(2, TimeUnit.SECONDS) || probe.exitValue() != 0)
                throw new IOException("ICB_CLI_CAPABILITY_CHECK_FAILED");
            help = new String(Json.bounded(probe.getInputStream(), 65536), StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("ICB_CLI_CAPABILITY_CHECK_INTERRUPTED");
        } finally {
            if (probe.isAlive()) probe.destroyForcibly();
        }
        boolean direct =
                java.util.regex.Pattern.compile("(?m)^\\s*--no-daemon(?:\\s|$)")
                        .matcher(help)
                        .find();
        byte[] script;
        try (var input = NativeCodexLauncher.class.getResourceAsStream("/terminal/codex")) {
            if (input == null) throw new IOException("ICB_CLI_LAUNCHER_MISSING");
            script = input.readAllBytes();
        }
        if (Files.isSymbolicLink(directory)) throw new IOException("ICB_CLI_LAUNCHER_CHANGED");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path executable = directory.resolve("codex");
        if (Files.exists(executable, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(executable)
                    || !java.util.Arrays.equals(Files.readAllBytes(executable), script))
                throw new IOException("ICB_CLI_LAUNCHER_CHANGED");
        } else {
            Files.createFile(
                    executable,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            Files.write(executable, script, StandardOpenOption.WRITE);
        }
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return new Prepared(directory, cli, direct);
    }
}
