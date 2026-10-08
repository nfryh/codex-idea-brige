// 由 IDEA 管理的普通 Java 本地读取进程，保留安全目录描述符和路径边界校验。
package dev.local.icb.client;

import dev.local.icb.contract.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 只读指定本地内容根，不提供写文件或执行命令能力。 */
public final class LocalFilesServer {
    /** 有界请求并发，慢文件不会占用唯一读取线程。 */
    public void run(InputStream input, OutputStream output) throws IOException {
        PrintWriter writer =
                new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(8),
                        task -> Thread.ofPlatform().daemon().unstarted(task),
                        new ThreadPoolExecutor.AbortPolicy());
        try {
            while (true) {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                int value;
                while ((value = input.read()) != -1 && value != '\n') {
                    if (frame.size() >= 1048576) throw new IOException("ICB_CONTEXT_TOO_LARGE");
                    frame.write(value);
                }
                if (value == -1 && frame.size() == 0) break;
                var request = Json.parse(frame.toString(StandardCharsets.UTF_8));
                String id = Json.required(request, "id");
                if (id.length() > 128) throw new IOException("REQUEST_INVALID");
                Runnable operation =
                        () -> {
                            com.google.gson.JsonObject response;
                            try {
                                Path root = Path.of(Json.required(request, "rootPath")).normalize();
                                if (!root.isAbsolute()) throw new IOException("PATH_DENIED");
                                String rootId = Json.required(request, "rootId");
                                // 每次重新验证目录边界，并通过已打开目录逐层读取，符号链接替换不能扩大范围。
                                String content =
                                        new PathPolicy(Map.of(rootId, root))
                                                .readText(
                                                        rootId,
                                                        Json.required(request, "relativePath"),
                                                        131072);
                                response = Json.object("id", id, "content", content);
                            } catch (NoSuchFileException ex) {
                                response = Json.object("id", id, "error", "FILE_NOT_FOUND");
                            } catch (IOException | IllegalArgumentException ex) {
                                response = Json.object("id", id, "error", "READ_REJECTED");
                            }
                            synchronized (writer) {
                                writer.println(Json.GSON.toJson(response));
                            }
                        };
                try {
                    workers.execute(operation);
                } catch (RejectedExecutionException ex) {
                    synchronized (writer) {
                        writer.println(
                                Json.GSON.toJson(Json.object("id", id, "error", "ICB_BUSY")));
                    }
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
