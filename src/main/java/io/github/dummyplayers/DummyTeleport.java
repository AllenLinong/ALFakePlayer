package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class DummyTeleport {
    private static final String BYPASS = "alfakeplayer.teleport.bypass";
    private final DummyPlayers plugin;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** 从其他子服跨服传送过来、等待到达的玩家：玩家 → 目标假人。 */
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();
    private record Arrival(UUID dummy, long expires) { }
    private int delaySeconds, cooldownSeconds;

    DummyTeleport(DummyPlayers plugin) { this.plugin = plugin; }

    void configure(int delaySeconds, int cooldownSeconds) {
        this.delaySeconds = delaySeconds;
        this.cooldownSeconds = cooldownSeconds;
    }

    void request(Player player, int slot, boolean bring) {
        UUID owner = player.getUniqueId();
        Roster.Entry entry = plugin.roster.entries.get(new Roster.Key(owner, slot));
        DummyPlayers.Live active = entry == null ? null : plugin.live.get(entry.id);
        String remote = null;
        if (entry == null || !plugin.allowed(player, entry)) {
            plugin.msg(player, "teleport_offline");
            return;
        }
        if (active == null || !online(active)) {
            // 假人在其他子服运行：tph 把它召回本服，tp 把玩家送过去
            remote = plugin.network.enabled() ? plugin.roster.remoteHolder(entry) : null;
            if (remote == null) { plugin.msg(player, "teleport_offline"); return; }
            if (bring) { plugin.transfer(player, entry, remote); return; }
        }
        if (!pending.add(owner)) { plugin.msg(player, "teleport_pending"); return; }
        boolean limited = !bring && !player.hasPermission(BYPASS);
        Long deadline = limited ? cooldowns.get(owner) : null;
        if (deadline != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                pending.remove(owner);
                plugin.msg(player, "teleport_cooldown", "seconds", Math.ceilDiv(remaining, TimeUnit.SECONDS.toNanos(1)));
                return;
            }
        }
        String server = remote;
        if (!limited || delaySeconds == 0) {
            if (server != null) travel(player, entry, server, limited);
            else execute(player, active, bring, limited);
            return;
        }
        plugin.msg(player, "teleport_wait", "seconds", delaySeconds);
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            if (!player.getScheduler().execute(plugin, () -> {
                if (player.isDead()) finish(player, owner, slot, "teleport_failed");
                else if (server != null) travel(player, entry, server, true);
                else execute(player, active, false, true);
            }, () -> pending.remove(owner), 1)) pending.remove(owner);
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /** 跨服传送：通知目标子服等待玩家，再通过代理把玩家送过去。在玩家线程调用。 */
    private void travel(Player player, Roster.Entry entry, String server, boolean limited) {
        UUID owner = player.getUniqueId();
        int slot = entry.key.slot();
        if (!plugin.allowed(player, entry) || !player.hasPermission(DummyPlayers.TELEPORT)) {
            finish(player, owner, slot, "no_permission");
            return;
        }
        if (!server.equals(plugin.roster.remoteHolder(entry))) {
            finish(player, owner, slot, "teleport_offline");
            return;
        }
        var body = new JsonObject();
        body.addProperty("player", owner.toString());
        body.addProperty("dummy", entry.id.toString());
        plugin.network.send(server, "arrival", body);
        try { plugin.network.connect(player, server); }
        catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not send " + player.getName() + " to server " + server, error);
            finish(player, owner, slot, "teleport_failed");
            return;
        }
        if (limited) startCooldown(owner);
        pending.remove(owner);
        plugin.msg(player, "teleport_connect", "server", plugin.serverLabel(server), "slot", slot);
    }

    /** 其他子服通知：该玩家将跨服传送到本服的假人。 */
    void expect(UUID player, UUID dummy) {
        arrivals.values().removeIf(arrival -> arrival.expires < System.currentTimeMillis());
        arrivals.put(player, new Arrival(dummy, System.currentTimeMillis() + 30_000));
        Player online = Bukkit.getPlayer(player);
        if (online != null) arrived(online);
    }

    /** 玩家进入本服（或已在本服）时，如有待完成的跨服传送，稍后传送到假人身边。 */
    void arrived(Player player) {
        Arrival arrival = arrivals.remove(player.getUniqueId());
        if (arrival == null || arrival.expires < System.currentTimeMillis()) return;
        player.getScheduler().runDelayed(plugin, task -> {
            UUID owner = player.getUniqueId();
            Roster.Entry entry = plugin.roster.byId.get(arrival.dummy);
            DummyPlayers.Live active = entry == null ? null : plugin.live.get(entry.id);
            if (active == null || !online(active) || !plugin.allowed(player, entry)) {
                plugin.msg(player, "teleport_offline");
                return;
            }
            if (!pending.add(owner)) return;
            execute(player, active, false, false);
        }, null, 10);
    }

    private void execute(Player player, DummyPlayers.Live active, boolean bring, boolean limited) {
        UUID owner = player.getUniqueId();
        int slot = active.entry.key.slot();
        if (!online(active)) { finish(player, owner, slot, "teleport_offline"); return; }
        if (!plugin.allowed(player, active.entry) || !player.hasPermission(DummyPlayers.TELEPORT)) {
            finish(player, owner, slot, "no_permission");
            return;
        }
        Player target = active.dummy.getBukkitEntity();
        Player anchor = bring ? player : target;
        Player traveler = bring ? target : player;
        Runnable offline = () -> finish(player, owner, slot, "teleport_offline");
        if (!anchor.getScheduler().execute(plugin, () -> {
            if (!online(active)) { offline.run(); return; }
            Location anchorPosition = anchor.getLocation();
            Location destination = bring ? centered(anchorPosition) : anchorPosition;
            if (!traveler.getScheduler().execute(plugin, () -> {
                if (!online(active)) { offline.run(); return; }
                if (!bring && (!plugin.allowed(player, active.entry) || !player.hasPermission(DummyPlayers.TELEPORT))) {
                    finish(player, owner, slot, "no_permission");
                    return;
                }
                if (bring) {
                    if (!player.isOnline()) { finish(player, owner, slot, "teleport_failed"); return; }
                    Location facing = traveler.getLocation();
                    destination.setYaw(facing.getYaw());
                    destination.setPitch(facing.getPitch());
                    plugin.prepareRelocation(active);
                }
                try {
                    traveler.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((success, error) -> {
                            if (error != null) logFailure(active, error);
                            boolean teleported = error == null && Boolean.TRUE.equals(success);
                            if (bring) active.relocating = false;
                            else if (teleported && limited) startCooldown(owner);
                            finish(player, owner, slot, teleported ? (bring ? "bring_done" : "teleport_done") : "teleport_failed");
                        });
                } catch (RuntimeException error) {
                    if (bring) active.relocating = false;
                    logFailure(active, error);
                    finish(player, owner, slot, "teleport_failed");
                }
            }, offline, 1)) offline.run();
        }, offline, 1)) offline.run();
    }

    static Location centered(Location position) {
        return new Location(position.getWorld(), position.getBlockX() + 0.5, position.getY(),
            position.getBlockZ() + 0.5, position.getYaw(), position.getPitch());
    }

    private void startCooldown(UUID owner) {
        if (cooldownSeconds == 0) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(cooldownSeconds);
        cooldowns.put(owner, deadline);
        if (plugin.isEnabled()) Bukkit.getAsyncScheduler().runDelayed(plugin,
            task -> cooldowns.remove(owner, deadline), cooldownSeconds, TimeUnit.SECONDS);
    }

    private boolean online(DummyPlayers.Live active) {
        return active.inWorld && !active.dead && plugin.live.get(active.entry.id) == active
            && plugin.roster.entries.get(active.entry.key) == active.entry;
    }

    private void logFailure(DummyPlayers.Live active, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "Could not teleport to fake player " + active.entry.profile(), error);
    }

    private void finish(Player player, UUID owner, int slot, String message) {
        pending.remove(owner);
        player.getScheduler().execute(plugin, () -> plugin.msg(player, message, "slot", slot), null, 1);
    }

}
