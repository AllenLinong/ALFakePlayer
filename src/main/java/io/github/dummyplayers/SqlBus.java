package io.github.dummyplayers;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 未启用 Redis 时，通过 MySQL 的 messages 表传递跨服消息。
 * 每个子服定时读取发给自己或广播的新消息；两分钟前的消息会被清理。
 */
final class SqlBus implements Network.Bus {
    /** 自增 ID 可能乱序提交，每次往回多看一段并按 ID 去重。 */
    private static final long LOOKBACK = 50;

    private final Database db;
    private final String serverId;
    private final long pollMillis;
    private final Logger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ALFakePlayer-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final TreeSet<Long> seen = new TreeSet<>();
    private Consumer<String> sink;
    private long lastId, lastCleanup, lastWarning;

    SqlBus(Database db, String serverId, long pollMillis, Logger logger) {
        this.db = db;
        this.serverId = serverId;
        this.pollMillis = pollMillis;
        this.logger = logger;
    }

    @Override public void start(Consumer<String> sink) throws SQLException {
        this.sink = sink;
        lastId = db.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM " + db.table("messages"));
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        });
        executor.scheduleWithFixedDelay(this::poll, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    @Override public void publish(String target, String message) {
        try {
            executor.execute(() -> {
                try {
                    db.withConnection(connection -> Database.update(connection, "INSERT INTO " + db.table("messages")
                        + " (target, payload, created) VALUES (?, ?, " + db.now() + ")", target, message));
                } catch (SQLException error) {
                    warn("Could not send a cross-server message", error);
                }
            });
        } catch (RejectedExecutionException stopped) {
            // 插件正在关闭
        }
    }

    private void poll() {
        try {
            List<Map.Entry<Long, String>> received = db.withConnection(connection -> {
                List<Map.Entry<Long, String>> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("SELECT id, payload FROM " + db.table("messages")
                        + " WHERE id > ? AND (target = ? OR target = '*') ORDER BY id LIMIT 500")) {
                    statement.setLong(1, Math.max(0, lastId - LOOKBACK));
                    statement.setString(2, serverId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) rows.add(Map.entry(result.getLong(1), result.getString(2)));
                    }
                }
                return rows;
            });
            for (var row : received) {
                if (!seen.add(row.getKey())) continue;
                lastId = Math.max(lastId, row.getKey());
                sink.accept(row.getValue());
            }
            seen.headSet(lastId - LOOKBACK * 4).clear();
            long now = System.currentTimeMillis();
            if (now - lastCleanup > 60_000) {
                lastCleanup = now;
                db.withConnection(connection -> Database.update(connection, "DELETE FROM " + db.table("messages")
                    + " WHERE created < " + db.now() + " - 120000"));
            }
        } catch (SQLException | RuntimeException error) {
            warn("Could not read cross-server messages", error);
        }
    }

    private void warn(String message, Exception error) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < 60_000) return;
        lastWarning = now;
        logger.warning(message + ": " + error.getMessage());
    }

    @Override public void close() {
        executor.shutdown();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
