// IDEA 原生终端栏显示当前编辑器状态，不改 Codex 屏幕或终端输出。
package dev.local.icb.terminal262;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import dev.local.icb.core.ProjectContextService;

import java.awt.BorderLayout;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.Timer;

/** 当前文件与真实选区的可见反馈，仅装到已绑定、仍在运行的 Codex 终端。 */
public final class TerminalContextBar implements Disposable {
    private final Project project;
    private final ProjectContextService service;
    private final Map<TerminalDraftSender.Destination, Bar> bars = new HashMap<>();
    private final Timer refresh;

    /** 界面栏与拥有者同寿命，插件关闭时不会留下定时器或界面组件。 */
    private record Bar(JBLabel label, Disposable lifetime) {}

    private TerminalContextBar(Project project, ProjectContextService service) {
        this.project = project;
        this.service = service;
        refresh = new Timer(200, event -> update());
        refresh.start();
    }

    /** 在项目服务第一次启动时创建提示栏，不启动或切换任何终端。 */
    public static void start(Project project, ProjectContextService service) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (project.isDisposed()) return;
        var controller = new TerminalContextBar(project, service);
        Disposer.register(service, controller);
    }

    /** 从已经校验的项目缓存更新标签，失去会话绑定时移除对应终端栏。 */
    private void update() {
        Map<TerminalDraftSender.Destination, Boolean> present = new HashMap<>();
        java.util.List<TerminalDraftSender.Target> terminals;
        try {
            terminals =
                    service.allowed()
                            ? TerminalDraftSender.candidates(project, service)
                            : java.util.List.of();
        } catch (IllegalStateException ex) {
            service.lastError = "ICB_TERMINAL_AMBIGUOUS";
            return;
        }
        // 引用只保留在实际运行的目标终端中，退出后的旧草稿不得传给下一次启动。
        service.retainTerminalDrafts(
                terminals.stream()
                        .map(TerminalDraftSender.Target::terminalId)
                        .collect(java.util.stream.Collectors.toSet()));
        for (var terminal : terminals) {
            var view = terminal.destination();
            present.put(view, true);
            Bar bar = bars.get(view);
            if (bar == null) {
                var lifetime = Disposer.newDisposable("Codex terminal context bar");
                var label = new JBLabel();
                var panel = new JPanel(new BorderLayout());
                panel.setBorder(JBUI.Borders.empty(4, 8));
                panel.add(label);
                // 适配器在 lifetime 销毁时还原窗口内容或移除经典终端通知栏。
                view.addBar(panel, lifetime);
                bar = new Bar(label, lifetime);
                bars.put(view, bar);
            }
            bar.label().setText(service.contextStatus());
            bar.label()
                    .setToolTipText("当前文件和选区变化会实时更新；Command + Option + K 将选区引用插入 Codex 草稿，不自动提交。");
        }
        bars.entrySet()
                .removeIf(
                        entry -> {
                            if (present.containsKey(entry.getKey())) return false;
                            Disposer.dispose(entry.getValue().lifetime());
                            return true;
                        });
    }

    /** 停止刷新并释放界面组件，项目关闭和插件卸载都执行。 */
    @Override
    public void dispose() {
        refresh.stop();
        // 项目服务可能在后台销毁，界面组件只在事件调度线程释放。
        Runnable remove =
                () -> {
                    bars.values().forEach(bar -> Disposer.dispose(bar.lifetime()));
                    bars.clear();
                };
        if (ApplicationManager.getApplication().isDispatchThread()) remove.run();
        else ApplicationManager.getApplication().invokeLater(remove);
    }
}
