// 使用 JetBrains 公开终端接口粘贴引用，不发送回车、不替换草稿、不启动进程。
package dev.local.icb.terminal262;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;
import com.intellij.terminal.frontend.view.TerminalView;
import com.intellij.terminal.frontend.view.TerminalViewSessionState;
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.ui.content.Content;
import com.jediterm.terminal.TerminalMode;
import com.jediterm.terminal.model.JediTerminal;

import dev.local.icb.core.BridgeApplicationService;
import dev.local.icb.core.ProjectContextService;

import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;

/** 精确匹配已收到原生回调的终端，绝不向普通 Shell 或别的项目插入文本。 */
public final class TerminalDraftSender {
    private TerminalDraftSender() {}

    /** 原生终端目标；view 为新版引擎，widget 为经典引擎，两者必须恰有一个非空。 */
    public record Destination(TerminalView view, TerminalWidget widget, Content content) {
        /** 通过各引擎的公开组件生命周期接口添加上下文栏。 */
        public void addBar(JComponent panel, Disposable lifetime) {
            if (view == null) {
                widget.addNotification(panel, lifetime);
                return;
            }
            // 使用公开的窗口内容接口包裹原组件，不依赖终端内部顶栏接口。
            JComponent original = content.getComponent();
            var wrapper = new JPanel(new BorderLayout());
            wrapper.add(panel, BorderLayout.NORTH);
            wrapper.add(original, BorderLayout.CENTER);
            content.setComponent(wrapper);
            Disposer.register(
                    lifetime,
                    () -> {
                        if (content.isValid() && content.getComponent() == wrapper) {
                            wrapper.remove(original);
                            content.setComponent(original);
                        }
                    });
        }
    }

    /**
     * 已验证的 IDEA 终端，不要求原生会话已经登记。
     *
     * @param terminalId 本实例注入凭证所绑定的终端标识
     * @param cwd 终端报告的当前目录；未报告时为 null，不猜测目录
     * @param destination 与终端标识匹配的原生界面组件
     */
    public record Target(String terminalId, String cwd, Destination destination) {}

    /**
     * 按本实例注入的凭证描述文件匹配唯一的终端视图。
     *
     * @param terminalId 本次引用明确选择的原生会话所属终端标识
     */
    public static Destination find(
            Project project, ProjectContextService service, String terminalId) {
        // 候选列表在回调前也可取得，已有会话仍按精确终端标识定位。
        var matches =
                candidates(project, service).stream()
                        .filter(target -> target.terminalId().equals(terminalId))
                        .toList();
        if (matches.size() > 1) throw new IllegalStateException("ICB_TERMINAL_AMBIGUOUS");
        return matches.isEmpty() ? null : matches.getFirst().destination();
    }

    /** 列出凭证属于当前项目、且仍在运行 Codex 的终端，不按照焦点或最近时间猜测。 */
    public static List<Target> candidates(Project project, ProjectContextService service) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        List<Target> found = new ArrayList<>();
        var bridge = ApplicationManager.getApplication().getService(BridgeApplicationService.class);
        for (var tab : TerminalToolWindowTabsManager.getInstance(project).getTabs()) {
            var view = tab.getView();
            var options = view.getStartupOptionsDeferred();
            if (!options.isCompleted() || options.isCancelled()) continue;
            String descriptor = options.getCompleted().getEnvVariables().get("ICB_ENDPOINT_FILE");
            String terminalId = bridge.terminalFor(service, descriptor);
            if (terminalId == null) continue;
            if (!(view.getSessionState().getValue() instanceof TerminalViewSessionState.Running))
                continue;
            Long pid = options.getCompleted().getPid();
            // 旧回调记录可能比进程寿命长，必须确认 Shell 的唯一前台子进程链仍是 Codex。
            if (pid == null || !codexRunning(pid)) continue;
            // 描述文件重复出现时拒绝猜测，避免把选区发给错误的窗口。
            if (found.stream().anyMatch(target -> target.terminalId().equals(terminalId)))
                throw new IllegalStateException("ICB_TERMINAL_AMBIGUOUS");
            found.add(
                    new Target(
                            terminalId,
                            view.getCurrentDirectory(),
                            new Destination(view, null, tab.getContent())));
        }
        // 经典终端有不同的公开接口，保持同样的凭证匹配和进程检查，不要求用户更换引擎。
        for (var widget : TerminalToolWindowManager.getInstance(project).getTerminalWidgets()) {
            var classic = ShellTerminalWidget.asShellJediTermWidget(widget);
            if (classic == null
                    || classic.getStartupOptions() == null
                    || classic.getProcessTtyConnector() == null) continue;
            String descriptor =
                    classic.getStartupOptions().getEnvVariables().get("ICB_ENDPOINT_FILE");
            String terminalId = bridge.terminalFor(service, descriptor);
            if (terminalId == null
                    || !classic.isSessionRunning()
                    || !codexRunning(classic.getProcessTtyConnector().getProcess().pid())) continue;
            if (found.stream().anyMatch(target -> target.terminalId().equals(terminalId)))
                throw new IllegalStateException("ICB_TERMINAL_AMBIGUOUS");
            found.add(
                    new Target(
                            terminalId,
                            classic.getCurrentDirectory(),
                            new Destination(null, widget, null)));
        }
        return List.copyOf(found);
    }

    /**
     * 检查本地 Shell 的唯一子进程链，不读取进程参数、认证信息或终端历史。
     *
     * @param shellPid 终端启动的本地 Shell 进程号
     */
    private static boolean codexRunning(long shellPid) {
        var process = ProcessHandle.of(shellPid).orElse(null);
        for (int depth = 0; depth < 8 && process != null && process.isAlive(); depth++) {
            String command = process.info().command().orElse("");
            String name = command.isEmpty() ? "" : Path.of(command).getFileName().toString();
            if (name.equals("codex")
                    || name.startsWith("codex-aarch64-")
                    || name.startsWith("codex-x86_64-")) return true;
            var children = process.children().filter(ProcessHandle::isAlive).limit(2).toList();
            process = children.size() == 1 ? children.getFirst() : null;
        }
        return false;
    }

    /**
     * 粘贴一个可审阅的引用草稿，要求终端正在接收括号粘贴，拒绝不支持的终端。
     *
     * @param text 单行文件引用，不能含回车、换行或控制字符
     */
    public static boolean paste(Destination destination, String text) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (text.isBlank() || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("ICB_REFERENCE_PATH_INVALID");
        // 括号粘贴让终端把引用作为文本处理；不调用 shouldExecute，不覆盖已有输入。
        if (destination.view != null)
            return destination
                    .view
                    .createSendTextBuilder()
                    .requireBracketedPasteMode()
                    .sendEndKeyBeforeText()
                    .trySend(" " + text + " ");
        var classic = ShellTerminalWidget.asShellJediTermWidget(destination.widget);
        if (classic == null
                || classic.getTerminalStarter() == null
                || !classic.isSessionRunning()
                || !(classic.getTerminal() instanceof JediTerminal terminal)
                || !terminal.isModelEnabled(TerminalMode.BracketedPasteMode)) return false;
        byte[] end = terminal.getCodeForKey(KeyEvent.VK_END, 0);
        if (end == null) return false;
        // 与新版引擎相同：移到当前输入行尾，括号粘贴，不发送回车或改变剪贴板。
        classic.getTerminalStarter()
                .sendString(
                        new String(end, StandardCharsets.US_ASCII)
                                + "\u001b[200~ "
                                + text
                                + " \u001b[201~",
                        false);
        return true;
    }

    /** 成功接收粘贴后打开已有终端并聚焦，不创建新的会话。 */
    public static void focus(Project project, Destination destination) {
        var window = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
        if (window == null) return;
        for (var tab : TerminalToolWindowTabsManager.getInstance(project).getTabs())
            if (tab.getView() == destination.view) {
                window.getContentManager().setSelectedContent(tab.getContent());
                window.activate(
                        () ->
                                destination
                                        .view
                                        .getPreferredFocusableComponent()
                                        .requestFocusInWindow());
                return;
            }
        if (destination.widget != null) {
            var container =
                    TerminalToolWindowManager.getInstance(project).getContainer(destination.widget);
            if (container != null)
                window.getContentManager().setSelectedContent(container.getContent());
            window.activate(destination.widget::requestFocus);
        }
    }
}
