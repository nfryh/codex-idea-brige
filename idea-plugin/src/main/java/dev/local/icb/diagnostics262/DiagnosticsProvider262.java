// 固定 IDEA 262 实现层的诊断适配，只读取已经产生的高亮。
package dev.local.icb.diagnostics262;

import com.google.gson.*;
import com.intellij.codeInsight.daemon.*;
import com.intellij.codeInsight.daemon.impl.*;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;

import dev.local.icb.contract.Json;

import java.time.Instant;

/** 不执行全项目分析，不把未完成状态标成零错误。 */
public final class DiagnosticsProvider262 {
    private DiagnosticsProvider262() {}

    /** 在调用方已经取得的短平台读操作中复制最多 100 条现有诊断。 */
    public static JsonObject collect(Project project, VirtualFile file) {
        var document = FileDocumentManager.getInstance().getCachedDocument(file);
        var psi = PsiManager.getInstance(project).findFile(file);
        if (document == null || psi == null)
            return Json.object("analysisState", "UNAVAILABLE", "diagnostics", new JsonArray());
        boolean ready =
                !DumbService.isDumb(project)
                        && java.util.Arrays.stream(
                                        FileEditorManager.getInstance(project).getAllEditors(file))
                                .anyMatch(
                                        editor ->
                                                DaemonCodeAnalyzerEx.isHighlightingCompleted(
                                                        editor, project));
        JsonArray diagnostics = new JsonArray();
        // 复制高亮的轻量字段，不在遍历回调里做文件或网络操作。
        DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.INFORMATION,
                0,
                document.getTextLength(),
                highlight -> {
                    if (diagnostics.size() >= 100) return false;
                    diagnostics.add(
                            Json.object(
                                    "severity",
                                    highlight.getSeverity().getName(),
                                    "startOffset",
                                    highlight.getStartOffset(),
                                    "endOffsetExclusive",
                                    highlight.getEndOffset(),
                                    "description",
                                    highlight.getDescription()));
                    return true;
                });
        return Json.object(
                "analysisState",
                ready ? "READY" : "PENDING",
                "documentModificationStamp",
                document.getModificationStamp(),
                "capturedAt",
                Instant.now().toString(),
                "diagnostics",
                diagnostics);
    }
}
