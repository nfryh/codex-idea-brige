// 通过本机已有 Codex、真实原生回调和实际 IDEA 服务验证未保存选区交接，不调用外部模型。
package dev.local.icb.core;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.sun.net.httpserver.HttpServer;

import dev.local.icb.contract.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 把真实编辑器冻结快照送入原生 Codex 的模型请求；本地测试服务只检查标记并返回固定文本。 */
public class NativeTerminalRoundTripTest extends HeavyPlatformTestCase {
    @Override
    protected com.intellij.testFramework.OpenProjectTaskBuilder getOpenProjectOptions() {
        return super.getOpenProjectOptions().runPostStartUpActivities(false);
    }

    /** 只输入 codex 后启动与工具连接生效；原生提交使用冻结选区而不是磁盘或后续修改。 */
    public void testPlainCodexNativeHooksDeliverFrozenUnsavedSelectionToLocalFixture()
            throws Exception {
        // 通过真实原生终端验证正常引用交接。
        runNativeSubmission(false, false);
    }

    /** 在项目外启动时，原生 Codex 仍把用户输入交给本地模型服务。 */
    public void testOutsideProjectNativeHookDoesNotBlockModelRequest() throws Exception {
        // 使用项目外工作目录复现用户截图中的路径校验失败。
        runNativeSubmission(true, false);
    }

    /** 桥接服务在终端启动后关闭，原生 Codex 仍能提交普通问题。 */
    public void testDisconnectedBridgeDoesNotBlockNativeModelRequest() throws Exception {
        // 在提交前关闭实际桥接服务，保留终端中的旧连接信息。
        runNativeSubmission(false, true);
    }

    /**
     * 使用隔离配置和本地模型服务验证实际原生提交，不读取真实登录凭据。
     *
     * @param outsideProject true 表示在项目外启动 Codex，false 表示在当前测试项目启动
     * @param disconnectBridge true 表示提交前关闭桥接服务，false 表示保持服务运行
     */
    private void runNativeSubmission(boolean outsideProject, boolean disconnectBridge)
            throws Exception {
        Path cli = LocalCodexPaths.findExecutable();
        assertNotNull("本机原生验收需要已安装的 Codex", cli);
        Path root = Path.of(getProject().getBasePath());
        Files.createDirectories(root);
        // 原生进程通信套接字有路径长度限制，隔离配置采用短临时路径，项目路径保持真实平台值。
        Path home = Files.createTempDirectory(Path.of("/tmp"), "icb-native-home-");
        Path source = root.resolve("native-context.txt");
        Files.writeString(source, "DISK_ONLY_FIXTURE\n");
        String nonce = "IDEA_FROZEN_" + UUID.randomUUID();
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
        assertNotNull(file);
        var document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        Editor editor =
                EditorFactory.getInstance()
                        .createEditor(document, getProject(), EditorKind.MAIN_EDITOR);
        var service = new ProjectContextService(getProject());
        var bridge = new BridgeApplicationService();
        boolean previousAuto = CompanionSettings.get().getState().autoContextEnabled;
        CompanionSettings.get().getState().autoContextEnabled = false;
        var observed = new AtomicReference<com.google.gson.JsonObject>();
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService modelWork = Executors.newSingleThreadExecutor();
        model.setExecutor(modelWork);
        model.createContext(
                "/v1/responses",
                exchange -> {
                    try {
                        var request =
                                Json.parse(
                                        new String(
                                                Json.bounded(exchange.getRequestBody(), 1048576),
                                                StandardCharsets.UTF_8));
                        String input = Json.GSON.toJson(request.get("input"));
                        // 只保存布尔证据，不保存模型请求正文、提示词或任何认证头。
                        // 原生客户端可能另发会话命名请求，累计布尔证据，不让后续请求覆盖已经确认的主请求。
                        observed.updateAndGet(
                                previous ->
                                        Json.object(
                                                "frozenNonceReceived",
                                                input.contains(nonce)
                                                        || previous != null
                                                                && previous.get(
                                                                                "frozenNonceReceived")
                                                                        .getAsBoolean(),
                                                "changedDocumentReceived",
                                                input.contains("CHANGED_AFTER_SEND")
                                                        || previous != null
                                                                && previous.get(
                                                                                "changedDocumentReceived")
                                                                        .getAsBoolean(),
                                                "existingDraftReceived",
                                                input.contains("existing user draft")
                                                        || previous != null
                                                                && previous.get(
                                                                                "existingDraftReceived")
                                                                        .getAsBoolean(),
                                                "fileReferenceReceived",
                                                input.contains("@native-context.txt#L1")
                                                        || previous != null
                                                                && previous.get(
                                                                                "fileReferenceReceived")
                                                                        .getAsBoolean()));
                        var item =
                                Json.object(
                                        "type",
                                        "message",
                                        "id",
                                        "msg_fixture",
                                        "status",
                                        "completed",
                                        "role",
                                        "assistant",
                                        "phase",
                                        "final_answer",
                                        "content",
                                        List.of(
                                                Json.object(
                                                        "type",
                                                        "output_text",
                                                        "text",
                                                        "fixture complete",
                                                        "annotations",
                                                        List.of(),
                                                        "logprobs",
                                                        List.of())));
                        var response =
                                Json.object(
                                        "id",
                                        "resp_fixture",
                                        "object",
                                        "response",
                                        "status",
                                        "completed",
                                        "model",
                                        "fixture-model",
                                        "output",
                                        List.of(item),
                                        "usage",
                                        Json.object(
                                                "input_tokens",
                                                1,
                                                "output_tokens",
                                                1,
                                                "total_tokens",
                                                2,
                                                "input_tokens_details",
                                                Json.object("cached_tokens", 0)));
                        String stream =
                                "event: response.output_item.done\ndata: "
                                        + Json.GSON.toJson(
                                                Json.object(
                                                        "type",
                                                        "response.output_item.done",
                                                        "output_index",
                                                        0,
                                                        "sequence_number",
                                                        0,
                                                        "item",
                                                        item))
                                        + "\n\nevent: response.completed\ndata: "
                                        + Json.GSON.toJson(
                                                Json.object(
                                                        "type",
                                                        "response.completed",
                                                        "sequence_number",
                                                        1,
                                                        "response",
                                                        response))
                                        + "\n\n";
                        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        model.start();
        Process controller = null;
        try {
            // 平台根和绑定只属于隔离测试项目，不能借用用户真实终端凭证。
            BridgeApplicationService.Binding binding;
            NativeCodexLauncher.Prepared launcher;
            try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                worker.submit(service::reloadRoots).get(5, TimeUnit.SECONDS);
                binding =
                        worker.submit(() -> bridge.createBinding(service)).get(5, TimeUnit.SECONDS);
                launcher =
                        worker.submit(
                                        () ->
                                                NativeCodexLauncher.prepare(
                                                        binding.descriptor()
                                                                .getParent()
                                                                .resolve("bin"),
                                                        cli,
                                                        Map.of()))
                                .get(5, TimeUnit.SECONDS);
            }
            Files.writeString(
                    home.resolve("config.toml"),
                    "check_for_update_on_startup=false\nmodel_provider=\"fixture\"\nmodel=\"fixture-model\"\n[model_providers.fixture]\nname=\"Local fixture\"\nbase_url=\"http://127.0.0.1:"
                            + model.getAddress().getPort()
                            + "/v1\"\nwire_api=\"responses\"\nrequires_openai_auth=false\n");
            // 原生首次启动界面需要登录状态；仅在测试目录写入假数据，不读取真实认证文件。
            Files.writeString(
                    home.resolve("auth.json"),
                    "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-not-real\"}");
            Files.setPosixFilePermissions(
                    home.resolve("auth.json"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            byte[] jar;
            try (var input = getClass().getResourceAsStream("/bridge/bridge-client.jar")) {
                assertNotNull(input);
                jar = input.readAllBytes();
            }
            var installer = new InstallService();
            var plan =
                    installer.prepare(
                            home, cli, Path.of(System.getProperty("java.home"), "bin/java"), true);
            installer.apply(plan, jar);
            Path script = root.resolve("native_terminal_controller.py");
            try (var input = getClass().getResourceAsStream("/native_terminal_controller.py")) {
                assertNotNull(input);
                Files.write(script, input.readAllBytes());
            }
            controller =
                    new ProcessBuilder(
                                    "/usr/bin/python3",
                                    "-u",
                                    script.toString(),
                                    cli.toString(),
                                    home.toString(),
                                    outsideProject ? home.toString() : root.toString(),
                                    binding.descriptor().toString(),
                                    launcher.directory().toString())
                            .redirectErrorStream(true)
                            .start();
            BufferedWriter commands =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    controller.getOutputStream(), StandardCharsets.UTF_8));
            // 终端已经可以输入时先发送选区，不等待原生启动回调，也不先发一条初始化问题。
            Process startedController = controller;
            try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                var started =
                        worker.submit(
                                () -> {
                                    var signals =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            startedController.getInputStream(),
                                                            StandardCharsets.UTF_8));
                                    String line;
                                    while ((line = signals.readLine()) != null) {
                                        if (Json.parse(line).has("started")) return true;
                                    }
                                    return false;
                                });
                try {
                    assertTrue("原生终端未启动", started.get(15, TimeUnit.SECONDS));
                } catch (TimeoutException | InterruptedException ex) {
                    // 关闭子进程解除标准输出读取，避免超时后等待工作线程退出而卡住测试。
                    startedController.destroyForcibly();
                    started.cancel(true);
                    throw ex;
                }
            }
            Thread.sleep(1500);
            WriteCommandAction.runWriteCommandAction(
                    getProject(), () -> document.setText(nonce + "\n"));
            editor.getSelectionModel().setSelection(0, nonce.length());
            var raw = service.capture(editor, "SELECTION_SNAPSHOT");
            List<Attachment> items;
            try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                items = worker.submit(() -> service.attachments(raw)).get(5, TimeUnit.SECONDS);
            }
            assertTrue(
                    service.sendTerminalDraftReferences(
                            binding.terminalId(),
                            items,
                            List.of("@native-context.txt#L1"),
                            () -> {
                                try {
                                    commands.write(
                                            Json.GSON.toJson(
                                                            Json.object(
                                                                    "op",
                                                                    "paste",
                                                                    "text",
                                                                    "existing user draft @native-context.txt#L1 "))
                                                    + "\n");
                                    commands.flush();
                                    return true;
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                            }));
            // 模拟快捷发送后继续编辑，原生请求仍必须包含发送时冻结的版本。
            WriteCommandAction.runWriteCommandAction(
                    getProject(), () -> document.setText("CHANGED_AFTER_SEND\n"));
            if (disconnectBridge) bridge.dispose();
            commands.write("{\"op\":\"submit\"}\n");
            commands.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (observed.get() == null && controller.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertNotNull("原生 Codex 请求未到达本地模型夹具", observed.get());
            assertEquals(
                    !outsideProject && !disconnectBridge,
                    observed.get().get("frozenNonceReceived").getAsBoolean());
            assertFalse(observed.get().get("changedDocumentReceived").getAsBoolean());
            assertTrue(observed.get().get("existingDraftReceived").getAsBoolean());
            assertTrue(observed.get().get("fileReferenceReceived").getAsBoolean());
            assertEquals("DISK_ONLY_FIXTURE\n", Files.readString(source));
            if (outsideProject || disconnectBridge) {
                assertEquals(1, service.terminalDrafts().size());
                assertTrue(
                        service.sessions.list().values().stream()
                                .allMatch(session -> session.turns.isEmpty()));
                commands.write("{\"op\":\"close\"}\n");
                commands.flush();
                assertTrue(controller.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, controller.exitValue());
                return;
            }
            assertTrue("原生模型上下文协议连接未建立", service.mcpObserved);
            assertTrue(
                    "必须观察到真实启动回调，不能只凭收到任意回调推断",
                    service.receivedHookEvents.contains("SessionStart"));
            assertEquals(1, service.sessions.list().size());
            var key = service.sessions.list().keySet().iterator().next();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (service.sessions.require(key).inTurn && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertFalse("原生 Stop 回调未完成本轮状态", service.sessions.require(key).inTurn);
            commands.write("{\"op\":\"close\"}\n");
            commands.flush();
            assertTrue(controller.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, controller.exitValue());
            Path report = Path.of(System.getProperty("icb.nativeTerminalReport"));
            Files.createDirectories(report.getParent());
            observed.get()
                    .addProperty(
                            "nativeSessionStartReceived",
                            service.receivedHookEvents.contains("SessionStart"));
            observed.get()
                    .addProperty(
                            "nativeUserPromptSubmitReceived",
                            service.receivedHookEvents.contains("UserPromptSubmit"));
            observed.get()
                    .addProperty("nativeStopReceived", service.receivedHookEvents.contains("Stop"));
            observed.get().addProperty("nativeMcpConnected", service.mcpObserved);
            observed.get().addProperty("plainCodexEntryUsed", true);
            observed.get().addProperty("externalModelRequestSent", false);
            Files.writeString(report, Json.GSON.toJson(observed.get()));
        } finally {
            if (controller != null && controller.isAlive()) {
                controller.getOutputStream().close();
                if (!controller.waitFor(5, TimeUnit.SECONDS)) controller.destroyForcibly();
            }
            model.stop(0);
            modelWork.shutdownNow();
            bridge.dispose();
            Disposer.dispose(service);
            EditorFactory.getInstance().releaseEditor(editor);
            CompanionSettings.get().getState().autoContextEnabled = previousAuto;
            try (var files = Files.walk(home)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
