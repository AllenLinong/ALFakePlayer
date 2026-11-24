package io.github.dummyplayers;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TabBenchmark {
    static final int MAX_ENTRIES = 80;
    private final DummyPlayers plugin;
    private final Map<UUID, ServerGamePacketListenerImpl> viewers = new HashMap<>();
    private List<UUID> ids = List.of();
    private ClientboundPlayerInfoUpdatePacket entries;

    TabBenchmark(DummyPlayers plugin) { this.plugin = plugin; }

    void command(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("stop")) {
            plugin.msg(sender, "benchmark-stopped", "count", clear());
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("spawn")) {
            try {
                int count = Integer.parseInt(args[2]);
                if (count >= 1 && count <= MAX_ENTRIES) {
                    Set<String> occupied = new HashSet<>();
                    for (Player player : Bukkit.getOnlinePlayers()) occupied.add(player.getName().toLowerCase(Locale.ROOT));
                    for (Roster.Entry entry : plugin.roster.entries.values()) occupied.add(entry.nativeName().toLowerCase(Locale.ROOT));
                    replace(count, occupied);
                    plugin.msg(sender, "benchmark-spawned", "count", count);
                    return;
                }
            } catch (NumberFormatException ignored) { }
        }
        plugin.msg(sender, "benchmark-help", "max", MAX_ENTRIES);
    }

    private synchronized void replace(int count, Set<String> occupied) {
        var batch = new ArrayList<Entry>(count);
        var newIds = new ArrayList<UUID>(count);
        for (int index = 0; index < count; index++) {
            UUID id;
            String name;
            do {
                id = UUID.randomUUID();
                name = Roster.Entry.defaultName(id);
            } while (!occupied.add(name.toLowerCase(Locale.ROOT)));
            var profile = new GameProfile(id, name);
            batch.add(new Entry(id, profile, true, 0, GameType.SURVIVAL, null, true, 0, null));
            newIds.add(id);
        }
        clear();
        ids = List.copyOf(newIds);
        entries = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED,
            Action.UPDATE_GAME_MODE, Action.UPDATE_LATENCY, Action.UPDATE_HAT), List.copyOf(batch));
        send(entries);
    }

    synchronized void join(Player player) {
        if (!(player instanceof CraftPlayer viewer) || viewer.getHandle() instanceof NativeDummy) return;
        var connection = viewer.getHandle().connection;
        if (connection == null || viewers.put(player.getUniqueId(), connection) == connection) return;
        if (entries != null) connection.send(entries);
    }

    synchronized void quit(Player player) {
        if (player instanceof CraftPlayer viewer) viewers.remove(player.getUniqueId(), viewer.getHandle().connection);
    }

    synchronized int clear() {
        int count = ids.size();
        if (count != 0) send(new ClientboundPlayerInfoRemovePacket(ids));
        ids = List.of();
        entries = null;
        return count;
    }

    synchronized void close() {
        clear();
        viewers.clear();
    }

    private void send(Packet<?> packet) {
        viewers.values().forEach(connection -> connection.send(packet));
    }
}
