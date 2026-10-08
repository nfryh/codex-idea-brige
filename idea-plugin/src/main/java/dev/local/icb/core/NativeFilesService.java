// IDEA 自动管理受限本地读取进程，避免通过内部文件系统接口或不安全读取绕开平台包装。
package dev.local.icb.core;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;

import dev.local.icb.contract.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** 自动建立和回收普通 Java 读取进程，命令参数独立传递，不控制 Codex。 */
@Service(Service.Level.APP)
public final class NativeFilesService implements Disposable {
    /** 在途读取只保存请求标识和结果，最多八项。 */
    private final ConcurrentMap<String, CompletableFuture<JsonObject>> pending =
            new ConcurrentHashMap<>();

    /** 有界并发，不在应用级锁中等候文件读取。 */
    private final Semaphore admission = new Semaphore(8);

    /** 只保护进程建立、销毁及短帧写入，不锁住结果等候。 */
    private final Object lifecycle = new Object();

    /** 本服务拥有的读取进程，不是用户的 Codex 进程。 */
    private Process process;

    /** 实际使用读取进程的项目实例，最后一个项目关闭后释放进程。 */
    private final Set<String> owners = new HashSet<>();

    /** 结果接收线程，进程结束或服务释放时退出。 */
    private Thread reader;

    /** 私有桥接归档和目录，服务释放时删除。 */
    private Path directory, jar;

    /** 请求输出流，只有生命周期锁内写入。 */
    private PrintWriter writer;

    /** true 表示服务已销毁，false 表示可以继续提供受限读取。 */
    private boolean closed;

    /**
     * 读取已经由项目服务验证的文件，普通 Java 文件系统继续执行同样的安全边界检查。
     *
     * @param projectId 当前打开项目实例的唯一标识，用于关闭项目时释放资源
     * @param rootId 当前项目内容根标识
     * @param relativePath 内容根内相对文件路径，不接受根外读取
     */
    public String read(String projectId, Path root, String rootId, String relativePath)
            throws IOException {
        boolean acquired;
        try {
            acquired = admission.tryAcquire(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("ICB_IDE_BUSY");
        }
        if (!acquired) throw new IOException("ICB_BUSY");
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        try {
            synchronized (lifecycle) {
                if (closed) throw new IOException("ICB_FILES_CLOSED");
                owners.add(projectId);
                // 首次使用才建立进程；后续请求复用普通 Java 的安全目录读取实现。
                if (process == null) {
                    try {
                        start();
                    } catch (IOException ex) {
                        stop();
                        throw ex;
                    }
                }
                if (!process.isAlive()) throw new IOException("ICB_FILES_UNAVAILABLE");
                pending.put(id, result);
                writer.println(
                        Json.GSON.toJson(
                                Json.object(
                                        "id",
                                        id,
                                        "rootPath",
                                        root.toString(),
                                        "rootId",
                                        rootId,
                                        "relativePath",
                                        relativePath)));
                if (writer.checkError()) throw new IOException("ICB_FILES_UNAVAILABLE");
            }
            JsonObject response;
            try {
                response = result.get(600, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("ICB_IDE_BUSY");
            } catch (ExecutionException | TimeoutException ex) {
                throw new IOException("ICB_FILES_UNAVAILABLE");
            }
            if (response.has("error")) {
                if (Json.required(response, "error").equals("FILE_NOT_FOUND"))
                    throw new NoSuchFileException(relativePath);
                throw new IOException(Json.required(response, "error"));
            }
            return response.get("content").getAsString();
        } finally {
            pending.remove(id);
            admission.release();
        }
    }

    /** 在受控私有目录释放读取归档，结果线程只接收有界协议帧。 */
    private void start() throws IOException {
        Path base = Path.of(PathManager.getSystemPath(), "icb-files");
        Files.createDirectories(base);
        directory =
                Files.createTempDirectory(
                        base,
                        "reader-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        jar = directory.resolve("bridge-client.jar");
        try (var input = getClass().getResourceAsStream("/bridge/bridge-client.jar")) {
            if (input == null) throw new IOException("ICB_FILES_RESOURCE_MISSING");
            Files.write(jar, Json.bounded(input, 1048576));
            Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-------"));
        }
        process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-jar",
                                jar.toString(),
                                "files")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        writer =
                new PrintWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8),
                        true);
        Process current = process;
        reader =
                Thread.ofPlatform()
                        .daemon()
                        .name("ICB local-file results")
                        .start(
                                () -> {
                                    try (InputStream input = current.getInputStream()) {
                                        while (true) {
                                            ByteArrayOutputStream frame =
                                                    new ByteArrayOutputStream();
                                            int value;
                                            while ((value = input.read()) != -1 && value != '\n') {
                                                if (frame.size() >= 1048576)
                                                    throw new IOException("ICB_CONTEXT_TOO_LARGE");
                                                frame.write(value);
                                            }
                                            if (value == -1 && frame.size() == 0) break;
                                            JsonObject response =
                                                    Json.parse(
                                                            frame.toString(StandardCharsets.UTF_8));
                                            var target =
                                                    pending.remove(Json.required(response, "id"));
                                            if (target != null) target.complete(response);
                                        }
                                    } catch (IOException | IllegalArgumentException ex) {
                                        /* 请求收到固定错误，不输出文件路径或正文。 */
                                    } finally {
                                        synchronized (lifecycle) {
                                            // 只回收当前进程，不让旧进程退出事件影响已经重新建立的读取进程。
                                            if (process == current) stop();
                                        }
                                    }
                                });
    }

    /**
     * 项目释放其读取资源，最后一个使用者离开时终止本服务拥有的进程。
     *
     * @param projectId 已关闭或已禁用联动的打开项目实例标识
     */
    public void release(String projectId) {
        synchronized (lifecycle) {
            owners.remove(projectId);
            // 所有项目都已停止使用时释放进程和私有归档，不保留空闲读取线程。
            if (owners.isEmpty()) stop();
        }
    }

    /** 在生命周期锁中回收当前读取进程和私有归档，不终止用户的 Codex。 */
    private void stop() {
        pending.values()
                .forEach(
                        future ->
                                future.completeExceptionally(
                                        new IOException("ICB_FILES_UNAVAILABLE")));
        pending.clear();
        if (process != null) process.destroyForcibly();
        if (writer != null) writer.close();
        if (reader != null) reader.interrupt();
        try {
            if (jar != null) Files.deleteIfExists(jar);
            if (directory != null) Files.deleteIfExists(directory);
        } catch (IOException ex) {
            System.err.println("ICB_FILES_CLEANUP_FAILED");
        }
        process = null;
        writer = null;
        reader = null;
        jar = null;
        directory = null;
    }

    /** 撤销并终止本服务拥有的读取进程，删除私有归档，不等待模型或触碰用户进程。 */
    @Override
    public void dispose() {
        synchronized (lifecycle) {
            closed = true;
            owners.clear();
            // 应用或插件卸载时统一回收，不能遗留读取子进程。
            stop();
        }
    }
}
