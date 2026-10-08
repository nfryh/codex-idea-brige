// IDEA 进程拥有的私有本地 HTTP 服务，项目关闭时撤销终端凭证。
package dev.local.icb.core;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.sun.net.httpserver.*;

import dev.local.icb.contract.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** 只监听 127.0.0.1 随机端口，不以请求路径选择项目。 */
@Service(Service.Level.APP)
public final class BridgeApplicationService implements Disposable {
    /** 当前 IDEA 实例唯一标识，用于隔离相同路径的多个窗口。 */
    public final String instanceId = UUID.randomUUID().toString();

    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Binding> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore admission = new Semaphore(40);
    private final Semaphore processing = new Semaphore(8);
    private LocalHttpServer server;
    private Path directory;

    /** true 表示应用服务已关闭，false 表示仍可创建本地终端凭证。 */
    private boolean closed;

    /**
     * 终端凭证仅存在内存和受限文件，不写日志。
     *
     * @param terminalId 本次新 Shell 的唯一标识
     * @param token 256 位随机认证秘密
     * @param descriptor 终端私有连接文件
     */
    public record Binding(
            String terminalId, String token, Path descriptor, ProjectContextService owner) {}

    /**
     * 仅按本实例已登记的描述文件定位终端，不在界面线程读取凭证文件。
     *
     * @param descriptor 终端启动环境中的描述文件路径，缺失时为 null
     */
    public String terminalFor(ProjectContextService owner, String descriptor) {
        if (descriptor == null || !owner.allowed()) return null;
        for (Binding binding : bindings.values())
            if (binding.owner == owner && binding.descriptor.toString().equals(descriptor))
                return binding.terminalId;
        return null;
    }

    /** 撤回准备失败的新终端，保留同项目的其他有效终端连接。 */
    public synchronized void revokeBinding(Binding binding) {
        if (bindings.remove(binding.terminalId, binding)) {
            connections.values().removeIf(connection -> connection == binding);
            try {
                Files.deleteIfExists(binding.descriptor);
            } catch (IOException ex) {
                binding.owner.lastError = "ICB_DESCRIPTOR_CLEANUP_FAILED";
            }
        }
        // 最后一个连接撤回后关闭监听；将来新终端可以重新创建服务。
        if (bindings.isEmpty() && server != null) {
            server.close();
            server = null;
        }
    }

    /** 创建终端私有连接文件，只由后台终端适配器调用。 */
    public synchronized Binding createBinding(ProjectContextService owner) throws IOException {
        if (closed) throw new IOException("ICB_BRIDGE_UNAVAILABLE");
        if (!owner.allowed()) throw new IOException("ICB_PROJECT_DENIED");
        if (server == null) {
            directory = Path.of(PathManager.getSystemPath(), "icb", instanceId);
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            server = new LocalHttpServer(this::handle);
            server.start();
        }
        String terminal = UUID.randomUUID().toString();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Path descriptor = directory.resolve("terminal-" + terminal + ".properties");
        String body =
                "protocol=1\norigin=http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "\ninstance_id="
                        + instanceId
                        + "\nproject_id="
                        + owner.projectId
                        + "\nterminal_id="
                        + terminal
                        + "\nauth_token="
                        + token
                        + "\n";
        boolean registered = false;
        try {
            Files.createFile(
                    descriptor,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(descriptor, body, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            if (!owner.allowed()) throw new IOException("ICB_PROJECT_DENIED");
            Binding binding = new Binding(terminal, token, descriptor, owner);
            bindings.put(terminal, binding);
            registered = true;
            return binding;
        } finally {
            // 文件写入或项目生命周期校验失败时，删除尚未注册的私有凭证文件。
            if (!registered) Files.deleteIfExists(descriptor);
        }
    }

    /** 验证每次请求全部绑定与传输限制，返回的错误不包含秘密。 */
    private void handle(HttpExchange exchange) throws IOException {
        if (!admission.tryAcquire()) {
            reply(exchange, 429, "{}", null);
            return;
        }
        ScheduledFuture<?> timeout =
                deadlines.schedule(exchange::close, 900, TimeUnit.MILLISECONDS);
        boolean entered = false;
        try {
            entered = processing.tryAcquire(200, TimeUnit.MILLISECONDS);
            if (!entered) {
                reply(exchange, 429, "{}", null);
                return;
            }
            Headers headers = exchange.getRequestHeaders();
            if (!exchange.getRequestMethod().equals("POST")
                    || exchange.getRequestURI().getRawQuery() != null
                    || headers.containsKey("Origin")
                    || !single(headers, "Host", "127.0.0.1:" + exchange.getLocalAddress().getPort())
                    || !single(headers, "X-ICB-Protocol", "1")
                    || !single(headers, "X-ICB-Instance-Id", instanceId)
                    || !single(headers, "Content-Type", "application/json; charset=utf-8")) {
                reply(exchange, 400, "{}", null);
                return;
            }
            String terminalId = headers.getFirst("X-ICB-Terminal-Id");
            if (terminalId == null) {
                reply(exchange, 401, "{}", null);
                return;
            }
            Binding binding = bindings.get(terminalId);
            String authorization = headers.getFirst("Authorization");
            if (binding == null
                    || authorization == null
                    || headers.get("Authorization").size() != 1
                    || !single(headers, "X-ICB-Terminal-Id", binding.terminalId)
                    || !MessageDigest.isEqual(
                            authorization.getBytes(StandardCharsets.US_ASCII),
                            ("Bearer " + binding.token).getBytes(StandardCharsets.US_ASCII))
                    || !single(headers, "X-ICB-Project-Id", binding.owner.projectId)) {
                reply(exchange, 401, "{}", null);
                return;
            }
            if (!binding.owner.allowed()) {
                reply(exchange, 403, "{}", null);
                return;
            }
            byte[] body;
            try {
                body = Json.bounded(exchange.getRequestBody(), 1048576);
            } catch (IOException ex) {
                reply(exchange, 413, "{}", null);
                return;
            }
            String route = exchange.getRequestURI().getRawPath();
            if (route.equals("/v1/hooks/ack")) {
                if (body.length != 0) {
                    reply(exchange, 400, "{}", null);
                    return;
                }
                binding.owner.ack(binding.terminalId, headers.getFirst("X-ICB-Batch-Id"));
                reply(exchange, 204, "", null);
                return;
            }
            JsonObject input = Json.parse(new String(body, StandardCharsets.UTF_8));
            switch (route) {
                case "/v1/hooks" -> {
                    // 事件分派只从凭证绑定项目，不从 cwd 搜索窗口。
                    ProjectContextService.HookResult result =
                            binding.owner.hook(binding.terminalId, input);
                    authenticatedReply(binding, exchange, 200, result.output(), result.batchId());
                }
                case "/v1/connections/open" -> {
                    if (!Json.required(input, "clientVersion").equals("1.0.0")
                            || !Set.of("2025-11-25", "2025-06-18", "2024-11-05")
                                    .contains(Json.required(input, "protocolVersion"))) {
                        reply(exchange, 409, "{}", null);
                        return;
                    }
                    if (connections.size() >= 128) {
                        reply(exchange, 429, "{}", null);
                        return;
                    }
                    String id = UUID.randomUUID().toString();
                    connections.put(id, binding);
                    binding.owner.mcpObserved = true;
                    authenticatedReply(
                            binding,
                            exchange,
                            200,
                            Json.GSON.toJson(
                                    Json.object(
                                            "connectionId", id, "capabilities", List.of("tools"))),
                            null);
                }
                case "/v1/connections/close" -> {
                    String id = Json.required(input, "connectionId");
                    if (connections.get(id) != binding) {
                        reply(exchange, 401, "{}", null);
                        return;
                    }
                    connections.remove(id);
                    reply(exchange, 204, "", null);
                }
                case "/v1/tools/call" -> {
                    if (connections.get(Json.required(input, "connectionId")) != binding) {
                        reply(exchange, 401, "{}", null);
                        return;
                    }
                    JsonObject result = binding.owner.tool(input);
                    authenticatedReply(binding, exchange, 200, Json.GSON.toJson(result), null);
                }
                case "/v1/health" ->
                        authenticatedReply(
                                binding,
                                exchange,
                                200,
                                "{\"protocol\":1,\"version\":\"1.0.0\",\"state\":\"RUNNING\"}",
                                null);
                default -> reply(exchange, 400, "{}", null);
            }
        } catch (IllegalArgumentException ex) {
            reply(exchange, 400, "{}", null);
        } catch (IllegalStateException ex) {
            reply(exchange, 409, "{}", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exchange.close();
        } catch (IOException ex) {
            exchange.close();
        } finally {
            timeout.cancel(false);
            if (entered) processing.release();
            admission.release();
            exchange.close();
        }
    }

    /**
     * 在输出正文前重新检查凭证是否仍属于当前项目，拒绝处理中撤销的请求。
     *
     * @param status 200 表示合法业务响应
     * @param body 已完成的自有协议 JSON，不包含平台对象
     * @param batchId 本轮提交交接标识，没有时为 null
     */
    private void authenticatedReply(
            Binding binding, HttpExchange exchange, int status, String body, String batchId)
            throws IOException {
        if (bindings.get(binding.terminalId) != binding || !binding.owner.allowed()) {
            connections.values().removeIf(connection -> connection == binding);
            reply(exchange, 403, "{}", null);
            return;
        }
        reply(exchange, status, body, batchId);
    }

    /**
     * 验证单一头部值，拒绝重复头部造成解析歧义。
     *
     * @param name 本地协议头部名
     * @param expected 此请求绑定要求的精确值
     */
    private static boolean single(Headers headers, String name, String expected) {
        return headers.get(name) != null
                && headers.get(name).size() == 1
                && expected.equals(headers.getFirst(name));
    }

    /**
     * 写出有界协议响应。
     *
     * @param status 200 为业务响应，204 为确认，400 为协议错误，401 为认证错误，403 为未授权，409 为冲突，413 为超量，429 为过载
     * @param body 脱敏错误或完整协议 JSON 正文
     * @param batchId 仅提交响应包含的交接批次标识
     */
    private static void reply(HttpExchange exchange, int status, String body, String batchId)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) {
            status = 413;
            bytes = "{}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (batchId != null) exchange.getResponseHeaders().set("X-ICB-Batch-Id", batchId);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) exchange.getResponseBody().write(bytes);
    }

    /** 撤销指定项目的终端和工具连接，删除私有描述文件。 */
    public synchronized void revoke(ProjectContextService owner) {
        bindings.values()
                .removeIf(
                        binding -> {
                            if (binding.owner != owner) return false;
                            connections.values().removeIf(connection -> connection == binding);
                            try {
                                Files.deleteIfExists(binding.descriptor);
                            } catch (IOException ex) {
                                owner.lastError = "ICB_DESCRIPTOR_CLEANUP_FAILED";
                            }
                            return true;
                        });
        // 所有项目终端都已撤销时，清理本实例拥有的启动入口，不保留运行脚本。
        if (bindings.isEmpty() && server != null) {
            server.close();
            server = null;
        }
        if (bindings.isEmpty() && directory != null)
            try {
                Files.deleteIfExists(directory.resolve("bin/codex"));
                Files.deleteIfExists(directory.resolve("bin"));
            } catch (IOException ex) {
                owner.lastError = "ICB_LAUNCHER_CLEANUP_FAILED";
            }
    }

    /** 停止本地服务，不终止任何用户的 Codex 进程。 */
    @Override
    public synchronized void dispose() {
        closed = true;
        if (server != null) server.close();
        for (Binding binding : bindings.values()) {
            try {
                Files.deleteIfExists(binding.descriptor);
            } catch (IOException ex) {
                binding.owner.lastError = "ICB_DESCRIPTOR_CLEANUP_FAILED";
            }
        }
        bindings.clear();
        connections.clear();
        deadlines.shutdownNow();
        if (directory != null)
            try {
                Files.deleteIfExists(directory.resolve("bin/codex"));
                Files.deleteIfExists(directory.resolve("bin"));
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                /* 系统缓存目录由平台管理，无法删除不影响凭证撤销。 */
            }
    }
}
