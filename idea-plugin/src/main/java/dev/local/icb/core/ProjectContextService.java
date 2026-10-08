// 项目范围的授权、上下文缓存、会话和只读工具生命周期。
package dev.local.icb.core;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.*;
import com.intellij.util.concurrency.AppExecutorUtil;

import dev.local.icb.contract.*;
import dev.local.icb.diagnostics262.DiagnosticsProvider262;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 仅服务当前项目，所有快照及队列在关闭后清空。 */
@Service(Service.Level.PROJECT)
public final class ProjectContextService implements Disposable {
    /** 当前打开项目实例的不可复用标识。 */
    public final String projectId = UUID.randomUUID().toString();

    /** 项目内会话和队列，不共享到其他窗口。 */
    public final SessionStore sessions = new SessionStore(projectId);

    /** 用户明确选择的目标；多个会话时不会猜测。 */
    public volatile SessionStore.Key target;

    /** 无会话时暂存的本地快照，须用户再次明确加入目标。 */
    public final List<Attachment> unassigned = new ArrayList<>();

    /** 终端环境是否被实际观察到。 */
    public volatile boolean terminalObserved;

    /** 最近一次新终端使用的原生运行方式，不代表启动回调已经执行。 */
    public volatile String terminalLaunchMode = "未准备新终端";

    /** 是否真正收到过 Hook 回调。 */
    public volatile boolean hookObserved;

    /** 已经实际收到的主会话回调名称，不保存事件正文、会话内容或凭证。 */
    public final Set<String> receivedHookEvents = ConcurrentHashMap.newKeySet();

    /** 是否真正建立过模型上下文协议连接。 */
    public volatile boolean mcpObserved;

    /** 终端实际继承的配置目录，用于诊断目录不一致。 */
    public volatile String shellCodexHome = "未观察到";

    /** 最近的脱敏错误码，不包含正文或凭证。 */
    public volatile String lastError = "无";

    /** 最近收到的回调类型。 */
    public volatile String lastEvent = "未观察到";

    private final Project project;
    private final ScheduledExecutorService background =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<JsonObject> active = new AtomicReference<>();
    private volatile PathPolicy paths = new PathPolicy(Map.of());
    private volatile Map<String, Path> roots = Map.of();
    private volatile WeakReference<Editor> lastEditor = new WeakReference<>(null);
    private volatile Instant captured = Instant.EPOCH;
    private volatile Instant changed = Instant.EPOCH;
    private ScheduledFuture<?> refresh;
    private boolean listenersStarted;
    private volatile boolean disposed;
    private final Set<String> sensitiveReferences = ConcurrentHashMap.newKeySet();

    /** 显式草稿标记与冻结快照的对应关系，删除标记后不继续发送该快照。 */
    private final ConcurrentMap<SessionStore.Key, Map<String, String>> draftReferences =
            new ConcurrentHashMap<>();

    /** 已明确发送给终端、但原生会话尚未执行启动回调的引用，最多 16 个终端。 */
    private final Map<String, TerminalDraft> terminalDrafts = new HashMap<>();

    /**
     * 只属于一个已认证终端的冻结引用；不和未绑定引用列表混用。
     *
     * @param items 用户发送时冻结的引用列表
     * @param markers 每条引用与终端可见文件标记的对应关系
     */
    private record TerminalDraft(List<Attachment> items, Map<String, String> markers) {}

    /** 本轮磁盘和编辑器基线及只读比较的拥有者。 */
    public final DiffReviewService diffs;

    public ProjectContextService(Project project) {
        this.project = project;
        diffs = new DiffReviewService(project, this);
    }

    /** 2026-09-30：仅检查服务生命周期和全局开关，不重复询问 Codex 已处理的项目授权。 */
    public boolean allowed() {
        return !disposed
                && !project.isDisposed()
                && project.getBasePath() != null
                && CompanionSettings.get().getState().integrationEnabled;
    }

    /** 开启监听和当前项目内容根加载，保持文件处理在后台。 */
    public synchronized void start() {
        if (listenersStarted) return;
        listenersStarted = true;
        background.execute(this::reloadRoots);
        // 终端实时提示使用本项目缓存，界面创建和销毁都在事件调度线程完成。
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> dev.local.icb.terminal262.TerminalContextBar.start(project, this),
                        project.getDisposed());
        var events = EditorFactory.getInstance().getEventMulticaster();
        events.addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void documentChanged(DocumentEvent event) {
                        scheduleRefresh();
                    }
                },
                this);
        events.addSelectionListener(
                new SelectionListener() {
                    @Override
                    public void selectionChanged(SelectionEvent event) {
                        scheduleRefresh();
                    }
                },
                this);
        events.addCaretListener(
                new CaretListener() {
                    @Override
                    public void caretPositionChanged(CaretEvent event) {
                        scheduleRefresh();
                    }
                },
                this);
        project.getMessageBus()
                .connect(this)
                .subscribe(
                        FileEditorManagerListener.FILE_EDITOR_MANAGER,
                        new FileEditorManagerListener() {
                            @Override
                            public void selectionChanged(FileEditorManagerEvent event) {
                                scheduleRefresh();
                            }
                        });
        project.getMessageBus()
                .connect(this)
                .subscribe(
                        com.intellij.openapi.roots.ModuleRootListener.TOPIC,
                        new com.intellij.openapi.roots.ModuleRootListener() {
                            @Override
                            public void rootsChanged(
                                    com.intellij.openapi.roots.ModuleRootEvent event) {
                                // 模块范围变化后重新加载真实路径，并重新采集编辑器，不沿用旧根缓存。
                                active.set(null);
                                execute(ProjectContextService.this::reloadRoots);
                                scheduleRefresh();
                            }
                        });
        background.scheduleAtFixedRate(
                () -> {
                    sessions.cleanup();
                    draftReferences.keySet().removeIf(key -> !sessions.list().containsKey(key));
                },
                5,
                5,
                TimeUnit.SECONDS);
        scheduleRefresh();
    }

    /** 2026-10-06：保留项目根和项目外模块根，规范化真实路径，避免排除顶层文件与绕开敏感目录。 */
    public void reloadRoots() {
        if (!allowed()) {
            roots = Map.of();
            paths = new PathPolicy(Map.of());
            active.set(null);
            return;
        }
        try {
            // 平台根列表只在短读操作取得；真实路径处理退出平台锁之后执行。
            VirtualFile[] files =
                    read(() -> ProjectRootManager.getInstance(project).getContentRoots());
            // 项目目录始终属于本项目范围；模块根不能把顶层构建脚本和终端工作目录排除。
            Path projectRoot = Path.of(project.getBasePath()).toRealPath();
            Map<String, Path> next = new LinkedHashMap<>();
            next.put("root-" + Json.sha(projectRoot.toString()).substring(0, 12), projectRoot);
            for (VirtualFile file : files)
                if (file.isInLocalFileSystem()) {
                    Path root = Path.of(file.getPath()).toRealPath();
                    // 项目内模块由同一个根约束，防止换用更深的根绕开敏感目录检查。
                    if (root.startsWith(projectRoot)) continue;
                    next.put("root-" + Json.sha(root.toString()).substring(0, 12), root);
                }
            roots = Map.copyOf(next);
            paths = new PathPolicy(next);
        } catch (IOException ex) {
            lastError = "ICB_ROOT_UNAVAILABLE";
        }
    }

    /** 让短时间内的文档、选区和文件切换合并为一次缓存采集。 */
    private synchronized void scheduleRefresh() {
        if (disposed) return;
        changed = Instant.now();
        if (refresh != null) refresh.cancel(false);
        refresh =
                background.schedule(
                        () ->
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (!allowed()) {
                                                        active.set(null);
                                                        return;
                                                    }
                                                    Editor editor =
                                                            FileEditorManager.getInstance(project)
                                                                    .getSelectedTextEditor();
                                                    if (editor == null
                                                            || (editor.getEditorKind()
                                                                            != EditorKind
                                                                                    .MAIN_EDITOR
                                                                    && editor.getEditorKind()
                                                                            != EditorKind
                                                                                    .UNTYPED)) {
                                                        active.set(null);
                                                        lastEditor.clear();
                                                        return;
                                                    }
                                                    lastEditor = new WeakReference<>(editor);
                                                    try {
                                                        // 先复制编辑器轻量数据，再后台校验路径和摘要，不将 Editor 交给 JSON
                                                        // 编码器。
                                                        RawCapture raw = capture(editor, "AUTO");
                                                        background.execute(() -> updateActive(raw));
                                                    } catch (IllegalArgumentException ex) {
                                                        active.set(null);
                                                        lastError = "ICB_CONTEXT_UNAVAILABLE";
                                                    }
                                                    // 只读取编辑器并更新本地缓存，允许在模态界面打开时继续捕获，不修改平台文档。
                                                },
                                                ModalityState.any(),
                                                project.getDisposed()),
                        100,
                        TimeUnit.MILLISECONDS);
    }

    /**
     * 界面线程冻结的真实片段。
     *
     * @param start 文档 UTF-16 起点
     * @param end 文档 UTF-16 不含尾端的终点
     * @param first 从 1 开始的首行
     * @param last 从 1 开始的最后选中行
     * @param content 精确片段，不包含未选择的中间文本
     */
    public record Segment(int start, int end, int first, int last, String content) {}

    /**
     * 平台对象退出界面线程后的不可变捕获结果。
     *
     * @param path 捕获时的本地文件路径
     * @param kind PATH_ONLY 为路径，SELECTION_SNAPSHOT 为选区，FILE_SNAPSHOT 为全文，AUTO 为自动缓存
     * @param stamp 捕获时文档版本
     * @param unsaved true 表示有未保存修改，false 表示已保存
     * @param cursor 当前光标 UTF-16 偏移
     * @param language 文件类型显示名称
     * @param at 本地捕获时刻
     * @param fullDocument 独立比较需要的完整文档快照，超大或二进制文件时为 null；不作为自动上下文发送
     * @param contentOmitted true 表示自动正文超过采集上限，false 表示未因采集上限省略正文
     * @param selectionLineCount 实际选区覆盖的不重复行数，零表示没有选区，不把附近代码算成选区
     */
    public record RawCapture(
            String path,
            String kind,
            long stamp,
            boolean unsaved,
            int cursor,
            String language,
            Instant at,
            List<Segment> segments,
            String fullDocument,
            boolean contentOmitted,
            int selectionLineCount) {}

    /**
     * 立即读取操作发生时的编辑器，不等待会话选择弹窗结束。
     *
     * @param kind SELECTION_SNAPSHOT 为真实选区，FILE_SNAPSHOT 为全文，PATH_ONLY 为路径，AUTO 为有界自动选区或元信息
     */
    public RawCapture capture(Editor editor, String kind) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        // 平台正常文件编辑器也可能使用 UNTYPED；本地文档映射与路径校验继续排除终端、差异和根外文件。
        if (file == null
                || !file.isInLocalFileSystem()
                || (!kind.equals("PATH_ONLY") && file.getFileType().isBinary())
                || (editor.getEditorKind() != EditorKind.MAIN_EDITOR
                        && editor.getEditorKind() != EditorKind.UNTYPED))
            throw new IllegalArgumentException("ICB_CONTEXT_UNAVAILABLE");
        Document document = editor.getDocument();
        long before = document.getModificationStamp();
        int selectionLineCount = 0, coveredThrough = -1;
        var actualSelections =
                editor.getCaretModel().getAllCarets().stream()
                        .filter(Caret::hasSelection)
                        .sorted(Comparator.comparingInt(Caret::getSelectionStart))
                        .toList();
        for (Caret caret : actualSelections) {
            int first = document.getLineNumber(caret.getSelectionStart());
            int last = document.getLineNumber(caret.getSelectionEnd() - 1);
            selectionLineCount += Math.max(0, last - Math.max(first, coveredThrough + 1) + 1);
            coveredThrough = Math.max(coveredThrough, last);
        }
        List<Segment> segments = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        boolean contentOmitted = false;
        if (kind.equals("FILE_SNAPSHOT")) ranges.add(new int[] {0, document.getTextLength()});
        else if (!kind.equals("PATH_ONLY")) {
            for (Caret caret : editor.getCaretModel().getAllCarets())
                if (caret.hasSelection())
                    ranges.add(new int[] {caret.getSelectionStart(), caret.getSelectionEnd()});
            if (kind.equals("AUTO")
                    && ranges.isEmpty()
                    && CompanionSettings.get().getState().includeNearbyCode) {
                int line = document.getLineNumber(editor.getCaretModel().getOffset());
                ranges.add(
                        new int[] {
                            document.getLineStartOffset(Math.max(0, line - 3)),
                            document.getLineEndOffset(
                                    Math.min(document.getLineCount() - 1, line + 3))
                        });
            }
        }
        ranges.sort(Comparator.comparingInt(range -> range[0]));
        if (ranges.size() > 16) {
            if (!kind.equals("AUTO")) throw new IllegalArgumentException("ICB_CONTEXT_TOO_LARGE");
            ranges.clear();
            contentOmitted = true;
        }
        for (int[] range : ranges) {
            int start = range[0], end = range[1];
            if (end - start > (kind.equals("AUTO") ? 8192 : 131072)) {
                if (!kind.equals("AUTO"))
                    throw new IllegalArgumentException("ICB_CONTEXT_TOO_LARGE");
                contentOmitted = true;
                continue;
            }
            CharSequence chars = document.getCharsSequence();
            for (int endpoint : range)
                if (endpoint > 0
                        && endpoint < chars.length()
                        && Character.isHighSurrogate(chars.charAt(endpoint - 1))
                        && Character.isLowSurrogate(chars.charAt(endpoint)))
                    throw new IllegalArgumentException("RANGE_INVALID");
            String content = document.getText(new com.intellij.openapi.util.TextRange(start, end));
            segments.add(
                    new Segment(
                            start,
                            end,
                            document.getLineNumber(start) + 1,
                            end > start
                                    ? document.getLineNumber(end - 1) + 1
                                    : document.getLineNumber(start) + 1,
                            content));
        }
        String fullDocument =
                !file.getFileType().isBinary() && document.getTextLength() <= 131072
                        ? document.getText()
                        : null;
        if (before != document.getModificationStamp())
            throw new IllegalArgumentException("ICB_CONTEXT_STALE");
        return new RawCapture(
                file.getPath(),
                kind,
                before,
                FileDocumentManager.getInstance().isDocumentUnsaved(document),
                editor.getCaretModel().getOffset(),
                file.getFileType().getName(),
                Instant.now(),
                List.copyOf(segments),
                fullDocument,
                contentOmitted,
                selectionLineCount);
    }

    /** 后台完成路径验证和内容摘要，保留原捕获时间与文本。 */
    public List<Attachment> attachments(RawCapture raw) throws IOException {
        return attachments(raw, false);
    }

    /**
     * 按单次明确许可捕获敏感引用，不扩展自动内容或模型工具许可。
     *
     * @param sensitiveConfirmed true 表示用户已批准这次敏感引用，false 表示使用默认拒绝策略
     */
    public List<Attachment> attachments(RawCapture raw, boolean sensitiveConfirmed)
            throws IOException {
        if (!allowed()) throw new IOException("ICB_PROJECT_DENIED");
        var identity = paths.identify(Path.of(raw.path), sensitiveConfirmed);
        List<Attachment> result;
        if (raw.kind.equals("PATH_ONLY"))
            result = List.of(Attachment.path(identity.getKey(), identity.getValue()));
        else {
            if (raw.segments.isEmpty()) throw new IOException("SELECTION_EMPTY");
            if (raw.segments.stream().anyMatch(segment -> Json.bytes(segment.content) > 131072))
                throw new IOException("ICB_CONTEXT_TOO_LARGE");
            result =
                    raw.segments.stream()
                            .map(
                                    segment ->
                                            new Attachment(
                                                    UUID.randomUUID().toString(),
                                                    identity.getKey(),
                                                    identity.getValue(),
                                                    raw.kind,
                                                    segment.start,
                                                    segment.end,
                                                    segment.first,
                                                    segment.last,
                                                    raw.stamp,
                                                    Json.sha(segment.content),
                                                    raw.unsaved,
                                                    raw.at,
                                                    segment.content))
                            .toList();
        }
        // 已完成路径检查后保存独立比较快照；正文不进入 Hook 自动上下文。
        diffs.rememberEditor(
                paths.resolve(identity.getKey(), identity.getValue(), sensitiveConfirmed), raw);
        if (sensitiveConfirmed) result.forEach(item -> sensitiveReferences.add(item.dedupeKey()));
        return result;
    }

    /** 仅复核已经获得本次明确确认的快照身份，不为模型工具提供放行入口。 */
    public Path resolveAttachment(Attachment attachment) throws IOException {
        return paths.resolve(
                attachment.rootId(),
                attachment.relativePath(),
                sensitiveReferences.contains(attachment.dedupeKey()));
    }

    /**
     * 在原生首次登记之前也能发送草稿；接收失败不登记本次内容。
     *
     * @param terminalId 用户已经确定、并经 IDEA 终端凭证与运行进程验证的目标终端
     */
    public boolean sendTerminalDraftReferences(
            String terminalId,
            List<Attachment> items,
            List<String> markers,
            BooleanSupplier paste) {
        if (!allowed() || items.isEmpty() || items.size() != markers.size())
            throw new IllegalStateException("ICB_REFERENCE_INVALID");
        var known =
                sessions.list().entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getKey().terminalId().equals(terminalId)
                                                && !entry.getValue().conflict)
                        .toList();
        if (known.isEmpty()
                && sessions.list().keySet().stream()
                        .anyMatch(key -> key.terminalId().equals(terminalId)))
            throw new IllegalStateException("ICB_SESSION_AMBIGUOUS");
        if (known.size() == 1) {
            // 已登记会话继续使用已有的并发、执行状态与失败回滚校验。
            return sendDraftReferences(known.getFirst().getKey(), items, markers, paste);
        }
        if (known.size() > 1) throw new IllegalStateException("ICB_SESSION_AMBIGUOUS");
        synchronized (terminalDrafts) {
            if (terminalDrafts.size() >= 16 && !terminalDrafts.containsKey(terminalId))
                throw new IllegalArgumentException("ICB_CONTEXT_TOO_LARGE");
            TerminalDraft old = terminalDrafts.get(terminalId);
            List<Attachment> next = new ArrayList<>(old == null ? List.of() : old.items());
            Map<String, String> mapping = new HashMap<>(old == null ? Map.of() : old.markers());
            for (int index = 0; index < items.size(); index++) {
                Attachment item = items.get(index);
                Attachment stored =
                        next.stream()
                                .filter(value -> value.dedupeKey().equals(item.dedupeKey()))
                                .findFirst()
                                .orElse(null);
                if (stored == null) {
                    next.add(item);
                    stored = item;
                }
                mapping.put(stored.attachmentId(), markers.get(index));
            }
            if (next.size() > 16
                    || next.stream()
                                    .mapToInt(
                                            item ->
                                                    item.content() == null
                                                            ? 0
                                                            : Json.bytes(item.content()))
                                    .sum()
                            > 24576)
                throw new IllegalArgumentException("选区正文总量超过 24 KiB，请缩小选区后再发送引用");
            if (!paste.getAsBoolean()) return false;
            terminalDrafts.put(
                    terminalId, new TerminalDraft(List.copyOf(next), Map.copyOf(mapping)));
            return true;
        }
    }

    /** 仅将同一个凭证终端的草稿关联到实际登记的原生会话，不使用工作目录猜目标。 */
    private void bindTerminalDraft(SessionStore.Key key) {
        synchronized (terminalDrafts) {
            var pending = terminalDrafts.get(key.terminalId());
            if (pending == null) return;
            sessions.add(key, pending.items());
            draftReferences.put(key, new ConcurrentHashMap<>(pending.markers()));
            terminalDrafts.remove(key.terminalId());
        }
    }

    /** 供用户审阅尚待首条原生提交关联的终端引用，返回不可修改的快照。 */
    public Map<String, List<Attachment>> terminalDrafts() {
        synchronized (terminalDrafts) {
            Map<String, List<Attachment>> result = new HashMap<>();
            terminalDrafts.forEach((terminal, draft) -> result.put(terminal, draft.items()));
            return Map.copyOf(result);
        }
    }

    /** 原生终端退出后丢弃尚未提交的快照，不能留给下次重新启动的会话。 */
    public void retainTerminalDrafts(Set<String> runningTerminals) {
        synchronized (terminalDrafts) {
            terminalDrafts.keySet().removeIf(terminal -> !runningTerminals.contains(terminal));
        }
    }

    /**
     * 删除用户明确取消的终端待发快照，不改动原生输入文字。
     *
     * @param terminalId 引用所在的目标终端标识
     * @param attachmentId 用户在审阅列表中选中的引用标识
     */
    public void removeTerminalDraft(String terminalId, String attachmentId) {
        synchronized (terminalDrafts) {
            var old = terminalDrafts.get(terminalId);
            if (old == null) return;
            var next =
                    old.items().stream()
                            .filter(item -> !item.attachmentId().equals(attachmentId))
                            .toList();
            Map<String, String> mapping = new HashMap<>(old.markers());
            mapping.remove(attachmentId);
            if (next.isEmpty()) terminalDrafts.remove(terminalId);
            else terminalDrafts.put(terminalId, new TerminalDraft(next, Map.copyOf(mapping)));
        }
    }

    /**
     * 用户明确刷新尚未提交的终端快照；已经交给原生会话的内容不能在这里改写。
     *
     * @param terminalId 引用所在的目标终端标识
     * @param attachmentId 用户选择刷新的旧引用标识
     */
    public void replaceTerminalDraft(
            String terminalId, String attachmentId, Attachment replacement) {
        synchronized (terminalDrafts) {
            var old = terminalDrafts.get(terminalId);
            if (old == null || !old.markers().containsKey(attachmentId))
                throw new IllegalStateException("该引用已经提交或移除，请重新选择当前引用");
            var next =
                    old.items().stream()
                            .map(
                                    item ->
                                            item.attachmentId().equals(attachmentId)
                                                    ? replacement
                                                    : item)
                            .toList();
            if (next.stream()
                            .mapToInt(
                                    item -> item.content() == null ? 0 : Json.bytes(item.content()))
                            .sum()
                    > 24576) throw new IllegalArgumentException("ICB_CONTEXT_TOO_LARGE");
            Map<String, String> mapping = new HashMap<>(old.markers());
            String marker = mapping.remove(attachmentId);
            mapping.put(replacement.attachmentId(), marker);
            terminalDrafts.put(terminalId, new TerminalDraft(next, Map.copyOf(mapping)));
        }
    }

    /** 在会话锁内登记引用并交给原生草稿；粘贴被拒绝时回滚这次新增的快照。 */
    public boolean sendDraftReferences(
            SessionStore.Key key,
            List<Attachment> items,
            List<String> markers,
            BooleanSupplier paste) {
        if (items.isEmpty() || items.size() != markers.size())
            throw new IllegalArgumentException("ICB_REFERENCE_INVALID");
        var session = sessions.require(key);
        synchronized (session) {
            if (session.inTurn) throw new IllegalStateException("Codex 正在执行本轮任务，请完成或中断后再发送引用");
            Set<String> previous = new HashSet<>();
            session.queued.forEach(item -> previous.add(item.attachmentId()));
            // 容量、冲突和生命周期校验通过后才粘贴；未接收的引用不得留下待发正文。
            sessions.add(key, items);
            boolean accepted = false;
            try {
                if (session.queued.stream()
                                .mapToInt(
                                        item ->
                                                item.content() == null
                                                        ? 0
                                                        : Json.bytes(item.content()))
                                .sum()
                        > 24576) throw new IllegalArgumentException("选区正文总量超过 24 KiB，请缩小选区后再发送引用");
                accepted = paste.getAsBoolean();
                if (accepted) {
                    var mapping =
                            draftReferences.computeIfAbsent(
                                    key, ignored -> new ConcurrentHashMap<>());
                    for (int index = 0; index < items.size(); index++) {
                        Attachment item = items.get(index);
                        Attachment queued =
                                session.queued.stream()
                                        .filter(old -> old.dedupeKey().equals(item.dedupeKey()))
                                        .findFirst()
                                        .orElseThrow();
                        mapping.put(queued.attachmentId(), markers.get(index));
                    }
                }
                return accepted;
            } finally {
                if (!accepted)
                    for (Attachment item : List.copyOf(session.queued))
                        if (!previous.contains(item.attachmentId()))
                            sessions.remove(key, item.attachmentId());
            }
        }
    }

    /** 更新自动缓存，敏感路径和关闭的联动不会写入缓存。 */
    private void updateActive(RawCapture raw) {
        try {
            if (!allowed()) {
                active.set(null);
                return;
            }
            var identity = paths.identify(Path.of(raw.path));
            JsonObject value =
                    Json.object(
                            "rootId",
                            identity.getKey(),
                            "relativePath",
                            identity.getValue(),
                            "language",
                            raw.language,
                            "cursorOffset",
                            raw.cursor,
                            "documentModificationStamp",
                            raw.stamp,
                            "unsaved",
                            raw.unsaved,
                            "capturedAt",
                            raw.at.toString(),
                            "selectionLineCount",
                            raw.selectionLineCount);
            if (raw.contentOmitted)
                value.addProperty("warning", "ICB_CONTEXT_TOO_LARGE：自动正文超限，仅附加元信息");
            if (!raw.segments.isEmpty()) {
                int bytes = raw.segments.stream().mapToInt(s -> Json.bytes(s.content)).sum();
                if (bytes <= 8192) value.add("content", Json.GSON.toJsonTree(raw.segments));
                else value.addProperty("warning", "ICB_CONTEXT_TOO_LARGE：自动选区超限，仅附加元信息");
            }
            // 当前编辑器的独立完整版本只供只读比较，不扩大自动附加的 8192 字节上限。
            diffs.rememberEditor(paths.resolve(identity.getKey(), identity.getValue()), raw);
            synchronized (this) {
                if (!allowed()) return;
                captured = raw.at;
                active.set(value);
            }
        } catch (IOException ex) {
            active.set(null);
            lastError = "ICB_PATH_DENIED";
        }
    }

    /** 获得已完成自动缓存；缓存过时只发元信息并说明原因。 */
    public JsonObject activeContext() {
        if (!allowed() || !CompanionSettings.get().getState().autoContextEnabled) return null;
        // 自动 Hook 与按需工具共同使用缓存年龄检查，授权方式分别判断。
        return cachedActive();
    }

    /** 终端栏显示文件与选区元信息，不读取正文，不因自动附加关闭而隐藏选择反馈。 */
    public String contextStatus() {
        JsonObject value = cachedActive();
        String text = "Codex：";
        if (value == null) text += "没有可发送的当前文件";
        else {
            text += Json.required(value, "relativePath");
            int lines = value.get("selectionLineCount").getAsInt();
            if (lines > 0) text += " · 已选中 " + lines + " 行";
            if (value.get("unsaved").getAsBoolean()) text += " · 未保存";
        }
        if (!CompanionSettings.get().getState().autoContextEnabled) text += " · 自动附加已关闭";
        return text;
    }

    /** 获得当前项目的有界缓存，过期变动正文不会继续发送。 */
    private JsonObject cachedActive() {
        if (!allowed()) return null;
        JsonObject result = active.get();
        if (result == null) return null;
        result = result.deepCopy();
        if (changed.isAfter(captured) && captured.isBefore(Instant.now().minusMillis(500))) {
            result.remove("content");
            result.addProperty("warning", "ICB_IDE_BUSY：内容未及时取得");
        }
        return result;
    }

    /**
     * 一个合法回调结果。
     *
     * @param output 原生 Codex 可解释的完整 JSON
     * @param batchId 提交交接标识；非提交事件为 null
     */
    public record HookResult(String output, String batchId) {}

    /**
     * 2026-10-06：首次回调关联同一个认证终端的草稿，删除标记后移除快照，分派失败返回原生阻止结果。
     *
     * @param terminalId 请求凭证绑定的终端标识
     */
    public HookResult hook(String terminalId, JsonObject input) throws IOException {
        String event = Json.required(input, "hook_event_name");
        if (!Set.of("SessionStart", "UserPromptSubmit", "Stop", "Interrupt", "SessionEnd")
                .contains(event)) throw new IllegalArgumentException("EVENT_INVALID");
        String sessionId = Json.required(input, "session_id");
        String cwd = Json.required(input, "cwd");
        hookObserved = true;
        lastEvent = event;
        if (input.has("agent_id") && !input.get("agent_id").isJsonNull())
            return new HookResult("{}", null);
        receivedHookEvents.add(event);
        SessionStore.Key key = new SessionStore.Key(terminalId, sessionId);
        try {
            // 凭证先确定项目，真实工作目录校验后才允许会话注册。
            String canonicalCwd = paths.validateCwd(cwd).toString();
            switch (event) {
                case "SessionStart" -> {
                    sessions.register(terminalId, sessionId, canonicalCwd);
                    // 首条输入前已发送给这个终端的引用，在原生回调实际执行后关联。
                    bindTerminalDraft(key);
                }
                case "UserPromptSubmit" -> {
                    String turn = Json.required(input, "turn_id");
                    String prompt = Json.required(input, "prompt");
                    if (sessionId.length() > 128 || turn.length() > 128)
                        throw new IllegalArgumentException("ICB_PROTOCOL_MISMATCH");
                    String requestHash =
                            Json.sha(
                                    Json.GSON.toJson(
                                            Json.object(
                                                    "event", event, "session", sessionId, "turn",
                                                    turn, "cwd", cwd, "prompt", prompt)));
                    // 2026-09-30：原生启动回调可能漏收；提交时幂等补注册，不接管其他终端或未绑定引用。
                    sessions.register(terminalId, sessionId, canonicalCwd);
                    // 启动回调未收到时，首条提交也按相同认证终端关联引用。
                    bindTerminalDraft(key);
                    SessionStore.Session session = sessions.require(key);
                    List<Attachment> queued;
                    synchronized (session) {
                        SessionStore.Batch previous = session.turns.get(turn);
                        if (previous != null) {
                            if (!previous.requestHash.equals(requestHash))
                                throw new IllegalStateException("ICB_IDEMPOTENCY_CONFLICT");
                            return new HookResult(previous.output, previous.id);
                        }
                        // 只有本轮仍可见的草稿引用才发送对应正文，不能删除界面引用却继续附加。
                        var mapping = draftReferences.get(key);
                        if (mapping != null) {
                            for (Attachment item : List.copyOf(session.queued)) {
                                String marker = mapping.get(item.attachmentId());
                                if (marker != null && !DraftReference.present(prompt, marker))
                                    sessions.remove(key, item.attachmentId());
                            }
                        }
                        queued = List.copyOf(session.queued);
                    }
                    for (Attachment item : queued) resolveAttachment(item);
                    JsonObject automatic = activeContext();
                    // 引用列表和自动缓存由提交事务冻结；相同轮次重试保持结果逐字一致。
                    SessionStore.Batch batch = sessions.submit(key, turn, requestHash, automatic);
                    draftReferences.remove(key);
                    diffs.captureBefore(key, turn, batch.attachments, automatic);
                    return new HookResult(batch.output, batch.id);
                }
                case "Stop", "Interrupt" -> {
                    String turn = Json.required(input, "turn_id");
                    sessions.finish(key, turn, event.equals("Interrupt"));
                    background.execute(() -> diffs.finish(key, turn));
                }
                case "SessionEnd" -> {
                    sessions.close(key);
                    draftReferences.remove(key);
                    if (key.equals(target)) target = null;
                }
                default -> throw new IllegalArgumentException("EVENT_INVALID");
            }
            return new HookResult("{}", null);
        } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            lastError = "ICB_CONTEXT_UNAVAILABLE";
            for (String known :
                    List.of(
                            "ICB_PROTOCOL_MISMATCH",
                            "ICB_SESSION_AMBIGUOUS",
                            "ICB_SESSION_UNKNOWN",
                            "ICB_IDEMPOTENCY_CONFLICT",
                            "ICB_CONTEXT_STALE",
                            "ICB_CONTEXT_TOO_LARGE"))
                if (message.startsWith(known)) lastError = known;
            if (!input.has("turn_id") && event.equals("UserPromptSubmit"))
                lastError = "ICB_PROTOCOL_MISMATCH";
            if (event.equals("UserPromptSubmit"))
                return new HookResult(
                        Json.GSON.toJson(
                                Json.object(
                                        "decision",
                                        "block",
                                        "reason",
                                        "IDEA 引用未附加（"
                                                + lastError
                                                + "）。请检查目标会话、联动开关、路径或引用容量；队列已保留。")),
                        null);
            throw ex;
        }
    }

    /**
     * 将确认限制在来源终端的已存在批次中。
     *
     * @param terminalId 请求凭证的终端标识
     * @param batchId 客户端已完成 stdout 写入的批次标识
     */
    public void ack(String terminalId, String batchId) {
        if (batchId == null) throw new IllegalArgumentException("BATCH_REQUIRED");
        for (var entry : sessions.list().entrySet())
            if (entry.getKey().terminalId().equals(terminalId)) {
                synchronized (entry.getValue()) {
                    if (entry.getValue().turns.values().stream()
                            .anyMatch(b -> b.id.equals(batchId))) {
                        sessions.ack(entry.getKey(), batchId);
                        return;
                    }
                }
            }
        throw new IllegalArgumentException("BATCH_UNKNOWN");
    }

    /** 执行五个受限工具，根和真实路径始终限制在当前项目内容根。 */
    public JsonObject tool(JsonObject request) {
        try {
            if (!allowed()) return ToolCatalog.error("ICB_PROJECT_DENIED");
            String name = Json.required(request, "name");
            JsonObject args = request.getAsJsonObject("arguments");
            // 按工具目录校验参数，拒绝模型伪造项目和连接字段。
            JsonObject schema = null;
            for (JsonElement item : ToolCatalog.tools())
                if (item.getAsJsonObject().get("name").getAsString().equals(name))
                    schema = item.getAsJsonObject().getAsJsonObject("inputSchema");
            if (schema == null || args == null) return ToolCatalog.error("TOOL_INVALID");
            for (String field : args.keySet())
                if (!schema.getAsJsonObject("properties").has(field))
                    return ToolCatalog.error("ARGUMENT_INVALID");
            for (JsonElement field : schema.getAsJsonArray("required"))
                if (!args.has(field.getAsString())) return ToolCatalog.error("ARGUMENT_REQUIRED");
            for (var entry : args.entrySet()) {
                JsonElement value = entry.getValue();
                String type =
                        schema.getAsJsonObject("properties")
                                .getAsJsonObject(entry.getKey())
                                .get("type")
                                .getAsString();
                if (!value.isJsonPrimitive()) return ToolCatalog.error("ARGUMENT_INVALID");
                if (type.equals("string") && !value.getAsJsonPrimitive().isString())
                    return ToolCatalog.error("ARGUMENT_INVALID");
                if (type.equals("integer")) {
                    if (!value.getAsJsonPrimitive().isNumber())
                        return ToolCatalog.error("RANGE_INVALID");
                    try {
                        value.getAsBigDecimal().intValueExact();
                    } catch (ArithmeticException ex) {
                        return ToolCatalog.error("RANGE_INVALID");
                    }
                }
            }
            if (name.equals("ide_get_context")) {
                if (args.has("view") && !args.get("view").getAsString().equals("active"))
                    return ToolCatalog.error("ARGUMENT_INVALID");
                JsonObject value = cachedActive();
                if (value != null
                        && value.get("unsaved").getAsBoolean()
                        && !CompanionSettings.get().getState().allowUnsavedMcpRead) {
                    value.remove("content");
                    value.addProperty("warning", "UNSAVED_READ_DENIED：仅提供元信息");
                }
                return ToolCatalog.result(
                        value == null
                                ? Json.object("state", "UNAVAILABLE", "roots", roots.keySet())
                                : value);
            }
            if (name.equals("ide_show_diff")) {
                diffs.show(Json.required(args, "reviewId"));
                return ToolCatalog.result(Json.object("state", "SCHEDULED"));
            }
            Path path =
                    paths.resolve(
                            Json.required(args, "rootId"), Json.required(args, "relativePath"));
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
            if (file == null || file.getFileType().isBinary())
                return ToolCatalog.error("FILE_UNAVAILABLE");
            if (name.equals("ide_get_diagnostics"))
                return ToolCatalog.result(
                        read(() -> DiagnosticsProvider262.collect(project, file)));
            if (name.equals("ide_open_file")) {
                int line = args.get("line").getAsInt(), column = args.get("column").getAsInt();
                return read(
                        () -> {
                            Document document = FileDocumentManager.getInstance().getDocument(file);
                            if (document == null
                                    || line < 1
                                    || line > document.getLineCount()
                                    || column < 1
                                    || column - 1
                                            > document.getLineEndOffset(line - 1)
                                                    - document.getLineStartOffset(line - 1))
                                return ToolCatalog.error("RANGE_INVALID");
                            int offset = document.getLineStartOffset(line - 1) + column - 1;
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                if (allowed())
                                                    new OpenFileDescriptor(project, file, offset)
                                                            .navigate(true);
                                            },
                                            project.getDisposed());
                            return ToolCatalog.result(Json.object("state", "SCHEDULED"));
                        });
            }
            if (Files.size(path) > 131072) return ToolCatalog.error("ICB_CONTEXT_TOO_LARGE");
            JsonObject editor =
                    read(
                            () -> {
                                Document document =
                                        FileDocumentManager.getInstance().getCachedDocument(file);
                                if (document == null
                                        || !CompanionSettings.get().getState().allowUnsavedMcpRead)
                                    return null;
                                if (document.getTextLength() > 131072)
                                    throw new IllegalArgumentException("ICB_CONTEXT_TOO_LARGE");
                                return Json.object(
                                        "content",
                                        document.getText(),
                                        "source",
                                        "EDITOR_SNAPSHOT",
                                        "documentModificationStamp",
                                        document.getModificationStamp(),
                                        "unsaved",
                                        FileDocumentManager.getInstance()
                                                .isDocumentUnsaved(document));
                            });
            if (editor == null) {
                paths.resolve(Json.required(args, "rootId"), Json.required(args, "relativePath"));
                String text =
                        readDisk(
                                Json.required(args, "rootId"), Json.required(args, "relativePath"));
                editor = Json.object("content", text, "source", "DISK", "unsaved", false);
            }
            String text = editor.get("content").getAsString();
            if (args.has("startLine") || args.has("endLine")) {
                String[] lines = text.split("\n", -1);
                int start = args.has("startLine") ? args.get("startLine").getAsInt() : 1;
                int end = args.has("endLine") ? args.get("endLine").getAsInt() : lines.length;
                if (start < 1 || end < start || end > lines.length)
                    return ToolCatalog.error("RANGE_INVALID");
                text = String.join("\n", Arrays.copyOfRange(lines, start - 1, end));
                editor.addProperty("content", text);
            }
            if (Json.bytes(text) > 24576) return ToolCatalog.error("ICB_CONTEXT_TOO_LARGE");
            editor.addProperty("capturedAt", Instant.now().toString());
            editor.addProperty("contentSha256", Json.sha(text));
            return ToolCatalog.result(editor);
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            return ToolCatalog.error("ICB_TOOL_REJECTED");
        }
    }

    /** 短读操作最多等候 100 毫秒，取消不会被吞成成功。 */
    public <T> T read(Supplier<T> supplier) {
        // 通用工具读取采用文档规定的 100 毫秒预算；比较基线另用更短预算。
        return read(supplier, 100);
    }

    /**
     * 按调用场景的期限取得短读锁，超时取消且不伪装成功。
     *
     * @param timeoutMillis 本次平台读操作最大等待毫秒数，普通工具为 100，比较补采为 5
     */
    public <T> T read(Supplier<T> supplier, int timeoutMillis) {
        var task =
                ReadAction.nonBlocking(supplier::get)
                        .expireWith(this)
                        .submit(AppExecutorUtil.getAppExecutorService());
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            task.cancel();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ICB_IDE_BUSY");
        } catch (ExecutionException ex) {
            if (ex.getCause()
                    instanceof com.intellij.openapi.progress.ProcessCanceledException canceled)
                throw canceled;
            throw new IllegalStateException("ICB_IDE_BUSY");
        } catch (TimeoutException ex) {
            task.cancel();
            throw new IllegalStateException("ICB_IDE_BUSY");
        }
    }

    /**
     * 在后台通过授权目录的安全文件描述符读取磁盘，不经过可替换的裸路径。
     *
     * @param rootId 当前项目内容根标识
     * @param relativePath 该根内文件路径，不允许扩大读取范围
     */
    public String readDisk(String rootId, String relativePath) throws IOException {
        // 受限磁盘读取在普通 Java 进程完成，避免依赖 IDEA 文件系统内部接口或弱化安全目录检查。
        paths.resolve(rootId, relativePath);
        Path root = roots.get(rootId);
        if (root == null) throw new IOException("PATH_DENIED");
        String content =
                ApplicationManager.getApplication()
                        .getService(NativeFilesService.class)
                        .read(projectId, root, rootId, relativePath);
        // 返回前再次检查项目和当前内容根，拒绝读取期间关闭项目或撤走文件范围的结果。
        if (!allowed()) throw new IOException("ICB_PROJECT_DENIED");
        paths.resolve(rootId, relativePath);
        return content;
    }

    /** 获取当前许可下的路径策略。 */
    public PathPolicy pathPolicy() {
        return paths;
    }

    /** 在后台执行文件工作，执行前后不持有界面锁。 */
    public void execute(Runnable task) {
        if (!disposed) background.execute(task);
    }

    /** 撤销项目联动，不干预用户原生终端。 */
    public void revoke() {
        ApplicationManager.getApplication().getService(BridgeApplicationService.class).revoke(this);
        // 释放当前项目对安全磁盘读取进程的使用，最后一个项目关闭时回收进程。
        ApplicationManager.getApplication().getService(NativeFilesService.class).release(projectId);
        sessions.clear();
        draftReferences.clear();
        synchronized (terminalDrafts) {
            terminalDrafts.clear();
        }
        synchronized (this) {
            active.set(null);
        }
        target = null;
        diffs.clear();
        sensitiveReferences.clear();
        synchronized (unassigned) {
            unassigned.clear();
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        background.shutdownNow();
        revoke();
    }
}
