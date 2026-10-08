// 在临时 Codex 配置目录验证无损合并、幂等、卸载和并发修改。
package dev.local.icb.core;

import static org.junit.Assert.*;

import dev.local.icb.contract.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** 安装测试不读取或修改真实 ~/.codex。 */
public class InstallServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path home, cli, java;
    private final InstallService installer = new InstallService();

    @Before
    public void setup() throws Exception {
        home = temporary.newFolder("Codex home 中文").toPath();
        cli = temporary.newFile("codex fake ' quoted").toPath();
        Files.writeString(cli, "#!/bin/sh\nprintf 'codex-cli 0.159.1\\n'\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"));
        java = Path.of(System.getProperty("java.home"), "bin", "java");
    }

    @Test
    public void installPreservesUnknownAndThirdPartyHook() throws Exception {
        Files.writeString(
                home.resolve("hooks.json"),
                "{\"unknown\":true,\"hooks\":{\"SessionStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"third-party\"}]}]}}");
        Files.writeString(
                home.resolve("config.toml"),
                "# user comment\nmodel = \"test-model\"\n[mcp_servers.other]\ncommand = \"other\"\n");
        var plan = installer.prepare(home, cli, java, true);
        installer.apply(plan, new byte[] {1, 2, 3});
        var hooks = Json.parse(Files.readString(home.resolve("hooks.json")));
        assertTrue(hooks.get("unknown").getAsBoolean());
        assertEquals(2, hooks.getAsJsonObject("hooks").getAsJsonArray("SessionStart").size());
        assertTrue(
                Files.readString(home.resolve("config.toml"))
                        .startsWith("# user comment\nmodel = \"test-model\""));
    }

    @Test
    public void repeatInstallationIsIdempotent() throws Exception {
        var first = installer.prepare(home, cli, java, true);
        installer.apply(first, new byte[] {1});
        String hooks = Files.readString(home.resolve("hooks.json")),
                config = Files.readString(home.resolve("config.toml"));
        var second = installer.prepare(home, cli, java, true);
        assertEquals(hooks, second.hooksAfter());
        assertEquals(config, second.configAfter());
    }

    @Test
    public void invalidJsonStopsWithoutTouchingFiles() throws Exception {
        Files.writeString(home.resolve("hooks.json"), "{invalid}");
        assertThrows(
                IllegalArgumentException.class, () -> installer.prepare(home, cli, java, true));
        assertEquals("{invalid}", Files.readString(home.resolve("hooks.json")));
    }

    @Test
    public void invalidTomlStopsWithoutTouchingFiles() throws Exception {
        Files.writeString(home.resolve("config.toml"), "x = [");
        assertThrows(IOException.class, () -> installer.prepare(home, cli, java, true));
        assertEquals("x = [", Files.readString(home.resolve("config.toml")));
    }

    @Test
    public void concurrentEditAfterPreviewIsNeverOverwritten() throws Exception {
        var plan = installer.prepare(home, cli, java, true);
        Files.writeString(home.resolve("config.toml"), "model = \"new\"\n");
        assertThrows(IOException.class, () -> installer.apply(plan, new byte[] {1}));
        assertEquals("model = \"new\"\n", Files.readString(home.resolve("config.toml")));
    }

    @Test
    public void uninstallPreservesNewUserConfiguration() throws Exception {
        installer.apply(installer.prepare(home, cli, java, true), new byte[] {1});
        Files.writeString(
                home.resolve("config.toml"),
                Files.readString(home.resolve("config.toml"))
                        + "\n[mcp_servers.new_user_server]\ncommand = \"new\"\n");
        installer.apply(installer.prepareUninstall(home), new byte[0]);
        String config = Files.readString(home.resolve("config.toml"));
        assertTrue(config.contains("new_user_server"));
        assertFalse(config.contains("mcp_servers.idea_companion"));
    }

    @Test
    public void modifiedPluginTomlIsNeverOverwritten() throws Exception {
        installer.apply(installer.prepare(home, cli, java, true), new byte[] {1});
        Files.writeString(
                home.resolve("config.toml"),
                Files.readString(home.resolve("config.toml"))
                        .replace("required = false", "required = true"));
        assertThrows(IOException.class, () -> installer.prepare(home, cli, java, true));
        assertTrue(Files.readString(home.resolve("config.toml")).contains("required = true"));
    }

    @Test
    public void existingInlineHooksAreDetectedWithoutManualChoice() throws Exception {
        Files.writeString(
                home.resolve("config.toml"),
                "[[hooks.SessionStart]]\n[[hooks.SessionStart.hooks]]\ntype = \"command\"\ncommand = \"third-party\"\n");
        var plan = installer.prepare(home, cli, java, true);
        assertTrue(plan.configAfter().contains("third-party"));
        assertNull(plan.hooksAfter());
        installer.apply(plan, new byte[] {1});
        assertEquals(plan.configAfter(), installer.prepare(home, cli, java, true).configAfter());
    }

    @Test
    public void existingUserMcpTableCannotBeReplaced() throws Exception {
        Files.writeString(
                home.resolve("config.toml"),
                "[mcp_servers.idea_companion]\ncommand = \"user-owned\"\n");
        assertThrows(IOException.class, () -> installer.prepare(home, cli, java, true));
    }

    @Test
    public void installedFilesArePrivate() throws Exception {
        installer.apply(installer.prepare(home, cli, java, true), new byte[] {1});
        assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(home.resolve("hooks.json")));
        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(home.resolve("idea-companion")));
    }

    @Test
    public void newerCodexVersionIsAcceptedAndRecorded() throws Exception {
        Files.writeString(cli, "#!/bin/sh\nprintf 'codex-cli 0.200.0\\n'\n");
        var plan = installer.prepare(home, cli, java, true);
        assertEquals("codex-cli 0.200.0", plan.cliVersion());
        installer.apply(plan, new byte[] {1});
        assertFalse(Files.exists(home.resolve("idea-companion/compatibility-lock.json")));
        assertTrue(
                Files.readString(home.resolve("idea-companion/installation-info.json"))
                        .contains("0.200.0"));
    }

    @Test
    public void olderCodexVersionIsAcceptedWithoutExactVersionRequirement() throws Exception {
        Files.writeString(cli, "#!/bin/sh\nprintf 'codex-cli 0.158.0\\n'\n");
        assertEquals("codex-cli 0.158.0", installer.prepare(home, cli, java, true).cliVersion());
    }

    /** 使用本机已安装的完整 Codex 准备预览，配置目录仍是测试临时目录，绝不执行安装。 */
    @Test
    public void installedCodexCanPreparePreviewWithoutWritingConfiguration() throws Exception {
        Path installed = LocalCodexPaths.findExecutable();
        Assume.assumeTrue("本机未安装 Codex，略过现有安装检查", installed != null);
        var plan = installer.prepare(home, installed, java, true);
        assertTrue(plan.cliVersion().startsWith("codex-cli "));
        assertTrue(plan.preview().contains(plan.cliVersion()));
        assertFalse(Files.exists(home.resolve("config.toml")));
        assertFalse(Files.exists(home.resolve("hooks.json")));
    }

    @Test
    public void nonCodexExecutableIsRejectedBeforeWriting() throws Exception {
        Files.writeString(cli, "#!/bin/sh\nprintf 'other-cli 0.159.1\\n'\n");
        assertThrows(IOException.class, () -> installer.prepare(home, cli, java, true));
        assertFalse(Files.exists(home.resolve("config.toml")));
        assertFalse(Files.exists(home.resolve("hooks.json")));
    }

    @Test
    public void editedHandlerCannotCreateDuplicateOnUpgrade() throws Exception {
        installer.apply(installer.prepare(home, cli, java, true), new byte[] {1});
        var hooks = Json.parse(Files.readString(home.resolve("hooks.json")));
        hooks.getAsJsonObject("hooks")
                .getAsJsonArray("UserPromptSubmit")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("hooks")
                .get(0)
                .getAsJsonObject()
                .addProperty("timeout", 2);
        Files.writeString(home.resolve("hooks.json"), Json.GSON.toJson(hooks));
        assertThrows(IOException.class, () -> installer.prepare(home, cli, java, true));
        assertEquals(1, hooks.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit").size());
    }

    @Test
    public void uninstallKeepsEditedPluginTable() throws Exception {
        installer.apply(installer.prepare(home, cli, java, true), new byte[] {1});
        String changed =
                Files.readString(home.resolve("config.toml"))
                        .replace("required = false", "required = true");
        Files.writeString(home.resolve("config.toml"), changed);
        var plan = installer.prepareUninstall(home);
        installer.apply(plan, new byte[0]);
        assertEquals(changed, Files.readString(home.resolve("config.toml")));
        assertTrue(plan.preview().contains("原样保留"));
    }

    @Test
    public void interruptedTwoFileInstallationCanBeSafelyMergedAgain() throws Exception {
        var planned = installer.prepare(home, cli, java, true);
        Files.createDirectories(home.resolve("idea-companion"));
        var staged = Json.parse(planned.manifestAfter());
        staged.addProperty("installationState", "PENDING");
        staged.add(
                "tomlBlocks", Json.GSON.toJsonTree(List.of(staged.get("tomlBlock").getAsString())));
        Files.writeString(
                home.resolve("idea-companion/install-manifest.json"), Json.GSON.toJson(staged));
        Files.writeString(home.resolve("hooks.json"), planned.hooksAfter());
        Files.writeString(
                home.resolve("config.toml"), "model = \"user-added-after-interruption\"\n");
        var recovered = installer.prepare(home, cli, java, true);
        installer.apply(recovered, new byte[] {1});
        assertEquals(
                1,
                Json.parse(Files.readString(home.resolve("hooks.json")))
                        .getAsJsonObject("hooks")
                        .getAsJsonArray("UserPromptSubmit")
                        .size());
        assertTrue(
                Files.readString(home.resolve("config.toml"))
                        .contains("user-added-after-interruption"));
    }

    @Test
    public void shellQuotingHandlesSingleQuotesAndSpaces() throws Exception {
        String value = "a ' 中文 space";
        Process process =
                new ProcessBuilder(
                                "/bin/zsh", "-c", "printf '%s' " + InstallService.quoteShell(value))
                        .start();
        assertEquals(
                value, new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, process.waitFor());
    }
}
