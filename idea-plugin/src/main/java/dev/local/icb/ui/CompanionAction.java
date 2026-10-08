// 右键和快捷键发送可见文件引用；保留原生终端草稿，不提交问题或修改文件。
package dev.local.icb.ui;

import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.*;

import dev.local.icb.contract.*;
import dev.local.icb.core.*;
import dev.local.icb.terminal262.TerminalDraftSender;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;

/** 所有操作基于当前项目和明确目标会话，不承担 Codex 启动职责。 */
public final class CompanionAction extends AnAction implements DumbAware {
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        ProjectContextService service = project.getService(ProjectContextService.class);
        String action = ActionManager.getInstance().getId(this);
        if (action.equals("ICB.ConnectionDiagnostics")) {
            diagnostics(project, service);
            return;
        }
        if (!service.allowed()) {
            notify(project, "Codex 联动已关闭，或当前项目已关闭");
            return;
        }
        // 动态安装后首次动作也启动项目监听，不要求用户关闭真实项目。
        service.start();
        try {
            switch (action) {
                case "ICB.SelectSession" -> {
                    choose(project, service);
                }
                case "ICB.ShowContextQueue" -> new QueueDialog(project, service).show();
                case "ICB.ShowDiff" -> {
                    var reviews = service.diffs.list();
                    if (reviews.isEmpty()) {
                        notify(project, "ICB_DIFF_BASE_MISSING：没有已记录的本轮比较");
                        return;
                    }
                    String[] options =
                            reviews.stream()
                                    .map(
                                            r ->
                                                    r.path()
                                                            + " | "
                                                            + (r.origin().equals("DISK_BEFORE_TURN")
                                                                    ? "磁盘基线"
                                                                    : "独立编辑器快照")
                                                            + (r.deleted() ? " | 文件已删除" : "")
                                                            + " | "
                                                            + r.at()
                                                            + " | reviewId="
                                                            + r.id())
                                    .toArray(String[]::new);
                    int index =
                            SelectionDialog.choose(project, "选择已记录文件的只读比较", "Codex 文件比较", options);
                    if (index >= 0) service.diffs.show(reviews.get(index).id());
                }
                case "ICB.FindFileAndAdd" -> find(project, service);
                case "ICB.AddProjectFiles" -> {
                    VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
                    if (files == null) return;
                    // 项目树多选按路径排序；默认仅路径，正文需从编辑器显式选择。
                    List<Path> selected =
                            Arrays.stream(files)
                                    .filter(file -> !file.isDirectory())
                                    .sorted(Comparator.comparing(VirtualFile::getPath))
                                    .map(file -> Path.of(file.getPath()))
                                    .toList();
                    service.execute(
                            () -> {
                                try {
                                    List<Attachment> items = new ArrayList<>();
                                    for (Path path : selected) {
                                        var identity = service.pathPolicy().identify(path);
                                        items.add(
                                                Attachment.path(
                                                        identity.getKey(), identity.getValue()));
                                    }
                                    addOnUi(project, service, items);
                                } catch (IOException ex) {
                                    onUi(
                                            project,
                                            () -> notify(project, "文件引用被拒绝：路径不属于当前项目、敏感路径或文件不可访问"));
                                }
                            });
                }
                case "ICB.AddSelection", "ICB.AddCurrentFile" -> {
                    Editor editor = event.getData(CommonDataKeys.EDITOR);
                    if (editor == null) {
                        notify(project, "当前操作没有源码编辑器");
                        return;
                    }
                    String kind =
                            action.equals("ICB.AddCurrentFile")
                                    ? "PATH_ONLY"
                                    : "SELECTION_SNAPSHOT";
                    // 必须在会话选择前冻结当前选区，后续编辑不会改变本次快照。
                    ProjectContextService.RawCapture raw = service.capture(editor, kind);
                    service.execute(
                            () -> {
                                try {
                                    addOnUi(project, service, service.attachments(raw));
                                } catch (IOException ex) {
                                    if ("SENSITIVE_PATH_DENIED".equals(ex.getMessage()))
                                        onUi(
                                                project,
                                                () -> {
                                                    int answer =
                                                            Messages.showYesNoDialog(
                                                                    project,
                                                                    "该文件命中敏感路径规则。是否仅允许本次冻结引用发送给你选择的会话？\n"
                                                                            + raw.path()
                                                                            + "\n不会允许自动上下文或模型工具读取该文件。",
                                                                    "敏感引用单次确认",
                                                                    null);
                                                    if (answer == Messages.YES)
                                                        service.execute(
                                                                () -> {
                                                                    try {
                                                                        addOnUi(
                                                                                project,
                                                                                service,
                                                                                service.attachments(
                                                                                        raw, true));
                                                                    } catch (IOException failure) {
                                                                        onUi(
                                                                                project,
                                                                                () ->
                                                                                        notify(
                                                                                                project,
                                                                                                "单次引用仍被拒绝：根边界或路径验证未通过"));
                                                                    }
                                                                });
                                                });
                                    else
                                        onUi(
                                                project,
                                                () ->
                                                        notify(
                                                                project,
                                                                "引用被拒绝：选区为空、路径不属于当前项目或文件不可访问"));
                                }
                            });
                }
                default -> throw new IllegalArgumentException("ACTION_UNKNOWN");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            notify(project, ex.getMessage());
        }
    }

    /** 查找或明确选择会话，不按照最后焦点或活动时间猜测。 */
    public static SessionStore.Key choose(Project project, ProjectContextService service) {
        var available =
                service.sessions.list().entrySet().stream()
                        .sorted(Comparator.comparing(e -> e.getKey().sessionId()))
                        .toList();
        if (available.isEmpty()) {
            notify(project, "没有可用的唯一绑定会话。请先在已有终端手动输入 codex；冲突会话请退出重复恢复后重新连接。");
            return null;
        }
        String[] labels =
                available.stream()
                        .map(
                                entry ->
                                        entry.getValue().displayName
                                                + " | "
                                                + entry.getKey().sessionId()
                                                + " | 终端 "
                                                + entry.getKey().terminalId()
                                                + " | "
                                                + entry.getValue().cwd
                                                + " | "
                                                + entry.getValue().lastSeen
                                                + (entry.getValue().conflict
                                                        ? " | 绑定冲突：选择后仅此终端可以交接"
                                                        : ""))
                        .toArray(String[]::new);
        int index = SelectionDialog.choose(project, "选择本次引用的明确目标（不会启动或切换终端）", "Codex 目标会话", labels);
        if (index < 0) return null;
        service.target = available.get(index).getKey();
        service.sessions.selectOwner(service.target);
        return service.target;
    }

    /** 将已冻结附件和可见标记一起交给明确的原生终端，不复读当前编辑器。 */
    public static void addOnUi(
            Project project, ProjectContextService service, List<Attachment> items) {
        onUi(
                project,
                () -> {
                    try {
                        if (!service.allowed()) {
                            notify(project, "联动或项目已关闭，本次快照未加入");
                            return;
                        }
                        // 输入区域已存在时即可引用，不要求原生先执行一次启动或提交回调。
                        var terminals = TerminalDraftSender.candidates(project, service);
                        if (terminals.isEmpty()) {
                            notify(project, "没有发现当前项目的 Codex 终端。请在新的 IDEA 终端输入 codex，再发送引用。");
                            return;
                        }
                        var selected = terminals.size() == 1 ? terminals.getFirst() : null;
                        if (selected == null && service.target != null)
                            selected =
                                    terminals.stream()
                                            .filter(
                                                    value ->
                                                            value.terminalId()
                                                                    .equals(
                                                                            service.target
                                                                                    .terminalId()))
                                            .findFirst()
                                            .orElse(null);
                        if (selected == null) {
                            String[] labels =
                                    terminals.stream()
                                            .map(
                                                    value ->
                                                            "终端 "
                                                                    + value.terminalId()
                                                                    + " | "
                                                                    + (value.cwd() == null
                                                                            ? "目录尚未报告"
                                                                            : value.cwd()))
                                            .toArray(String[]::new);
                            int index =
                                    SelectionDialog.choose(
                                            project, "选择本次引用要进入的 Codex 输入区域", "Codex 目标终端", labels);
                            if (index < 0) return;
                            selected = terminals.get(index);
                        }
                        var chosen = selected;
                        // 文件规范化和敏感规则复查在后台执行，再回到界面线程操作已有终端。
                        service.execute(
                                () -> {
                                    try {
                                        List<String> markers = new ArrayList<>();
                                        String cwd = chosen.cwd();
                                        Path directory =
                                                cwd == null || cwd.isBlank()
                                                        ? null
                                                        : service.pathPolicy().validateCwd(cwd);
                                        for (Attachment item : items)
                                            markers.add(
                                                    DraftReference.format(
                                                            service.resolveAttachment(item),
                                                            directory,
                                                            item));
                                        onUi(
                                                project,
                                                () -> {
                                                    try {
                                                        if (!service.allowed()) {
                                                            notify(project, "联动已关闭，引用未发送");
                                                            return;
                                                        }
                                                        var view =
                                                                TerminalDraftSender.find(
                                                                        project,
                                                                        service,
                                                                        chosen.terminalId());
                                                        if (view == null) {
                                                            notify(
                                                                    project,
                                                                    "没有找到此会话正在运行的 Codex 终端。请在新 IDEA 终端运行 codex，再发送引用；没有保留待发正文。");
                                                            return;
                                                        }
                                                        // 粘贴与登记在同一会话锁内完成，接收失败不留隐形的待发引用。
                                                        boolean accepted =
                                                                service.sendTerminalDraftReferences(
                                                                        chosen.terminalId(),
                                                                        items,
                                                                        markers,
                                                                        () ->
                                                                                TerminalDraftSender
                                                                                        .paste(
                                                                                                view,
                                                                                                String
                                                                                                        .join(
                                                                                                                " ",
                                                                                                                markers)));
                                                        if (!accepted) {
                                                            notify(
                                                                    project,
                                                                    "终端没有接收引用。请确认 Codex 已打开输入区域，引用未加入待发内容。");
                                                            return;
                                                        }
                                                        TerminalDraftSender.focus(project, view);
                                                    } catch (IllegalArgumentException
                                                            | IllegalStateException ex) {
                                                        notify(project, ex.getMessage());
                                                    }
                                                });
                                    } catch (IOException ex) {
                                        onUi(
                                                project,
                                                () -> notify(project, "引用未发送：文件路径已不可访问或不符合敏感文件规则"));
                                    }
                                });
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        notify(project, ex.getMessage());
                    }
                });
    }

    /** 索引就绪时按文件名搜索；索引期间仅搜索已经打开的文件。 */
    private static void find(Project project, ProjectContextService service) {
        String query = Messages.showInputDialog(project, "输入文件名或路径片段", "搜索文件并添加到 Codex", null);
        if (query == null || query.isBlank()) return;
        boolean indexing = DumbService.isDumb(project);
        service.execute(
                () -> {
                    List<Path> found = new ArrayList<>();
                    try {
                        Collection<VirtualFile> candidates =
                                service.read(
                                        () -> {
                                            if (indexing || DumbService.isDumb(project))
                                                return Arrays.asList(
                                                        FileEditorManager.getInstance(project)
                                                                .getOpenFiles());
                                            List<VirtualFile> files = new ArrayList<>();
                                            ProjectFileIndex.getInstance(project)
                                                    .iterateContent(
                                                            file -> {
                                                                if (!file.isDirectory()
                                                                        && file.getPath()
                                                                                .toLowerCase(
                                                                                        Locale.ROOT)
                                                                                .contains(
                                                                                        query
                                                                                                .toLowerCase(
                                                                                                        Locale
                                                                                                                .ROOT)))
                                                                    files.add(file);
                                                                return files.size() < 1000;
                                                            });
                                            return files;
                                        });
                        for (VirtualFile file : candidates)
                            if (file.isInLocalFileSystem()
                                    && !file.isDirectory()
                                    && !file.getFileType().isBinary()) {
                                try {
                                    var identity =
                                            service.pathPolicy().identify(Path.of(file.getPath()));
                                    if ((identity.getKey() + "/" + identity.getValue())
                                            .toLowerCase(Locale.ROOT)
                                            .contains(query.toLowerCase(Locale.ROOT)))
                                        found.add(Path.of(file.getPath()));
                                } catch (IOException ignored) {
                                    /* 文件搜索主动排除策略拒绝的文件，不把它们显示为可发送。 */
                                }
                            }
                        List<String> labels = new ArrayList<>();
                        for (Path path : found) {
                            var identity = service.pathPolicy().identify(path);
                            labels.add(identity.getKey() + "/" + identity.getValue());
                        }
                        onUi(
                                project,
                                () -> {
                                    if (found.isEmpty()) {
                                        notify(
                                                project,
                                                indexing ? "项目索引尚未就绪，已打开文件中没有匹配项" : "没有匹配的获准文件");
                                        return;
                                    }
                                    String[] options = labels.toArray(String[]::new);
                                    int index =
                                            SelectionDialog.choose(
                                                    project,
                                                    indexing ? "项目索引尚未就绪：仅列已打开文件" : "选择文件，加入路径引用",
                                                    "Codex 文件搜索",
                                                    options);
                                    if (index >= 0) {
                                        service.execute(
                                                () -> {
                                                    try {
                                                        var identity =
                                                                service.pathPolicy()
                                                                        .identify(found.get(index));
                                                        addOnUi(
                                                                project,
                                                                service,
                                                                List.of(
                                                                        Attachment.path(
                                                                                identity.getKey(),
                                                                                identity
                                                                                        .getValue())));
                                                    } catch (IOException ex) {
                                                        onUi(
                                                                project,
                                                                () ->
                                                                        notify(
                                                                                project,
                                                                                "选中的文件已不可访问，请重新搜索"));
                                                    }
                                                });
                                    }
                                });
                    } catch (IOException | IllegalStateException ex) {
                        onUi(project, () -> notify(project, "搜索未完成：IDE 忙碌或项目根不可访问"));
                    }
                });
    }

    /** 展示实际观察状态，配置存在不代表 Hook 已信任或运行。 */
    private static void diagnostics(Project project, ProjectContextService service) {
        var settings = CompanionSettings.get().getState();
        SessionStore.Batch newest = null;
        for (var session : service.sessions.list().values())
            synchronized (session) {
                for (var batch : session.turns.values())
                    if (newest == null || batch.createdAt.isAfter(newest.createdAt)) newest = batch;
            }
        // 将真实的本地交接状态解释给用户，不把配置存在当成成功注入。
        String lastDelivery =
                newest == null
                        ? "未观察到提交批次"
                        : QueueDialog.stateLabel(newest.state)
                                + " | "
                                + Json.bytes(newest.output)
                                + " 字节响应";
        String actualBuild =
                com.intellij.openapi.application.ApplicationInfo.getInstance()
                        .getBuild()
                        .asString();
        String body =
                "插件已安装\n联动服务启用："
                        + service.allowed()
                        + "\n项目信任与命令审批：由原生 Codex 处理\n新终端连接信息已注入："
                        + service.terminalObserved
                        + "\nIDEA 终端运行方式："
                        + service.terminalLaunchMode
                        + "\n安装器观察的 Codex 命令行版本："
                        + settings.observedCliVersion
                        + "\n当前 IDEA 构建："
                        + actualBuild
                        + "\nHook 信任：由用户在 CLI /hooks 审核，插件不读取信任缓存\nHook 回调已收到："
                        + service.hookObserved
                        + "\n最近事件："
                        + service.lastEvent
                        + "\n模型上下文协议连接已收到："
                        + service.mcpObserved
                        + "\n终端 CODEX_HOME："
                        + service.shellCodexHome
                        + "\n安装配置目录："
                        + settings.codexHome
                        + "\n当前目标："
                        + (service.target == null ? "尚未指定" : service.target.sessionId())
                        + "\n最近一次交接："
                        + lastDelivery
                        + "\n最近错误："
                        + service.lastError;
        JTextArea text = new JTextArea(body, 20, 80);
        text.setEditable(false);
        DialogWrapper dialog =
                new DialogWrapper(project) {
                    {
                        setTitle("Codex 联动诊断");
                        init();
                    }

                    @Override
                    protected JComponent createCenterPanel() {
                        return new JScrollPane(text);
                    }

                    @Override
                    protected Action[] createActions() {
                        Action copy =
                                new AbstractAction("复制脱敏诊断") {
                                    @Override
                                    public void actionPerformed(java.awt.event.ActionEvent event) {
                                        String safe =
                                                "IDEA="
                                                        + actualBuild
                                                        + "; CLI="
                                                        + settings.observedCliVersion
                                                        + "; integrationActive="
                                                        + service.allowed()
                                                        + "; terminalObserved="
                                                        + service.terminalObserved
                                                        + "; hookObserved="
                                                        + service.hookObserved
                                                        + "; mcpObserved="
                                                        + service.mcpObserved
                                                        + "; lastEvent="
                                                        + service.lastEvent
                                                        + "; error="
                                                        + service.lastError;
                                        com.intellij.openapi.ide.CopyPasteManager.getInstance()
                                                .setContents(
                                                        new java.awt.datatransfer.StringSelection(
                                                                safe));
                                    }
                                };
                        return new Action[] {copy, getOKAction()};
                    }
                };
        dialog.show();
    }

    /**
     * 通过非模态通知报告引用结果和可执行的错误原因。
     *
     * @param message 面向用户的动作结果，不包含凭证
     */
    public static void notify(Project project, String message) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("ICB")
                .createNotification(
                        message == null ? "操作未完成" : message, NotificationType.INFORMATION)
                .notify(project);
    }

    /** 项目关闭后不再执行界面回调。 */
    public static void onUi(Project project, Runnable task) {
        ApplicationManager.getApplication().invokeLater(task, project.getDisposed());
    }
}
