// 对慢速头部、超量正文和线程释放验证整个连接期限。
package dev.local.icb.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 真实 Socket 传输测试，不使用模拟超时结果。 */
public class LocalHttpServerTest {
    @Test
    public void slowHeaderConnectionIsClosedWithinTotalDeadline() throws Exception {
        try (LocalHttpServer server =
                new LocalHttpServer(
                        exchange -> {
                            throw new AssertionError(
                                    "Slow incomplete headers must not reach handler");
                        })) {
            server.start();
            long start = System.nanoTime();
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream()
                        .write(
                                "POST /v1/hooks HTTP/1.1\r\nHost: "
                                        .getBytes(StandardCharsets.US_ASCII));
                socket.getInputStream().readAllBytes();
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1400);
        }
    }

    @Test
    public void declaredOversizeBodyReturns413WithoutReadingBody() throws Exception {
        try (LocalHttpServer server =
                new LocalHttpServer(
                        exchange -> {
                            throw new AssertionError("Oversize bodies must not reach handler");
                        })) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream()
                        .write(
                                "POST /v1/hooks HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1048577\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII));
                assertTrue(
                        new String(
                                        socket.getInputStream().readAllBytes(),
                                        StandardCharsets.US_ASCII)
                                .startsWith("HTTP/1.1 413"));
            }
        }
    }

    @Test
    public void chunkedMessagesAreRejectedAsInvalidTransport() throws Exception {
        try (LocalHttpServer server =
                new LocalHttpServer(
                        exchange -> {
                            throw new AssertionError("Chunked messages must not reach handler");
                        })) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream()
                        .write(
                                "POST /v1/hooks HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII));
                assertTrue(
                        new String(
                                        socket.getInputStream().readAllBytes(),
                                        StandardCharsets.US_ASCII)
                                .startsWith("HTTP/1.1 400"));
            }
        }
    }
}
