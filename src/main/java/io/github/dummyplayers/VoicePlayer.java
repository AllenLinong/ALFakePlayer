package io.github.dummyplayers;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * TAB 测试假人通过服务器聊天流程说话时用的 Bukkit 玩家对象（见 {@link TestChat}）。它没有连接，也没有登录过，
 * 聊天插件把它当真人处理时有两处会出问题，这里补上：
 * <ul>
 *   <li>跨服聊天插件（如 TrChat 的代理频道）用发言人的连接给代理端发插件消息。测试假人没有连接，消息发不出去，
 *       谁也收不到；这里借一个在线真人的连接发出去。</li>
 *   <li>聊天插件按权限判断能不能在频道发言、用哪个格式。测试假人没有登录，LuckPerms 没有接管它的权限；
 *       这里按它在 LuckPerms 里的用户（和档案同一个权限组）回答。</li>
 *   <li>PlaceholderAPI 的变量（如 %player_world%）只对在线玩家有值，这里报告为在线，聊天格式里的变量和真人一样。</li>
 * </ul>
 */
final class VoicePlayer extends CraftPlayer {
    private final DummyPlayers plugin;

    VoicePlayer(DummyPlayers plugin, CraftServer server, ServerPlayer handle) {
        super(server, handle);
        this.plugin = plugin;
    }

    private static Player relay() {
        for (Player player : Bukkit.getOnlinePlayers()) if (TestChat.real(player)) return player;
        return null;
    }

    @Override public boolean isOnline() { return true; }

    /** 没有连接时原本会抛异常（聊天格式里常有 %player_ping%）。按 UUID 给一个固定的、像真人的延迟。 */
    @Override public int getPing() { return 20 + TestChat.trait(getHandle().getUUID(), 3); }

    @Override public org.bukkit.event.player.PlayerResourcePackStatusEvent.Status getResourcePackStatus() { return null; }

    @Override public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        Player relay = relay();
        if (relay != null) relay.sendPluginMessage(source, channel, message);
    }

    @Override public Set<String> getListeningPluginChannels() {
        Player relay = relay();
        return relay != null ? relay.getListeningPluginChannels() : Set.of();
    }

    @Override public boolean hasPermission(String name) {
        Boolean value = plugin.groups().permission(getUniqueId(), name);
        return value != null ? value : super.hasPermission(name);
    }

    @Override public boolean hasPermission(Permission permission) {
        Boolean value = plugin.groups().permission(getUniqueId(), permission.getName());
        return value != null ? value : super.hasPermission(permission);
    }

    @Override public boolean isPermissionSet(String name) {
        return plugin.groups().permission(getUniqueId(), name) != null || super.isPermissionSet(name);
    }

    @Override public boolean isPermissionSet(Permission permission) {
        return plugin.groups().permission(getUniqueId(), permission.getName()) != null || super.isPermissionSet(permission);
    }

    @Override public int hashCode() { return 97 * 5 + getHandle().getUUID().hashCode(); }
}
