package io.github.dummyplayers;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨服 TAB：在本服玩家的 TAB 列表中显示正在其他子服运行的假人，以及其他子服 /afp test 添加的测试条目。
 * 运行假人的子服在显示名（前缀、名字、称号）变化时广播出来，测试条目在变化时整份广播；
 * 本服每秒对照各子服的运行状态增删条目，子服心跳超时后它的条目一并删除。
 * 只发送 TAB 数据包，不改变本服在线人数，其他插件也看不到这些条目。
 */
final class RemoteTab {
    static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();
    private static final EnumSet<Action> ADD = EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED,
        Action.UPDATE_GAME_MODE, Action.UPDATE_LATENCY, Action.UPDATE_DISPLAY_NAME, Action.UPDATE_HAT);
    private static final EnumSet<Action> RENAME = EnumSet.of(Action.UPDATE_DISPLAY_NAME);

    /** name：游戏档案名（决定 TAB 排序和皮肤）；label：显示名的 JSON。 */
    private record Shown(String name, String label) { }

    private final DummyPlayers plugin;
    private final Map<UUID, ServerGamePacketListenerImpl> viewers = new HashMap<>();
    /** 已发送给本服玩家的条目。受 this 锁保护。 */
    private final Map<UUID, Shown> shown = new HashMap<>();
    /** 其他子服广播的显示名：假人 → (档案名, 显示名 JSON)。 */
    private final Map<UUID, Shown> labels = new ConcurrentHashMap<>();
    /** 其他子服的测试条目：子服 ID → (UUID → 条目)，显示名在收到时就算好。 */
    private final Map<String, Map<UUID, Shown>> tests = new ConcurrentHashMap<>();

    RemoteTab(DummyPlayers plugin) { this.plugin = plugin; }

    /** 收到其他子服广播的显示名。 */
    void label(UUID dummy, String name, String label) { labels.put(dummy, new Shown(name, label)); }

    /** 收到其他子服的测试条目（整份替换，空表示已清除）。值为 {档案名, 显示名 JSON}，显示名缺失时只显示名字。 */
    void tests(String server, Map<UUID, String[]> entries) {
        if (entries.isEmpty()) { tests.remove(server); return; }
        Map<UUID, Shown> shown = new HashMap<>();
        entries.forEach((id, item) -> shown.put(id, new Shown(item[0], item[1] != null ? item[1] : JSON.serialize(Component.text(item[0])))));
        tests.put(server, Map.copyOf(shown));
    }

    /** 在线子服正在显示的测试条目名字（小写）。 */
    java.util.Set<String> testNames() {
        var names = new java.util.HashSet<String>();
        for (var server : tests.entrySet()) {
            if (online(server.getKey())) server.getValue().values().forEach(shown -> names.add(shown.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return names;
    }

    /** 在线子服的测试条目数，计入在线人数变量。 */
    int remoteTests() {
        int count = 0;
        for (var server : tests.entrySet()) if (online(server.getKey())) count += server.getValue().size();
        return count;
    }

    private boolean online(String server) {
        return plugin.roster.online(server, plugin.network.settings.timeoutMillis()) && !server.equals(plugin.serverId);
    }

    /** 对照其他子服正在运行的假人，增删、更新本服玩家 TAB 中的条目。可在任意线程调用。 */
    synchronized void sync() {
        Map<UUID, Shown> wanted = new HashMap<>();
        plugin.roster.remoteSessions().forEach((id, server) -> {
            if (plugin.live.containsKey(id)) return;
            Roster.Entry entry = plugin.roster.byId.get(id);
            Shown broadcast = labels.get(id);
            if (entry == null && broadcast == null) return;
            String name = entry != null ? entry.nativeName() : broadcast.name();
            // 还没收到显示名时先显示名字，不带前缀
            String label = broadcast != null && broadcast.name().equals(name) ? broadcast.label()
                : JSON.serialize(Component.text(entry != null ? entry.name() : name));
            wanted.put(id, new Shown(name, label));
        });
        tests.forEach((server, entries) -> {
            if (online(server)) entries.forEach(wanted::putIfAbsent);
        });
        List<UUID> removed = new ArrayList<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> added = new ArrayList<>(), renamed = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Shown>> iterator = shown.entrySet().iterator(); iterator.hasNext(); ) {
            var current = iterator.next();
            Shown next = wanted.get(current.getKey());
            if (next != null && next.name().equals(current.getValue().name())) continue;
            iterator.remove();
            // 假人已经回到本服运行时，条目由本服的实体接管，不能删除，否则客户端不显示这个玩家
            if (!plugin.live.containsKey(current.getKey())) removed.add(current.getKey());
        }
        wanted.forEach((id, next) -> {
            Shown previous = shown.put(id, next);
            if (previous == null) added.add(entry(id, next));
            else if (!previous.label().equals(next.label())) renamed.add(entry(id, next));
        });
        if (!removed.isEmpty()) send(new ClientboundPlayerInfoRemovePacket(removed));
        if (!added.isEmpty()) send(new ClientboundPlayerInfoUpdatePacket(ADD, added));
        if (!renamed.isEmpty()) send(new ClientboundPlayerInfoUpdatePacket(RENAME, renamed));
    }

    /** 假人开始在本服运行：条目交给本服的实体，不再由这里管理（不发送删除，客户端已有的条目沿用）。 */
    synchronized void claimLocal(UUID dummy) { shown.remove(dummy); }

    synchronized void join(Player player) {
        if (!(player instanceof CraftPlayer viewer) || viewer.getHandle() instanceof NativeDummy) return;
        var connection = viewer.getHandle().connection;
        if (connection == null || viewers.put(player.getUniqueId(), connection) == connection || shown.isEmpty()) return;
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
        shown.forEach((id, entry) -> entries.add(entry(id, entry)));
        connection.send(new ClientboundPlayerInfoUpdatePacket(ADD, entries));
    }

    synchronized void quit(Player player) {
        if (player instanceof CraftPlayer viewer) viewers.remove(player.getUniqueId(), viewer.getHandle().connection);
    }

    synchronized void close() {
        List<UUID> ids = shown.keySet().stream().filter(id -> !plugin.live.containsKey(id)).toList();
        if (!ids.isEmpty()) send(new ClientboundPlayerInfoRemovePacket(ids));
        shown.clear();
        viewers.clear();
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry entry(UUID id, Shown shown) {
        net.minecraft.network.chat.Component display;
        try { display = PaperAdventure.asVanilla(JSON.deserialize(shown.label())); }
        catch (RuntimeException invalid) { display = net.minecraft.network.chat.Component.literal(shown.name()); }
        return new ClientboundPlayerInfoUpdatePacket.Entry(id, new GameProfile(id, shown.name()), true, 0,
            GameType.SURVIVAL, display, true, 0, null);
    }

    private void send(Packet<?> packet) {
        viewers.values().forEach(connection -> connection.send(packet));
    }
}
