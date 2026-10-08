// 为本地桥接提供从接受连接开始计算的期限，慢速请求头不能占满工作线程。
package dev.local.icb.core;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 有界、只接受固定长度正文的本地 HTTP/1.1 服务，不接受升级、代理或长连接。 */
final class LocalHttpServer implements AutoCloseable {
    private final ServerSocket server;
    private final HttpHandler handler;
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    8,
                    8,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32),
                    runnable -> Thread.ofPlatform().daemon().name("ICB_HTTP").unstarted(runnable));
    private final ScheduledThreadPoolExecutor deadlines =
            new ScheduledThreadPoolExecutor(
                    1,
                    runnable ->
                            Thread.ofPlatform().daemon().name("ICB_DEADLINE").unstarted(runnable));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private Thread acceptor;

    /** 监听系统分配的本地随机端口，所有连接都有 900 毫秒总期限。 */
    LocalHttpServer(HttpHandler handler) throws IOException {
        this.handler = handler;
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** 启动单一接收线程，不在接收线程执行上下文业务。 */
    void start() {
        acceptor =
                Thread.ofPlatform()
                        .daemon()
                        .name("ICB_ACCEPT")
                        .start(
                                () -> {
                                    while (!closed) {
                                        try {
                                            Socket socket = server.accept();
                                            socket.setSoTimeout(600);
                                            sockets.add(socket);
                                            // 总期限从接受连接计时，包含排队、慢速头部和慢速响应写入。
                                            ScheduledFuture<?> timeout =
                                                    deadlines.schedule(
                                                            () -> closeSocket(socket),
                                                            900,
                                                            TimeUnit.MILLISECONDS);
                                            try {
                                                workers.execute(
                                                        () -> {
                                                            try {
                                                                handler.handle(parse(socket));
                                                            } catch (BadRequest ex) {
                                                                error(socket, ex.status);
                                                            } catch (IOException
                                                                    | IllegalArgumentException ex) {
                                                                error(socket, 400);
                                                            } finally {
                                                                timeout.cancel(false);
                                                                sockets.remove(socket);
                                                                closeSocket(socket);
                                                            }
                                                        });
                                            } catch (RejectedExecutionException ex) {
                                                error(socket, 429);
                                                timeout.cancel(false);
                                                sockets.remove(socket);
                                                closeSocket(socket);
                                            }
                                        } catch (IOException ex) {
                                            if (!closed) close();
                                        }
                                    }
                                });
    }

    /** 当前监听地址仅用于签发精确 Host 和连接描述文件。 */
    InetSocketAddress getAddress() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    /** 解析最多 16 KiB 头部，不读取正文，认证由项目桥接服务完成。 */
    private static Exchange parse(Socket socket) throws IOException {
        InputStream input = new BufferedInputStream(socket.getInputStream());
        int[] budget = {0};
        String[] request = line(input, budget).split(" ", -1);
        if (request.length != 3
                || !request[2].equals("HTTP/1.1")
                || !request[1].startsWith("/")
                || request[1].contains("#")) throw new BadRequest(400);
        Headers headers = new Headers();
        String value;
        while (!(value = line(input, budget)).isEmpty()) {
            int colon = value.indexOf(':');
            if (colon < 1 || !value.substring(0, colon).matches("[A-Za-z0-9-]+"))
                throw new BadRequest(400);
            headers.add(value.substring(0, colon), value.substring(colon + 1).trim());
        }
        if (headers.containsKey("Transfer-Encoding") || headers.containsKey("Expect"))
            throw new BadRequest(400);
        List<String> lengths = headers.get("Content-Length");
        if (lengths == null || lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,8}"))
            throw new BadRequest(400);
        int length = Integer.parseInt(lengths.getFirst());
        if (length > 1048576) throw new BadRequest(413);
        return new Exchange(socket, input, request[0], URI.create(request[1]), headers, length);
    }

    /** 读取单一 CRLF 头部行并累计整个请求头的字节数。 */
    private static String line(InputStream input, int[] budget) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current == -1
                    || ++budget[0] > 16384
                    || current > 127
                    || (current < 32 && current != '\r' && current != '\n' && current != '\t'))
                throw new BadRequest(400);
            if (previous == '\r') {
                if (current != '\n') throw new BadRequest(400);
                return output.toString(StandardCharsets.US_ASCII);
            }
            if (current == '\n') throw new BadRequest(400);
            if (current != '\r') output.write(current);
            previous = current;
        }
    }

    /**
     * 使用最小脱敏响应拒绝协议错误或过载。
     *
     * @param status 400 为非法协议，413 为超量正文，429 为排队过载
     */
    private static void error(Socket socket, int status) {
        try {
            socket.getOutputStream()
                    .write(
                            ("HTTP/1.1 "
                                            + status
                                            + " Rejected\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                                    .getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
            /* 客户端断开时没有可用的错误输出通道，连接仍由期限和 finally 关闭。 */
        }
    }

    /** 关闭期限到达或撤销的连接，不等待对端确认。 */
    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            /* 连接已经关闭，无正文和凭证日志。 */
        }
    }

    /** 停止监听并释放所有活动连接和线程。 */
    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            /* 即使监听关闭失败也撤销工作连接。 */
        }
        sockets.forEach(LocalHttpServer::closeSocket);
        sockets.clear();
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    /** 有限的传输拒绝，不含源代码和认证信息。 */
    private static final class BadRequest extends IOException {
        private final int status;

        /**
         * @param status 400 为协议错误，413 为正文超量
         */
        private BadRequest(int status) {
            this.status = status;
        }
    }

    /** 为既有桥接处理器提供受限交换对象，始终关闭连接而非保留长连接。 */
    private static final class Exchange extends HttpExchange {
        private final Socket socket;
        private final String method;
        private final URI uri;
        private final Headers request;
        private final Headers response = new Headers();
        private final InputStream body;
        private final OutputStream output;
        private int responseCode = -1;

        /**
         * 创建已经解析但尚未读取正文的请求。
         *
         * @param method 请求方法，本地服务只允许 POST，其余方法由处理器拒绝
         * @param length 本次正文的固定字节长度，上限为 1 MiB
         */
        private Exchange(
                Socket socket,
                InputStream input,
                String method,
                URI uri,
                Headers request,
                int length)
                throws IOException {
            this.socket = socket;
            this.method = method;
            this.uri = uri;
            this.request = request;
            output = socket.getOutputStream();
            body =
                    new FilterInputStream(input) {
                        private int remaining = length;

                        @Override
                        public int read() throws IOException {
                            if (remaining == 0) return -1;
                            int value = super.read();
                            if (value == -1) throw new EOFException();
                            remaining--;
                            return value;
                        }

                        /**
                         * 有界读取本次请求正文。
                         *
                         * @param offset 写入目标缓冲区的起始字节位置，从 0 开始
                         * @param count 调用方最多希望读取的字节数，零表示不读取
                         */
                        @Override
                        public int read(byte[] bytes, int offset, int count) throws IOException {
                            if (count == 0) return 0;
                            if (remaining == 0) return -1;
                            int actual = in.read(bytes, offset, Math.min(count, remaining));
                            if (actual == -1) throw new EOFException();
                            remaining -= actual;
                            return actual;
                        }
                    };
        }

        @Override
        public Headers getRequestHeaders() {
            return request;
        }

        @Override
        public Headers getResponseHeaders() {
            return response;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public InputStream getRequestBody() {
            return body;
        }

        @Override
        public OutputStream getResponseBody() {
            return output;
        }

        /**
         * 写出精确响应长度，连接在此次交互后关闭。
         *
         * @param code 200 为业务响应，204 为确认，400 为协议错误，401 为认证失败，403 为未授权，409 为冲突，413 为超量，429 为过载
         * @param length 完整响应的字节数，-1 表示 204 无正文
         */
        @Override
        public void sendResponseHeaders(int code, long length) throws IOException {
            if (responseCode != -1) throw new IOException("RESPONSE_ALREADY_SENT");
            responseCode = code;
            StringBuilder headers =
                    new StringBuilder(
                            "HTTP/1.1 "
                                    + code
                                    + " ICB\r\nConnection: close\r\nContent-Length: "
                                    + Math.max(0, length)
                                    + "\r\n");
            response.forEach(
                    (name, values) ->
                            values.forEach(
                                    value ->
                                            headers.append(name)
                                                    .append(": ")
                                                    .append(value)
                                                    .append("\r\n")));
            socket.getOutputStream()
                    .write(headers.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public void close() {
            closeSocket(socket);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return (InetSocketAddress) socket.getRemoteSocketAddress();
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return (InetSocketAddress) socket.getLocalSocketAddress();
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        /**
         * @param name 请求附加属性名；本地协议不提供任何附加属性
         */
        @Override
        public Object getAttribute(String name) {
            return null;
        }

        /**
         * @param name 请求附加属性名；本地协议禁止设置附加属性
         */
        @Override
        public void setAttribute(String name, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            throw new UnsupportedOperationException();
        }
    }
}
