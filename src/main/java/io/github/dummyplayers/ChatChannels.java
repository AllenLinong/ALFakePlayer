package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/**
 * 让测试假人在聊天插件的指定频道说话（tab_chat.yml 的 channels）。目前支持 TrChat：说话前让它加入分给它的频道，
 * 和玩家输入 /channel join 一样，会检查频道的加入权限（Join-Permission），结果记在 TrChat 自己的数据里。
 * 通过反射调用，TrChat 2.4 和 2.5 都有这些方法。
 */
final class ChatChannels {
    private ChatChannels() { }

    /** TrChat 的方法。TrChat 重载（类加载器变了）时重新查找。 */
    private record Handles(ClassLoader loader, Object channels, Method join, Method all, Method id, Object sessions, Method session,
                           Method current) {
        static Handles find(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> channel = Class.forName("me.arasple.mc.trchat.module.display.channel.Channel", true, loader);
            Object channels = channel.getField("Companion").get(null);
            Class<?> session = Class.forName("me.arasple.mc.trchat.module.display.ChatSession", true, loader);
            Object sessions = session.getField("Companion").get(null);
            return new Handles(loader, channels, channels.getClass().getMethod("join", Player.class, String.class, boolean.class),
                channels.getClass().getMethod("getChannels"), channel.getMethod("getId"), sessions,
                sessions.getClass().getMethod("getSession", Player.class), session.getMethod("getChannel"));
        }
    }

    private static volatile Handles handles;

    static boolean trChat() { return Bukkit.getPluginManager().isPluginEnabled("TrChat"); }

    /** 让 player 在 wanted 频道说话。已经在这个频道或加入成功时为 null，否则为原因（英文，给后台提示用）。 */
    static String join(Player player, String wanted) {
        Plugin trChat = Bukkit.getPluginManager().getPlugin("TrChat");
        if (trChat == null || !trChat.isEnabled()) return "tab_chat.yml channels only works with TrChat, which is not enabled";
        try {
            ClassLoader loader = trChat.getClass().getClassLoader();
            Handles found = handles;
            if (found == null || found.loader() != loader) handles = found = Handles.find(loader);
            Object current = found.current().invoke(found.session().invoke(found.sessions(), player));
            if (current != null && wanted.equalsIgnoreCase(String.valueOf(found.id().invoke(current)))) return null;
            var names = new ArrayList<String>();
            String id = null;
            for (Object key : ((Map<?, ?>) found.all().invoke(found.channels())).keySet()) {
                names.add(String.valueOf(key));
                if (wanted.equalsIgnoreCase(String.valueOf(key))) id = String.valueOf(key);
            }
            if (id == null) return "TrChat has no channel '" + wanted + "' (it has " + String.join(", ", names) + ")";
            if (Boolean.TRUE.equals(found.join().invoke(found.channels(), player, id, false))) return null;
            return "test players may not join TrChat channel '" + id + "' (check its Join-Permission against their LuckPerms group)";
        } catch (InvocationTargetException error) {
            return "TrChat failed to switch the channel: " + error.getCause();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return "this TrChat version is not supported for channels (" + error + ")";
        }
    }
}
