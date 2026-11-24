package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

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
        if (active == null || !online(active) || !plugin.allowed(player, entry)) {
            plugin.msg(player, "teleport-offline");
            return;
        }
        if (!pending.add(owner)) { plugin.msg(player, "teleport-pending"); return; }
        boolean limited = !bring && !player.hasPermission(BYPASS);
        Long deadline = limited ? cooldowns.get(owner) : null;
        if (deadline != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                pending.remove(owner);
                plugin.msg(player, "teleport-cooldown", "seconds", Math.ceilDiv(remaining, TimeUnit.SECONDS.toNanos(1)));
                return;
            }
        }
        if (!limited || delaySeconds == 0) {
            execute(player, active, bring, limited);
            return;
        }
        plugin.msg(player, "teleport-wait", "seconds", delaySeconds);
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            if (!player.getScheduler().execute(plugin, () -> {
                if (player.isDead()) finish(player, owner, slot, "teleport-failed");
                else execute(player, active, false, true);
            }, () -> pending.remove(owner), 1)) pending.remove(owner);
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void execute(Player player, DummyPlayers.Live active, boolean bring, boolean limited) {
        UUID owner = player.getUniqueId();
        int slot = active.entry.key.slot();
        if (!online(active)) { finish(player, owner, slot, "teleport-offline"); return; }
        if (!plugin.allowed(player, active.entry) || !player.hasPermission(DummyPlayers.TELEPORT)) {
            finish(player, owner, slot, "no-permission");
            return;
        }
        Player target = active.dummy.getBukkitEntity();
        Player anchor = bring ? player : target;
        Player traveler = bring ? target : player;
        Runnable offline = () -> finish(player, owner, slot, "teleport-offline");
        if (!anchor.getScheduler().execute(plugin, () -> {
            if (!online(active)) { offline.run(); return; }
            Location anchorPosition = anchor.getLocation();
            Location destination = bring ? centered(anchorPosition) : anchorPosition;
            if (!traveler.getScheduler().execute(plugin, () -> {
                if (!online(active)) { offline.run(); return; }
                if (!bring && (!plugin.allowed(player, active.entry) || !player.hasPermission(DummyPlayers.TELEPORT))) {
                    finish(player, owner, slot, "no-permission");
                    return;
                }
                if (bring) {
                    if (!player.isOnline()) { finish(player, owner, slot, "teleport-failed"); return; }
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
                            finish(player, owner, slot, teleported ? (bring ? "bring-done" : "teleport-done") : "teleport-failed");
                        });
                } catch (RuntimeException error) {
                    if (bring) active.relocating = false;
                    logFailure(active, error);
                    finish(player, owner, slot, "teleport-failed");
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
