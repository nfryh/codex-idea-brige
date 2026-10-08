// Codex 所拥有的短生命周期 Hook 进程和标准输入输出工具进程。
package dev.local.icb.client;

import dev.local.icb.contract.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 不启动 Codex，也不修改终端输入的桥接入口。 */
public final class BridgeClientMain {
    private BridgeClientMain() {}

    /**
     * 根据安装器配置选择运行模式。
     *
     * @param args submit 为提交回调，event 为会话事件，mcp 为模型上下文协议标准输入输出进程，files 为 IDEA 管理的受限本地读取进程
     */
    public static void main(String[] args) {
        long startedNanos = System.nanoTime();
        String mode = args.length == 1 ? args[0] : "";
        if (mode.equals("files")) {
            // 该进程由 IDEA 管理，只读本机文件，不运行 Codex 或其他系统命令。
            try {
                new LocalFilesServer().run(System.in, System.out);
            } catch (IOException ex) {
                System.err.println("ICB_FILES_TRANSPORT_FAILED");
            }
            return;
        }
        String descriptor = System.getenv("ICB_ENDPOINT_FILE");
        if (mode.equals("mcp")) {
            // 工具协议在 IDEA 外仍可初始化，不阻塞原生命令行启动。
            try {
                new McpServer(descriptor).run(System.in, System.out);
            } catch (IOException ex) {
                System.err.println("ICB_MCP_TRANSPORT_FAILED");
            }
            return;
        }
        if (descriptor == null || descriptor.isBlank()) return;
        if (!mode.equals("submit") && !mode.equals("event")) {
            System.err.println("ICB_MODE_INVALID");
            return;
        }
        Endpoint endpoint;
        Endpoint.Response response;
        // 单次转发只需一个工作线程，避免为短命进程初始化虚拟线程调度器。
        ExecutorService executor = hookWorker();
        try {
            Future<Endpoint.Response> task =
                    executor.submit(
                            () -> {
                                Endpoint target = Endpoint.load(descriptor);
                                byte[] event = Json.bounded(System.in, 1048576);
                                Json.parse(new String(event, StandardCharsets.UTF_8));
                                return target.post("/v1/hooks", event, null, 600);
                            });
            try {
                response = task.get(1200, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                task.cancel(true);
                executor.shutdownNow();
                throw new IOException("ICB_BRIDGE_TIMEOUT");
            }
            endpoint = Endpoint.load(descriptor);
            if (response.body().length > 0)
                Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            if (mode.equals("submit")) {
                System.out.print(
                        Json.GSON.toJson(
                                Json.object("systemMessage", "IDEA 上下文未注入，本次按普通 CLI 请求继续")));
                System.out.flush();
            }
            System.err.println("ICB_BRIDGE_UNAVAILABLE");
            return;
        } finally {
            executor.shutdownNow();
        }
        // 先一次写出合法结果，再确认交接；确认失败绝不追加第二个 JSON。
        System.out.write(response.body(), 0, response.body().length);
        System.out.flush();
        if (!System.out.checkError() && response.batchId() != null) {
            // 确认同样采用有界、可取消的单线程任务，不延长进程退出。
            ExecutorService acknowledgement = hookWorker();
            try {
                Endpoint confirmedEndpoint = endpoint;
                Future<?> task =
                        acknowledgement.submit(
                                () ->
                                        confirmedEndpoint.post(
                                                "/v1/hooks/ack",
                                                new byte[0],
                                                response.batchId(),
                                                200));
                task.get(200, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException ex) {
                System.err.println("ICB_DELIVERY_UNCERTAIN");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                System.err.println("ICB_DELIVERY_UNCERTAIN");
            } finally {
                acknowledgement.shutdownNow();
            }
        }
        // 使用单调时钟测量入口后的回调处理时间，避免为单条日志启动 Java 管理组件。
        // 仅记录运行时间、模式和字节数，禁止记录请求、源码、路径或凭证。
        System.err.println(
                "ICB_HOOK_TIMING mode="
                        + mode
                        + " elapsedMs="
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
                        + " responseBytes="
                        + response.body().length);
    }

    /** 创建短生命周期回调线程；输入关闭异常时守护线程不会阻止桥接进程退出。 */
    private static ExecutorService hookWorker() {
        return Executors.newSingleThreadExecutor(
                task -> {
                    Thread worker = new Thread(task, "icb-hook-transfer");
                    worker.setDaemon(true);
                    return worker;
                });
    }
}
