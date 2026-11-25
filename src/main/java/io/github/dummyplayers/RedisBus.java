package io.github.dummyplayers;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 只实现发布/订阅所需命令的 Redis 客户端（RESP2），不依赖第三方库。
 * 订阅连接在独立线程上阻塞读取，断线后自动重连；发布在单独的线程上串行执行。
 */
final class RedisBus implements Network.Bus {
    private static final byte[] CRLF = {'\r', '\n'};

    private record Link(Socket socket, InputStream in, OutputStream out) implements Closeable {
        @Override public void close() throws IOException { socket.close(); }
    }

    private final String host, password, channel;
    private final int port;
    private final Logger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ALFakePlayer-redis");
        thread.setDaemon(true);
        return thread;
    });
    private final Object subscriberLock = new Object();
    private volatile boolean running;
    private Thread subscriberThread;
    private Link subscriber;
    private Link publisher;
    private long lastWarning;

    RedisBus(String host, int port, String password, String channel, Logger logger) {
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.channel = channel;
        this.logger = logger;
    }

    @Override public void start(Consumer<String> sink) {
        running = true;
        try (Link probe = open()) {
            write(probe.out, "PING");
            read(probe.in);
        } catch (IOException error) {
            logger.severe("Cannot connect to Redis at " + host + ":" + port + " (" + error.getMessage()
                + "); cross-server messages will be retried in the background.");
        }
        subscriberThread = new Thread(() -> subscribe(sink), "ALFakePlayer-redis-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        // 订阅连接平时没有数据，定时 PING 以便及时发现断线
        executor.scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
    }

    private void subscribe(Consumer<String> sink) {
        long backoff = 1000;
        while (running) {
            try (Link link = open()) {
                link.socket.setSoTimeout(60_000);
                synchronized (subscriberLock) {
                    subscriber = link;
                    write(link.out, "SUBSCRIBE", channel);
                }
                backoff = 1000;
                while (running) {
                    if (read(link.in) instanceof List<?> reply && reply.size() >= 3 && "message".equals(reply.get(0))
                        && reply.get(2) instanceof String payload) {
                        try { sink.accept(payload); }
                        catch (RuntimeException error) { logger.log(Level.WARNING, "Cross-server message handler failed", error); }
                    }
                }
            } catch (IOException error) {
                if (running) warn("Redis subscription lost; reconnecting", error);
            } finally {
                synchronized (subscriberLock) { subscriber = null; }
            }
            if (!running) return;
            try { Thread.sleep(backoff); }
            catch (InterruptedException interrupted) { return; }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void ping() {
        synchronized (subscriberLock) {
            if (subscriber == null) return;
            try { write(subscriber.out, "PING"); }
            catch (IOException error) {
                try { subscriber.close(); } catch (IOException ignored) { }
            }
        }
    }

    @Override public void publish(String target, String message) {
        try {
            executor.execute(() -> {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        if (publisher == null) publisher = open();
                        write(publisher.out, "PUBLISH", channel, message);
                        read(publisher.in);
                        return;
                    } catch (IOException error) {
                        closePublisher();
                        if (attempt == 2) warn("Could not publish a cross-server message to Redis", error);
                    }
                }
            });
        } catch (RejectedExecutionException stopped) {
            // 插件正在关闭
        }
    }

    private Link open() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(5000);
            var link = new Link(socket, new BufferedInputStream(socket.getInputStream()),
                new BufferedOutputStream(socket.getOutputStream()));
            if (!password.isEmpty()) {
                write(link.out, "AUTH", password);
                read(link.in);
            }
            return link;
        } catch (IOException error) {
            socket.close();
            throw error;
        }
    }

    private void closePublisher() {
        if (publisher == null) return;
        try { publisher.close(); } catch (IOException ignored) { }
        publisher = null;
    }

    private synchronized void warn(String message, Exception error) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < 60_000) return;
        lastWarning = now;
        logger.warning(message + ": " + error.getMessage());
    }

    static void write(OutputStream out, String... args) throws IOException {
        out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write(CRLF);
        }
        out.flush();
    }

    static Object read(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException("Redis closed the connection");
        String line = line(in);
        return switch (type) {
            case '+' -> line;
            case '-' -> throw new IOException("Redis error: " + line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length < 0) yield null;
                byte[] data = in.readNBytes(length);
                if (data.length != length) throw new EOFException("Redis closed the connection");
                line(in);
                yield new String(data, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) yield null;
                List<Object> values = new ArrayList<>(count);
                for (int index = 0; index < count; index++) values.add(read(in));
                yield values;
            }
            default -> throw new IOException("Unexpected Redis reply type: " + (char) type);
        };
    }

    private static String line(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int next = in.read();
            if (next < 0) throw new EOFException("Redis closed the connection");
            if (previous == '\r' && next == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buffer.write(next);
            previous = next;
        }
    }

    @Override public void close() {
        running = false;
        synchronized (subscriberLock) {
            if (subscriber != null) try { subscriber.close(); } catch (IOException ignored) { }
        }
        executor.shutdown();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        closePublisher();
        if (subscriberThread != null) subscriberThread.interrupt();
    }
}
