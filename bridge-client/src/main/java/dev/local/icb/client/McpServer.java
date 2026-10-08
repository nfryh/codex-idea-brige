// 换行 JSON-RPC 标准输入输出适配，不把内部 HTTP 端点暴露为 MCP URL。
package dev.local.icb.client;

import com.google.gson.*;

import dev.local.icb.contract.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 实现基础握手、工具调用、取消、半包和退出清理。 */
public final class McpServer {
    private final String descriptor;
    private Endpoint endpoint;
    private String connectionId;
    private boolean initialized;
    private boolean ready;
    private final Set<String> usedIds = new HashSet<>();
    private final ConcurrentMap<String, Future<?>> active = new ConcurrentHashMap<>();

    /**
     * 创建连接级工具进程。
     *
     * @param descriptor 来自终端环境的连接文件路径；null 表示 IDEA 不可用
     */
    public McpServer(String descriptor) {
        this.descriptor = descriptor;
    }

    /** 读取换行帧，工具调用在可取消后台任务中执行。 */
    public void run(InputStream input, OutputStream output) throws IOException {
        PrintWriter writer =
                new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                while (true) {
                    // 字节级分帧保持 1 MiB 上限，不使用无限长 readLine。
                    byte[] line = line(input);
                    if (line == null) break;
                    if (line.length == 0) continue;
                    JsonObject request;
                    try {
                        request = Json.parse(new String(line, StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException ex) {
                        write(writer, rpcError(JsonNull.INSTANCE, -32700, "Parse error"));
                        continue;
                    }
                    JsonElement id = request.get("id");
                    String method;
                    try {
                        if (!request.has("jsonrpc")
                                || !request.get("jsonrpc").getAsString().equals("2.0"))
                            throw new IllegalArgumentException();
                        method = Json.required(request, "method");
                    } catch (RuntimeException ex) {
                        write(
                                writer,
                                rpcError(
                                        id == null ? JsonNull.INSTANCE : id,
                                        -32600,
                                        "Invalid Request"));
                        continue;
                    }
                    if (request.has("params") && !request.get("params").isJsonObject()) {
                        if (id != null) write(writer, rpcError(id, -32602, "Invalid parameters"));
                        continue;
                    }
                    JsonObject params =
                            request.has("params") && request.get("params").isJsonObject()
                                    ? request.getAsJsonObject("params")
                                    : new JsonObject();
                    if (id == null) {
                        if (method.equals("notifications/initialized") && initialized) ready = true;
                        if (method.equals("notifications/cancelled") && params.has("requestId")) {
                            Future<?> pending = active.remove(params.get("requestId").toString());
                            if (pending != null) pending.cancel(true);
                        }
                        continue;
                    }
                    if (!id.isJsonPrimitive()
                            || (!id.getAsJsonPrimitive().isString()
                                    && !id.getAsJsonPrimitive().isNumber())
                            || id.toString().length() > 128
                            || usedIds.size() >= 10000
                            || !usedIds.add(id.toString())) {
                        write(writer, rpcError(id, -32600, "Duplicate or invalid request id"));
                        continue;
                    }
                    if (method.equals("tools/call") && ready) {
                        if (active.size() >= 8) {
                            write(
                                    writer,
                                    Json.object(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            id,
                                            "result",
                                            ToolCatalog.error("ICB_BUSY")));
                            continue;
                        }
                        FutureTask<Void> task =
                                new FutureTask<>(
                                        () -> {
                                            JsonObject result;
                                            try {
                                                result = call(params);
                                            } catch (IOException | IllegalArgumentException ex) {
                                                result = ToolCatalog.error("IDE_UNAVAILABLE");
                                            }
                                            if (!Thread.currentThread().isInterrupted())
                                                write(
                                                        writer,
                                                        Json.object(
                                                                "jsonrpc", "2.0", "id", id,
                                                                "result", result));
                                            active.remove(id.toString());
                                            return null;
                                        });
                        active.put(id.toString(), task);
                        executor.execute(task);
                        continue;
                    }
                    try {
                        JsonObject result;
                        switch (method) {
                            case "initialize" -> {
                                if (initialized)
                                    throw new IllegalStateException("Already initialized");
                                String protocol = Json.required(params, "protocolVersion");
                                if (!Set.of("2025-11-25", "2025-06-18", "2024-11-05")
                                        .contains(protocol))
                                    throw new IllegalStateException("Unsupported protocol version");
                                if (descriptor != null && !descriptor.isBlank()) {
                                    try {
                                        endpoint = Endpoint.load(descriptor);
                                        JsonObject binding =
                                                Json.parse(
                                                        new String(
                                                                endpoint.post(
                                                                                "/v1/connections/open",
                                                                                Json.GSON
                                                                                        .toJson(
                                                                                                Json
                                                                                                        .object(
                                                                                                                "clientVersion",
                                                                                                                "1.0.0",
                                                                                                                "protocolVersion",
                                                                                                                protocol))
                                                                                        .getBytes(
                                                                                                StandardCharsets
                                                                                                        .UTF_8),
                                                                                null,
                                                                                600)
                                                                        .body(),
                                                                StandardCharsets.UTF_8));
                                        connectionId = Json.required(binding, "connectionId");
                                    } catch (IOException | IllegalArgumentException ex) {
                                        endpoint = null;
                                    }
                                }
                                initialized = true;
                                result =
                                        Json.object(
                                                "protocolVersion",
                                                protocol,
                                                "capabilities",
                                                Json.object(
                                                        "tools", Json.object("listChanged", false)),
                                                "serverInfo",
                                                Json.object(
                                                        "name",
                                                        "idea-companion",
                                                        "version",
                                                        "1.0.0"));
                            }
                            case "ping" -> result = new JsonObject();
                            case "tools/list" -> {
                                if (!ready) throw new IllegalStateException("Not initialized");
                                result = Json.object("tools", ToolCatalog.tools());
                            }
                            case "tools/call" -> throw new IllegalStateException("Not initialized");
                            default -> {
                                write(writer, rpcError(id, -32601, "Method not found"));
                                continue;
                            }
                        }
                        write(writer, Json.object("jsonrpc", "2.0", "id", id, "result", result));
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        write(writer, rpcError(id, -32602, "Invalid state or parameters"));
                    }
                }
            } finally {
                // 输入超量或读取失败也必须取消工具，不能在执行器关闭时无限等候后台任务。
                active.values().forEach(task -> task.cancel(true));
                active.clear();
                executor.shutdownNow();
            }
        } finally {
            if (endpoint != null && connectionId != null) {
                try {
                    endpoint.post(
                            "/v1/connections/close",
                            Json.GSON
                                    .toJson(Json.object("connectionId", connectionId))
                                    .getBytes(StandardCharsets.UTF_8),
                            null,
                            200);
                } catch (IOException ex) {
                    System.err.println("ICB_MCP_CLOSE_UNCONFIRMED");
                }
            }
        }
    }

    /** 工具请求不接受模型重新指定项目或会话身份。 */
    private JsonObject call(JsonObject params) throws IOException {
        if (endpoint == null || connectionId == null) return ToolCatalog.error("IDE_UNAVAILABLE");
        JsonObject request =
                Json.object(
                        "name",
                        Json.required(params, "name"),
                        "arguments",
                        params.has("arguments") ? params.get("arguments") : new JsonObject(),
                        "connectionId",
                        connectionId);
        return Json.parse(
                new String(
                        endpoint.post(
                                        "/v1/tools/call",
                                        Json.GSON.toJson(request).getBytes(StandardCharsets.UTF_8),
                                        null,
                                        600)
                                .body(),
                        StandardCharsets.UTF_8));
    }

    /**
     * 拼装协议错误，不暴露内部异常。
     *
     * @param code -32700 为解析错误，-32600 为请求错误，-32601 为方法不存在，-32602 为参数或状态错误
     * @param message 固定英文协议错误说明
     */
    private static JsonObject rpcError(JsonElement id, int code, String message) {
        return Json.object(
                "jsonrpc", "2.0", "id", id, "error", Json.object("code", code, "message", message));
    }

    /** 在单一写锁下输出完整消息，避免并发工具结果交错。 */
    private static void write(PrintWriter writer, JsonObject message) {
        synchronized (writer) {
            writer.println(Json.GSON.toJson(message));
        }
    }

    /** 读取一个有界换行帧；文件结束时仍处理最后一帧。 */
    private static byte[] line(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (result.size() >= 1048576) throw new IOException("ICB_CONTEXT_TOO_LARGE");
            result.write(value);
        }
        return value == -1 && result.size() == 0 ? null : result.toByteArray();
    }
}
