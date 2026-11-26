package io.github.dummyplayers;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI 变量（只在安装了 PlaceholderAPI 时加载这个类）：
 * %alfakeplayer_online%        全服：所有子服的真实玩家 + 所有子服的假人（单服时就是本服）
 * %alfakeplayer_fake%          全服假人
 * %alfakeplayer_server_online% 本服真实玩家 + 本服假人
 * %alfakeplayer_server_fake%   本服假人
 * 假人包括已召唤的假人和 /afp test 添加的测试条目。
 * 其他子服的数量来自心跳，最多延迟一个心跳间隔（database.yml 中的 sync.heartbeat_seconds）；
 * 只统计安装了本插件并连接同一个数据库的子服。
 */
final class OnlinePlaceholders extends PlaceholderExpansion {
    /** TAB、计分板插件会为每个玩家频繁请求变量，结果缓存 1 秒，期间直接返回。 */
    private record Counts(long time, String online, String fake, String serverOnline, String serverFake) { }
    private final DummyPlayers plugin;
    private volatile Counts counts;

    private OnlinePlaceholders(DummyPlayers plugin) { this.plugin = plugin; }

    private Counts counts() {
        Counts current = counts;
        long now = System.currentTimeMillis();
        if (current != null && now - current.time() < 1000) return current;
        current = new Counts(now, String.valueOf(plugin.onlineCount(true, true)), String.valueOf(plugin.onlineCount(true, false)),
            String.valueOf(plugin.onlineCount(false, true)), String.valueOf(plugin.onlineCount(false, false)));
        counts = current;
        return current;
    }

    static Object register(DummyPlayers plugin) {
        var expansion = new OnlinePlaceholders(plugin);
        return expansion.register() ? expansion : null;
    }

    static void unregister(Object expansion) {
        if (expansion instanceof OnlinePlaceholders registered) registered.unregister();
    }

    @Override public String getIdentifier() { return "alfakeplayer"; }
    @Override public String getAuthor() { return "ALFakePlayer"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    /** 由插件注册，/papi reload 时不要卸载。 */
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "online" -> counts().online();
            case "fake" -> counts().fake();
            case "server_online" -> counts().serverOnline();
            case "server_fake" -> counts().serverFake();
            default -> null;
        };
    }
}
