// 用本地模拟服务验证桥接传输与原生独立进程，不使用登录凭据。
package dev.local.icb.client;

import static org.junit.Assert.*;

import com.sun.net.httpserver.*;

import dev.local.icb.contract.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** 传输、降级和模型上下文协议基础生命周期测试。 */
public class ClientTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HttpServer server;
    private Path descriptor;
    private String token;

    @Before
    public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        Path directory = temporary.newFolder("private").toPath();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        descriptor = directory.resolve("terminal.properties");
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        Files.writeString(
                descriptor,
                "protocol=1\norigin=http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "\ninstance_id="
                        + UUID.randomUUID()
                        + "\nproject_id="
                        + UUID.randomUUID()
                        + "\nterminal_id="
                        + UUID.randomUUID()
                        + "\nauth_token="
                        + token
                        + "\n");
        Files.setPosixFilePermissions(descriptor, PosixFilePermissions.fromString("rw-------"));
    }

    @After
    public void cleanup() {
        server.stop(0);
    }

    @Test
    public void bearerAndBindingHeadersAreTransmitted() throws Exception {
        server.createContext(
                "/v1/hooks",
                exchange -> {
                    assertEquals(
                            "Bearer " + token,
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("1", exchange.getRequestHeaders().getFirst("X-ICB-Protocol"));
                    assertNotNull(exchange.getRequestHeaders().getFirst("X-ICB-Project-Id"));
                    assertEquals(
                            "{\"fixture\":true}",
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    reply(exchange, 200, "{\"systemMessage\":\"fixture\"}");
                });
        var result =
                Endpoint.load(descriptor.toString())
                        .post(
                                "/v1/hooks",
                                "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8),
                                null,
                                600);
        assertEquals(
                "fixture",
                Json.required(
                        Json.parse(new String(result.body(), StandardCharsets.UTF_8)),
                        "systemMessage"));
    }

    @Test
    public void redirectsAreRejectedWithoutFollowing() throws Exception {
        server.createContext(
                "/v1/hooks",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/secret");
                    reply(exchange, 302, "{}");
                });
        assertThrows(
                IOException.class,
                () ->
                        Endpoint.load(descriptor.toString())
                                .post("/v1/hooks", "{}".getBytes(), null, 600));
    }

    @Test
    public void responseOver64KiBIsRejected() throws Exception {
        server.createContext("/v1/hooks", exchange -> reply(exchange, 200, "x".repeat(65537)));
        assertThrows(
                IOException.class,
                () ->
                        Endpoint.load(descriptor.toString())
                                .post("/v1/hooks", "{}".getBytes(), null, 600));
    }

    @Test
    public void readTimeoutIsBounded() throws Exception {
        server.createContext(
                "/v1/hooks",
                exchange -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        long start = System.nanoTime();
        assertThrows(
                IOException.class,
                () ->
                        Endpoint.load(descriptor.toString())
                                .post("/v1/hooks", "{}".getBytes(), null, 50));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500);
    }

    @Test
    public void openDescriptorPermissionsAreRejected() throws Exception {
        Files.setPosixFilePermissions(descriptor, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> Endpoint.load(descriptor.toString()));
    }

    @Test
    public void symlinkDescriptorIsRejected() throws Exception {
        Path link = descriptor.getParent().resolve("link");
        Files.createSymbolicLink(link, descriptor);
        assertThrows(IOException.class, () -> Endpoint.load(link.toString()));
    }

    @Test
    public void remoteOriginIsRejected() throws Exception {
        Files.writeString(
                descriptor, Files.readString(descriptor).replace("127.0.0.1", "example.com"));
        assertThrows(IOException.class, () -> Endpoint.load(descriptor.toString()));
    }

    @Test
    public void outsideIdeHookDoesNothingAndDoesNotWaitForStdin() throws Exception {
        Process process = process(null, "submit");
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals("", new String(process.getInputStream().readAllBytes()));
    }

    @Test
    public void failedSubmitPrintsOneValidWarning() throws Exception {
        server.stop(0);
        Process process = process(descriptor.toString(), "submit");
        process.getOutputStream().write("{}".getBytes());
        process.getOutputStream().close();
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(Json.required(Json.parse(output), "systemMessage").contains("未注入"));
    }

    @Test
    public void failedNonSubmitDoesNotForgeSubmitOutput() throws Exception {
        server.stop(0);
        Process process = process(descriptor.toString(), "event");
        process.getOutputStream().write("{}".getBytes());
        process.getOutputStream().close();
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        assertEquals("", new String(process.getInputStream().readAllBytes()));
    }

    @Test
    public void ackFailureCannotAppendSecondJson() throws Exception {
        server.createContext(
                "/v1/hooks",
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("X-ICB-Batch-Id", UUID.randomUUID().toString());
                    reply(
                            exchange,
                            200,
                            "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"unique-fixture\"}}");
                });
        server.createContext("/v1/hooks/ack", exchange -> reply(exchange, 503, "{}"));
        Process process = process(descriptor.toString(), "submit");
        process.getOutputStream().write("{}".getBytes());
        process.getOutputStream().close();
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(Json.parse(output).has("hookSpecificOutput"));
        assertFalse(output.contains("systemMessage"));
    }

    @Test
    public void stdinNeverEndingIsBoundedByOverallDeadline() throws Exception {
        Process process = process(descriptor.toString(), "submit");
        process.getOutputStream().write("{".getBytes());
        process.getOutputStream().flush();
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        assertTrue(
                Json.parse(
                                new String(
                                        process.getInputStream().readAllBytes(),
                                        StandardCharsets.UTF_8))
                        .has("systemMessage"));
    }

    @Test
    public void mcpSupportsInitializationToolsAndEofOutsideIde() throws Exception {
        String input =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}\n"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpServer(null)
                .run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(3, lines.length);
        assertEquals(
                5, Json.parse(lines[1]).getAsJsonObject("result").getAsJsonArray("tools").size());
    }

    @Test
    public void duplicateMcpIdIsProtocolError() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpServer(null)
                .run(
                        new ByteArrayInputStream(
                                ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n")
                                        .repeat(2)
                                        .getBytes()),
                        output);
        assertEquals(
                -32600,
                Json.parse(output.toString(StandardCharsets.UTF_8).split("\n")[1])
                        .getAsJsonObject("error")
                        .get("code")
                        .getAsInt());
    }

    @Test
    public void unsupportedMcpProtocolIsExplicitError() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpServer(null)
                .run(
                        new ByteArrayInputStream(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"future\"}}\n"
                                        .getBytes()),
                        output);
        assertTrue(Json.parse(output.toString(StandardCharsets.UTF_8).trim()).has("error"));
    }

    @Test
    public void notificationsHaveNoResponses() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpServer(null)
                .run(
                        new ByteArrayInputStream(
                                "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}\n".getBytes()),
                        output);
        assertEquals(0, output.size());
    }

    @Test
    public void malformedMcpParametersAreProtocolErrors() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpServer(null)
                .run(
                        new ByteArrayInputStream(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":42}\n"
                                        .getBytes(StandardCharsets.UTF_8)),
                        output);
        assertEquals(
                -32602,
                Json.parse(output.toString(StandardCharsets.UTF_8).trim())
                        .getAsJsonObject("error")
                        .get("code")
                        .getAsInt());
    }

    @Test
    public void toolCallBeforeHandshakeIsStateError() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpServer(null)
                .run(
                        new ByteArrayInputStream(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}\n"
                                        .getBytes(StandardCharsets.UTF_8)),
                        output);
        assertEquals(
                -32602,
                Json.parse(output.toString(StandardCharsets.UTF_8).trim())
                        .getAsJsonObject("error")
                        .get("code")
                        .getAsInt());
    }

    @Test
    public void disconnectedIdeToolReturnsBusinessError() throws Exception {
        String input =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}\n"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ide_get_context\",\"arguments\":{}}}\n";
        PipedInputStream inputStream = new PipedInputStream();
        PipedOutputStream source = new PipedOutputStream(inputStream);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running =
                    executor.submit(
                            () -> {
                                new McpServer(null).run(inputStream, output);
                                return null;
                            });
            source.write(input.getBytes(StandardCharsets.UTF_8));
            source.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (output.toString(StandardCharsets.UTF_8).split("\n").length < 2
                    && System.nanoTime() < deadline) Thread.sleep(5);
            source.close();
            running.get(2, TimeUnit.SECONDS);
        }
        var reply = Json.parse(output.toString(StandardCharsets.UTF_8).split("\n")[1]);
        assertTrue(reply.getAsJsonObject("result").get("isError").getAsBoolean());
        assertFalse(reply.has("error"));
    }

    /**
     * 启动真实桥接子进程，所有环境绑定均由当前测试私有生成。
     *
     * @param descriptor 当前测试私有文件，null 表示 IDEA 外的原生进程
     * @param mode submit 为提交，event 为普通事件
     */
    private static Process process(String descriptor, String mode) throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-XX:TieredStopAtLevel=1",
                        "-jar",
                        System.getProperty("bridge.jar"),
                        mode);
        builder.environment().remove("ICB_ENDPOINT_FILE");
        if (descriptor != null) builder.environment().put("ICB_ENDPOINT_FILE", descriptor);
        return builder.start();
    }

    /**
     * 发送本机模拟响应。
     *
     * @param status 200 为成功，302 为重定向，503 为不可用
     * @param content 当前测试的固定响应，不包含真实用户数据
     */
    private static void reply(HttpExchange exchange, int status, String content)
            throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}
