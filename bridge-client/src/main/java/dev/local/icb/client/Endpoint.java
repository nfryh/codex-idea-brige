// 从终端私有描述文件建立直接本地连接，不读取 Codex 登录状态。
package dev.local.icb.client;

import dev.local.icb.contract.Json;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 仅使用已验证的终端连接信息，禁止代理与重定向。 */
public final class Endpoint {
    private final Properties fields;
    private final URI origin;

    private Endpoint(Properties fields, URI origin) {
        this.fields = fields;
        this.origin = origin;
    }

    /**
     * 安全读取私有连接文件。
     *
     * @param descriptor 环境变量提供的描述文件绝对路径
     */
    public static Endpoint load(String descriptor) throws IOException {
        Path path = Path.of(descriptor);
        if (!path.isAbsolute()
                || Files.isSymbolicLink(path)
                || Files.isSymbolicLink(path.getParent()))
            throw new IOException("ICB_DESCRIPTOR_INVALID");
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix"))
            throw new IOException("ICB_PLATFORM_UNSUPPORTED");
        // 首版只支持已验证的 Unix 私有权限，不能用只读属性冒充 Windows 访问控制。
        for (Path item : List.of(path, path.getParent())) {
            Set<PosixFilePermission> perms =
                    Files.getPosixFilePermissions(item, LinkOption.NOFOLLOW_LINKS);
            if (perms.stream()
                            .anyMatch(
                                    p ->
                                            p.name().startsWith("GROUP")
                                                    || p.name().startsWith("OTHERS"))
                    || !Files.getOwner(item, LinkOption.NOFOLLOW_LINKS)
                            .getName()
                            .equals(System.getProperty("user.name")))
                throw new IOException("ICB_DESCRIPTOR_INVALID");
        }
        Properties fields = new Properties();
        try (var channel =
                Files.newByteChannel(
                        path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            fields.load(
                    new StringReader(
                            new String(
                                    Json.bounded(Channels.newInputStream(channel), 8192),
                                    StandardCharsets.UTF_8)));
        }
        for (String key :
                List.of(
                        "protocol",
                        "origin",
                        "instance_id",
                        "project_id",
                        "terminal_id",
                        "auth_token"))
            if (fields.getProperty(key, "").isBlank())
                throw new IOException("ICB_DESCRIPTOR_INVALID");
        if (!fields.getProperty("protocol").equals("1")
                || !fields.getProperty("auth_token").matches("[A-Za-z0-9_-]{43}"))
            throw new IOException("ICB_DESCRIPTOR_INVALID");
        for (String key : List.of("instance_id", "project_id", "terminal_id")) {
            try {
                UUID.fromString(fields.getProperty(key));
            } catch (IllegalArgumentException ex) {
                throw new IOException("ICB_DESCRIPTOR_INVALID");
            }
        }
        String address = fields.getProperty("origin");
        if (!address.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}"))
            throw new IOException("ICB_DESCRIPTOR_INVALID");
        URI origin = URI.create(address);
        if (origin.getPort() < 1 || origin.getPort() > 65535)
            throw new IOException("ICB_DESCRIPTOR_INVALID");
        return new Endpoint(fields, origin);
    }

    /**
     * 完整获得有界响应后再交给协议输出层。
     *
     * @param route 仅允许桥接程序内部固定的 /v1 路由
     * @param body 待发送 JSON 正文或确认请求的空正文
     * @param batchId 确认交接的批次标识，没有时为 null
     * @param timeoutMillis 本次读响应最大等待毫秒数，主请求 600，确认请求 200
     */
    public Response post(String route, byte[] body, String batchId, int timeoutMillis)
            throws IOException {
        if (!Set.of(
                        "/v1/hooks",
                        "/v1/hooks/ack",
                        "/v1/tools/call",
                        "/v1/connections/open",
                        "/v1/connections/close",
                        "/v1/health")
                .contains(route)) throw new IOException("ROUTE_INVALID");
        HttpURLConnection connection =
                (HttpURLConnection) origin.resolve(route).toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(150);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty(
                "Authorization", "Bearer " + fields.getProperty("auth_token"));
        connection.setRequestProperty("X-ICB-Protocol", "1");
        connection.setRequestProperty("X-ICB-Instance-Id", fields.getProperty("instance_id"));
        connection.setRequestProperty("X-ICB-Project-Id", fields.getProperty("project_id"));
        connection.setRequestProperty("X-ICB-Terminal-Id", fields.getProperty("terminal_id"));
        if (batchId != null) connection.setRequestProperty("X-ICB-Batch-Id", batchId);
        try {
            connection.getOutputStream().write(body);
            int status = connection.getResponseCode();
            if (status != 200 && status != 204) throw new IOException("ICB_BRIDGE_REJECTED");
            byte[] result =
                    status == 204 ? new byte[0] : Json.bounded(connection.getInputStream(), 65536);
            String batch = connection.getHeaderField("X-ICB-Batch-Id");
            if (batch != null && !batch.matches("[a-fA-F0-9-]{36}"))
                throw new IOException("BATCH_INVALID");
            return new Response(result, batch);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 本地响应及可选的交接批次。
     *
     * @param body 已经获得的完整 JSON 字节
     * @param batchId 本次提交交接标识，没有提交批次时为 null
     */
    public record Response(byte[] body, String batchId) {}
}
