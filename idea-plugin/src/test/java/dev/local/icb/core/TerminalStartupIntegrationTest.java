// 使用本机 IDEA 的真实终端配置器和 zsh 启动，验证用户只输入 codex 时命中插件入口。
package dev.local.icb.core;

import com.intellij.testFramework.HeavyPlatformTestCase;

import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner;
import org.jetbrains.plugins.terminal.ShellStartupOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** 用户 Shell 配置改变 PATH 后，平台集成仍在启动末尾正确恢复 Codex 命令入口。 */
public class TerminalStartupIntegrationTest extends HeavyPlatformTestCase {
    @Override
    protected com.intellij.testFramework.OpenProjectTaskBuilder getOpenProjectOptions() {
        return super.getOpenProjectOptions().runPostStartUpActivities(false);
    }

    /** 真实平台配置与 Shell 初始化共同保证命令入口，无需手写运行参数或修改用户配置。 */
    public void testPlainCodexUsesNativeLauncherAfterUserProfileResetsPath() throws Exception {
        Path root = Path.of(getProject().getBasePath());
        Files.createDirectories(root);
        Path home = Files.createDirectory(root.resolve("fixture-home"));
        Files.writeString(
                home.resolve(".zshrc"), "# controlled fixture\nexport PATH=/usr/bin:/bin\n");
        Path cli = root.resolve("fixture-native-codex");
        Files.writeString(
                cli,
                "#!/bin/sh\nif [ \"$1\" = '--help' ]; then printf '  --no-daemon\\n'; exit 0; fi\nprintf 'ICB_FIXTURE_ARG=%s\\n' \"$@\"\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"));
        String previous = CompanionSettings.get().getState().cliPath;
        CompanionSettings.get().getState().cliPath = cli.toString();
        CompanionSettings.get().getState().integrationEnabled = true;
        var service = getProject().getService(ProjectContextService.class);
        try {
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("HOME", home.toString());
            env.remove("ZDOTDIR");
            var initial =
                    new ShellStartupOptions.Builder()
                            .workingDirectory(root.toString())
                            .shellCommand(List.of("/bin/zsh", "-l", "-i"))
                            .envVariables(env)
                            .build();
            ShellStartupOptions configured;
            // 终端配置器在后台执行实际的本插件启动扩展和平台 Shell 集成。
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                configured =
                        executor.submit(
                                        () ->
                                                LocalTerminalDirectRunner.createTerminalRunner(
                                                                getProject())
                                                        .configureStartupOptions(initial))
                                .get(10, TimeUnit.SECONDS);
            }
            var builder =
                    new ProcessBuilder(configured.getShellCommand())
                            .directory(root.toFile())
                            .redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(configured.getEnvVariables());
            Process shell = builder.start();
            try {
                // 与用户终端相同，先完成交互 Shell 启动，再从输入流键入命令，不使用执行脚本模式。
                shell.getOutputStream()
                        .write("codex --fixture\nexit\n".getBytes(StandardCharsets.UTF_8));
                shell.getOutputStream().close();
                assertTrue(shell.waitFor(5, TimeUnit.SECONDS));
                String output =
                        new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(output, 0, shell.exitValue());
                assertTrue(output, output.contains("ICB_FIXTURE_ARG=--no-daemon"));
                assertTrue(output, output.contains("ICB_FIXTURE_ARG=--fixture"));
                assertTrue(service.terminalObserved);
                Path descriptor = Path.of(configured.getEnvVariables().get("ICB_ENDPOINT_FILE"));
                Path launcher = descriptor.getParent().resolve("bin/codex");
                assertTrue(Files.isExecutable(launcher));
                // 撤销项目后回收本实例启动脚本，用户配置内容保持原样。
                service.revoke();
                assertFalse(Files.exists(descriptor));
                assertFalse(Files.exists(launcher));
                assertEquals(
                        "# controlled fixture\nexport PATH=/usr/bin:/bin\n",
                        Files.readString(home.resolve(".zshrc")));
            } finally {
                if (shell.isAlive()) shell.destroyForcibly();
            }
        } finally {
            service.revoke();
            CompanionSettings.get().getState().cliPath = previous;
        }
    }
}
