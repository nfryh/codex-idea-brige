// 仅用于引用审阅、显式刷新和重新排队，不承担聊天职责。
package dev.local.icb.ui;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.LocalFileSystem;

import dev.local.icb.contract.*;
import dev.local.icb.core.*;

import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.List;

import javax.swing.*;

/** 显示未绑定、待发和最近批次，交接不等同于模型读取。 */
public final class QueueDialog extends DialogWrapper {
    private final Project project;
    private final ProjectContextService service;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JTextArea preview = new JTextArea(18, 70);
    private final Map<String, String> freshness = new HashMap<>();
    private long generation;

    /**
     * 引用弹窗的一行。
     *
     * @param item 冻结的引用内容
     * @param source LOCAL_UNBOUND 为未绑定，QUEUED 为待发，其他值为历史批次交接状态
     */
    private record Row(Attachment item, String source, SessionStore.Key key) {
        /** 将交接状态和引用模式转为可理解的中文标签。 */
        @Override
        public String toString() {
            return stateLabel(source)
                    + " | "
                    + item.rootId()
                    + "/"
                    + item.relativePath()
                    + " | "
                    + kindLabel(item.kind())
                    + " | "
                    + (item.content() == null ? 0 : Json.bytes(item.content()))
                    + " 字节正文";
        }
    }

    public QueueDialog(Project project, ProjectContextService service) {
        super(project);
        this.project = project;
        this.service = service;
        setTitle("Codex 引用（交接完成不代表模型已读取）");
        init();
        reload();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        preview.setEditable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(
                new DefaultListCellRenderer() {
                    /**
                     * 渲染冻结引用及文件版本检查结果。
                     *
                     * @param index 引用在弹窗列表中的位置，从 0 开始
                     * @param selected true 表示当前选中该引用，false 表示未选中
                     * @param focus true 表示该引用具有键盘焦点，false 表示没有焦点
                     */
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> values,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        Row row = (Row) value;
                        String stale =
                                row.item.capturedAt().isBefore(Instant.now().minusSeconds(1800))
                                        ? " | 已过期：需刷新或明确重新加入"
                                        : "";
                        return super.getListCellRendererComponent(
                                values,
                                value + stale + freshness.getOrDefault(row.item.attachmentId(), ""),
                                index,
                                selected,
                                focus);
                    }
                });
        list.addListSelectionListener(
                event -> {
                    Row row = list.getSelectedValue();
                    if (row == null) {
                        preview.setText("");
                        return;
                    }
                    Attachment item = row.item;
                    preview.setText(
                            "目标："
                                    + (row.key == null ? "尚未绑定，不会自动发给新会话" : row.key.sessionId())
                                    + "\n状态："
                                    + stateLabel(row.source)
                                    + "\n捕获时间："
                                    + item.capturedAt()
                                    + "\n未保存："
                                    + (item.unsaved() ? "是" : "否")
                                    + "\n范围："
                                    + (item.startLineOneBased() == null
                                            ? "仅路径，不适用文本范围"
                                            : item.startLineOneBased()
                                                    + "—"
                                                    + item.endLineInclusiveOneBased())
                                    + "\n文件检查："
                                    + freshness.getOrDefault(item.attachmentId(), "检查中")
                                    + "\n旧快照不会静默替换。\n\n"
                                    + (item.content() == null ? "仅路径，不发送正文" : item.content()));
                });
        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(preview));
        split.setResizeWeight(0.4);
        panel.add(split);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton remove = new JButton("移除下次引用"),
                refresh = new JButton("显式刷新所选引用"),
                requeue = new JButton("加入当前目标下一条"),
                target = new JButton("选择目标会话"),
                name = new JButton("目标会话命名"),
                output = new JButton("查看该批次回调输出");
        remove.addActionListener(
                event -> {
                    Row row = list.getSelectedValue();
                    if (row == null) return;
                    if (row.source.equals("LOCAL_UNBOUND")) {
                        synchronized (service.unassigned) {
                            service.unassigned.remove(row.item);
                        }
                    } else if (row.source.equals("TERMINAL_DRAFT"))
                        service.removeTerminalDraft(row.key.terminalId(), row.item.attachmentId());
                    else if (row.source.equals("QUEUED"))
                        service.sessions.remove(row.key, row.item.attachmentId());
                    else {
                        CompanionAction.notify(project, "已冻结或交付的历史内容无法撤回，只能移除尚未发送的引用");
                        return;
                    }
                    reload();
                });
        target.addActionListener(
                event -> {
                    CompanionAction.choose(project, service);
                    reload();
                });
        name.addActionListener(
                event -> {
                    if (service.target == null) {
                        CompanionAction.choose(project, service);
                        if (service.target == null) return;
                    }
                    String label =
                            Messages.showInputDialog(
                                    project, "名称仅保留在本机内存，不改变原生 Codex 会话", "目标会话命名", null);
                    if (label != null) {
                        if (label.length() > 80) {
                            CompanionAction.notify(project, "显示名称最多 80 个字符");
                            return;
                        }
                        service.sessions.require(service.target).displayName = label;
                        reload();
                    }
                });
        requeue.addActionListener(
                event -> {
                    Row row = list.getSelectedValue();
                    if (row == null) return;
                    if (row.source.equals("TERMINAL_DRAFT")) {
                        CompanionAction.notify(project, "该引用已经在目标终端草稿中。需要更换目标时，先删除草稿引用，再用快捷键发送。");
                        return;
                    }
                    SessionStore.Key selected = service.target;
                    if (selected == null) selected = CompanionAction.choose(project, service);
                    if (selected == null) return;
                    try {
                        // 用户明确重发旧内容时生成新排队时间，不自动重试旧任务。
                        Attachment old = row.item;
                        Attachment next =
                                new Attachment(
                                        UUID.randomUUID().toString(),
                                        old.rootId(),
                                        old.relativePath(),
                                        old.kind(),
                                        old.startOffset(),
                                        old.endOffsetExclusive(),
                                        old.startLineOneBased(),
                                        old.endLineInclusiveOneBased(),
                                        old.documentModificationStamp(),
                                        old.contentSha256(),
                                        old.unsaved(),
                                        Instant.now(),
                                        old.content());
                        if (row.source.equals("QUEUED") && selected.equals(row.key))
                            service.sessions.replace(selected, old.attachmentId(), next);
                        else service.sessions.add(selected, List.of(next));
                        if (row.source.equals("LOCAL_UNBOUND"))
                            synchronized (service.unassigned) {
                                service.unassigned.remove(row.item);
                            }
                        if (row.source.equals("QUEUED") && !selected.equals(row.key))
                            service.sessions.remove(row.key, row.item.attachmentId());
                        reload();
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        CompanionAction.notify(project, ex.getMessage());
                    }
                });
        refresh.addActionListener(
                event -> {
                    Row row = list.getSelectedValue();
                    if (row != null) refresh(row);
                });
        output.addActionListener(
                event -> {
                    Row row = list.getSelectedValue();
                    if (row == null || row.key == null) {
                        CompanionAction.notify(project, "请选择已经冻结的提交批次引用");
                        return;
                    }
                    if (row.source.equals("TERMINAL_DRAFT")) {
                        CompanionAction.notify(project, "该引用仍在终端草稿中，尚没有提交回调输出");
                        return;
                    }
                    var session = service.sessions.require(row.key);
                    String frozen;
                    // 只在短锁内复制不可变输出，显示弹窗前释放锁，不阻塞终端回调。
                    synchronized (session) {
                        frozen =
                                session.turns.values().stream()
                                        .filter(batch -> batch.attachments.contains(row.item))
                                        .map(batch -> batch.output)
                                        .findFirst()
                                        .orElse(null);
                    }
                    if (frozen == null) {
                        CompanionAction.notify(project, "该引用尚未冻结，或对应历史批次已到保留期限");
                        return;
                    }
                    JTextArea body = new JTextArea(frozen, 22, 90);
                    body.setEditable(false);
                    body.setLineWrap(true);
                    DialogWrapper details =
                            new DialogWrapper(project) {
                                {
                                    setTitle("本轮冻结的回调输出（不表示模型消费回执）");
                                    init();
                                }

                                @Override
                                protected JComponent createCenterPanel() {
                                    return new JScrollPane(body);
                                }
                            };
                    details.show();
                });
        buttons.add(remove);
        buttons.add(refresh);
        buttons.add(requeue);
        buttons.add(target);
        buttons.add(name);
        buttons.add(output);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    /** 刷新真实文件和显式选区；历史记录保持不变，结果进入下一条引用。 */
    private void refresh(Row row) {
        service.execute(
                () -> {
                    try {
                        var path =
                                service.pathPolicy()
                                        .resolve(row.item.rootId(), row.item.relativePath());
                        Attachment next;
                        if (row.item.kind().equals("PATH_ONLY"))
                            next = Attachment.path(row.item.rootId(), row.item.relativePath());
                        else {
                            next =
                                    service.read(
                                            () -> {
                                                var file =
                                                        LocalFileSystem.getInstance()
                                                                .findFileByNioFile(path);
                                                var document =
                                                        file == null
                                                                ? null
                                                                : FileDocumentManager.getInstance()
                                                                        .getDocument(file);
                                                if (document == null
                                                        || document.getTextLength() > 131072)
                                                    throw new IllegalArgumentException(
                                                            "ICB_CONTEXT_TOO_LARGE");
                                                return Attachment.snapshot(
                                                        row.item.rootId(),
                                                        row.item.relativePath(),
                                                        row.item.kind(),
                                                        document.getText(),
                                                        row.item.kind().equals("FILE_SNAPSHOT")
                                                                ? 0
                                                                : row.item.startOffset(),
                                                        row.item.kind().equals("FILE_SNAPSHOT")
                                                                ? document.getTextLength()
                                                                : row.item.endOffsetExclusive(),
                                                        document.getModificationStamp(),
                                                        FileDocumentManager.getInstance()
                                                                .isDocumentUnsaved(document));
                                            });
                        }
                        CompanionAction.onUi(
                                project,
                                () -> {
                                    if (row.source.equals("LOCAL_UNBOUND")) {
                                        synchronized (service.unassigned) {
                                            int index = service.unassigned.indexOf(row.item);
                                            if (index >= 0) service.unassigned.set(index, next);
                                        }
                                    } else if (row.source.equals("TERMINAL_DRAFT")) {
                                        service.replaceTerminalDraft(
                                                row.key.terminalId(),
                                                row.item.attachmentId(),
                                                next);
                                    } else if (row.source.equals("QUEUED")) {
                                        service.sessions.replace(
                                                row.key, row.item.attachmentId(), next);
                                    } else CompanionAction.addOnUi(project, service, List.of(next));
                                    reload();
                                });
                    } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
                        CompanionAction.onUi(
                                project,
                                () ->
                                        CompanionAction.notify(
                                                project, "刷新失败：文件已过期或范围不可访问，请重新选中并添加引用"));
                    }
                });
    }

    /** 刷新弹窗列表，不删除交接不确定或中断的历史内容。 */
    private void reload() {
        long version = ++generation;
        model.clear();
        freshness.clear();
        synchronized (service.unassigned) {
            service.unassigned.forEach(
                    item -> model.addElement(new Row(item, "LOCAL_UNBOUND", null)));
        }
        service.terminalDrafts()
                .forEach(
                        (terminal, items) ->
                                items.forEach(
                                        item ->
                                                model.addElement(
                                                        new Row(
                                                                item,
                                                                "TERMINAL_DRAFT",
                                                                new SessionStore.Key(
                                                                        terminal, "终端草稿")))));
        service.sessions
                .list()
                .forEach(
                        (key, session) -> {
                            synchronized (session) {
                                session.queued.forEach(
                                        item -> model.addElement(new Row(item, "QUEUED", key)));
                                session.turns
                                        .values()
                                        .forEach(
                                                batch ->
                                                        batch.attachments.forEach(
                                                                item ->
                                                                        model.addElement(
                                                                                new Row(
                                                                                        item,
                                                                                        batch.state,
                                                                                        key))));
                            }
                        });
        setTitle("Codex 引用 — 目标：" + (service.target == null ? "尚未指定" : service.target.sessionId()));
        List<Row> rows = Collections.list(model.elements());
        service.execute(
                () -> {
                    Map<String, String> states = new HashMap<>();
                    for (Row row : rows) {
                        try {
                            var path = service.resolveAttachment(row.item);
                            Long stamp =
                                    service.read(
                                            () -> {
                                                var file =
                                                        LocalFileSystem.getInstance()
                                                                .findFileByNioFile(path);
                                                var document =
                                                        file == null
                                                                ? null
                                                                : FileDocumentManager.getInstance()
                                                                        .getCachedDocument(file);
                                                return document == null
                                                        ? null
                                                        : document.getModificationStamp();
                                            });
                            states.put(
                                    row.item.attachmentId(),
                                    stamp != null
                                                    && row.item.documentModificationStamp() != null
                                                    && !stamp.equals(
                                                            row.item.documentModificationStamp())
                                            ? " | 与当前文档不同"
                                            : " | 捕获路径仍可访问");
                        } catch (IOException ex) {
                            states.put(row.item.attachmentId(), " | stalePath：路径已变更或不可访问");
                        } catch (IllegalStateException ex) {
                            states.put(row.item.attachmentId(), " | IDE 忙碌，尚未完成版本检查");
                        }
                    }
                    CompanionAction.onUi(
                            project,
                            () -> {
                                if (generation == version
                                        && !com.intellij.openapi.util.Disposer.isDisposed(
                                                getDisposable())) {
                                    freshness.putAll(states);
                                    list.repaint();
                                }
                            });
                });
    }

    /**
     * 展示本地交接的真实含义，不暗示模型逐项读取。
     *
     * @param state LOCAL_UNBOUND 为未绑定，TERMINAL_DRAFT 为已在终端草稿中，QUEUED 为待发，RESERVED
     *     为冻结待确认，HANDED_TO_CLI 为本地交接完成，TURN_FINISHED 为本轮结束，INTERRUPTED 为中断，DELIVERY_UNCERTAIN
     *     为交接未确认
     */
    public static String stateLabel(String state) {
        return switch (state) {
            case "LOCAL_UNBOUND" -> "未绑定，仅保留在本地";
            case "TERMINAL_DRAFT" -> "已在目标终端草稿中，提交时关联会话";
            case "QUEUED" -> "待发送";
            case "RESERVED" -> "已冻结，等待交接确认";
            case "HANDED_TO_CLI" -> "已交接给命令行";
            case "TURN_FINISHED" -> "本轮任务已结束";
            case "INTERRUPTED" -> "本轮任务已中断";
            case "DELIVERY_UNCERTAIN" -> "交接结果不确定";
            default -> throw new IllegalArgumentException("STATE_INVALID");
        };
    }

    /**
     * 展示用户选择的引用模式。
     *
     * @param kind PATH_ONLY 为路径引用，SELECTION_SNAPSHOT 为选区快照，FILE_SNAPSHOT 为全文快照
     */
    private static String kindLabel(String kind) {
        return switch (kind) {
            case "PATH_ONLY" -> "仅路径";
            case "SELECTION_SNAPSHOT" -> "选区快照";
            case "FILE_SNAPSHOT" -> "全文快照";
            default -> throw new IllegalArgumentException("KIND_INVALID");
        };
    }
}
