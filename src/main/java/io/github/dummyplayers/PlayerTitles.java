package io.github.dummyplayers;

import cn.handyplus.title.api.PlayerTitleApi;
import cn.handyplus.title.entity.TitleList;
import cn.handyplus.title.entity.TitlePlayer;
import cn.handyplus.title.entity.TitleRequire;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * PlayerTitle（米饭插件的称号插件）兼容：给假人从称号商店里随机匹配几个称号，按概率戴上一个，像真人买过、戴着称号一样。
 * 只挑 config.yml 里 playertitle.buy_types 列出的购买类型（默认免费和各种付费的），权限称号永远不挑；隐藏的称号不挑。
 * 每个假人只在第一次出现、还一个称号都没有时匹配一次，之后由 PlayerTitle 自己保存（同一个假人跨服、重启后都一样）。
 *
 * <p>称号显示在哪里由 PlayerTitle 的变量 %playerTitle_use% 决定：聊天插件的格式、tab_chat.yml 的 format 里写了这个变量，
 * 测试假人说话时就带着称号（变量按测试假人解析，见 {@link VoicePlayer}）。</p>
 */
final class PlayerTitles {
    record Settings(boolean enabled, boolean testPlayers, boolean summoned, Set<String> types, int min, int max, int wearPercent) {
        static Settings read(ConfigurationSection yaml) {
            var types = new HashSet<String>();
            for (String type : yaml.contains("buy_types") ? yaml.getStringList("buy_types")
                : List.of("not", "vault", "playerPoints", "coin", "itemStack", "playerCurrency")) {
                String key = normalize(type);
                if (key.equals("permission")) {
                    throw new IllegalArgumentException("config.yml: playertitle.buy_types must not contain permission (permission titles are never given)");
                }
                if (!key.isEmpty()) types.add(key);
            }
            List<Integer> count = yaml.contains("count") ? yaml.getIntegerList("count") : List.of(1, 3);
            if (count.isEmpty() && yaml.isInt("count")) count = List.of(yaml.getInt("count"));
            if (count.isEmpty() || count.size() > 2 || count.getFirst() < 1 || count.getLast() > 20 || count.getFirst() > count.getLast()) {
                throw new IllegalArgumentException("config.yml: playertitle.count must be [min, max] between 1 and 20");
            }
            int wear = yaml.getInt("wear_percent", 70);
            if (wear < 0 || wear > 100) throw new IllegalArgumentException("config.yml: playertitle.wear_percent must be 0-100");
            return new Settings(yaml.getBoolean("enabled", true), yaml.getBoolean("test_players", true), yaml.getBoolean("summoned", false),
                Set.copyOf(types), count.getFirst(), count.getLast(), wear);
        }
    }

    /** 购买类型统一成小写、去掉下划线：PLAYER_POINTS 和 playerPoints 一样。 */
    static String normalize(String type) { return type == null ? "" : type.strip().toLowerCase(Locale.ROOT).replace("_", ""); }

    private final DummyPlayers plugin;
    private volatile Settings settings = new Settings(false, false, false, Set.of(), 1, 1, 0);
    /** 本次运行已经处理过的假人。 */
    private final Set<UUID> checked = ConcurrentHashMap.newKeySet();
    private volatile boolean warned, warnedEmpty;

    PlayerTitles(DummyPlayers plugin) { this.plugin = plugin; }

    void configure(Settings next) {
        settings = next;
        checked.clear();
        warnedEmpty = false;
    }

    static boolean installed() { return Bukkit.getPluginManager().isPluginEnabled("PlayerTitle"); }

    /** 测试假人进 TAB 时调用。 */
    void testPlayer(UUID id, String name) {
        Settings s = settings;
        if (s.enabled() && s.testPlayers()) prepare(id, name, Runnable::run);
    }

    /** 召唤的假人进服后调用。戴称号时 PlayerTitle 会刷新在线玩家的显示和 buff，要在假人自己的线程里做，由 wear 执行。 */
    void summoned(UUID id, String name, Executor wear) {
        Settings s = settings;
        if (s.enabled() && s.summoned()) prepare(id, name, wear);
    }

    private void prepare(UUID id, String name, Executor wear) {
        if (!installed() || !checked.add(id)) return;
        Settings s = settings;
        Bukkit.getAsyncScheduler().runNow(plugin, unused -> {
            try {
                Hook.apply(this, id, name, s, wear);
            } catch (RuntimeException | LinkageError error) {
                checked.remove(id);
                if (warned) return;
                warned = true;
                plugin.getLogger().log(Level.WARNING, "Could not give PlayerTitle titles to fake player " + name
                    + "; other fake players are skipped silently until /afp reload", error);
            }
        });
    }

    private void empty() {
        if (warnedEmpty) return;
        warnedEmpty = true;
        plugin.getLogger().info("PlayerTitle has no visible shop title of the types in config.yml playertitle.buy_types"
            + " (permission titles are never used); fake players get no titles.");
    }

    /** 用到 PlayerTitle 的类，只在装了 PlayerTitle 时才加载。 */
    private static final class Hook {
        static void apply(PlayerTitles owner, UUID id, String name, Settings s, Executor wear) {
            // 已经有称号（之前给过，或者管理员给的）就不动
            if (!PlayerTitleApi.findAllTitleIdByPlayer(id).isEmpty()) return;
            var shop = new ArrayList<TitleList>();
            for (TitleList title : PlayerTitleApi.findShopTitle(id)) if (title.getId() != null && eligible(title, s.types())) shop.add(title);
            if (shop.isEmpty()) { owner.empty(); return; }
            // 按 UUID 固定：同一个假人在哪个子服第一次出现都挑到同样的
            shop.sort(Comparator.comparing(TitleList::getId));
            var random = new Random(id.getMostSignificantBits() * 31 ^ id.getLeastSignificantBits() ^ 0x7469746C65L);
            Collections.shuffle(shop, random);
            int count = Math.min(shop.size(), s.min() + random.nextInt(s.max() - s.min() + 1));
            var chosen = shop.subList(0, count);
            for (TitleList title : chosen) PlayerTitleApi.setPlayerTitle(name, id, title.getId(), 0); // 0 为永久
            if (random.nextInt(100) >= s.wearPercent()) return;
            Integer worn = chosen.getFirst().getId();
            for (TitlePlayer owned : PlayerTitleApi.findPlayerTitle(id)) {
                if (!worn.equals(owned.getTitleId()) || owned.getId() == null) continue;
                Integer record = owned.getId();
                wear.execute(() -> PlayerTitleApi.playerUseTitle(id, record));
                return;
            }
        }

        /** 不是隐藏的，而且每个购买条件的类型都在允许的类型里（权限称号永远不要）。没有购买条件的按免费算。 */
        static boolean eligible(TitleList title, Set<String> types) {
            if (Boolean.TRUE.equals(title.getIsHide())) return false;
            var kinds = new ArrayList<String>();
            List<TitleRequire> requires = title.getRequires();
            if (requires != null) for (TitleRequire require : requires) if (require.getBuyType() != null) kinds.add(require.getBuyType());
            if (title.getBuyType() != null) kinds.add(title.getBuyType());
            if (kinds.isEmpty()) kinds.add("not");
            for (String kind : kinds) {
                String key = normalize(kind);
                if (key.equals("permission") || !types.contains(key)) return false;
            }
            return true;
        }
    }
}
