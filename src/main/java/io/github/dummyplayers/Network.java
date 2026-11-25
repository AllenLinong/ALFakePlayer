package io.github.dummyplayers;

import com.google.common.io.ByteStreams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 子服之间的消息。启用 Redis 时走 Redis 发布/订阅，否则走 MySQL 消息表轮询。
 * 单服（SQLite）时不启用，所有发送都是空操作。
 */
final class Network {
    static final String PROXY_CHANNEL = "BungeeCord";

    record Settings(boolean redis, String host, int port, String password, String channel,
                    long heartbeatMillis, long timeoutMillis, long pollMillis, long requestMillis) {
        static Settings read(ConfigurationSection yaml) {
            long heartbeat = Math.clamp(yaml.getLong("sync.heartbeat_seconds", 10), 5, 60) * 1000L;
            long timeout = Math.max(heartbeat * 3, Math.clamp(yaml.getLong("sync.timeout_seconds", 60), 15, 600) * 1000L);
            String channel = yaml.getString("redis.channel", "alfakeplayer_channel").strip();
            if (channel.isEmpty()) throw new IllegalArgumentException("database.yml: redis.channel must not be empty");
            int port = yaml.getInt("redis.port", 6379);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("database.yml: redis.port is out of range");
            return new Settings(yaml.getBoolean("redis.enabled", false), yaml.getString("redis.host", "localhost").strip(),
                port, yaml.getString("redis.password", ""), channel, heartbeat, timeout,
                Math.clamp(yaml.getLong("sync.poll_interval_millis", 1000), 200, 5000),
                Math.clamp(yaml.getLong("sync.request_timeout_seconds", 10), 3, 60) * 1000L);
        }
    }

    /** 消息传输方式。 */
    interface Bus {
        void start(Consumer<String> sink) throws Exception;
        void publish(String target, String message);
        void close();
    }

    @FunctionalInterface interface Handler {
        void handle(String type, String from, JsonObject body, Consumer<JsonObject> reply);
    }

    private final JavaPlugin plugin;
    final String serverId;
    final Settings settings;
    private final boolean enabled;
    private final Map<String, CompletableFuture<JsonObject>> requests = new ConcurrentHashMap<>();
    private volatile Bus bus;
    private Handler handler;

    Network(JavaPlugin plugin, String serverId, Settings settings, boolean enabled) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.settings = settings;
        this.enabled = enabled;
    }

    boolean enabled() { return enabled; }

    void start(Database database, Handler handler) throws Exception {
        if (!enabled) return;
        this.handler = handler;
        Bus selected = settings.redis
            ? new RedisBus(settings.host, settings.port, settings.password, settings.channel, plugin.getLogger())
            : new SqlBus(database, serverId, settings.pollMillis, plugin.getLogger());
        selected.start(this::receive);
        bus = selected;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PROXY_CHANNEL);
        plugin.getLogger().info("Cross-server sync enabled as '" + serverId + "' via " + (settings.redis ? "Redis" : "MySQL polling") + ".");
    }

    void send(String target, String type, JsonObject body) { publish(target, type, body, null, null); }

    void broadcast(String type, JsonObject body) { publish("*", type, body, null, null); }

    /** 向指定子服发送请求，等待它回复；超时后以 TimeoutException 结束。 */
    CompletableFuture<JsonObject> request(String target, String type, JsonObject body) {
        if (!enabled || bus == null) return CompletableFuture.failedFuture(new IllegalStateException("Cross-server sync is disabled"));
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<JsonObject>();
        requests.put(id, future);
        future.orTimeout(settings.requestMillis, TimeUnit.MILLISECONDS).whenComplete((result, error) -> requests.remove(id));
        publish(target, type, body, id, null);
        return future;
    }

    private void publish(String target, String type, JsonObject body, String id, String replyTo) {
        Bus current = bus;
        if (!enabled || current == null) return;
        var envelope = new JsonObject();
        envelope.addProperty("from", serverId);
        envelope.addProperty("to", target);
        envelope.addProperty("type", type);
        if (id != null) envelope.addProperty("id", id);
        if (replyTo != null) envelope.addProperty("re", replyTo);
        envelope.add("body", body == null ? new JsonObject() : body);
        current.publish(target, envelope.toString());
    }

    private void receive(String raw) {
        try {
            JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
            String from = envelope.get("from").getAsString();
            String to = envelope.get("to").getAsString();
            if (from.equals(serverId) || !to.equals(serverId) && !to.equals("*")) return;
            String type = envelope.get("type").getAsString();
            JsonObject body = envelope.has("body") ? envelope.getAsJsonObject("body") : new JsonObject();
            if (type.equals("reply")) {
                var future = envelope.has("re") ? requests.remove(envelope.get("re").getAsString()) : null;
                if (future != null) future.complete(body);
                return;
            }
            String id = envelope.has("id") ? envelope.get("id").getAsString() : null;
            Consumer<JsonObject> reply = id == null ? ignored -> { } : answer -> publish(from, "reply", answer, null, id);
            handler.handle(type, from, body, reply);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Ignored malformed cross-server message", error);
        }
    }

    /** 通过 BungeeCord/Velocity 把玩家送到另一个子服。必须在玩家所在线程调用。 */
    void connect(Player player, String server) {
        var out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.sendPluginMessage(plugin, PROXY_CHANNEL, out.toByteArray());
    }

    void close() {
        Bus current = bus;
        bus = null;
        if (current != null) current.close();
        requests.values().forEach(future -> future.cancel(false));
        requests.clear();
    }
}
