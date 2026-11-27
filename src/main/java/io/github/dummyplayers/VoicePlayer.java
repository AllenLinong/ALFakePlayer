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
 *   <li>聊天插件的防刷屏（和上一句太像、发言冷却、发言太频繁）会悄悄吞掉测试假人的话，它们说话的频率由 tab_chat.yml 控制，
 *       这里给它们这几项的绕过权限（LuckPerms 里明确设为 false 的除外）。</li>
 *   <li>聊天插件不让它说话时会发一句提示给它（如“你没有权限在此频道发言”），这里记下来，发不出去时在后台提示原因。</li>
 * </ul>
 */
final class VoicePlayer extends CraftPlayer {
    /** 防刷屏的绕过权限：TrChat 的相似消息、重复短语、发言冷却、发言频率、消息长度。 */
    private static final Set<String> BYPASS = Set.of("trchat.bypass.repeat", "trchat.bypass.duplicate", "trchat.bypass.chatcd",
        "trchat.bypass.highfrequency", "trchat.bypass.chatlength");
    private final DummyPlayers plugin;
    /** 聊天插件最近一次发给它的提示和时间。 */
    private volatile String notice = "";
    private volatile long noticeAt;

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
        return value != null ? value : BYPASS.contains(name) || super.hasPermission(name);
    }

    @Override public boolean hasPermission(Permission permission) {
        Boolean value = plugin.groups().permission(getUniqueId(), permission.getName());
        return value != null ? value : BYPASS.contains(permission.getName()) || super.hasPermission(permission);
    }

    /** since 之后聊天插件发给它的提示（去掉颜色）；没有时为空字符串。 */
    String noticeSince(long since) { return noticeAt >= since ? notice : ""; }

    private void notice(String text) {
        String plain = TestAi.clean(text, 120);
        if (plain.isEmpty()) return;
        notice = plain;
        noticeAt = System.currentTimeMillis();
    }

    @Override public void sendRawMessage(java.util.UUID sender, String message) { if (message != null) notice(message); }

    // TrChat 等用动作栏提示“没有这个频道的说话权限”
    @Override public void sendActionBar(String message) { if (message != null) notice(message); }

    @Override public void sendActionBar(net.kyori.adventure.text.Component message) {
        notice(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message));
    }

    @SuppressWarnings("deprecation")
    @Override public void sendActionBar(net.md_5.bungee.api.chat.BaseComponent... message) {
        notice(net.md_5.bungee.api.chat.BaseComponent.toLegacyText(message));
    }

    /** 记下通过 spigot() 发来的提示（TabooLib 的动作栏就是这样发的），其余照常。 */
    @SuppressWarnings("deprecation")
    private final Player.Spigot spigot = new Player.Spigot() {
        @Override public void sendMessage(net.md_5.bungee.api.chat.BaseComponent component) { notice(component.toLegacyText()); }
        @Override public void sendMessage(net.md_5.bungee.api.chat.BaseComponent... components) {
            notice(net.md_5.bungee.api.chat.BaseComponent.toLegacyText(components));
        }
        @Override public void sendMessage(java.util.UUID sender, net.md_5.bungee.api.chat.BaseComponent... components) { sendMessage(components); }
        @Override public void sendMessage(net.md_5.bungee.api.ChatMessageType position, net.md_5.bungee.api.chat.BaseComponent component) {
            notice(component.toLegacyText());
        }
        @Override public void sendMessage(net.md_5.bungee.api.ChatMessageType position, net.md_5.bungee.api.chat.BaseComponent... components) {
            sendMessage(components);
        }
        @Override public void sendMessage(net.md_5.bungee.api.ChatMessageType position, java.util.UUID sender,
                                          net.md_5.bungee.api.chat.BaseComponent... components) { sendMessage(components); }
        @Override public int getPing() { return VoicePlayer.this.getPing(); }
        @Override public java.util.Set<Player> getHiddenPlayers() { return java.util.Set.of(); }
    };

    @Override public Player.Spigot spigot() { return spigot; }

    @SuppressWarnings("removal")
    @Override public void sendMessage(net.kyori.adventure.identity.Identity identity, net.kyori.adventure.text.Component message,
                                      net.kyori.adventure.audience.MessageType type) {
        notice(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Override public void sendMessage(net.kyori.adventure.text.Component message, net.kyori.adventure.chat.ChatType.Bound boundChatType) {
        notice(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Override public boolean isPermissionSet(String name) {
        return plugin.groups().permission(getUniqueId(), name) != null || super.isPermissionSet(name);
    }

    @Override public boolean isPermissionSet(Permission permission) {
        return plugin.groups().permission(getUniqueId(), permission.getName()) != null || super.isPermissionSet(permission);
    }

    @Override public int hashCode() { return 97 * 5 + getHandle().getUUID().hashCode(); }
}
