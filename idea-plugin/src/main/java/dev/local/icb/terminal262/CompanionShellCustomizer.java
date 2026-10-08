// 使用本机终端启动接口保留 codex 入口，并为原生回调绑定独立的终端环境。
package dev.local.icb.terminal262;

import com.intellij.idea.AppMode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.platform.eel.provider.LocalEelDescriptor;

import dev.local.icb.core.*;

import org.jetbrains.plugins.terminal.startup.*;

import java.io.IOException;
import java.nio.file.Path;

/** 2026-10-06：新本地终端自动适配原生执行方式，用户仍只输入 codex。 */
public final class CompanionShellCustomizer implements ShellExecOptionsCustomizer {
    @Override
    public void customizeExecOptions(Project project, MutableShellExecOptions options) {
        ProjectContextService service = project.getService(ProjectContextService.class);
        String productPrefix = System.getProperty("idea.platform.prefix", "idea");
        // 固定版本的远程后端不一定是无界面模式；必须检查实际产品模式，并拒绝远程前端产品。
        if (!service.allowed()
                || options.getEelDescriptor() != LocalEelDescriptor.INSTANCE
                || AppMode.isRemoteDevHost()
                || "JetBrainsClient".equals(productPrefix)
                || "Gateway".equals(productPrefix)
                || !System.getProperty("os.name").equals("Mac OS X")
                || System.getenv("SSH_CONNECTION") != null) return;
        BridgeApplicationService bridge =
                ApplicationManager.getApplication().getService(BridgeApplicationService.class);
        BridgeApplicationService.Binding binding = null;
        try {
            // 动态安装到已打开项目时也建立监听并加载内容根，避免依赖下一次项目启动。
            ApplicationManager.getApplication().invokeLater(service::start, project.getDisposed());
            service.reloadRoots();
            // 连接描述文件在终端后台回调创建，不触碰终端字符输入。
            binding = bridge.createBinding(service);
            String configuredCli = CompanionSettings.get().getState().cliPath;
            Path cli =
                    configuredCli.isBlank()
                            ? LocalCodexPaths.findExecutable()
                            : Path.of(configuredCli);
            if (cli == null) throw new IOException("ICB_CLI_PATH_INVALID");
            // 官方终端接口在 Shell 配置加载后应用 PATH 前缀，不修改用户配置或替换已有 Codex 文件。
            var launcher =
                    NativeCodexLauncher.prepare(
                            binding.descriptor().getParent().resolve("bin"),
                            cli,
                            options.getEnvs());
            options.prependEntryToPATH(launcher.directory());
            options.setEnvironmentVariable("ICB_REAL_CODEX", launcher.executable().toString());
            options.setEnvironmentVariable(
                    "ICB_DIRECT_MODE_SUPPORTED", Boolean.toString(launcher.directModeSupported()));
            options.setEnvironmentVariable("ICB_ENDPOINT_FILE", binding.descriptor().toString());
            service.terminalObserved = true;
            service.terminalLaunchMode =
                    launcher.directModeSupported()
                            ? "原生进程内运行（插件自动适配，仍输入 codex）"
                            : "沿用本机 Codex 声明的原生运行方式";
            String home = options.getEnvs().get("CODEX_HOME");
            service.shellCodexHome = home == null ? "默认 ~/.codex" : home;
        } catch (IOException | IllegalArgumentException ex) {
            if (binding != null) bridge.revokeBinding(binding);
            service.lastError = "ICB_TERMINAL_SETUP_FAILED";
            // 准备失败必须可见，不能让用户继续把普通终端误认为已经联动。
            dev.local.icb.ui.CompanionAction.onUi(
                    project,
                    () ->
                            dev.local.icb.ui.CompanionAction.notify(
                                    project,
                                    "Codex 终端联动准备失败，请在插件设置中核对已安装的 Codex 可执行路径，并查看“Codex 联动诊断”。"));
        }
    }

    @Override
    public Path getDefaultStartWorkingDirectory(Project project) {
        return null;
    }
}
