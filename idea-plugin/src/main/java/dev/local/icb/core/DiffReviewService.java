// 为相关文件记录有限基线并使用 IDEA 原生只读比较，不应用任何补丁。
package dev.local.icb.core;

import com.google.gson.JsonObject;
import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;

import dev.local.icb.contract.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 磁盘和编辑器快照明确分开，变化来源不归因给模型。 */
public final class DiffReviewService {
    private final Project project;
    private final ProjectContextService owner;
    private final Map<String, List<Baseline>> before = new LinkedHashMap<>();
    private final Map<String, Review> reviews = new LinkedHashMap<>();

    /** 当前编辑器已捕获的独立版本，受全部项目共享的比较内存预算限制。 */
    private EditorBaseline cachedEditor;

    /** 缓存编辑器快照对应的真实路径，不作为模型读取的授权入口。 */
    private Path cachedEditorPath;

    private static final AtomicLong MEMORY = new AtomicLong();

    /**
     * 独立记录完整编辑器版本，不将选区片段冒充整个文档。
     *
     * @param text 提交前完整文档快照
     * @param stamp 文档修改版本
     * @param unsaved true 表示未保存，false 表示已保存
     */
    private record EditorBaseline(String text, long stamp, boolean unsaved) {}

    /**
     * 已记录的单文件比较基线。
     *
     * @param rootId 当前项目内容根标识
     * @param relativePath 该根内相对文件路径
     * @param disk 提交前已保存的磁盘文本
     * @param capturedPath 捕获时已验证的真实路径，仅供任务后定向刷新
     * @param editor 单独的显式编辑器快照，没有时为 null
     */
    private record Baseline(
            String rootId,
            String relativePath,
            String disk,
            Path capturedPath,
            EditorBaseline editor) {}

    /**
     * 只读比较记录。
     *
     * @param id 允许工具显示的比较标识
     * @param path 根内文件路径
     * @param origin DISK_BEFORE_TURN 为提交前已保存磁盘，EDITOR_SNAPSHOT 为独立编辑器快照
     * @param left origin 指定的比较前版本，不将编辑器内容当成已保存文件
     * @param right 任务结束后磁盘版本，删除文件时为空
     * @param deleted true 表示原文件已删除，false 表示原文件仍存在
     * @param at 比较创建时刻
     */
    public record Review(
            String id,
            String path,
            String origin,
            String left,
            String right,
            boolean deleted,
            Instant at) {}

    public DiffReviewService(Project project, ProjectContextService owner) {
        this.project = project;
        this.owner = owner;
    }

    /** 保存已经通过路径检查的独立编辑器版本，提交线程不需要等界面取得同一内容。 */
    public synchronized void rememberEditor(Path path, ProjectContextService.RawCapture raw) {
        if (!owner.allowed()
                || raw.fullDocument() == null
                || Json.bytes(raw.fullDocument()) > 131072) return;
        long difference =
                Json.bytes(raw.fullDocument())
                        - (cachedEditor == null ? 0 : Json.bytes(cachedEditor.text));
        // 快照正文计入统一的比较预算，超量只省略比较缓存，不影响显式引用排队。
        if (!reserve(difference)) return;
        cachedEditor = new EditorBaseline(raw.fullDocument(), raw.stamp(), raw.unsaved());
        cachedEditorPath = path;
    }

    /**
     * 在本轮首次交接前捕获有限相关文件，重试不重写基线。
     *
     * @param turnId 原生 Codex 本轮标识
     */
    public void captureBefore(
            SessionStore.Key key, String turnId, List<Attachment> attachments, JsonObject active) {
        String identity = key + ":" + turnId;
        synchronized (this) {
            if (before.containsKey(identity)) return;
            before.put(identity, List.of());
        }
        Map<String, Attachment> related = new LinkedHashMap<>();
        for (Attachment item : attachments)
            related.put(item.rootId() + ":" + item.relativePath(), item);
        if (active != null) {
            Attachment item =
                    Attachment.path(
                            active.get("rootId").getAsString(),
                            active.get("relativePath").getAsString());
            related.putIfAbsent(item.rootId() + ":" + item.relativePath(), item);
        }
        List<Baseline> captured = new ArrayList<>();
        for (Attachment item : related.values()) {
            try {
                Path path = owner.pathPolicy().resolve(item.rootId(), item.relativePath());
                String disk = owner.readDisk(item.rootId(), item.relativePath());
                EditorBaseline editor;
                synchronized (this) {
                    editor = path.equals(cachedEditorPath) ? cachedEditor : null;
                }
                if (editor == null) {
                    try {
                        // 只有缺少缓存的相关文件才补采；界面忙碌时保留磁盘基线而不是丢掉整条记录。
                        editor =
                                owner.read(
                                        () -> {
                                            var file =
                                                    LocalFileSystem.getInstance()
                                                            .findFileByNioFile(path);
                                            var document =
                                                    file == null
                                                            ? null
                                                            : FileDocumentManager.getInstance()
                                                                    .getCachedDocument(file);
                                            if (document == null
                                                    || document.getTextLength() > 131072)
                                                return null;
                                            return new EditorBaseline(
                                                    document.getText(),
                                                    document.getModificationStamp(),
                                                    FileDocumentManager.getInstance()
                                                            .isDocumentUnsaved(document));
                                        },
                                        5);
                    } catch (IllegalStateException ex) {
                        owner.lastError = "ICB_IDE_BUSY";
                    }
                }
                if (editor != null && Json.bytes(editor.text) > 131072) editor = null;
                captured.add(new Baseline(item.rootId(), item.relativePath(), disk, path, editor));
            } catch (IOException ex) {
                owner.lastError = "ICB_DIFF_BASE_MISSING";
            } catch (IllegalStateException ex) {
                owner.lastError = "ICB_IDE_BUSY";
            }
        }
        synchronized (this) {
            if (!owner.allowed()) return;
            long bytes = captured.stream().mapToLong(DiffReviewService::baselineBytes).sum();
            if (!reserve(bytes)) {
                owner.lastError = "ICB_DIFF_BASE_MISSING";
                return;
            }
            before.put(identity, List.copyOf(captured));
            while (before.size() > 20) {
                List<Baseline> removed = before.remove(before.keySet().iterator().next());
                MEMORY.addAndGet(
                        -removed.stream().mapToLong(DiffReviewService::baselineBytes).sum());
            }
        }
    }

    /**
     * 任务后后台检查已记录文件并定向刷新，保留平台未保存冲突处理。
     *
     * @param turnId 原生任务标识
     */
    public void finish(SessionStore.Key key, String turnId) {
        List<Baseline> baselines;
        synchronized (this) {
            baselines = before.remove(key + ":" + turnId);
        }
        if (baselines == null) return;
        MEMORY.addAndGet(-baselines.stream().mapToLong(DiffReviewService::baselineBytes).sum());
        for (Baseline baseline : baselines) {
            String current;
            boolean deleted = false;
            try {
                current = owner.readDisk(baseline.rootId, baseline.relativePath);
            } catch (NoSuchFileException ex) {
                current = "";
                deleted = true;
            } catch (IOException ex) {
                owner.lastError = "ICB_DIFF_FILE_UNAVAILABLE";
                continue;
            }
            // 磁盘基线与独立编辑器快照分别展示，避免把未保存文本误称为已经应用的修改。
            if (deleted || !current.equals(baseline.disk))
                addReview(baseline, "DISK_BEFORE_TURN", baseline.disk, current, deleted);
            if (baseline.editor != null && (deleted || !current.equals(baseline.editor.text)))
                addReview(baseline, "EDITOR_SNAPSHOT", baseline.editor.text, current, deleted);
            // 使用捕获时的已知路径刷新删除状态，不扫描项目也不强制重载未保存文档。
            var file = LocalFileSystem.getInstance().findFileByNioFile(baseline.capturedPath);
            if (file != null) file.refresh(true, false);
        }
    }

    /**
     * 保存有界的只读比较，不写入编辑器或磁盘。
     *
     * @param origin DISK_BEFORE_TURN 为提交前磁盘，EDITOR_SNAPSHOT 为独立编辑器快照
     * @param left 本次基线的完整正文
     * @param right 当前磁盘正文，删除时为空
     * @param deleted true 表示原文件已删除，false 表示仍存在
     */
    private synchronized void addReview(
            Baseline baseline, String origin, String left, String right, boolean deleted) {
        if (!owner.allowed()) return;
        if (!reserve(Json.bytes(left) + Json.bytes(right))) {
            owner.lastError = "ICB_DIFF_BASE_MISSING";
            return;
        }
        Review review =
                new Review(
                        UUID.randomUUID().toString(),
                        baseline.rootId + "/" + baseline.relativePath,
                        origin,
                        left,
                        right,
                        deleted,
                        Instant.now());
        reviews.put(review.id, review);
        while (reviews.size() > 20) {
            Review removed = reviews.remove(reviews.keySet().iterator().next());
            MEMORY.addAndGet(-Json.bytes(removed.left) - Json.bytes(removed.right));
        }
    }

    /** 列出可显示的比较记录，不推断所有改动由 Codex 造成。 */
    public synchronized List<Review> list() {
        return List.copyOf(reviews.values());
    }

    /**
     * 显示服务已存在的只读比较。
     *
     * @param reviewId 当前项目服务已经记录的比较标识
     */
    public void show(String reviewId) {
        Review review;
        synchronized (this) {
            review = reviews.get(reviewId);
        }
        if (review == null) throw new IllegalArgumentException("ICB_DIFF_BASE_MISSING");
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (!owner.allowed()) return;
                            // 只有展示，无二次应用和文档重载，左右来源明确写在标题中。
                            var factory = DiffContentFactory.getInstance();
                            SimpleDiffRequest request =
                                    new SimpleDiffRequest(
                                            review.path,
                                            factory.create(project, review.left),
                                            factory.create(project, review.right),
                                            review.origin.equals("DISK_BEFORE_TURN")
                                                    ? "提交前已保存磁盘"
                                                    : "独立编辑器快照（不表示已保存或已应用）",
                                            review.deleted ? "文件已删除" : "任务结束后磁盘（可能包含并行修改）");
                            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
                            request.putUserData(
                                    DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
                                    new boolean[] {true, true});
                            DiffManager.getInstance().showDiff(project, request);
                        },
                        project.getDisposed());
    }

    /** 清除项目关闭时的所有内存源代码。 */
    public synchronized void clear() {
        MEMORY.addAndGet(
                -before.values().stream()
                                .flatMap(Collection::stream)
                                .mapToLong(DiffReviewService::baselineBytes)
                                .sum()
                        - reviews.values().stream()
                                .mapToLong(
                                        review ->
                                                Json.bytes(review.left) + Json.bytes(review.right))
                                .sum());
        if (cachedEditor != null) MEMORY.addAndGet(-Json.bytes(cachedEditor.text));
        cachedEditor = null;
        cachedEditorPath = null;
        before.clear();
        reviews.clear();
    }

    /** 估算独立磁盘和编辑器基线的正文占用。 */
    private static long baselineBytes(Baseline baseline) {
        return Json.bytes(baseline.disk)
                + (baseline.editor == null ? 0 : Json.bytes(baseline.editor.text));
    }

    /**
     * 为全部项目的差异正文预留最多 16 MiB。
     *
     * @param bytes 新增磁盘、编辑器或比较正文的 UTF-8 字节数
     */
    private static boolean reserve(long bytes) {
        while (true) {
            long current = MEMORY.get();
            if (current + bytes > 16777216) return false;
            if (MEMORY.compareAndSet(current, current + bytes)) return true;
        }
    }
}
