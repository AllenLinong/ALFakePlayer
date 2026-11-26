package io.github.dummyplayers;

import net.kyori.adventure.text.Component;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DummyNames {
    private static final String TITLE = "%playerTitle_use%";
    private final Method placeholders;

    DummyNames(DummyPlayers plugin) {
        Method method = null;
        var dependency = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (dependency != null && dependency.isEnabled()) {
            try {
                method = dependency.getClass().getClassLoader().loadClass("me.clip.placeholderapi.PlaceholderAPI")
                    .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (ReflectiveOperationException | LinkageError error) {
                plugin.getLogger().warning("Cannot load PlaceholderAPI for fake player titles: " + error);
            }
        }
        placeholders = method;
    }

    /** 更新假人在 TAB 中的显示名（名字后加上 PlaceholderAPI 称号），返回最终显示的内容。 */
    Component tab(NativeDummy dummy, Component label, HiddenTag display) {
        Player player = dummy.getBukkitEntity();
        Component rendered = label;
        if (placeholders != null && player.isValid()) {
            try {
                if (placeholders.invoke(null, player, TITLE) instanceof String title && !title.isBlank() && !TITLE.equals(title)) {
                    rendered = rendered.append(DummyPlayers.TEXT.deserialize(title.replace((char) 167, '&')));
                }
            } catch (ReflectiveOperationException | LinkageError ignored) { }
        }
        display.update(dummy, PaperAdventure.asVanilla(rendered));
        return rendered;
    }

    static final class HiddenTag {
        private final ClientboundPlayerInfoRemovePacket removeTab;
        private final Map<UUID, ServerGamePacketListenerImpl> viewers = new HashMap<>();
        private boolean cleared;

        HiddenTag(Roster.Entry entry) {
            removeTab = new ClientboundPlayerInfoRemovePacket(List.of(entry.id));
        }

        synchronized void showTo(NativeDummy dummy, ServerPlayer viewer) {
            if (cleared || viewer.connection == null) return;
            if (viewers.put(viewer.getUUID(), viewer.connection) != viewer.connection) {
                viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(dummy, true));
            }
        }

        synchronized void update(NativeDummy dummy, net.minecraft.network.chat.Component rendered) {
            if (cleared || rendered.equals(dummy.listName)) return;
            dummy.listName = rendered;
            if (!viewers.isEmpty()) {
                var packet = new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, dummy);
                viewers.values().forEach(connection -> connection.send(packet));
            }
        }

        synchronized void forget(UUID viewer) { viewers.remove(viewer); }

        synchronized void clear() {
            cleared = true;
            viewers.values().forEach(connection -> connection.send(removeTab));
            viewers.clear();
        }
    }
}
