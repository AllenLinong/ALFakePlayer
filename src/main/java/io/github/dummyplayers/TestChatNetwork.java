package io.github.dummyplayers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * TAB 测试假人聊天的跨服部分（database.yml 用 mysql 时）。
 *
 * <p>全网的测试假人和真人在同一个聊天里。有测试假人、开了聊天的子服中 server_id 排序最前的一个负责安排：
 * 它的 {@link TestChat} 决定全网的测试假人谁说、说什么；其他子服把本服真人的发言和进服转给它。
 * 话由测试假人所在的子服说出来，聊天插件显示的子服才是对的。</p>
 *
 * <p>说出的话会广播给其他子服。聊天插件可能已经把这句话跨服转发过去了，也可能没有：每个子服都看着发给本服真人的聊天数据包，
 * 收到广播后等一会儿，没看到聊天插件转发来的这句话，才按本服 tab_chat.yml 的 format 补发。补发之后又看到转发来的，
 * 说明这个聊天插件转发得慢，之后多等一会儿。这样不管装的是哪个聊天插件、会不会跨服转发，每句话都只显示一次。</p>
 */
final class TestChatNetwork {
    private static final String WATCHER = "alfakeplayer_test_chat";
    /** 本服真人名单多久广播一次，以及多久没更新就作废。 */
    private static final long PRESENCE_MILLIS = 15_000, PRESENCE_EXPIRE = 40_000;
    /** 离开某个子服后多久内在另一个子服出现算换服，不欢迎。 */
    private static final long SWITCH_MILLIS = 15_000;
    /** 等聊天插件转发的时间：起始值和上限。补发后多久内还在看有没有迟到的转发。 */
    private static final long WAIT_START = 1_500, WAIT_MAX = 6_000, LATE_MILLIS = 10_000;

    private record Presence(Set<UUID> players, long at) { }
    private record Seen(String text, long at) { }
    /** 补发过的一句：之后又看到聊天插件转发来的同一句，说明它转发得慢。 */
    private record Late(String server, String name, String text, long arrived, long until) { }
    private record Leave(Runnable leave, long at) { }

    private final DummyPlayers plugin;
    private final TestChat chat;
    /** 其他子服的真人名单。 */
    private final Map<String, Presence> presence = new ConcurrentHashMap<>();
    /** 刚离开某个子服的真人 → 离开时间。 */
    private final Map<UUID, Long> gone = new ConcurrentHashMap<>();
    /** 每个子服说的话，等它的聊天插件转发多久。 */
    private final Map<String, Long> waits = new ConcurrentHashMap<>();
    /** 本服测试假人等着说再见的下线动作，小写名字 → 动作。 */
    private final Map<String, Leave> farewells = new ConcurrentHashMap<>();
    /** 最近发给本服真人的聊天（规范化后的文字），以及补发过的、本插件自己发的数据包。受 seen 锁保护。 */
    private final ArrayDeque<Seen> seen = new ArrayDeque<>();
    private final List<Late> late = new ArrayList<>();
    private final ArrayDeque<Object> own = new ArrayDeque<>();
    /** 全网有测试假人、本服参与聊天时为 true：这时才记录聊天数据包、广播真人名单。 */
    private volatile boolean armed;
    private volatile boolean presenceChanged;
    private long lastPresence;
    private ScheduledTask task;

    TestChatNetwork(DummyPlayers plugin, TestChat chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    boolean enabled() { return plugin.network != null && plugin.network.enabled(); }

    /** 本服参与跨服聊天：开了跨服同步，本服 tab_chat.yml 开了聊天和 cross_server。 */
    boolean participating() {
        var settings = chat.settings();
        return enabled() && settings != null && settings.enabled() && settings.crossServer();
    }

    /**
     * 负责安排聊天的子服：参与跨服聊天、有测试假人的子服中 server_id 最小的一个；全网都没有时为 null。
     * 本服不参与跨服聊天时总是本服（只管本服的测试假人和真人）。会读 TabBenchmark 的锁，不能在持有 TestChat 的锁时调用。
     */
    String leader() {
        if (!participating()) return plugin.serverId;
        String best = plugin.benchmark().count() > 0 ? plugin.serverId : null;
        for (String server : plugin.remoteChatHosts().keySet()) if (best == null || server.compareTo(best) < 0) best = server;
        return best;
    }

    boolean leading() { return plugin.serverId.equals(leader()); }

    /** 其他子服 TAB 中、开了聊天的测试假人：子服 → (UUID → 名字)。 */
    Map<String, Map<UUID, String>> remoteHosts() { return participating() ? plugin.remoteChatHosts() : Map.of(); }

    /** 这个名字的测试假人在哪个其他子服；不在其他子服时为 null。 */
    String host(String name) {
        for (var server : remoteHosts().entrySet()) {
            for (String shown : server.getValue().values()) if (shown.equalsIgnoreCase(name)) return server.getKey();
        }
        return null;
    }

    // ══ 生命周期 ══════════════════════════════════════════

    void start() {
        if (!enabled() || task != null) return;
        Bukkit.getOnlinePlayers().forEach(this::watch);
        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, unused -> tick(), 1, 1, TimeUnit.SECONDS);
    }

    void stop() {
        if (task != null) { task.cancel(); task = null; }
        // 告诉其他子服本服的真人都不在了：他们几秒内出现在别的子服时算换服
        if (armed) broadcastPresence(Set.of());
        armed = false;
        for (Player player : Bukkit.getOnlinePlayers()) unwatch(player);
        farewells.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        boolean active = participating() && (plugin.benchmark().count() > 0 || !plugin.remoteChatHosts().isEmpty());
        armed = active;
        if (active && (presenceChanged || now - lastPresence >= PRESENCE_MILLIS)) {
            presenceChanged = false;
            lastPresence = now;
            broadcastPresence(localReal());
        }
        presence.entrySet().removeIf(entry -> now - entry.getValue().at() > PRESENCE_EXPIRE
            || !plugin.roster.online(entry.getKey(), plugin.network.settings.timeoutMillis()));
        gone.values().removeIf(at -> now - at > SWITCH_MILLIS);
        farewells.values().removeIf(pending -> now - pending.at() > 60_000);
        synchronized (seen) {
            while (!seen.isEmpty() && now - seen.peekFirst().at() > lookback() + WAIT_MAX) seen.pollFirst();
            late.removeIf(entry -> entry.until() < now);
        }
    }

    // ══ 真人名单（全网人数、换服） ═══════════════════════

    private static Set<UUID> localReal() {
        var result = new HashSet<UUID>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player instanceof CraftPlayer craft && !(craft.getHandle() instanceof NativeDummy)) result.add(player.getUniqueId());
        }
        return result;
    }

    private void broadcastPresence(Set<UUID> players) {
        var ids = new JsonArray();
        players.forEach(id -> ids.add(id.toString()));
        var body = new JsonObject();
        body.add("players", ids);
        plugin.network.broadcast("tab_chat_presence", body);
    }

    /** 在线的真人：参与跨服聊天时为全网的（本服的加上其他子服最近报告的，同一个人只算一次），否则为本服的。 */
    int realOnline() {
        Set<UUID> players = localReal();
        if (participating()) presence.values().forEach(entry -> players.addAll(entry.players()));
        return players.size();
    }

    /** 真人刚在 server 进服：他还在别的子服，或者刚从别的子服离开，是换服，不是进服。不参与跨服聊天时总是 false。 */
    boolean switched(UUID player, String server) {
        if (!participating()) return false;
        Long left = gone.get(player);
        if (left != null && System.currentTimeMillis() - left < SWITCH_MILLIS) return true;
        for (var entry : presence.entrySet()) {
            if (!entry.getKey().equals(server) && entry.getValue().players().contains(player)) return true;
        }
        return !server.equals(plugin.serverId) && localReal().contains(player);
    }

    void joined(Player player) {
        if (!enabled()) return;
        watch(player);
        presenceChanged = true;
    }

    void quit(Player player) {
        if (!enabled()) return;
        gone.put(player.getUniqueId(), System.currentTimeMillis());
        presenceChanged = true;
    }

    // ══ 转给负责安排的子服 ════════════════════════════════

    /** 负责安排的是其他子服时，把事件发给它，结果为发给了谁；本服负责或全网都没有测试假人时为 null。 */
    private String toLeader(String type, JsonObject body) {
        if (!participating()) return null;
        String leader = leader();
        if (leader == null || leader.equals(plugin.serverId)) return null;
        plugin.network.send(leader, type, body);
        return leader;
    }

    void heard(UUID player, String name, String text) {
        var body = new JsonObject();
        body.addProperty("id", player.toString());
        body.addProperty("name", name);
        body.addProperty("text", text);
        toLeader("tab_chat_heard", body);
    }

    void realJoined(UUID player, String name, boolean firstTime) {
        var body = new JsonObject();
        body.addProperty("id", player.toString());
        body.addProperty("name", name);
        body.addProperty("first", firstTime);
        toLeader("tab_chat_join", body);
    }

    /** 本服的测试假人刚进服：让负责安排的子服决定要不要打招呼。 */
    void testJoined(String name, UUID id) {
        var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("id", id.toString());
        toLeader("tab_chat_greet", body);
    }

    /**
     * 本服的测试假人下线前说一声：请负责安排的子服排队，说出口后再调用 leave。结果为 false 时直接下线。
     * 由 TabBenchmark 调用（它持有自己的锁），leave 总是在另一个线程执行。
     */
    boolean farewell(String name, UUID id, Runnable leave) {
        if (!participating()) return false;
        String leader = leader();
        if (leader == null || leader.equals(plugin.serverId)) return false;
        String key = name.toLowerCase(Locale.ROOT);
        farewells.put(key, new Leave(leave, System.currentTimeMillis()));
        var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("id", id.toString());
        plugin.network.request(leader, "tab_chat_farewell", body).whenComplete((reply, error) -> {
            if (error == null && reply.has("queued") && reply.get("queued").getAsBoolean()) return;
            Leave pending = farewells.remove(key);
            if (pending != null) Bukkit.getAsyncScheduler().runNow(plugin, unused -> pending.leave().run());
        });
        return true;
    }

    /** 让 server 上的测试假人说一句。 */
    void say(String server, String name, String text, boolean farewell, boolean manual) {
        var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("text", text);
        body.addProperty("farewell", farewell);
        body.addProperty("manual", manual);
        plugin.network.send(server, "tab_chat_say", body);
    }

    /** 本服的测试假人说了一句：告诉其他子服，它们没收到聊天插件转发的就补发。manual 为 /afp test say。 */
    void said(String name, String group, String text, boolean viaChat, boolean manual) {
        if (!armed) return;
        var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("group", group);
        body.addProperty("text", text);
        body.addProperty("chat", viaChat);
        body.addProperty("manual", manual);
        plugin.network.broadcast("tab_chat_line", body);
    }

    // ══ 收到其他子服的消息 ════════════════════════════════

    void handle(String type, String from, JsonObject body, Consumer<JsonObject> reply) {
        if (type.equals("tab_chat_presence")) {
            var players = new HashSet<UUID>();
            for (var element : body.getAsJsonArray("players")) players.add(UUID.fromString(element.getAsString()));
            long now = System.currentTimeMillis();
            Presence previous = presence.put(from, new Presence(Set.copyOf(players), now));
            if (previous != null) for (UUID id : previous.players()) if (!players.contains(id)) gone.put(id, now);
            return;
        }
        if (!participating()) {
            if (type.equals("tab_chat_farewell")) reply.accept(queued(false));
            return;
        }
        // 下面的处理可能要等 LuckPerms、调用聊天流程，不占用收消息的线程
        Bukkit.getAsyncScheduler().runNow(plugin, unused -> {
            try {
                switch (type) {
                    case "tab_chat_heard" -> chat.heard(UUID.fromString(string(body, "id")), string(body, "name"), string(body, "text"));
                    case "tab_chat_join" -> {
                        UUID id = UUID.fromString(string(body, "id"));
                        if (!switched(id, from)) chat.joined(id, string(body, "name"), body.get("first").getAsBoolean());
                    }
                    case "tab_chat_greet" -> chat.greet(string(body, "name"), UUID.fromString(string(body, "id")));
                    case "tab_chat_farewell" -> reply.accept(queued(chat.remoteFarewell(string(body, "name"), UUID.fromString(string(body, "id")))));
                    case "tab_chat_say" -> {
                        String name = string(body, "name");
                        chat.speakHere(name, string(body, "text"), body.get("manual").getAsBoolean());
                        if (body.get("farewell").getAsBoolean()) {
                            Leave pending = farewells.remove(name.toLowerCase(Locale.ROOT));
                            if (pending != null) {
                                Bukkit.getAsyncScheduler().runDelayed(plugin, later -> pending.leave().run(),
                                    ThreadLocalRandom.current().nextLong(3000, 9001), TimeUnit.MILLISECONDS);
                            }
                        }
                    }
                    case "tab_chat_line" -> line(from, string(body, "name"), string(body, "group"), string(body, "text"),
                        body.get("chat").getAsBoolean(), body.get("manual").getAsBoolean());
                    default -> { }
                }
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().log(Level.WARNING, "Could not handle cross-server test chat message " + type, error);
            }
        });
    }

    private static String string(JsonObject body, String key) { return body.get(key).getAsString(); }

    private static JsonObject queued(boolean value) {
        var body = new JsonObject();
        body.addProperty("queued", value);
        return body;
    }

    /** 其他子服的测试假人说了一句：按设置等聊天插件转发，没等到就补发。 */
    private void line(String from, String name, String group, String text, boolean viaChat, boolean manual) {
        if (manual) chat.heardTest(name, text);
        if (localReal().isEmpty()) return;
        String forwarded = chat.settings().forwarded();
        if (viaChat && forwarded.equals("true")) return;
        if (!viaChat || forwarded.equals("false")) { show(name, group, text); return; }
        long arrived = System.currentTimeMillis();
        String normalName = normalize(name), normalText = normalize(text);
        Bukkit.getAsyncScheduler().runDelayed(plugin, unused -> {
            synchronized (seen) {
                for (Seen entry : seen) {
                    if (entry.at() >= arrived - lookback() && entry.text().contains(normalText) && entry.text().contains(normalName)) return;
                }
                late.add(new Late(from, normalName, normalText, arrived, System.currentTimeMillis() + LATE_MILLIS));
            }
            show(name, group, text);
        }, waits.getOrDefault(from, WAIT_START), TimeUnit.MILLISECONDS);
    }

    /** 聊天插件转发来的话可能比本插件的广播先到：MySQL 轮询时广播最多晚一个轮询间隔。 */
    private long lookback() {
        var settings = plugin.network.settings;
        return (settings.redis() ? 0 : settings.pollMillis()) + 3_000;
    }

    /** 按本服 tab_chat.yml 的 format 发给本服真人。 */
    private void show(String name, String group, String text) {
        String line = chat.settings().format().replace("%prefix%", plugin.groupPrefix(group)).replace("%name%", name)
            .replace("%message%", text);
        Component message = plugin.legacy(line);
        var packet = new ClientboundSystemChatPacket(message, false);
        synchronized (seen) {
            own.addLast(packet);
            while (own.size() > 32) own.pollFirst();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!TestChat.real(player)) continue;
            var connection = ((CraftPlayer) player).getHandle().connection;
            if (connection != null) connection.send(packet);
        }
    }

    // ══ 看着发给真人的聊天数据包 ══════════════════════════

    private void watch(Player player) {
        Channel channel = channel(player);
        if (channel == null) return;
        try {
            var pipeline = channel.pipeline();
            if (pipeline.get(WATCHER) != null) pipeline.remove(WATCHER);
            if (pipeline.get("packet_handler") != null) pipeline.addBefore("packet_handler", WATCHER, new Watcher());
        } catch (RuntimeException error) {
            // 连接正在断开，或其他线程刚改过管道
        }
    }

    private static void unwatch(Player player) {
        Channel channel = channel(player);
        if (channel == null) return;
        try { if (channel.pipeline().get(WATCHER) != null) channel.pipeline().remove(WATCHER); }
        catch (RuntimeException ignored) { }
    }

    private static Channel channel(Player player) {
        if (!(player instanceof CraftPlayer craft) || craft.getHandle() instanceof NativeDummy) return null;
        var listener = craft.getHandle().connection;
        return listener == null || listener.connection == null ? null : listener.connection.channel;
    }

    /** 在 Netty 线程上调用。 */
    private void observe(Object packet) {
        if (!armed) return;
        String raw;
        // 玩家聊天类数据包不套聊天类型的格式取文字：插件注册的聊天类型的翻译键服务端不认识，套上后名字和内容会丢
        if (packet instanceof ClientboundSystemChatPacket system && !system.overlay()) raw = system.content().getString();
        else if (packet instanceof ClientboundPlayerChatPacket player) {
            raw = player.chatType().name().getString() + " "
                + (player.unsignedContent() != null ? player.unsignedContent().getString() : player.body().content());
        } else if (packet instanceof ClientboundDisguisedChatPacket disguised) {
            raw = disguised.chatType().name().getString() + " " + disguised.message().getString();
        } else return;
        synchronized (seen) {
            for (Object mine : own) if (mine == packet) return;
        }
        String text = normalize(raw);
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (seen) {
            // 同一句发给每个真人各一个数据包，只记一次
            if (seen.isEmpty() || !seen.peekLast().text().equals(text) || now - seen.peekLast().at() > 1_000) seen.addLast(new Seen(text, now));
            while (seen.size() > 500) seen.pollFirst();
            for (Iterator<Late> iterator = late.iterator(); iterator.hasNext(); ) {
                Late entry = iterator.next();
                if (!text.contains(entry.text()) || !text.contains(entry.name())) continue;
                iterator.remove();
                long wait = Math.min(WAIT_MAX, now - entry.arrived() + 1_000);
                if (wait > waits.getOrDefault(entry.server(), WAIT_START)) {
                    waits.put(entry.server(), wait);
                    plugin.getLogger().info("The chat plugin forwarded a test player's chat from " + entry.server() + " after "
                        + (now - entry.arrived()) + " ms; now waiting " + wait + " ms before showing it here.");
                }
            }
        }
    }

    /** 小写，去掉颜色代码和空白：聊天插件排版后，名字和内容还能对上。 */
    private static String normalize(String text) {
        var result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '§' && index + 1 < text.length()) { index++; continue; }
            if (!Character.isWhitespace(c)) result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    private final class Watcher extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { observe(message); }
            catch (RuntimeException | LinkageError ignored) { }
            super.write(context, message, promise);
        }
    }
}
