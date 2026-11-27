package io.github.dummyplayers;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 用 PlaceholderAPI 解析文字里的变量，任何插件注册的变量都可以用（%luckperms_suffix%、%vault_prefix%、%player_world% 等）。
 * 没装 PlaceholderAPI 时原样返回；只在装了时才加载它的类。
 */
final class Papi {
    private static volatile boolean warned;

    private Papi() { }

    static boolean enabled() { return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"); }

    /** player 为 null 时只解析和玩家无关的变量（如 %server_online%）。某个变量的扩展报错时原样返回，并在后台提示一次。 */
    static String apply(Logger logger, OfflinePlayer player, String text) {
        if (text.indexOf('%') < 0 || !enabled()) return text;
        try {
            return Hook.apply(player, text);
        } catch (RuntimeException | LinkageError error) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "A PlaceholderAPI expansion failed while filling \"" + text + "\"; such lines are sent without placeholders filled", error);
            }
            return text;
        }
    }

    private static final class Hook {
        static String apply(OfflinePlayer player, String text) { return PlaceholderAPI.setPlaceholders(player, text); }
    }
}
