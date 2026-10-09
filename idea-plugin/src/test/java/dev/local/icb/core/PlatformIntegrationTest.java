// 在真实 IDEA 平台测试环境验证文档快照和项目隔离，不调用模型。
package dev.local.icb.core;

import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.HeavyPlatformTestCase;

import dev.local.icb.client.Endpoint;
import dev.local.icb.contract.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 硬验证 IDEA Document 未保存内容与磁盘不同，并通过真实本地桥接交接。 */
public class PlatformIntegrationTest extends HeavyPlatformTestCase {
    private ProjectContextService service;
    private Path root;
    private Editor editor;
    private BridgeApplicationService bridge;

    /** 隔离平台测试不运行其他商业插件启动活动，当前插件服务由测试明确建立。 */
    @Override
    protected com.intellij.testFramework.OpenProjectTaskBuilder getOpenProjectOptions() {
        return super.getOpenProjectOptions().runPostStartUpActivities(false);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Files.createDirectories(Path.of(getProject().getBasePath()));
        root = Path.of(getProject().getBasePath()).toRealPath();
        CompanionSettings.get().getState().integrationEnabled = true;
        service = new ProjectContextService(getProject());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(service::reloadRoots).get(5, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (editor != null) EditorFactory.getInstance().releaseEditor(editor);
            if (bridge != null) bridge.dispose();
            if (service != null) Disposer.dispose(service);
            CompanionSettings.get().getState().integrationEnabled = true;
        } finally {
            super.tearDown();
        }
    }

    /** 验证插件动作实际注册且默认键位映射可触发，避免只写说明却没有按键绑定。 */
    public void testSelectionAndFileActionsHaveRegisteredShortcuts() {
        var actions = com.intellij.openapi.actionSystem.ActionManager.getInstance();
        assertNotNull(actions.getAction("ICB.AddSelection"));
        assertNotNull(actions.getAction("ICB.AddCurrentFile"));
        var keymaps = com.intellij.openapi.keymap.KeymapManager.getInstance();
        for (String name : List.of("$default", "Mac OS X")) {
            var keymap = keymaps.getKeymap(name);
            assertNotNull(keymap);
            String modifiers = name.equals("Mac OS X") ? "meta alt " : "ctrl alt ";
            var selection =
                    new com.intellij.openapi.actionSystem.KeyboardShortcut(
                            javax.swing.KeyStroke.getKeyStroke(modifiers + "K"), null);
            var file =
                    new com.intellij.openapi.actionSystem.KeyboardShortcut(
                            javax.swing.KeyStroke.getKeyStroke(modifiers + "shift K"), null);
            assertTrue(Arrays.asList(keymap.getShortcuts("ICB.AddSelection")).contains(selection));
            assertTrue(Arrays.asList(keymap.getShortcuts("ICB.AddCurrentFile")).contains(file));
            var queue =
                    new com.intellij.openapi.actionSystem.KeyboardShortcut(
                            javax.swing.KeyStroke.getKeyStroke(
                                    name.equals("Mac OS X") ? "meta alt Q" : "ctrl alt shift Q"),
                            null);
            assertTrue(Arrays.asList(keymap.getShortcuts("ICB.ShowContextQueue")).contains(queue));
        }
    }

    /** 终端拒绝粘贴时撤回本次快照，但保留用户之前已经加入的内容。 */
    public void testDraftPasteFailureRollsBackOnlyNewAttachments() {
        var key = new SessionStore.Key("draft-terminal", "draft-session");
        service.sessions.register(key.terminalId(), key.sessionId(), root.toString());
        var old = Attachment.path("root", "old.txt");
        service.sessions.add(key, List.of(old));
        var item = Attachment.path("root", "new.txt");
        assertFalse(
                service.sendDraftReferences(key, List.of(item), List.of("@new.txt"), () -> false));
        assertEquals(List.of(old), service.sessions.require(key).queued);
    }

    /** 首条提交前的引用仅关联同一个认证终端，其他终端和未绑定列表不消费它。 */
    public void testPreSessionDraftBindsOnlyToItsChosenTerminal() throws Exception {
        Path file = root.resolve("pending-native.txt");
        Files.writeString(file, "disk");
        var identity = service.pathPolicy().identify(file);
        var item =
                Attachment.snapshot(
                        identity.getKey(),
                        identity.getValue(),
                        "SELECTION_SNAPSHOT",
                        "FIRST_DRAFT_NONCE",
                        0,
                        17,
                        1,
                        true);
        assertTrue(
                service.sendTerminalDraftReferences(
                        "chosen-terminal",
                        List.of(item),
                        List.of("@pending-native.txt#L1"),
                        () -> true));
        var other =
                service.hook(
                        "other-terminal",
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                "other-session",
                                "turn_id",
                                "other-turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "@pending-native.txt#L1"));
        assertFalse(other.output().contains("FIRST_DRAFT_NONCE"));
        assertEquals(1, service.terminalDrafts().size());
        service.unassigned.add(Attachment.path(identity.getKey(), identity.getValue()));
        var own =
                service.hook(
                        "chosen-terminal",
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                "chosen-session",
                                "turn_id",
                                "chosen-turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "@pending-native.txt#L1 explain"));
        assertTrue(own.output().contains("FIRST_DRAFT_NONCE"));
        assertTrue(service.terminalDrafts().isEmpty());
        assertEquals(1, service.unassigned.size());
    }

    /** 首次原生登记前删除草稿引用也不能发出旧正文；失败粘贴和退出终端回收待发快照。 */
    public void testPreSessionDeletedReferenceFailureAndTerminalExitAreSafe() throws Exception {
        Path file = root.resolve("removed-native.txt");
        Files.writeString(file, "disk");
        var identity = service.pathPolicy().identify(file);
        var item =
                Attachment.snapshot(
                        identity.getKey(),
                        identity.getValue(),
                        "SELECTION_SNAPSHOT",
                        "REMOVED_DRAFT_NONCE",
                        0,
                        19,
                        1,
                        true);
        assertFalse(
                service.sendTerminalDraftReferences(
                        "failed-terminal",
                        List.of(item),
                        List.of("@removed-native.txt#L1"),
                        () -> false));
        assertTrue(service.terminalDrafts().isEmpty());
        assertTrue(
                service.sendTerminalDraftReferences(
                        "chosen-terminal",
                        List.of(item),
                        List.of("@removed-native.txt#L1"),
                        () -> true));
        var output =
                service.hook(
                        "chosen-terminal",
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                "chosen-session",
                                "turn_id",
                                "chosen-turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "explain without that reference"));
        assertFalse(output.output().contains("REMOVED_DRAFT_NONCE"));
        assertTrue(service.terminalDrafts().isEmpty());
        assertTrue(
                service.sendTerminalDraftReferences(
                        "exited-terminal",
                        List.of(item),
                        List.of("@removed-native.txt#L1"),
                        () -> true));
        service.retainTerminalDrafts(Set.of("another-terminal"));
        assertTrue(service.terminalDrafts().isEmpty());
    }

    /** 同时提交两条引用时只发送草稿仍包含的那一条，重复回调保留冻结结果。 */
    public void testDeletedDraftReferenceDoesNotSendHiddenSnapshotAndRetryIsFrozen()
            throws Exception {
        Files.writeString(root.resolve("kept.txt"), "disk");
        Files.writeString(root.resolve("deleted.txt"), "disk");
        String rootId = service.pathPolicy().identify(root.resolve("kept.txt")).getKey();
        var kept =
                Attachment.snapshot(
                        rootId,
                        "kept.txt",
                        "SELECTION_SNAPSHOT",
                        "KEPT_SELECTION",
                        0,
                        14,
                        1,
                        false);
        var deleted =
                Attachment.snapshot(
                        rootId,
                        "deleted.txt",
                        "SELECTION_SNAPSHOT",
                        "REMOVED_SELECTION",
                        0,
                        17,
                        1,
                        false);
        var key = new SessionStore.Key("draft-terminal", "draft-session");
        service.sessions.register(key.terminalId(), key.sessionId(), root.toString());
        assertTrue(
                service.sendDraftReferences(
                        key,
                        List.of(kept, deleted),
                        List.of("@kept.txt#L1", "@deleted.txt#L1"),
                        () -> true));
        var event =
                Json.object(
                        "hook_event_name",
                        "UserPromptSubmit",
                        "session_id",
                        key.sessionId(),
                        "turn_id",
                        "draft-turn",
                        "cwd",
                        root.toString(),
                        "prompt",
                        "original draft @kept.txt#L1 explain");
        var output = service.hook(key.terminalId(), event);
        assertTrue(output.output().contains("KEPT_SELECTION"));
        assertFalse(output.output().contains("REMOVED_SELECTION"));
        assertEquals(output.output(), service.hook(key.terminalId(), event).output());
        assertTrue(service.sessions.require(key).queued.isEmpty());
    }

    /** 正在执行任务时不向可能正在显示审批的输入区域插入引用。 */
    public void testRunningTurnRejectsDraftBeforeCallingTerminal() {
        var key = new SessionStore.Key("draft-terminal", "draft-session");
        service.sessions.register(key.terminalId(), key.sessionId(), root.toString());
        service.sessions.require(key).inTurn = true;
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.sendDraftReferences(
                                key,
                                List.of(Attachment.path("root", "file.txt")),
                                List.of("@file.txt"),
                                () -> {
                                    invoked.set(true);
                                    return true;
                                }));
        assertFalse(invoked.get());
        assertTrue(service.sessions.require(key).queued.isEmpty());
    }

    /** 选到下一行的起点不多算一行，光标附近文本也不冒充用户选区。 */
    public void testLiveSelectionCountUsesRealRangesAndIgnoresNearbyCode() throws Exception {
        Path path = root.resolve("count.txt");
        Files.writeString(path, "one\ntwo\nthree\n");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        editor =
                EditorFactory.getInstance()
                        .createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        editor.getSelectionModel().setSelection(0, 8);
        assertEquals(2, service.capture(editor, "AUTO").selectionLineCount());
        editor.getSelectionModel().removeSelection();
        CompanionSettings.get().getState().includeNearbyCode = true;
        try {
            var captured = service.capture(editor, "AUTO");
            assertEquals(0, captured.selectionLineCount());
            assertFalse(captured.segments().isEmpty());
        } finally {
            CompanionSettings.get().getState().includeNearbyCode = false;
        }
    }

    /** 实际文件切换与选区事件更新终端状态，关闭文件后不能继续显示旧文件。 */
    public void testLiveStatusFollowsEditorSelectionAndClearsAfterClosingFile() throws Exception {
        Path path = root.resolve("live-status.txt");
        Files.writeString(path, "one\ntwo\nthree\n");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject());
        var opened =
                manager.openTextEditor(
                        new com.intellij.openapi.fileEditor.OpenFileDescriptor(getProject(), file),
                        false);
        assertNotNull(opened);
        service.start();
        // 等待真实平台事件和后台路径校验，断言用户看得到的状态，而不是测试伪造缓存。
        awaitStatus("live-status.txt");
        opened.getSelectionModel().setSelection(0, 8);
        awaitStatus("已选中 2 行");
        opened.getSelectionModel().removeSelection();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.contextStatus().contains("已选中") && System.nanoTime() < deadline) {
            com.intellij.testFramework.PlatformTestUtil
                    .dispatchAllInvocationEventsInIdeEventQueue();
            Thread.sleep(10);
        }
        assertFalse(service.contextStatus().contains("已选中"));
        manager.closeFile(file);
        awaitStatus("没有可发送的当前文件");
    }

    /**
     * 在有界等待中处理真实界面事件，超时给出最后状态。
     *
     * @param expected 终端提示中应出现的文件路径或选区状态文字
     */
    private void awaitStatus(String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!service.contextStatus().contains(expected) && System.nanoTime() < deadline) {
            com.intellij.testFramework.PlatformTestUtil
                    .dispatchAllInvocationEventsInIdeEventQueue();
            Thread.sleep(10);
        }
        var selected =
                com.intellij
                        .openapi
                        .fileEditor
                        .FileEditorManager
                        .getInstance(getProject())
                        .getSelectedTextEditor();
        assertTrue(
                service.contextStatus()
                        + "; error="
                        + service.lastError
                        + "; editor="
                        + (selected == null ? "null" : selected.getEditorKind()),
                service.contextStatus().contains(expected));
    }

    /** 未保存随机标记必须来自 Document，磁盘不应被保存动作污染。 */
    public void testUnsavedSelectionIsExactAndDiskStaysUnchanged() throws Exception {
        Path path = root.resolve("context.txt");
        Files.writeString(path, "DISK_ONLY\n");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        editor =
                EditorFactory.getInstance()
                        .createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        String nonce = "IDEA_UNSAVED_" + UUID.randomUUID();
        WriteCommandAction.runWriteCommandAction(
                getProject(), () -> document.setText(nonce + "\n"));
        editor.getSelectionModel().setSelection(0, nonce.length());
        var raw = service.capture(editor, "SELECTION_SNAPSHOT");
        List<Attachment> captured;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            captured = executor.submit(() -> service.attachments(raw)).get(5, TimeUnit.SECONDS);
        }
        assertEquals(nonce, captured.getFirst().content());
        assertTrue(captured.getFirst().unsaved());
        assertFalse(Files.readString(path).contains(nonce));
        WriteCommandAction.runWriteCommandAction(
                getProject(), () -> document.setText("CHANGED_AFTER_CAPTURE\n"));
        assertEquals(nonce, captured.getFirst().content());
        var key = new SessionStore.Key("terminal", "session");
        service.sessions.register(key.terminalId(), key.sessionId(), root.toString());
        service.sessions.add(key, captured);
        var result =
                service.hook(
                        "terminal",
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                "session",
                                "turn_id",
                                "turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "fixture"));
        assertTrue(result.output().contains(nonce));
        assertFalse(result.output().contains("CHANGED_AFTER_CAPTURE"));
    }

    /** 关闭联动以后不可继续建立凭证，撤销服务删除旧终端凭证。 */
    public void testLocalProjectWorksWithoutAuthorizationAndGlobalDisableRejectsBinding()
            throws Exception {
        bridge = new BridgeApplicationService();
        BridgeApplicationService.Binding binding;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            binding = executor.submit(() -> bridge.createBinding(service)).get(5, TimeUnit.SECONDS);
        }
        assertTrue(service.allowed());
        assertTrue(Files.exists(binding.descriptor()));
        CompanionSettings.get().getState().integrationEnabled = false;
        assertFalse(service.allowed());
        assertThrows(java.io.IOException.class, () -> bridge.createBinding(service));
        bridge.revoke(service);
        assertFalse(Files.exists(binding.descriptor()));
    }

    /** 模块仅含子目录时，项目顶层脚本与启动工作目录仍可用，敏感目录不能换根放行。 */
    public void testModuleContentRootsDoNotExcludeProjectRootOrBypassSensitiveDirectories()
            throws Exception {
        Path moduleRoot = Files.createDirectories(root.resolve("module"));
        Path script = root.resolve("gradlew");
        Files.writeString(script, "# public fixture\n");
        Path hiddenRoot = Files.createDirectories(root.resolve(".codex"));
        Path hidden = hiddenRoot.resolve("notes.txt");
        Files.writeString(hidden, "fixture");
        com.intellij.testFramework.PsiTestUtil.addContentRoot(
                getModule(), LocalFileSystem.getInstance().refreshAndFindFileByNioFile(moduleRoot));
        com.intellij.testFramework.PsiTestUtil.addContentRoot(
                getModule(), LocalFileSystem.getInstance().refreshAndFindFileByNioFile(hiddenRoot));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(service::reloadRoots).get(5, TimeUnit.SECONDS);
        }
        assertEquals(root, service.pathPolicy().validateCwd(root.toString()));
        assertEquals("gradlew", service.pathPolicy().identify(script).getValue());
        assertThrows(java.io.IOException.class, () -> service.pathPolicy().identify(hidden));
    }

    /** 原生独立 Hook 进程获得真实服务快照，交接后仅标记本地完成。 */
    public void testRealHookClientAndAuthenticatedServerDeliverFrozenSnapshot() throws Exception {
        Path path = root.resolve("hook-context.txt");
        Files.writeString(path, "DISK_ONLY\n");
        var identity = service.pathPolicy().identify(path);
        String nonce = "IDEA_UNSAVED_" + UUID.randomUUID();
        var item =
                Attachment.snapshot(
                        identity.getKey(),
                        identity.getValue(),
                        "SELECTION_SNAPSHOT",
                        nonce,
                        0,
                        nonce.length(),
                        1,
                        true);
        bridge = new BridgeApplicationService();
        BridgeApplicationService.Binding binding;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            binding = executor.submit(() -> bridge.createBinding(service)).get(5, TimeUnit.SECONDS);
        }
        Endpoint endpoint = Endpoint.load(binding.descriptor().toString());
        endpoint.post(
                "/v1/hooks",
                Json.GSON
                        .toJson(
                                Json.object(
                                        "hook_event_name",
                                        "SessionStart",
                                        "session_id",
                                        "real-hook",
                                        "cwd",
                                        root.toString()))
                        .getBytes(StandardCharsets.UTF_8),
                null,
                600);
        var key = new SessionStore.Key(binding.terminalId(), "real-hook");
        service.sessions.add(key, List.of(item));
        Path jar = root.resolve("test-bridge-client.jar");
        try (var input = getClass().getResourceAsStream("/bridge/bridge-client.jar")) {
            assertNotNull(input);
            Files.write(jar, input.readAllBytes());
        }
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-XX:TieredStopAtLevel=1",
                        "-jar",
                        jar.toString(),
                        "submit");
        builder.environment().put("ICB_ENDPOINT_FILE", binding.descriptor().toString());
        Process client = builder.start();
        client.getOutputStream()
                .write(
                        Json.GSON
                                .toJson(
                                        Json.object(
                                                "hook_event_name",
                                                "UserPromptSubmit",
                                                "session_id",
                                                "real-hook",
                                                "turn_id",
                                                "real-turn",
                                                "cwd",
                                                root.toString(),
                                                "prompt",
                                                "fixture"))
                                .getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().close();
        assertTrue(client.waitFor(3, TimeUnit.SECONDS));
        String output = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(Json.parse(output).has("hookSpecificOutput"));
        assertTrue(output.contains(nonce));
        assertFalse(Files.readString(path).contains(nonce));
        assertEquals("HANDED_TO_CLI", service.sessions.require(key).turns.get("real-turn").state);
        bridge.revoke(service);
        assertThrows(
                java.io.IOException.class,
                () -> endpoint.post("/v1/health", "{}".getBytes(), null, 600));
    }

    /** 漏收启动回调时提交仍可登记本终端，会话之外的本地待绑定引用不被自动消费。 */
    public void testSubmitRegistersMissingSessionWithoutTakingUnassignedReferences()
            throws Exception {
        Files.writeString(root.resolve("pending.txt"), "disk");
        service.unassigned.add(
                Attachment.path(
                        service.pathPolicy().identify(root.resolve("pending.txt")).getKey(),
                        "pending.txt"));
        var output =
                service.hook(
                        "terminal-new",
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                "lazy-session",
                                "turn_id",
                                "lazy-turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "test"));
        assertFalse(Json.parse(output.output()).has("decision"));
        assertEquals(
                root.toString(),
                service.sessions.require(new SessionStore.Key("terminal-new", "lazy-session")).cwd);
        assertEquals(1, service.unassigned.size());
    }

    /** 根外工作目录不附加项目上下文，也不阻止用户继续使用 Codex。 */
    public void testOutsideWorkingDirectoryWarnsWithoutBlockingCodex() throws Exception {
        var output =
                service.hook(
                        "terminal-new",
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                "outside-session",
                                "turn_id",
                                "outside-turn",
                                "cwd",
                                root.getParent().toString(),
                                "prompt",
                                "test"));
        var response = Json.parse(output.output());
        assertTrue(response.get("systemMessage").getAsString().contains("继续使用 Codex"));
        assertFalse(response.has("decision"));
        assertFalse(response.has("hookSpecificOutput"));
        assertNull(output.batchId());
        assertTrue(service.sessions.list().isEmpty());
    }

    /** 已插入草稿的引用文件不可用时保留队列，不附加正文，也不阻断普通输入。 */
    public void testUnavailableDraftReferenceWarnsAndRemainsQueued() throws Exception {
        Path file = root.resolve("unavailable.txt");
        Files.writeString(file, "disk");
        var identity = service.pathPolicy().identify(file);
        var key = new SessionStore.Key("terminal", "unavailable-session");
        service.sessions.register(key.terminalId(), key.sessionId(), root.toString());
        var item = Attachment.path(identity.getKey(), identity.getValue());
        assertTrue(
                service.sendDraftReferences(
                        key, List.of(item), List.of("@unavailable.txt"), () -> true));
        Files.delete(file);
        var output =
                service.hook(
                        key.terminalId(),
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                key.sessionId(),
                                "turn_id",
                                "unavailable-turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "hi @unavailable.txt"));
        var response = Json.parse(output.output());
        assertTrue(response.get("systemMessage").getAsString().contains("继续使用 Codex"));
        assertFalse(response.has("decision"));
        assertFalse(response.has("hookSpecificOutput"));
        assertNull(output.batchId());
        assertEquals(List.of(item), service.sessions.require(key).queued);
        assertTrue(service.sessions.require(key).turns.isEmpty());
        assertFalse(service.sessions.require(key).inTurn);
        // 同一引用恢复可用后，只有草稿仍保留标记的下一次提交才正常交接。
        Files.writeString(file, "restored");
        var retry =
                service.hook(
                        key.terminalId(),
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                key.sessionId(),
                                "turn_id",
                                "restored-turn",
                                "cwd",
                                root.toString(),
                                "prompt",
                                "hi @unavailable.txt"));
        assertTrue(Json.parse(retry.output()).has("hookSpecificOutput"));
        assertNotNull(retry.batchId());
        assertTrue(service.sessions.require(key).queued.isEmpty());
    }

    /** 外部磁盘修改保持未保存文档，并为两种真实基线分别生成只读记录。 */
    public void testDiffPreservesUnsavedDocumentAndKeepsBaselineSourcesSeparate() throws Exception {
        Path path = root.resolve("diff.txt");
        Files.writeString(path, "DISK_BEFORE");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(
                getProject(), () -> document.setText("EDITOR_UNSAVED"));
        editor =
                EditorFactory.getInstance()
                        .createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        var captured = service.capture(editor, "FILE_SNAPSHOT");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> service.attachments(captured)).get(3, TimeUnit.SECONDS);
        }
        var identity = service.pathPolicy().identify(path);
        var key = new SessionStore.Key("terminal", "diff-session");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(
                            () ->
                                    service.diffs.captureBefore(
                                            key,
                                            "diff-turn",
                                            List.of(
                                                    Attachment.path(
                                                            identity.getKey(),
                                                            identity.getValue())),
                                            null))
                    .get(3, TimeUnit.SECONDS);
            Files.writeString(path, "DISK_AFTER");
            executor.submit(() -> service.diffs.finish(key, "diff-turn")).get(3, TimeUnit.SECONDS);
        }
        assertEquals("EDITOR_UNSAVED", document.getText());
        var reviews = service.diffs.list();
        assertEquals(2, reviews.size());
        assertTrue(
                reviews.stream()
                        .anyMatch(
                                review ->
                                        review.origin().equals("DISK_BEFORE_TURN")
                                                && review.left().equals("DISK_BEFORE")));
        assertTrue(
                reviews.stream()
                        .anyMatch(
                                review ->
                                        review.origin().equals("EDITOR_SNAPSHOT")
                                                && review.left().equals("EDITOR_UNSAVED")));
        assertTrue(reviews.stream().allMatch(review -> review.right().equals("DISK_AFTER")));
    }

    /** 文件删除生成带明确信息的只读比较，不将缺失文件冒充成功读取的空文件。 */
    public void testDeletedFileIsRecordedAsDeletion() throws Exception {
        Path path = root.resolve("delete.txt");
        Files.writeString(path, "BEFORE_DELETE");
        var identity = service.pathPolicy().identify(path);
        var key = new SessionStore.Key("terminal", "delete-session");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(
                    "BEFORE_DELETE",
                    executor.submit(() -> service.readDisk(identity.getKey(), identity.getValue()))
                            .get(3, TimeUnit.SECONDS));
            executor.submit(
                            () ->
                                    service.diffs.captureBefore(
                                            key,
                                            "delete-turn",
                                            List.of(
                                                    Attachment.path(
                                                            identity.getKey(),
                                                            identity.getValue())),
                                            null))
                    .get(3, TimeUnit.SECONDS);
            Files.delete(path);
            executor.submit(() -> service.diffs.finish(key, "delete-turn"))
                    .get(3, TimeUnit.SECONDS);
        }
        assertEquals(1, service.diffs.list().size());
        var review = service.diffs.list().getFirst();
        assertTrue(review.deleted());
        assertEquals("BEFORE_DELETE", review.left());
        assertEquals("", review.right());
    }

    /** 两个本地应用服务即使复用同一项目路径，实例凭证也不能混用。 */
    public void testCredentialsCannotCrossApplicationInstances() throws Exception {
        bridge = new BridgeApplicationService();
        var second = new BridgeApplicationService();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstBinding =
                    executor.submit(() -> bridge.createBinding(service)).get(3, TimeUnit.SECONDS);
            var secondBinding =
                    executor.submit(() -> second.createBinding(service)).get(3, TimeUnit.SECONDS);
            Properties first = new Properties(), other = new Properties();
            first.load(new java.io.StringReader(Files.readString(firstBinding.descriptor())));
            other.load(new java.io.StringReader(Files.readString(secondBinding.descriptor())));
            Path copied =
                    firstBinding.descriptor().getParent().resolve("wrong-instance.properties");
            first.setProperty("origin", other.getProperty("origin"));
            try (var writer = Files.newBufferedWriter(copied)) {
                first.store(writer, "test-only");
            }
            Files.setPosixFilePermissions(
                    copied, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try {
                assertThrows(
                        java.io.IOException.class,
                        () ->
                                Endpoint.load(copied.toString())
                                        .post(
                                                "/v1/health",
                                                "{}".getBytes(StandardCharsets.UTF_8),
                                                null,
                                                600));
            } finally {
                Files.delete(copied);
            }
        } finally {
            second.dispose();
        }
    }

    /** 已关闭的应用服务不可继续创建终端文件，即使项目对象仍然存在。 */
    public void testDisposedApplicationRejectsNewBindings() throws Exception {
        bridge = new BridgeApplicationService();
        bridge.dispose();
        assertThrows(java.io.IOException.class, () -> bridge.createBinding(service));
    }

    /** 在真实 IDEA 服务中测量提交处理和新 Java 回调进程的延迟；不冒充原生 Codex 或模型验收。 */
    public void testLocalServiceAndColdHookPerformanceBudgets() throws Exception {
        Path path = root.resolve("latency.txt");
        Files.writeString(path, "DISK_ONLY");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        editor =
                EditorFactory.getInstance()
                        .createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        var raw = service.capture(editor, "FILE_SNAPSHOT");
        bridge = new BridgeApplicationService();
        long[] serverTimes = new long[30], hookTimes = new long[30];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> service.attachments(raw)).get(3, TimeUnit.SECONDS);
            var binding =
                    executor.submit(() -> bridge.createBinding(service)).get(3, TimeUnit.SECONDS);
            var identity = service.pathPolicy().identify(path);
            var key = new SessionStore.Key(binding.terminalId(), "latency-session");
            service.sessions.register(key.terminalId(), key.sessionId(), root.toString());
            Path jar = root.resolve("latency-bridge.jar");
            try (var input = getClass().getResourceAsStream("/bridge/bridge-client.jar")) {
                assertNotNull(input);
                Files.write(jar, input.readAllBytes());
            }
            for (int sample = 0; sample < 30; sample++) {
                String marker = "latency-fixture-" + sample;
                service.sessions.add(
                        key,
                        List.of(
                                Attachment.snapshot(
                                        identity.getKey(),
                                        identity.getValue(),
                                        "SELECTION_SNAPSHOT",
                                        marker,
                                        0,
                                        marker.length(),
                                        1,
                                        true)));
                var event =
                        Json.object(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "session_id",
                                key.sessionId(),
                                "turn_id",
                                "direct-" + sample,
                                "cwd",
                                root.toString(),
                                "prompt",
                                "fixture");
                long started = System.nanoTime();
                var result =
                        executor.submit(() -> service.hook(key.terminalId(), event))
                                .get(3, TimeUnit.SECONDS);
                serverTimes[sample] = System.nanoTime() - started;
                assertTrue(result.output().contains(marker));
                service.sessions.add(
                        key,
                        List.of(
                                Attachment.snapshot(
                                        identity.getKey(),
                                        identity.getValue(),
                                        "SELECTION_SNAPSHOT",
                                        marker,
                                        0,
                                        marker.length(),
                                        1,
                                        true)));
                event.addProperty("turn_id", "cold-" + sample);
                var builder =
                        new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-XX:TieredStopAtLevel=1",
                                "-jar",
                                jar.toString(),
                                "submit");
                builder.environment().put("ICB_ENDPOINT_FILE", binding.descriptor().toString());
                started = System.nanoTime();
                Process child = builder.start();
                child.getOutputStream()
                        .write(Json.GSON.toJson(event).getBytes(StandardCharsets.UTF_8));
                child.getOutputStream().close();
                assertTrue(child.waitFor(3, TimeUnit.SECONDS));
                hookTimes[sample] = System.nanoTime() - started;
                assertEquals(0, child.exitValue());
                assertTrue(
                        new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                                .contains(marker));
            }
        }
        Arrays.sort(serverTimes);
        Arrays.sort(hookTimes);
        double serverP95 = serverTimes[28] / 1000000.0, hookP95 = hookTimes[28] / 1000000.0;
        var report =
                Json.object(
                        "scope",
                        "IDEA service and fresh Java bridge process; excludes native Codex TUI and model",
                        "javaArguments",
                        "-XX:TieredStopAtLevel=1",
                        "samplesPerPath",
                        30,
                        "serviceP95Millis",
                        serverP95,
                        "coldBridgeP95Millis",
                        hookP95,
                        "serviceBudgetMillis",
                        50,
                        "coldBridgeBudgetMillis",
                        500);
        Path target = Path.of(System.getProperty("icb.performanceReport"));
        Files.createDirectories(target.getParent());
        Files.writeString(target, Json.GSON.toJson(report));
        assertTrue("service p95=" + serverP95, serverP95 < 50);
        assertTrue("cold bridge p95=" + hookP95, hookP95 < 500);
    }

    /** 真实多光标捕获只包含各自选区，不混入中间未选择的代码。 */
    public void testRealMultipleCaretsCaptureOnlySelectedSegments() throws Exception {
        Path path = root.resolve("multi.txt");
        Files.writeString(path, "first\nmiddle\nlast");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        editor =
                EditorFactory.getInstance()
                        .createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        editor.getCaretModel().getPrimaryCaret().setSelection(0, 5);
        var second = editor.getCaretModel().addCaret(editor.offsetToVisualPosition(17));
        assertNotNull(second);
        second.setSelection(13, 17);
        var raw = service.capture(editor, "SELECTION_SNAPSHOT");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var items = executor.submit(() -> service.attachments(raw)).get(3, TimeUnit.SECONDS);
            assertEquals(2, items.size());
            assertEquals("first", items.get(0).content());
            assertEquals("last", items.get(1).content());
        }
    }

    /** 工具没有未保存读取许可时使用真实磁盘版本，开启许可后才返回内存文档。 */
    public void testDocumentToolRespectsUnsavedReadPermission() throws Exception {
        Path path = root.resolve("tool.txt");
        Files.writeString(path, "TOOL_DISK");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(
                getProject(), () -> document.setText("TOOL_UNSAVED"));
        var identity = service.pathPolicy().identify(path);
        var request =
                Json.object(
                        "name",
                        "ide_read_document",
                        "arguments",
                        Json.object(
                                "rootId", identity.getKey(), "relativePath", identity.getValue()));
        var state = CompanionSettings.get().getState();
        boolean previous = state.allowUnsavedMcpRead;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            state.allowUnsavedMcpRead = false;
            var disk = executor.submit(() -> service.tool(request)).get(3, TimeUnit.SECONDS);
            assertFalse(disk.get("isError").getAsBoolean());
            var diskBody =
                    Json.parse(
                            disk.getAsJsonArray("content")
                                    .get(0)
                                    .getAsJsonObject()
                                    .get("text")
                                    .getAsString());
            assertEquals("DISK", diskBody.get("source").getAsString());
            assertEquals("TOOL_DISK", diskBody.get("content").getAsString());
            state.allowUnsavedMcpRead = true;
            var memory = executor.submit(() -> service.tool(request)).get(3, TimeUnit.SECONDS);
            assertFalse(memory.get("isError").getAsBoolean());
            var memoryBody =
                    Json.parse(
                            memory.getAsJsonArray("content")
                                    .get(0)
                                    .getAsJsonObject()
                                    .get("text")
                                    .getAsString());
            assertEquals("EDITOR_SNAPSHOT", memoryBody.get("source").getAsString());
            assertEquals("TOOL_UNSAVED", memoryBody.get("content").getAsString());
            request.getAsJsonObject("arguments").addProperty("projectId", "another-project");
            assertTrue(
                    executor.submit(() -> service.tool(request))
                            .get(3, TimeUnit.SECONDS)
                            .get("isError")
                            .getAsBoolean());
        } finally {
            state.allowUnsavedMcpRead = previous;
        }
        assertEquals("TOOL_UNSAVED", document.getText());
        assertEquals("TOOL_DISK", Files.readString(path));
    }

    /** 缺少终端身份时返回认证失败，不抛出空值异常或访问任何项目内容。 */
    public void testMissingTerminalHeaderIsRejected() throws Exception {
        bridge = new BridgeApplicationService();
        BridgeApplicationService.Binding binding;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            binding = executor.submit(() -> bridge.createBinding(service)).get(3, TimeUnit.SECONDS);
        }
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(Files.readString(binding.descriptor())));
        var connection =
                (java.net.HttpURLConnection)
                        java.net
                                .URI
                                .create(properties.getProperty("origin") + "/v1/health")
                                .toURL()
                                .openConnection(java.net.Proxy.NO_PROXY);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-ICB-Protocol", "1");
        connection.setRequestProperty("X-ICB-Instance-Id", bridge.instanceId);
        connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(401, connection.getResponseCode());
        connection.disconnect();
        assertFalse(service.hookObserved);
    }

    /** 浏览器 Origin 与错误绑定在解析上下文前拒绝。 */
    public void testBrowserOriginIsRejectedBeforeContextDispatch() throws Exception {
        bridge = new BridgeApplicationService();
        BridgeApplicationService.Binding binding;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            binding = executor.submit(() -> bridge.createBinding(service)).get(5, TimeUnit.SECONDS);
        }
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(Files.readString(binding.descriptor())));
        var url = java.net.URI.create(properties.getProperty("origin") + "/v1/health").toURL();
        var connection = (java.net.HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Origin", "https://example.test");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, connection.getResponseCode());
        connection.disconnect();
        assertFalse(service.hookObserved);
    }
}
