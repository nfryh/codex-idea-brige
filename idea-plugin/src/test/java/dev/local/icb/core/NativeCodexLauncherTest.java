// 真实执行私有启动入口，验证参数、安全、能力探测和禁用后的原生命令恢复。
package dev.local.icb.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 不替换用户安装的 Codex；测试脚本只打印收到的独立参数，不调用模型。 */
public class NativeCodexLauncherTest {
    /** 参数中的空格、引号与命令替换字符保持原样，不变成 Shell 命令。 */
    @Test
    public void ordinaryCodexEntryAddsNativeModeAndPreservesEveryArgument() throws Exception {
        Path root = Files.createTempDirectory("icb-launcher-test-");
        try {
            Path cli = root.resolve("real codex");
            Files.writeString(
                    cli,
                    "#!/bin/sh\nif [ \"$1\" = '--help' ]; then printf '  --no-daemon\\n'; exit 0; fi\nprintf '%s\\n' \"$@\"\n");
            Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"));
            var prepared = NativeCodexLauncher.prepare(root.resolve("bin"), cli, Map.of());
            assertTrue(prepared.directModeSupported());
            Path descriptor = Files.createFile(root.resolve("descriptor"));
            String hostile = "$(touch " + root.resolve("unexpected") + ")";
            var process =
                    new ProcessBuilder(
                            prepared.directory().resolve("codex").toString(),
                            "resume",
                            "a b",
                            hostile,
                            "quote'\"value");
            process.environment().put("ICB_REAL_CODEX", cli.toString());
            process.environment().put("ICB_DIRECT_MODE_SUPPORTED", "true");
            process.environment().put("ICB_ENDPOINT_FILE", descriptor.toString());
            Process child = process.start();
            assertTrue(child.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertEquals(
                    List.of("--no-daemon", "resume", "a b", hostile, "quote'\"value"),
                    new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .toList());
            assertFalse(Files.exists(root.resolve("unexpected")));
            assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(prepared.directory().resolve("codex")));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }

    /** 未声明新选项的已有安装保留其参数，删除描述文件后也恢复原生默认启动方式。 */
    @Test
    public void olderCapabilitiesAndRevokedConnectionPreserveOriginalExecution() throws Exception {
        Path root = Files.createTempDirectory("icb-launcher-test-");
        try {
            Path cli = root.resolve("real-codex");
            Files.writeString(
                    cli,
                    "#!/bin/sh\nif [ \"$1\" = '--help' ]; then printf 'Usage: codex\\n'; exit 0; fi\nprintf '%s\\n' \"$@\"\n");
            Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"));
            var prepared = NativeCodexLauncher.prepare(root.resolve("bin"), cli, Map.of());
            assertFalse(prepared.directModeSupported());
            for (String supported : List.of("false", "true")) {
                var process =
                        new ProcessBuilder(
                                prepared.directory().resolve("codex").toString(), "--version");
                process.environment().put("ICB_REAL_CODEX", cli.toString());
                process.environment().put("ICB_DIRECT_MODE_SUPPORTED", supported);
                process.environment()
                        .put("ICB_ENDPOINT_FILE", root.resolve("removed-descriptor").toString());
                Process child = process.start();
                assertTrue(child.waitFor(3, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
                assertEquals(
                        "--version\n",
                        new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }

    /** 已有启动入口被替换或变成符号链接时拒绝使用，不覆盖未知内容。 */
    @Test
    public void replacedLauncherCannotRedirectNativeCommand() throws Exception {
        Path root = Files.createTempDirectory("icb-launcher-test-");
        try {
            Path cli = root.resolve("real-codex");
            Files.writeString(cli, "#!/bin/sh\nprintf '  --no-daemon\\n'\n");
            Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"));
            Path bin = Files.createDirectory(root.resolve("bin"));
            Path launcher = bin.resolve("codex");
            Files.writeString(launcher, "unknown content");
            assertThrows(
                    java.io.IOException.class,
                    () -> NativeCodexLauncher.prepare(bin, cli, Map.of()));
            assertEquals("unknown content", Files.readString(launcher));
            Files.delete(launcher);
            Files.createSymbolicLink(launcher, cli);
            assertThrows(
                    java.io.IOException.class,
                    () -> NativeCodexLauncher.prepare(bin, cli, Map.of()));
            assertTrue(Files.isSymbolicLink(launcher));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }
}
