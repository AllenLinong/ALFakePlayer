package io.github.dummyplayers;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DummyPlayers extends JavaPlugin implements Listener, TabExecutor {
    static final String USE = "alfakeplayer.use", ADMIN = "alfakeplayer.admin", TELEPORT = "alfakeplayer.teleport";
    private static final List<String> TIER_NAMES = List.of("normal", "pro", "promax");
    private static final Set<String> PLAYER_ACTIONS = Set.of("spawn", "list", "dir", "rename", "tp", "tph");
    private static final NamespacedKey RENAME_CARD = Objects.requireNonNull(NamespacedKey.fromString("alfakeplayer:rename_card"));
    private static final int MAX_COMPLETIONS = 100;
    static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacyAmpersand();
    final Map<UUID, Live> live = new ConcurrentHashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    /** clear 指令的二次确认：发送者|目标 → 截止时间。 */
    private final Map<String, Long> clearConfirmations = new ConcurrentHashMap<>();
    private final Object admissions = new Object();
    Roster roster;
    Menus menus;
    RosterMenus rosterMenus;
    Network network;
    private Database database;
    private final DummyTeleport teleports = new DummyTeleport(this);
    private final TabBenchmark benchmark = new TabBenchmark(this);
    private final RemoteTab remoteTab = new RemoteTab(this);
    /** 已注册的 PlaceholderAPI 扩展；用 Object 保存，未安装 PlaceholderAPI 时不会加载它的类。 */
    private Object placeholders;
    /** 各档位的运行半径（区块），即假人的模拟距离。重载时整体替换。 */
    private volatile Map<DummyTier, Integer> tierRadius = Map.of();
    private volatile Map<DummyTier, Double> tierPrices = Map.of();
    private Groups groups = Groups.NONE;
    private volatile String luckPermsGroup = "default";

    /** 与真实假人相同的 TAB 显示名：luckperms.group 权限组的前缀 + 名字。用于 /afp test 的测试条目。 */
    Component testLabel(String name) {
        return TEXT.deserialize(groups.groupPrefix(luckPermsGroup).replace('§', '&')).append(Component.text(name));
    }
    private DummyNames names;
    private volatile PurchasePrice purchasePrice;
    private FoliaBridge folia;
    private volatile YamlConfiguration messages;
    private ScheduledTask saveTask;
    String serverId = "server", serverName = "server";
    private int maxActive, baseSlots, maxSlots, saveInterval;
    int targetInterval, idleMaxInterval, useInterval, eatLevel, minimumDurability, unloadInterval, unloadMaxInterval, keepFood;
    float passiveExhaustion;
    boolean lethalStarvation, unloadChests;
    Set<Material> foodBlacklist = Set.of();
    private boolean ignoreHostileMobs, affectsSpawning, passiveSpawnsNeedPlayer;
    private boolean paidSummoning;
    private volatile boolean shuttingDown;
    /** 正在执行 reload：期间不接受指令和召唤。只在 admissions 锁内置为 true。 */
    private volatile boolean reloading;
    /** reload 期间暂时移出世界的假人，重载完成后在原位置重新召唤。 */
    private final Map<UUID, Parked> parked = new ConcurrentHashMap<>();

    /** 暂时移出世界的假人：位置和需要恢复的状态（经验由 NativeDummy 屏蔽，不需要保存）。 */
    private record Parked(Roster.Entry entry, Location position, double health, int food, float saturation,
                          float exhaustion, Collection<PotionEffect> effects) { }

    static final class Live {
        final Roster.Entry entry;
        final NativeDummy dummy;
        final Actions actions;
        final DummyNames.HiddenTag hiddenTag;
        volatile boolean dead;
        volatile boolean relocating;
        volatile boolean inWorld;
        volatile CompletableFuture<Void> released = CompletableFuture.completedFuture(null);
        /** 最近一次广播给其他子服的 TAB 显示名（JSON）。 */
        volatile String sharedLabel;
        int tick;

        Live(Roster.Entry entry, NativeDummy dummy) {
            this.entry = entry;
            this.dummy = dummy;
            actions = new Actions(dummy, entry);
            hiddenTag = new DummyNames.HiddenTag(entry);
        }
    }

    // ══ 启动 ══════════════════════════════════════════════

    @Override public void onEnable() {
        String version = Bukkit.getMinecraftVersion();
        if (!(version.equals("26.1.2") || version.startsWith("26.1.") && patchAtLeastTwo(version)
            || version.matches("26\\.(?:[2-9]|[1-9][0-9]+)(?:\\.[0-9]+)?"))) {
            getLogger().severe("Unsupported Minecraft version: " + version);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            var configs = new ConfigUpgrade(this);
            Runnable settings = loadSettings(configs);
            serverId = getConfig().getString("server_id", "server").strip();
            if (!serverId.matches("[A-Za-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("config.yml: server_id must be 1-64 letters, digits, '.', '-' or '_'");
            }
            serverName = serverName(getConfig(), serverId);
            settings.run();

            YamlConfiguration storage = configs.load("database.yml", yaml -> {
                Database.Settings.read(yaml);
                Network.Settings.read(yaml);
            });
            var databaseSettings = Database.Settings.read(storage);
            var networkSettings = Network.Settings.read(storage);
            database = Database.open(this, databaseSettings);
            database.migrate();
            if (!database.mysql() && networkSettings.redis()) {
                getLogger().warning("database.yml: redis.enabled is ignored because cross-server sync requires database.type: mysql.");
            }
            network = new Network(this, serverId, networkSettings, database.mysql());
            roster = new Roster(this, database, serverId, serverName, networkSettings.timeoutMillis(), networkSettings.heartbeatMillis());
            purchasePrice = new PurchasePrice(purchasePrice.coins(), purchasePrice.points(), roster.loadPurchaseItems());
            network.start(database, this::handleRemote);
            if (network.enabled()) {
                // 其他子服运行的假人显示在本服 TAB 中：先向各子服要一次当前显示名，之后每秒同步
                Bukkit.getOnlinePlayers().forEach(remoteTab::join);
                network.broadcast("tab_request", new JsonObject());
                Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> remoteTab.sync(), 1, 1, TimeUnit.SECONDS);
            }
            folia = FoliaBridge.find();
            if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) groups = new Groups.Luck();
            else getLogger().info("LuckPerms not installed; fake players still appear in the vanilla tab list.");
            names = new DummyNames(this);
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try { placeholders = OnlinePlaceholders.register(this); }
                catch (RuntimeException | LinkageError error) { getLogger().warning("Cannot register PlaceholderAPI placeholders: " + error); }
            }
            Objects.requireNonNull(getCommand("alfakeplayer")).setExecutor(this);
            Objects.requireNonNull(getCommand("alfakeplayer")).setTabCompleter(this);
            Bukkit.getPluginManager().registerEvents(this, this);
            if (!NativeDummy.claimIdentitySupported()) {
                getLogger().warning("This server build does not allow replacing the fake player's Bukkit object;"
                    + " claim plugins (claims.plugins) will treat fake players as separate players.");
            }
            Bukkit.getOnlinePlayers().forEach(benchmark::join);
            Bukkit.getPluginManager().registerEvents(menus, this);
            Bukkit.getPluginManager().registerEvents(rosterMenus, this);
            saveTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> roster.save(), saveInterval, saveInterval);
            Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> roster.heartbeat(summonedCount(), Bukkit.getOnlinePlayers().size()),
                1000, networkSettings.heartbeatMillis(), TimeUnit.MILLISECONDS);
            // 其他插件全部启动后再检查经济服务，提示缺失的依赖
            Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> checkServices(), 1);
        } catch (Exception | LinkageError error) {
            getLogger().log(Level.SEVERE, "Cannot start ALFakePlayer", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private static boolean patchAtLeastTwo(String version) {
        try { return Integer.parseInt(version.substring(5)) >= 2; }
        catch (NumberFormatException ignored) { return false; }
    }

    private void checkServices() {
        try {
            if ((paidSummoning || purchasePrice.coins() > 0) && SpawnFee.find(1) == null) {
                getLogger().warning("No Vault economy service found. Summoning fees (economy.enabled) and coin purchases"
                    + " (purchase.coins) will fail until Vault and an economy plugin are installed;"
                    + " set them to false / 0 in config.yml to make them free.");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            getLogger().warning("Could not check the Vault economy service: " + error);
        }
        if (purchasePrice.points() > 0 && !Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            getLogger().warning("purchase.points is set but PlayerPoints is not installed; purchases will fail.");
        }
        String group = luckPermsGroup;
        groups.exists(group).whenComplete((found, error) -> {
            if (error != null) getLogger().warning("Could not check LuckPerms group '" + group + "': " + error);
            else if (!found) getLogger().warning(Groups.Luck.missing(group) + " Summoning will fail until then.");
        });
    }

    private static String serverName(FileConfiguration config, String serverId) {
        String name = config.getString("server_name", serverId).strip();
        if (name.isEmpty()) return serverId;
        return name.length() > 128 ? name.substring(0, 128) : name;
    }

    /**
     * 读取 config.yml、菜单和语言文件中可以重载的设置。全部读取并校验通过后才返回应用这些设置的任务，
     * 文件有错误时抛出异常，当前设置保持不变。server_id、server_name 和 database.yml 只在启动时读取。
     */
    private Runnable loadSettings(ConfigUpgrade configs) throws Exception {
        configs.load("config.yml", yaml -> { });
        reloadConfig();
        FileConfiguration config = getConfig();
        YamlConfiguration menuConfig = configs.loadMenus(yaml -> {
            Menus.validate(yaml);
            RosterMenus.validate(yaml);
        });
        YamlConfiguration text = loadMessages(configs, config.getString("language", "zh_CN"));
        int slotsMax = option(config, "slots.max_per_owner", 10, 1, 100);
        int slotsDefault = option(config, "slots.default", 1, 1, slotsMax);
        double coins = config.getDouble("purchase.coins", 300.0);
        int points = config.getInt("purchase.points", 0);
        if (!Double.isFinite(coins) || coins < 0 || points < 0) {
            throw new IllegalArgumentException("config.yml: purchase.coins and purchase.points must be zero or positive");
        }
        int activeMax = option(config, "slots.max_active_server", 20, 0, 200);
        var radius = new EnumMap<DummyTier, Integer>(DummyTier.class);
        var prices = new EnumMap<DummyTier, Double>(DummyTier.class);
        for (DummyTier tier : DummyTier.values()) {
            int chunks = option(config, "performance.dummy_chunks." + tier.key, tier.defaultChunkSize, 3, 9);
            radius.put(tier, (chunks - 1) / 2);
            // 缺少该项时取插件自带 config.yml 中的默认价格
            double price = config.getDouble("economy.tier_prices." + tier.key);
            if (!Double.isFinite(price) || price < 0) {
                throw new IllegalArgumentException("economy.tier_prices." + tier.key + " must be a finite non-negative number");
            }
            prices.put(tier, price);
        }
        int target = option(config, "performance.idle_target_interval_ticks", 3, 1, 20);
        int idleMax = option(config, "performance.idle_target_max_ticks", 20, target, 100);
        int use = option(config, "performance.use_interval_ticks", 4, 4, 100);
        int save = option(config, "performance.save_interval_ticks", 200, 40, 12000);
        boolean spawning = config.getBoolean("performance.affects_spawning", true);
        boolean passiveNeedPlayer = config.getBoolean("performance.passive_spawns_need_real_player", true);
        int eat = option(config, "survival.auto_eat_at_food_level", 2, 0, 19);
        int durability = option(config, "survival.minimum_tool_durability", 1, 1, 1000);
        Set<Material> blacklist = readFoodBlacklist(config.getStringList("survival.food_blacklist"));
        boolean unload = config.getBoolean("unloading.enabled", true);
        int unloadEvery = option(config, "unloading.interval_ticks", 40, 20, 1200);
        int unloadMax = option(config, "unloading.max_retry_ticks", 160, unloadEvery, 2400);
        int foodReserve = option(config, "unloading.keep_food", 64, 0, 2304);
        float exhaustion = (float) Math.clamp(config.getDouble("survival.passive_exhaustion_per_second"), 0.0, 4.0);
        boolean starvation = config.getBoolean("survival.lethal_starvation_any_non_peaceful_difficulty");
        boolean ignoreHostile = config.getBoolean("survival.ignore_hostile_mobs", true);
        boolean paid = config.getBoolean("economy.enabled", true);
        int teleportDelay = option(config, "teleport.to_dummy_delay_seconds", 5, 0, 300);
        int teleportCooldown = option(config, "teleport.to_dummy_cooldown_seconds", 5, 0, 300);
        String group = config.getString("luckperms.group", "default");
        List<String> claimPlugins = List.copyOf(config.getStringList("claims.plugins"));
        return () -> {
            if (menus == null) {
                menus = new Menus(this, menuConfig);
                rosterMenus = new RosterMenus(this, menuConfig);
            } else {
                menus.reload(menuConfig);
                rosterMenus.reload(menuConfig);
            }
            messages = text;
            maxSlots = slotsMax;
            baseSlots = slotsDefault;
            maxActive = activeMax;
            // 购买所需物品保存在数据库中，重载时保留
            purchasePrice = new PurchasePrice(coins, points, purchasePrice == null ? List.of() : purchasePrice.items());
            tierRadius = radius;
            tierPrices = prices;
            targetInterval = target;
            idleMaxInterval = idleMax;
            useInterval = use;
            saveInterval = save;
            affectsSpawning = spawning;
            passiveSpawnsNeedPlayer = passiveNeedPlayer;
            eatLevel = eat;
            minimumDurability = durability;
            foodBlacklist = blacklist;
            unloadChests = unload;
            unloadInterval = unloadEvery;
            unloadMaxInterval = unloadMax;
            keepFood = foodReserve;
            passiveExhaustion = exhaustion;
            lethalStarvation = starvation;
            ignoreHostileMobs = ignoreHostile;
            paidSummoning = paid;
            teleports.configure(teleportDelay, teleportCooldown);
            luckPermsGroup = group;
            ClaimIdentity.configure(claimPlugins);
        };
    }

    private YamlConfiguration loadMessages(ConfigUpgrade configs, String locale) throws Exception {
        if (!Set.of("zh_CN", "en_US").contains(locale)) {
            getLogger().warning("Unsupported language '" + locale + "'; using zh_CN.");
            locale = "zh_CN";
        }
        var chinese = configs.load("lang/zh_CN.yml", yaml -> { });
        var english = configs.load("lang/en_US.yml", yaml -> { });
        String path = "lang/" + locale + ".yml";
        YamlConfiguration loaded = locale.equals("zh_CN") ? chinese : english;
        try (var defaults = Objects.requireNonNull(getTextResource(path))) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        }
        return loaded;
    }

    private static int option(FileConfiguration config, String path, int fallback, int min, int max) {
        return Math.clamp(config.getInt(path, fallback), min, max);
    }

    private Set<Material> readFoodBlacklist(List<String> names) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name.strip());
            if (material == null || !material.isItem()) getLogger().warning("Invalid survival.food_blacklist entry: " + name);
            else materials.add(material);
        }
        return Set.copyOf(materials);
    }

    // ══ 菜单与状态查询 ═════════════════════════════════════

    String menuTierName(DummyTier tier) { return messages.getString("tier_name." + tier.key, tier.key); }
    String menuPrice(DummyTier tier) {
        return BigDecimal.valueOf(paidSummoning ? tierPrices.get(tier) : 0).stripTrailingZeros().toPlainString();
    }
    /** 收费召唤时先打开确认菜单。 */
    boolean summonNeedsConfirm(DummyTier tier) { return paidSummoning && tierPrices.get(tier) > 0; }

    boolean summoned(Roster.Entry entry) {
        return isSummoned(live.get(entry.id));
    }

    /** 主菜单显示用：本服运行中假人的 {生命, 生命上限, 饱食度}；不在本服运行时为 null。只读数值，任意线程可调用。 */
    float[] vitals(Roster.Entry entry) {
        Live active = live.get(entry.id);
        if (!isSummoned(active)) return null;
        return new float[] {active.dummy.getHealth(), active.dummy.getMaxHealth(), active.dummy.getFoodData().getFoodLevel()};
    }

    private static boolean isSummoned(Live active) {
        return active != null && active.inWorld && !active.dead;
    }

    private int summonedCount() {
        int count = 0;
        for (Live active : live.values()) if (isSummoned(active)) count++;
        return count;
    }

    /**
     * 在线数量，供 PlaceholderAPI 变量使用，可在任意线程调用。假人包括已召唤的假人和 /afp test 的测试条目；
     * withPlayers 为 true 时再加上真实玩家。allServers 为 true 时加上其他在线子服的数量
     * （假人和真实玩家数来自心跳，测试条目来自广播；心跳超时的子服不算）。
     */
    int onlineCount(boolean allServers, boolean withPlayers) {
        int count = summonedCount() + benchmark.count() + (withPlayers ? Bukkit.getOnlinePlayers().size() : 0);
        if (!allServers || network == null || !network.enabled()) return count;
        long timeout = network.settings.timeoutMillis();
        for (Roster.ServerInfo info : roster.servers()) {
            if (info.id().equals(serverId) || info.silentMillis() >= timeout) continue;
            count += info.live() + (withPlayers ? info.players() : 0);
        }
        return count + remoteTab.remoteTests();
    }

    String serverLabel(String server) {
        return server == null ? messages.getString("other_server", "?") : roster.serverName(server);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerListPing(PaperServerListPingEvent event) {
        if (event.shouldHidePlayers()) return;
        int summonedCount = summonedCount();
        if (summonedCount > 0) event.setNumPlayers(event.getNumPlayers() + summonedCount);
    }

    void requestTeleportFromMenu(Player player, int slot, boolean bring) {
        if (!player.hasPermission(TELEPORT)) msg(player, "no_permission");
        else teleports.request(player, slot, bring);
    }

    void summonFromMenu(Player player, Roster.Entry expected, int slot, DummyTier tier) {
        if (!player.hasPermission(USE) || slot < 1 || slot > slots(player.getUniqueId())) return;
        if (roster.entries.get(new Roster.Key(player.getUniqueId(), slot)) != expected) { msg(player, "not_found"); return; }
        summon(player, slot, tier);
    }

    /** 主菜单 Shift+左键：收回已召唤的假人（包括在其他子服运行的），背包和设置保留。收回后执行 refresh。 */
    void despawnFromMenu(Player player, Roster.Entry entry, Runnable refresh) {
        if (!allowed(player, entry)) return;
        if (reloading) { msg(player, "reload_busy"); return; }
        despawn(player, entry, () -> {
            msg(player, "despawn_done", "name", entry.name());
            refresh.run();
        });
    }

    PurchasePrice purchasePrice() { return purchasePrice; }

    Component menuMessage(String key, String price) {
        return itemText(messages.getString(key, key).replace("%price%", price));
    }

    // ══ 购买 ══════════════════════════════════════════════

    /** 保存购买所需物品到数据库并通知其他子服；结果在存储线程上完成。 */
    CompletableFuture<Boolean> savePurchaseItems(List<ItemStack> items) {
        var previous = purchasePrice;
        return roster.savePurchaseItems(items).handle((unused, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not save purchase items", error);
                return false;
            }
            purchasePrice = new PurchasePrice(previous.coins(), previous.points(), items);
            network.broadcast("purchase_items", new JsonObject());
            return true;
        });
    }

    /**
     * 购买：先在数据库中占用最小的空闲编号，再扣费；扣费失败时删除刚占用的编号。
     * 结果为新假人的编号，失败时为 -1。
     */
    CompletableFuture<Integer> buyDummy(Player player, PurchasePrice quoted) {
        if (!player.hasPermission(USE)) return CompletableFuture.completedFuture(-1);
        UUID owner = player.getUniqueId();
        if (roster.owned(owner).size() >= maxSlots) { msg(player, "purchase_limit"); return CompletableFuture.completedFuture(-1); }
        if (quoted != purchasePrice) { msg(player, "purchase_changed"); return CompletableFuture.completedFuture(-1); }
        var result = new CompletableFuture<Integer>();
        roster.create(owner, maxSlots).whenComplete((entry, error) -> {
            if (error != null || entry == null) {
                if (error != null) getLogger().log(Level.WARNING, "Could not reserve a fake player slot", error);
                tell(player, () -> msg(player, error != null ? "storage_error" : "purchase_limit"));
                result.complete(-1);
                return;
            }
            Runnable abandon = () -> { roster.delete(entry); result.complete(-1); };
            if (!player.getScheduler().execute(this, () -> {
                boolean paid;
                try {
                    if (quoted != purchasePrice) { msg(player, "purchase_changed"); abandon.run(); return; }
                    paid = quoted.take(player);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
                    getLogger().log(Level.WARNING, "Could not charge fake player purchase", problem);
                    msg(player, "purchase_unavailable");
                    abandon.run();
                    return;
                }
                if (!paid) { msg(player, "purchase_insufficient"); abandon.run(); return; }
                if (entry.key.slot() > slots(owner)) roster.setAllowance(owner, entry.key.slot());
                roster.commit().thenRun(() -> invalidate(owner));
                msg(player, "purchase_success");
                result.complete(entry.key.slot());
            }, abandon, 1)) abandon.run();
        });
        return result;
    }

    void saveDirection(Roster.Entry entry, float yaw) {
        entry.workYaw = yaw;
        entry.directionSet = true;
        roster.dirty(entry);
        roster.save();
    }

    // ══ 消息 ══════════════════════════════════════════════

    void msg(CommandSender sender, String key, Object... pairs) { sender.sendMessage(message(key, pairs)); }

    /** 带前缀的消息，%名称% 按 pairs（名称, 值, 名称, 值…）替换。 */
    Component message(String key, Object... pairs) {
        String value = messages.getString(key, key);
        Component rendered = TEXT.deserialize(messages.getString("prefix", "") + value);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String token = "%" + pairs[index] + "%";
            Component text = Component.text(String.valueOf(pairs[index + 1]));
            rendered = rendered.replaceText(builder -> builder.matchLiteral(token).replacement(text));
        }
        return rendered;
    }

    /** 不带前缀的消息文字，用于按钮等。 */
    Component messageText(String key) { return TEXT.deserialize(messages.getString(key, key)); }

    /** 回到发送者所在线程执行（玩家用实体调度器，后台用全局调度器）。 */
    private void tell(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) player.getScheduler().execute(this, action, null, 1);
        else Bukkit.getGlobalRegionScheduler().execute(this, action);
    }

    /** 玩家帮助：只列玩家指令；管理员额外看到一行 admin help 提示。后台没有玩家指令，直接显示管理员帮助。 */
    private void sendHelp(CommandSender sender) {
        boolean manage = sender instanceof Player && sender.hasPermission(USE);
        boolean admin = sender.hasPermission(ADMIN);
        if (!manage) {
            if (admin) sendAdminHelp(sender);
            else msg(sender, "no_permission");
            return;
        }
        helpLines(sender, "header", "/afp");
        helpLines(sender, "manage", "/afp");
        if (sender.hasPermission(TELEPORT)) helpLines(sender, "teleport", "/afp");
        if (admin) helpLines(sender, "admin_hint", "/afp");
    }

    /** admin help：管理员指令，只有管理员可以查看。 */
    private void sendAdminHelp(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg(sender, "no_permission"); return; }
        helpLines(sender, "admin", sender instanceof Player ? "/afp" : "afp");
    }

    private void helpLines(CommandSender sender, String group, String command) {
        for (String line : messages.getStringList("command_help." + group)) {
            sender.sendMessage(TEXT.deserialize(line.replace("%command%", command)));
        }
    }

    int slots(UUID owner) { return Math.min(maxSlots, Math.max(baseSlots, roster.allowances.getOrDefault(owner, baseSlots))); }
    private boolean fake(CommandSender sender) { return sender instanceof Player player && live.containsKey(player.getUniqueId()); }
    boolean allowed(Player player, Roster.Entry entry) {
        return player.hasPermission(USE) && entry.key.owner().equals(player.getUniqueId())
            && entry.key.slot() <= slots(player.getUniqueId()) && roster.entries.get(entry.key) == entry;
    }

    /** 能否使用假人的背包和设置界面：拥有者，或管理员（/afp b <玩家> <编号>）。 */
    boolean manages(Player player, Roster.Entry entry) {
        return allowed(player, entry) || player.hasPermission(ADMIN) && roster.entries.get(entry.key) == entry;
    }

    // ══ 召唤 ══════════════════════════════════════════════

    private void summon(Player owner, int slot, DummyTier requestedTier) {
        UUID ownerId = owner.getUniqueId();
        Roster.Entry entry;
        DummyTier selectedTier;
        synchronized (admissions) {
            if (Bukkit.isStopping() || !isEnabled()) return;
            if (reloading) { msg(owner, "reload_busy"); return; }
            entry = roster.entries.get(new Roster.Key(ownerId, slot));
            if (entry == null) { msg(owner, "purchase_required"); return; }
            if (live.containsKey(entry.id) || pending.contains(entry.id)) { msg(owner, "already_live"); return; }
            String remote = roster.remoteHolder(entry);
            if (remote != null) { msg(owner, "remote_live", "server", serverLabel(remote), "slot", slot); return; }
            if (!summonAllowedHere(owner)) return;
            if (live.size() + pending.size() >= maxActive) { msg(owner, "server_limit"); return; }
            selectedTier = requestedTier == null ? entry.tier : requestedTier;
            pending.add(entry.id);
        }
        admit(owner, entry, selectedTier);
    }

    /**
     * 把在其他子服运行的假人召回到玩家身边：请求对方收回并保存，然后在本服生成。
     * 假人已经召唤并付过费，跨服召回不再收费，保持原档位。
     */
    void transfer(Player player, Roster.Entry entry, String holder) {
        synchronized (admissions) {
            if (Bukkit.isStopping() || !isEnabled()) return;
            if (reloading) { msg(player, "reload_busy"); return; }
            if (live.containsKey(entry.id) || pending.contains(entry.id)) { msg(player, "already_live"); return; }
            if (!summonAllowedHere(player)) return;
            if (live.size() + pending.size() >= maxActive) { msg(player, "server_limit"); return; }
            pending.add(entry.id);
        }
        msg(player, "transfer_wait", "slot", entry.key.slot(), "server", serverLabel(holder));
        var body = new JsonObject();
        body.addProperty("dummy", entry.id.toString());
        body.addProperty("mode", "transfer");
        network.request(holder, "release", body).whenComplete((reply, error) -> {
            // 对方没有回应时仍尝试接管：如果它已经失联，租约过期后即可召回
            if (error == null && reply != null && reply.has("ok") && !reply.get("ok").getAsBoolean()) {
                clearPending(entry);
                tell(player, () -> msg(player, "remote_busy", "server", serverLabel(holder)));
                return;
            }
            admit(player, entry, null);
        });
    }

    /** 准备权限组并获取运行租约，再回到玩家所在区域生成。tier 为 null 表示跨服召回。 */
    private void admit(Player owner, Roster.Entry entry, DummyTier tier) {
        groups.prepare(entry, luckPermsGroup)
            .orTimeout(10, TimeUnit.SECONDS)
            .thenCompose(unused -> roster.acquire(entry, true))
            .whenComplete((claim, error) -> {
                boolean acquired = error == null && claim.status() == Roster.Claim.ACQUIRED;
                Runnable cancel = () -> {
                    clearPending(entry);
                    if (acquired) releaseLease(entry);
                };
                if (Bukkit.isStopping() || !isEnabled()) { cancel.run(); return; }
                boolean queued = owner.getScheduler().execute(this, () -> {
                    if (error != null) {
                        cancel.run();
                        getLogger().log(Level.WARNING, "Could not prepare " + entry.profile() + " for summoning", error);
                        msg(owner, "spawn_failed");
                        return;
                    }
                    switch (claim.status()) {
                        case MISSING -> { clearPending(entry); msg(owner, "not_found"); return; }
                        case HELD -> { clearPending(entry); msg(owner, "remote_busy", "server", serverLabel(claim.holder())); return; }
                        default -> { }
                    }
                    if (!allowed(owner, entry)) { cancel.run(); return; }
                    Location position = owner.getLocation();
                    if (Bukkit.isOwnedByCurrentRegion(position)) finishSummon(owner, entry, position, tier);
                    else Bukkit.getRegionScheduler().execute(this, position, () -> finishSummon(owner, entry, position, tier));
                }, cancel, 1);
                if (!queued) cancel.run();
            });
    }

    /** slots.max_active_server 为 0 时本服禁止召唤，也不能把其他子服的假人召回本服。 */
    boolean summonAllowedHere(Player player) {
        if (maxActive > 0) return true;
        msg(player, "summon_disabled");
        return false;
    }

    private void clearPending(Roster.Entry entry) {
        synchronized (admissions) { pending.remove(entry.id); }
    }

    private void finishSummon(Player owner, Roster.Entry entry, Location position, DummyTier tier) {
        boolean spawned = false;
        try {
            synchronized (admissions) {
                if (Bukkit.isStopping() || !isEnabled() || !pending.contains(entry.id)
                    || roster.entries.get(entry.key) != entry) return;
                // 召唤途中开始了 reload：此时还没有扣费，让玩家稍后重试。
                // 跨服召回（tier 为 null）照常完成，否则假人已被对方收回、在哪都不运行，再召唤还要收费
                if (reloading && tier != null) { msg(owner, "reload_busy"); return; }
            }
            if (!owner.isOnline()) return;
            spawned = tier == null ? spawnTransferred(owner, entry, position) : spawnWithFee(owner, entry, position, tier);
        } finally {
            clearPending(entry);
            if (!spawned) releaseLease(entry);
        }
    }

    private boolean spawnTransferred(Player player, Roster.Entry entry, Location position) {
        try { spawn(entry, position); }
        catch (RuntimeException | LinkageError problem) {
            getLogger().log(Level.SEVERE, "Could not spawn transferred " + entry.profile(), problem);
            msg(player, "spawn_failed");
            return false;
        }
        roster.save();
        msg(player, "bring_done", "slot", entry.key.slot());
        return true;
    }

    private boolean spawnWithFee(Player player, Roster.Entry entry, Location position, DummyTier tier) {
        SpawnFee fee = null;
        double price = tierPrices.get(tier);
        String cost = BigDecimal.valueOf(price).stripTrailingZeros().toPlainString();
        if (paidSummoning && price > 0) {
            try {
                fee = SpawnFee.find(price);
                if (fee == null) { msg(player, "economy_unavailable"); return false; }
                if (!fee.debit(player)) { msg(player, "economy_declined", "price", cost); return false; }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
                getLogger().log(Level.WARNING, "Vault rejected charge for " + player.getUniqueId(), problem);
                msg(player, "economy_unavailable");
                return false;
            }
        }
        DummyTier previousTier = entry.tier;
        entry.tier = tier;
        roster.dirty(entry);
        try { spawn(entry, position); }
        catch (RuntimeException | LinkageError problem) {
            entry.tier = previousTier;
            roster.dirty(entry);
            roster.save();
            getLogger().log(Level.SEVERE, "Could not spawn " + entry.profile(), problem);
            msg(player, "spawn_failed");
            if (fee != null) refund(player, fee, cost);
            return false;
        }
        roster.save();
        msg(player, "spawned", "name", entry.name(), "slot", entry.key.slot(),
            "tier", messages.getString("tier_name." + tier.key, tier.key),
            "chunks", tierRadius.get(tier) * 2 + 1);
        if (fee != null) msg(player, "economy_paid", "price", cost);
        return true;
    }

    private void refund(Player player, SpawnFee fee, String cost) {
        try {
            if (fee.refund(player)) { msg(player, "economy_refunded", "price", cost); return; }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
            getLogger().log(Level.SEVERE, "Vault refund threw for " + player.getUniqueId(), problem);
        }
        getLogger().severe("Manual refund required: " + player.getUniqueId() + ", amount " + cost);
        msg(player, "economy_refund_failed", "price", cost);
    }

    Live spawn(Roster.Entry entry, Location position) {
        position = DummyTeleport.centered(position);
        String nativeName = entry.nativeName();
        if (Bukkit.getPlayerExact(nativeName) != null || Bukkit.getPlayer(entry.id) != null
            || live.values().stream().anyMatch(active -> active.dummy.getGameProfile().name().equalsIgnoreCase(nativeName))) {
            throw new IllegalStateException("Online game profile conflict: " + entry.profile());
        }
        if (position.getWorld() == null || Bukkit.getWorld(position.getWorld().getUID()) != position.getWorld()) {
            throw new IllegalStateException("Spawn world is unloaded");
        }
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        var level = ((CraftWorld) position.getWorld()).getHandle();
        GameProfile profile = new GameProfile(entry.id, nativeName);
        // 领地插件按主人判断假人的权限时使用主人的名字（见 ClaimIdentity）
        Player ownerOnline = Bukkit.getPlayer(entry.key.owner());
        String ownerName = ownerOnline != null ? ownerOnline.getName() : Bukkit.getOfflinePlayer(entry.key.owner()).getName();
        NativeDummy dummy = new NativeDummy(server, level, profile, ignoreHostileMobs, entry, ownerName);
        dummy.setShiftKeyDown(entry.sneaking);
        Live active = new Live(entry, dummy);
        live.put(entry.id, active);
        remoteTab.claimLocal(entry.id);
        try {
            float yaw = entry.directionSet ? entry.workYaw : position.getYaw();
            dummy.snapTo(position.getX(), position.getY(), position.getZ(), yaw, 0);
            dummy.setYHeadRot(yaw);
            Player player = dummy.getBukkitEntity();
            player.setPersistent(false);
            player.setAffectsSpawning(affectsSpawning);
            // 档位决定模拟距离（实际运行的范围）。Paper 不接受低于 2 的视距，且模拟距离不能超过视距，
            // 所以视距取 max(2, 运行半径)；视距只多加载一圈不运行的区块，假人没有客户端，不发送区块
            int radius = tierRadius.getOrDefault(entry.tier, 1);
            int viewDistance = Math.max(2, radius);
            player.setViewDistance(viewDistance);
            player.setSimulationDistance(radius);
            player.setSendViewDistance(viewDistance);
            synchronized (roster) { player.getInventory().setContents(entry.items.clone()); }
            dummy.prepareForWorld(server);
            dummy.setGameMode(GameType.SURVIVAL);
            applyName(active);
            level.addNewPlayer(dummy);
            if (!player.isValid() || dummy.isRemoved()) throw new IllegalStateException("Dummy entity was not added");
            active.inWorld = true;
            dummy.ready();
            active.actions.direction(yaw);
            applyName(active);
            for (Player viewer : Bukkit.getOnlinePlayers()) showDummy(active, viewer);
            player.getScheduler().runAtFixedRate(this, task -> {
                if (live.get(entry.id) != active) { task.cancel(); return; }
                tick(active);
            }, () -> {
                active.inWorld = false;
                active.hiddenTag.clear();
                if (live.remove(entry.id, active)) releaseLease(entry);
            }, 1, 1);
        } catch (RuntimeException | LinkageError error) {
            discard(active);
            throw error;
        }
        return active;
    }

    private void applyName(Live active) {
        Player player = active.dummy.getBukkitEntity();
        String prefix = groups.prefix(active.entry);
        Component label = TEXT.deserialize(prefix.replace('§', '&')).append(Component.text(active.entry.name()));
        player.displayName(label);
        shareLabel(active, names.tab(active.dummy, label, active.hiddenTag));
    }

    /** 显示名变化时广播给其他子服，让它们的 TAB 显示同样的前缀和称号。to 为 null 表示广播。 */
    private void shareLabel(Live active, Component shown) {
        if (!network.enabled()) return;
        String label = RemoteTab.JSON.serialize(shown);
        if (label.equals(active.sharedLabel)) return;
        active.sharedLabel = label;
        network.broadcast("tab", labelJson(active, label));
    }

    /** 把本服 /afp test 的测试条目（整份）发给其他子服显示；to 为 null 时广播。 */
    private void shareTests(String to) {
        if (network == null || !network.enabled()) return;
        var entries = new com.google.gson.JsonArray();
        benchmark.entries().forEach((id, shown) -> {
            var item = new JsonObject();
            item.addProperty("id", id.toString());
            item.addProperty("name", shown.name());
            item.addProperty("label", RemoteTab.JSON.serialize(shown.label()));
            entries.add(item);
        });
        var body = new JsonObject();
        body.add("entries", entries);
        if (to == null) network.broadcast("tab_test", body);
        else network.send(to, "tab_test", body);
    }

    private static JsonObject labelJson(Live active, String label) {
        var body = new JsonObject();
        body.addProperty("dummy", active.entry.id.toString());
        body.addProperty("name", active.dummy.getGameProfile().name());
        body.addProperty("label", label);
        return body;
    }

    private void tick(Live active) {
            int tick = ++active.tick;
            if (active.dead || active.relocating) return;
            if (active.dummy.isRemoved() || !active.dummy.link.isConnected()) { unlink(active, true); return; }
            try {
                if (active.dummy.isAlive()) active.actions.tick(tick, this);
                if (tick % 100 == 0) applyName(active);
                int phase = tick + active.actions.offset;
                if (phase % saveInterval == 0) {
                    // 快照后立即排队写入（只写有变化的数据），崩溃时最多丢失一个保存间隔的变更
                    snapshot(active);
                    roster.save();
                } else if (phase % 10 == 0 && menus.watching(active.entry)) snapshot(active);
            } catch (RuntimeException | LinkageError error) {
                getLogger().log(Level.SEVERE, "Disabling broken session " + active.entry.profile(), error);
                unlink(active, true);
            }
    }

    // ══ 背包 ══════════════════════════════════════════════

    /** 本服持有租约，且在线假人位于当前线程所在区域时才能修改背包。 */
    boolean editable(Roster.Entry entry) {
        if (!roster.holds(entry)) return false;
        Live active = live.get(entry.id);
        return active == null || active.dead || !active.relocating && Bukkit.isOwnedByCurrentRegion(active.dummy.getBukkitEntity());
    }

    ItemStack item(Roster.Entry entry, int slot) {
        Live active = live.get(entry.id);
        if (active != null && !active.dead && Bukkit.isOwnedByCurrentRegion(active.dummy.getBukkitEntity())) {
            return active.dummy.getBukkitEntity().getInventory().getItem(slot);
        }
        synchronized (roster) { return entry.items[slot]; }
    }

    boolean replaceItem(Roster.Entry entry, int slot, ItemStack expected, ItemStack value) {
        synchronized (roster) {
            if (roster.entries.get(entry.key) != entry || !editable(entry)) return false;
            Live active = live.get(entry.id);
            if (active != null && !active.dead) {
                if (active.relocating || !Bukkit.isOwnedByCurrentRegion(active.dummy.getBukkitEntity())) return false;
                var inventory = active.dummy.getBukkitEntity().getInventory();
                if (!Objects.equals(expected, copy(inventory.getItem(slot)))) return false;
                if (slot == 0 || slot == 40) active.dummy.stopUsingItem();
                inventory.setItem(slot, value);
            } else if (!Objects.equals(expected, copy(entry.items[slot]))) return false;
            entry.items[slot] = copy(value);
            entry.inventoryRevision++;
            roster.dirty(entry);
        }
        // 立即写入数据库（合并成一次写入），缩短崩服时玩家身上和假人背包都有同一份物品的窗口
        roster.save();
        entry.wakeWork();
        return true;
    }

    static ItemStack copy(ItemStack value) { return value == null || value.isEmpty() ? null : value.clone(); }

    private void snapshot(Live active) {
        if (active.dead) return;
        ItemStack[] inventory = active.dummy.getBukkitEntity().getInventory().getContents();
        synchronized (roster) {
          boolean changed = false;
          for (int slot = 0; slot < active.entry.items.length; slot++) {
            ItemStack item = inventory[slot];
            if (item != null && item.getType().isAir()) item = null;
            if (!Objects.equals(active.entry.items[slot], item)) {
                active.entry.items[slot] = copy(item);
                changed = true;
            }
          }
          if (changed) {
              active.entry.inventoryRevision++;
              roster.dirty(active.entry);
          }
        }
    }

    /** 设置被修改：写入数据库；假人在其他子服运行时，同时转发给该子服。 */
    void settingsChanged(Roster.Entry entry) {
        roster.dirty(entry);
        roster.save();
        entry.wakeWork();
        String holder = roster.remoteHolder(entry);
        if (holder != null) network.send(holder, "settings", settingsJson(entry, null));
    }

    /** 假人在其他子服运行时，把新的工作方向转发过去。 */
    boolean forwardDirection(Roster.Entry entry, float yaw) {
        String holder = roster.remoteHolder(entry);
        if (holder == null) return false;
        entry.workYaw = yaw;
        entry.directionSet = true;
        roster.dirty(entry);
        roster.save();
        network.send(holder, "settings", settingsJson(entry, yaw));
        return true;
    }

    private static JsonObject settingsJson(Roster.Entry entry, Float yaw) {
        var body = new JsonObject();
        body.addProperty("dummy", entry.id.toString());
        body.addProperty("alias", entry.alias);
        body.addProperty("mine", entry.mine);
        body.addProperty("attack", entry.attack);
        body.addProperty("use", entry.use);
        body.addProperty("unload", entry.autoUnload);
        body.addProperty("pushable", entry.pushable);
        body.addProperty("sneak", entry.sneaking);
        body.addProperty("fish", entry.fishing);
        body.addProperty("interval", entry.attackIntervalTicks);
        body.addProperty("placement", entry.placementBlock == null ? "" : entry.placementBlock.getKey().toString());
        if (yaw != null) body.addProperty("yaw", yaw);
        return body;
    }

    void prepareRelocation(Live active) {
        active.actions.stopMining();
        active.entry.wakeWork();
        active.relocating = true;
    }

    // ══ 收回与租约 ═════════════════════════════════════════

    /** 移除假人实体并释放租约；keep 为 true 时先保存背包。结果在租约释放（数据写入）后完成。 */
    CompletableFuture<Void> unlink(Live active, boolean keep) {
        if (keep) snapshot(active);
        try { detach(active); }
        finally {
            if (live.remove(active.entry.id, active)) active.released = releaseLease(active.entry);
        }
        return active.released;
    }

    /** 移除实体但不保存、不释放租约：用于租约已被其他子服接管或假人已被删除。 */
    private void discard(Live active) {
        try { detach(active); }
        finally { live.remove(active.entry.id, active); }
    }

    private void detach(Live active) {
        active.hiddenTag.clear();
        active.inWorld = false;
        try {
            if (!active.dummy.isRemoved() && active.dummy.valid) {
                active.actions.stopMining();
                active.dummy.level().removePlayerImmediately(active.dummy, Entity.RemovalReason.DISCARDED);
            }
        } finally {
            try { folia.detach(active.dummy); }
            finally { active.dummy.link.dispose(); }
        }
    }

    /** 假人离开本服后的租约处理：背包编辑界面仍打开时降为编辑租约，否则释放。 */
    CompletableFuture<Void> releaseLease(Roster.Entry entry) {
        if (!shuttingDown && menus.editing(entry)) {
            roster.downgrade(entry);
            return CompletableFuture.completedFuture(null);
        }
        return roster.release(entry, false);
    }

    /** 存储线程回调：租约变更后通知其他子服。live 为 null 表示已释放。 */
    void sessionChanged(UUID dummy, Boolean liveNow) {
        if (network == null || !network.enabled()) return;
        var body = new JsonObject();
        body.addProperty("dummy", dummy.toString());
        body.addProperty("live", Boolean.TRUE.equals(liveNow));
        network.broadcast("session", body);
    }

    /** 存储线程回调：假人已在其他子服被删除。 */
    void entryRemoved(Roster.Entry entry) {
        synchronized (admissions) { pending.remove(entry.id); }
        if (menus != null) menus.close(entry);
        Live active = live.get(entry.id);
        if (active != null) active.dummy.getBukkitEntity().getScheduler().execute(this,
            () -> { if (live.get(entry.id) == active) discard(active); }, null, 1);
    }

    /** 存储线程回调：本服失联期间租约被其他子服接管，本地副本直接移除且不保存，防止复制物品。 */
    void leaseLost(Roster.Entry entry) {
        getLogger().warning("Another server took over " + entry.profile() + "; removing the local copy without saving.");
        menus.close(entry);
        Live active = live.get(entry.id);
        if (active != null) active.dummy.getBukkitEntity().getScheduler().execute(this,
            () -> { if (live.get(entry.id) == active) discard(active); }, null, 1);
    }

    private void invalidate(UUID owner) {
        if (!network.enabled()) return;
        var body = new JsonObject();
        body.addProperty("owner", owner.toString());
        network.broadcast("invalidate", body);
    }

    // ══ 跨服消息 ═══════════════════════════════════════════

    private void handleRemote(String type, String from, JsonObject body, Consumer<JsonObject> reply) {
        switch (type) {
            case "invalidate" -> roster.reloadOwner(uuid(body, "owner")).exceptionally(error -> {
                getLogger().log(Level.WARNING, "Could not refresh fake players after a cross-server change", error);
                return null;
            });
            case "session" -> roster.remoteSession(uuid(body, "dummy"), from, body.get("live").getAsBoolean());
            case "release" -> remoteRelease(uuid(body, "dummy"), body.get("mode").getAsString(), reply);
            case "settings" -> remoteSettings(uuid(body, "dummy"), body);
            case "arrival" -> teleports.expect(uuid(body, "player"), uuid(body, "dummy"));
            case "clear" -> Bukkit.getGlobalRegionScheduler().execute(this, this::clearLocal);
            case "tab" -> remoteTab.label(uuid(body, "dummy"), body.get("name").getAsString(), body.get("label").getAsString());
            case "tab_request" -> {
                live.values().forEach(active -> {
                    String label = active.sharedLabel;
                    if (isSummoned(active) && label != null) network.send(from, "tab", labelJson(active, label));
                });
                if (benchmark.count() > 0) shareTests(from);
            }
            case "tab_test" -> {
                Map<UUID, String[]> entries = new LinkedHashMap<>();
                for (var element : body.getAsJsonArray("entries")) {
                    JsonObject item = element.getAsJsonObject();
                    String name = item.get("name").getAsString();
                    entries.put(uuid(item, "id"), new String[] {name, item.has("label") ? item.get("label").getAsString() : null});
                }
                remoteTab.tests(from, entries);
            }
            case "purchase_items" -> roster.reloadPurchaseItems().thenAccept(items -> {
                var current = purchasePrice;
                purchasePrice = new PurchasePrice(current.coins(), current.points(), items);
            });
            default -> { }
        }
    }

    private static UUID uuid(JsonObject body, String key) { return UUID.fromString(body.get(key).getAsString()); }

    private static JsonObject result(boolean ok) {
        var body = new JsonObject();
        body.addProperty("ok", ok);
        return body;
    }

    /** 其他子服请求本服收回假人（召回、收回、删除）。数据写入并释放租约后才回复。 */
    private void remoteRelease(UUID id, String mode, Consumer<JsonObject> reply) {
        Roster.Entry entry = roster.byId.get(id);
        if (entry == null) { reply.accept(result(true)); return; }
        Runnable releaseOnly = () -> {
            menus.close(entry);
            parked.remove(id); // reload 期间暂时移出的假人不再召唤回来
            roster.release(entry, false).whenComplete((unused, error) -> reply.accept(result(error == null)));
        };
        Live active = live.get(id);
        if (active == null) { releaseOnly.run(); return; }
        boolean keep = !mode.equals("delete");
        if (!active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
            if (live.get(id) != active) { releaseOnly.run(); return; }
            CompletableFuture<Void> unlinked = unlink(active, keep);
            menus.close(entry);
            CompletableFuture.allOf(unlinked, roster.release(entry, false))
                .whenComplete((unused, error) -> reply.accept(result(error == null)));
            roster.save();
        }, releaseOnly, 1)) releaseOnly.run();
    }

    /** 其他子服修改了在本服运行的假人的设置。 */
    private void remoteSettings(UUID id, JsonObject body) {
        Roster.Entry entry = roster.byId.get(id);
        if (entry == null || !roster.holds(entry)) return;
        entry.alias = body.get("alias").getAsString();
        entry.mine = body.get("mine").getAsBoolean();
        entry.attack = body.get("attack").getAsBoolean();
        entry.use = body.get("use").getAsBoolean();
        entry.autoUnload = body.get("unload").getAsBoolean();
        entry.pushable = body.get("pushable").getAsBoolean();
        entry.sneaking = body.get("sneak").getAsBoolean();
        entry.fishing = body.has("fish") && body.get("fish").getAsBoolean();
        entry.attackIntervalTicks = Math.clamp(body.get("interval").getAsInt(), 20, 200);
        String placement = body.get("placement").getAsString();
        entry.placementBlock = placement.isEmpty() ? null : Material.matchMaterial(placement);
        roster.dirty(entry);
        roster.save();
        entry.wakeWork();
        Live active = live.get(id);
        if (active == null) return;
        active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
            if (live.get(id) != active || active.dead) return;
            if (body.has("yaw")) {
                float yaw = body.get("yaw").getAsFloat();
                active.actions.direction(yaw);
                saveDirection(entry, yaw);
            }
            applyName(active);
        }, null, 1);
    }

    // ══ 事件 ══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof CraftPlayer player && player.getHandle() instanceof NativeDummy dummy) {
            var item = ((CraftItem) event.getItem()).getHandle();
            // 开启搬运时，主背包放不下就不捡，等卸货腾出空间再捡；否则物品会塞进快捷栏，而快捷栏不卸货
            Live active = live.get(dummy.getUUID());
            if (unloadChests && active != null && active.entry.autoUnload && !dummy.canStore(item.getItem())) {
                event.setCancelled(true);
                return;
            }
            dummy.beforePickup(item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDummyBeaconHealing(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player && fake(player)
            && event.getCause() == EntityPotionEffectEvent.Cause.BEACON
            && event.getModifiedType().equals(PotionEffectType.REGENERATION)) {
            event.setCancelled(true);
        }
    }

    /**
     * 附近只有假人、没有真人时，取消动物、鱼、蝙蝠等非敌对生物的自然生成。
     * 敌对生物不受影响，刷怪塔照常出怪；距离按该类生物的原版消失距离判断（鱼 64 格，其余 128 格）。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalPassiveSpawn(PreCreatureSpawnEvent event) {
        if (!passiveSpawnsNeedPlayer || event.getReason() != CreatureSpawnEvent.SpawnReason.NATURAL || live.isEmpty()) return;
        MobCategory category = CraftEntityType.bukkitToMinecraft(event.getType()).getCategory();
        if (!category.isFriendly()) return;
        Location position = event.getSpawnLocation();
        if (!(position.getWorld() instanceof CraftWorld world)) return;
        var level = world.getHandle();
        double x = position.getBlockX() + 0.5, y = position.getBlockY(), z = position.getBlockZ() + 0.5;
        double radius = category.getDespawnDistance();
        if (level.getNearestPlayer(x, y, z, radius, player -> player instanceof NativeDummy && !player.isSpectator()) == null) return;
        if (level.getNearestPlayer(x, y, z, radius, player -> !(player instanceof NativeDummy)
            && !player.isSpectator() && player instanceof net.minecraft.world.entity.player.Player real && real.affectsSpawning) == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostileTarget(EntityTargetEvent event) {
        if (ignoreHostileMobs && event.getEntity() instanceof Enemy && event.getTarget() instanceof Player target && fake(target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostileDamage(EntityDamageByEntityEvent event) {
        if (!ignoreHostileMobs || !(event.getEntity() instanceof Player target) || !fake(target)) return;
        if (event.getDamageSource().getCausingEntity() instanceof Enemy || event.getDamager() instanceof Enemy
            || event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Enemy) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Live active = live.get(event.getEntity().getUniqueId());
        if (active == null || event.isCancelled()) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.getDrops().clear();
        snapshot(active);
        active.dead = true;
        roster.save();
        event.getEntity().getScheduler().execute(this, () -> {
            if (live.get(active.entry.id) == active) unlink(active, true);
        }, null, 1);
    }

    /**
     * 自动钓鱼：鱼咬钩时通知假人下一 tick 收竿。
     * 原版把钓到的东西从浮漂朝玩家抛过来，浮漂离得远、和玩家高度差小时会掉回水里；
     * 假人钓到的东西直接放到它脚下（此时还没加入世界），下一 tick 就能捡起。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Live active = live.get(event.getPlayer().getUniqueId());
        if (active == null) return;
        if (event.getState() == PlayerFishEvent.State.BITE) active.actions.bite();
        else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof CraftItem item) {
            item.getHandle().setPos(active.dummy.getX(), active.dummy.getY(), active.dummy.getZ());
        }
    }

    /** 领地插件在本插件之后启用、或被单独重载时，重新登记它的类加载器。 */
    @EventHandler public void onPluginEnable(PluginEnableEvent event) { ClaimIdentity.refresh(null); }
    @EventHandler public void onPluginDisable(PluginDisableEvent event) { ClaimIdentity.refresh(event.getPlugin()); }

    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        benchmark.join(player);
        remoteTab.join(player);
        live.values().forEach(active -> showDummy(active, player));
        if (network.enabled() && !fake(player)) {
            // 玩家可能刚从其他子服过来，重新读取他的假人
            roster.reloadOwner(player.getUniqueId()).exceptionally(error -> {
                getLogger().log(Level.WARNING, "Could not refresh fake players of " + player.getName(), error);
                return null;
            });
            teleports.arrived(player);
        }
    }

    private void showDummy(Live active, Player player) {
        if (active.inWorld && !active.dead && player instanceof CraftPlayer viewer && !(viewer.getHandle() instanceof NativeDummy)) {
            active.hiddenTag.showTo(active.dummy, viewer.getHandle());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackDummy(PlayerTrackEntityEvent event) {
        if (!(event.getEntity() instanceof CraftPlayer tracked) || !(tracked.getHandle() instanceof NativeDummy dummy)
            || !(event.getPlayer() instanceof CraftPlayer viewer) || viewer.getHandle() instanceof NativeDummy) return;
        Live active = live.get(dummy.getUUID());
        if (active != null && active.dummy == dummy && !active.dead) active.hiddenTag.showTo(dummy, viewer.getHandle());
    }

    @EventHandler public void onViewerQuit(PlayerQuitEvent event) {
        benchmark.quit(event.getPlayer());
        remoteTab.quit(event.getPlayer());
        UUID viewer = event.getPlayer().getUniqueId();
        live.values().forEach(active -> active.hiddenTag.forget(viewer));
    }

    // ══ 改名卡 ════════════════════════════════════════════

    private void rename(Player player, int slot, String name) {
        Roster.Key key = new Roster.Key(player.getUniqueId(), slot);
        Roster.Entry entry = roster.entries.get(key);
        if (entry == null) { msg(player, "rename_not_found"); return; }
        if (!name.matches("[A-Za-z0-9_]{1,16}") || Bukkit.getPlayerExact(name) != null || Bukkit.getOfflinePlayerIfCached(name) != null
            || name.matches("[0-9]+") || name.equalsIgnoreCase("all")) {
            msg(player, "bad_name"); return;
        }
        synchronized (roster) {
            if (roster.entries.get(key) != entry) { msg(player, "rename_not_found"); return; }
            if (entry.name().equals(name) || roster.entries.values().stream().anyMatch(other -> other != entry && other.matchesName(name))) {
                msg(player, "bad_name"); return;
            }
            if (!consumeRenameCard(player)) { msg(player, "rename_card_required"); return; }
            entry.alias = name;
        }
        settingsChanged(entry);
        roster.commit().thenRun(() -> invalidate(entry.key.owner()));
        Live active = live.get(entry.id);
        if (active != null) active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
            if (!active.dead && live.get(entry.id) == active) applyName(active);
        }, null, 1);
        msg(player, "renamed", "name", name);
        if (active != null || roster.remoteHolder(entry) != null) msg(player, "rename_resummon");
    }

    private boolean consumeRenameCard(Player player) {
        var inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack stack = storage[slot];
            if (!isRenameCard(stack)) continue;
            inventory.setItem(slot, stack.getAmount() == 1 ? null : stack.asQuantity(stack.getAmount() - 1));
            return true;
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (!isRenameCard(offhand)) return false;
        inventory.setItemInOffHand(offhand.getAmount() == 1 ? null : offhand.asQuantity(offhand.getAmount() - 1));
        return true;
    }

    private static boolean isRenameCard(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getType() == Material.PAPER
            && Byte.valueOf((byte) 1).equals(stack.getPersistentDataContainer().get(RENAME_CARD, PersistentDataType.BYTE));
    }

    private void giveRenameCards(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) { msg(sender, "no_permission"); return; }
        int amount = args.length == 2 ? 1 : args.length == 3 ? integer(args[2]) : -1;
        if (amount < 1 || amount > 64) { msg(sender, "givecard_help"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || fake(target)) { msg(sender, "givecard_offline"); return; }
        Runnable offline = () -> msg(sender, "givecard_offline");
        if (!target.getScheduler().execute(this, () -> {
            if (!target.isOnline()) { offline.run(); return; }
            ItemStack card = ItemStack.of(Material.PAPER, amount);
            card.editMeta(meta -> {
                meta.displayName(itemText(messages.getString("rename_card.name", "&6黑奴改名卡")));
                meta.lore(messages.getStringList("rename_card.lore").stream().map(DummyPlayers::itemText).toList());
                meta.getPersistentDataContainer().set(RENAME_CARD, PersistentDataType.BYTE, (byte) 1);
            });
            int leftover = target.getInventory().addItem(card).values().stream().mapToInt(ItemStack::getAmount).sum();
            if (leftover < amount) msg(sender, "givecard_done", "player", target.getName(), "amount", amount - leftover);
            if (leftover > 0) msg(sender, "givecard_full", "amount", leftover);
        }, offline, 1)) offline.run();
    }

    private static Component itemText(String value) {
        return TEXT.deserialize(value).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ══ 指令 ══════════════════════════════════════════════

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (fake(sender)) return true;
        if (reloading) { msg(sender, "reload_busy"); return true; }
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 0) {
            if (sender instanceof Player player && player.hasPermission(USE)) rosterMenus.openMain(player, 0);
            else sendHelp(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        // 管理员与通用指令（游戏内或后台）
        switch (action) {
            case "help" -> { sendHelp(sender); return true; }
            case "admin" -> { sendAdminHelp(sender); return true; }
            case "despawn" -> { despawnCommand(sender, admin, args); return true; }
            case "delete" -> { deleteCommand(sender, admin, args); return true; }
            case "grant", "set" -> { grantCommand(sender, admin, action.equals("set"), args); return true; }
            case "card" -> { giveRenameCards(sender, args); return true; }
            case "b" -> { inventoryCommand(sender, admin, args); return true; }
            case "items" -> {
                if (!admin) msg(sender, "no_permission");
                else if (sender instanceof Player player) rosterMenus.openPurchaseItems(player);
                else msg(sender, "player_only");
                return true;
            }
            case "clear" -> { clearCommand(sender, admin, args); return true; }
            case "servers" -> { listServers(sender, admin); return true; }
            case "reload" -> { reloadCommand(sender); return true; }
            case "test" -> {
                if (!admin) msg(sender, "no_permission");
                else {
                    benchmark.command(sender, args);
                    shareTests(null);
                }
                return true;
            }
            default -> { }
        }
        // 玩家指令（游戏内）；未知指令显示帮助
        if (!PLAYER_ACTIONS.contains(action)) { sendHelp(sender); return true; }
        if (!(sender instanceof Player player)) { msg(sender, "player_only"); return true; }
        if (!player.hasPermission(USE)) { msg(sender, "no_permission"); return true; }
        switch (action) {
            case "spawn" -> spawnCommand(player, args);
            case "list" -> listCommand(player);
            case "dir" -> directionCommand(player, args);
            case "rename" -> {
                int slot = args.length >= 3 ? integer(args[1]) : -1;
                if (slot < 1) msg(player, "rename_help");
                else rename(player, slot, String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip());
            }
            case "tp", "tph" -> teleportCommand(player, action.equals("tph"), args);
            default -> { }
        }
        return true;
    }

    /** b <编号>：打开自己假人的背包；b <玩家> <编号>：管理员打开任意玩家假人的背包（可编辑，包括超出栏位的假人）。 */
    private void inventoryCommand(CommandSender sender, boolean admin, String[] args) {
        if (!(sender instanceof Player player)) { msg(sender, "player_only"); return; }
        if (args.length == 3) {
            if (!admin) { msg(player, "no_permission"); return; }
            int slot = integer(args[2]);
            if (slot < 1) { msg(player, "inventory_admin_help"); return; }
            UUID owner = playerId(args[1]);
            Roster.Entry entry = owner == null ? null : roster.entries.get(new Roster.Key(owner, slot));
            if (entry == null) msg(player, "inventory_not_found", "player", args[1], "slot", slot);
            else menus.open(player, entry, "inventory");
            return;
        }
        if (!player.hasPermission(USE)) { msg(player, "no_permission"); return; }
        int slot = args.length == 2 ? integer(args[1]) : -1;
        if (slot < 1) { msg(player, admin ? "inventory_admin_help" : "inventory_help"); return; }
        if (!unlocked(player, slot)) return;
        Roster.Entry entry = roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
        if (entry == null) msg(player, "not_found");
        else menus.open(player, entry, "inventory");
    }

    private boolean unlocked(Player player, int slot) {
        int slots = slots(player.getUniqueId());
        if (slot <= slots) return true;
        msg(player, "locked", "slots", slots);
        return false;
    }

    /** spawn [编号] [normal|pro|promax]；只写等级时召唤 1 号。 */
    private void spawnCommand(Player player, String[] args) {
        if (args.length > 3) { msg(player, "spawn_help"); return; }
        int slot = 1;
        DummyTier tier = null;
        if (args.length >= 2) {
            DummyTier named = args.length == 2 ? DummyTier.fromName(args[1]) : null;
            if (named != null) tier = named;
            else slot = integer(args[1]);
        }
        if (args.length == 3) {
            tier = DummyTier.fromName(args[2]);
            if (tier == null) { msg(player, "spawn_help"); return; }
        }
        if (slot < 1) { msg(player, "spawn_help"); return; }
        if (unlocked(player, slot)) summon(player, slot, tier);
    }

    private void listCommand(Player player) {
        msg(player, "slots", "slots", slots(player.getUniqueId()));
        for (Roster.Entry bot : roster.owned(player.getUniqueId())) {
            String remote = roster.remoteHolder(bot);
            if (summoned(bot)) msg(player, "list_live", "name", bot.name(), "slot", bot.key.slot());
            else if (remote != null) msg(player, "list_remote", "name", bot.name(), "slot", bot.key.slot(), "server", serverLabel(remote));
            else msg(player, "list_offline", "name", bot.name(), "slot", bot.key.slot());
        }
    }

    private void directionCommand(Player player, String[] args) {
        int slot = args.length == 2 ? integer(args[1]) : -1;
        if (slot < 1) { msg(player, "direction_help"); return; }
        if (!unlocked(player, slot)) return;
        Roster.Entry entry = roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
        Live active = entry == null ? null : live.get(entry.id);
        float yaw = player.getLocation().getYaw();
        if (active == null || active.dead) {
            if (entry != null && forwardDirection(entry, yaw)) msg(player, "direction_set", "slot", slot);
            else msg(player, "direction_offline");
            return;
        }
        active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
            if (active.dead || live.get(entry.id) != active) return;
            active.actions.direction(yaw);
            saveDirection(entry, yaw);
            player.getScheduler().execute(this, () -> msg(player, "direction_set", "slot", slot), null, 1);
        }, null, 1);
    }

    /** tp / tph <编号或名字>。 */
    private void teleportCommand(Player player, boolean bring, String[] args) {
        String target = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
        int slot = integer(target);
        if (slot < 1 && !target.isEmpty()) slot = roster.owned(player.getUniqueId()).stream()
            .filter(entry -> allowed(player, entry) && entry.matchesName(target))
            .mapToInt(entry -> entry.key.slot()).findFirst().orElse(-1);
        if (!player.hasPermission(TELEPORT)) msg(player, "no_permission");
        else if (slot < 1) msg(player, bring ? "bring_help" : "teleport_help");
        else teleports.request(player, slot, bring);
    }

    /** 按编号（只找自己的）或名字（管理员可找任意玩家的）查找假人。 */
    private Roster.Entry find(Player player, boolean admin, String target) {
        int slot = integer(target);
        if (slot > 0) return player == null ? null : roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
        UUID owner = player == null ? null : player.getUniqueId();
        return roster.entries.values().stream()
            .filter(bot -> (admin || bot.key.owner().equals(owner)) && bot.matchesName(target))
            .min(Comparator.comparing(bot -> !bot.key.owner().equals(owner))).orElse(null);
    }

    /** despawn [编号或名字]：玩家收回自己的假人；管理员可按名字收回任意玩家的假人（包括其他子服）。 */
    private void despawnCommand(CommandSender sender, boolean admin, String[] args) {
        Player player = sender instanceof Player p && p.hasPermission(USE) ? p : null;
        if (player == null && !admin) { msg(sender, "no_permission"); return; }
        String target = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
        if (target.isEmpty() && player == null) { msg(sender, "despawn_help"); return; }
        Roster.Entry entry = find(player, admin, target.isEmpty() ? "1" : target);
        if (entry == null) { msg(sender, "not_found"); return; }
        if (player != null && entry.key.owner().equals(player.getUniqueId())) {
            if (!allowed(player, entry)) msg(player, "despawn_offline");
            else despawn(player, entry, () -> msg(player, "despawn_done", "name", entry.name()));
        } else kill(sender, entry);
    }

    /** delete <编号或名字>：玩家删除自己的假人；管理员可按名字删除任意玩家的假人。 */
    private void deleteCommand(CommandSender sender, boolean admin, String[] args) {
        Player player = sender instanceof Player p && p.hasPermission(USE) ? p : null;
        if (player == null && !admin) { msg(sender, "no_permission"); return; }
        String target = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
        if (target.isEmpty()) { msg(sender, "delete_help"); return; }
        Roster.Entry entry = find(player, admin, target);
        if (entry == null) msg(sender, "not_found");
        else deleteEverywhere(sender, entry);
    }

    /** grant <玩家> <数量>：增加；set <玩家> <总数>：补到指定总数。 */
    private void grantCommand(CommandSender sender, boolean admin, boolean setTotal, String[] args) {
        if (!admin) { msg(sender, "no_permission"); return; }
        UUID owner = args.length >= 2 ? owner(args[1]) : null;
        int amount = args.length == 3 ? integer(args[2]) : -1;
        if (owner == null || amount < (setTotal ? 0 : 1) || amount > maxSlots) { msg(sender, "grant_help"); return; }
        String name = args[1];
        roster.grant(owner, amount, setTotal, maxSlots, baseSlots).whenComplete((total, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not grant fake players", error);
                tell(sender, () -> msg(sender, "storage_error"));
                return;
            }
            invalidate(owner);
            tell(sender, () -> msg(sender, "granted", "name", name, "slots", total));
        });
    }

    /** 收回自己的假人：本服直接移除，其他子服则发送请求。 */
    private void despawn(Player player, Roster.Entry entry, Runnable done) {
        Live active = live.get(entry.id);
        if (active != null && summoned(entry)) {
            Runnable offline = () -> player.getScheduler().execute(this, () -> msg(player, "despawn_offline"), null, 1);
            if (!active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
                if (live.get(entry.id) != active || !summoned(entry)) { offline.run(); return; }
                unlink(active, true);
                roster.save();
                player.getScheduler().execute(this, done, null, 1);
            }, offline, 1)) offline.run();
            return;
        }
        String holder = roster.remoteHolder(entry);
        if (holder == null) { msg(player, "despawn_offline"); return; }
        remoteRequest(player, entry, holder, "despawn", done);
    }

    private void remoteRequest(CommandSender sender, Roster.Entry entry, String holder, String mode, Runnable done) {
        var body = new JsonObject();
        body.addProperty("dummy", entry.id.toString());
        body.addProperty("mode", mode);
        network.request(holder, "release", body).whenComplete((reply, error) -> {
            boolean ok = error == null && reply != null && reply.has("ok") && reply.get("ok").getAsBoolean();
            if (ok) roster.remoteSession(entry.id, holder, false);
            tell(sender, ok ? done : () -> msg(sender, "remote_timeout", "server", serverLabel(holder)));
        });
    }

    /** 清除已召唤的假人（保留存档），支持其他子服。 */
    private void kill(CommandSender sender, Roster.Entry entry) {
        Live active = live.get(entry.id);
        if (isSummoned(active)) {
            if (!active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
                if (live.get(entry.id) != active || !isSummoned(active)) return;
                unlink(active, true);
                roster.save();
            }, null, 1)) msg(sender, "despawn_offline");
            else msg(sender, "despawn_all_queued", "count", 1);
            return;
        }
        String holder = roster.remoteHolder(entry);
        if (holder == null) { msg(sender, "despawn_offline"); return; }
        remoteRequest(sender, entry, holder, "despawn", () -> msg(sender, "despawn_all_queued", "count", 1));
    }

    /** 永久删除：假人在其他子服运行时，先请求对方移除（无论是否回应都会删除，对方下次保存时会自行清理）。 */
    private void deleteEverywhere(CommandSender sender, Roster.Entry entry) {
        String holder = roster.remoteHolder(entry);
        if (holder == null) {
            delete(entry);
            msg(sender, "deleted", "name", entry.name());
            return;
        }
        var body = new JsonObject();
        body.addProperty("dummy", entry.id.toString());
        body.addProperty("mode", "delete");
        network.request(holder, "release", body).whenComplete((reply, error) -> tell(sender, () -> {
            if (roster.byId.get(entry.id) != entry) return;
            delete(entry);
            msg(sender, "deleted", "name", entry.name());
        }));
    }

    private void delete(Roster.Entry entry) {
        synchronized (admissions) { pending.remove(entry.id); }
        menus.close(entry);
        Live active = live.get(entry.id);
        roster.delete(entry).whenComplete((unused, error) -> {
            if (error != null) getLogger().log(Level.SEVERE, "Could not delete " + entry.profile() + " from the database", error);
            else invalidate(entry.key.owner());
        });
        if (active != null) active.dummy.getBukkitEntity().getScheduler().execute(this,
            () -> { if (live.get(entry.id) == active) discard(active); }, null, 1);
    }

    /** clear [服务器ID|all]：清除本服、指定子服或全部子服已召唤的假人，保留存档。10 秒内输入两次才执行。 */
    private void clearCommand(CommandSender sender, boolean admin, String[] args) {
        if (!admin) { msg(sender, "no_permission"); return; }
        if (args.length > 2) { msg(sender, "clear_help"); return; }
        String target = args.length == 2 ? args[1] : serverId;
        boolean all = target.equalsIgnoreCase("all");
        boolean local = target.equals(serverId);
        if (!all && !local) {
            if (!network.enabled()) { msg(sender, "network_disabled"); return; }
            if (roster.servers().stream().noneMatch(info -> info.id().equals(target))) {
                msg(sender, "server_unknown", "target", target);
                return;
            }
        }
        String key = (sender instanceof Player player ? player.getUniqueId().toString() : "console") + "|" + target;
        long now = System.currentTimeMillis();
        Long deadline = clearConfirmations.remove(key);
        if (deadline == null || deadline < now) {
            clearConfirmations.values().removeIf(time -> time < now);
            clearConfirmations.put(key, now + 10_000);
            String typed = (sender instanceof Player ? "/afp clear" : "afp clear") + (args.length == 2 ? " " + args[1] : "");
            msg(sender, "clear_confirm", "target", all ? "all" : serverLabel(target), "command", typed);
            return;
        }
        if (!all && !local) {
            network.send(target, "clear", new JsonObject());
            msg(sender, "despawn_all_sent", "target", serverLabel(target));
            return;
        }
        if (all && network.enabled()) {
            network.broadcast("clear", new JsonObject());
            msg(sender, "despawn_all_sent", "target", "all");
        }
        msg(sender, "despawn_all_queued", "count", clearLocal());
    }

    private int clearLocal() {
        int queued = 0;
        for (Live active : live.values()) {
            if (!isSummoned(active)) continue;
            boolean scheduled = active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
                if (live.get(active.entry.id) != active || !isSummoned(active)) return;
                unlink(active, true);
                roster.save();
            }, null, 1);
            if (scheduled) queued++;
        }
        // reload 期间暂时移出的假人：不再召唤回来，直接释放租约
        for (Parked state : List.copyOf(parked.values())) {
            if (parked.remove(state.entry.id, state)) {
                releaseLease(state.entry);
                queued++;
            }
        }
        return queued;
    }

    private void listServers(CommandSender sender, boolean admin) {
        if (!admin) { msg(sender, "no_permission"); return; }
        if (!network.enabled()) { msg(sender, "network_disabled"); return; }
        msg(sender, "servers_header");
        long timeout = network.settings.timeoutMillis();
        for (Roster.ServerInfo info : roster.servers()) {
            boolean self = info.id().equals(serverId);
            if (self || info.silentMillis() < timeout) {
                msg(sender, "servers_online", "name", info.name(), "id", info.id(), "count", self ? summonedCount() : info.live());
            } else {
                msg(sender, "servers_offline", "name", info.name(), "id", info.id(), "seconds", info.silentMillis() / 1000);
            }
        }
    }

    private UUID owner(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player != null && !fake(player)) return player.getUniqueId();
        var offline = offlinePlayer(name);
        var id = offline == null ? null : offline.getUniqueId();
        return offline != null && (offline.hasPlayedBefore() || roster.allowances.containsKey(id))
            && !roster.byId.containsKey(id) ? id : null;
    }

    /** 按名字或 UUID 查找玩家，不要求在本服玩过（跨服时玩家可能只去过其他子服）。 */
    private UUID playerId(String name) {
        try { return UUID.fromString(name); }
        catch (IllegalArgumentException notUuid) { }
        Player player = Bukkit.getPlayerExact(name);
        if (player != null) return player.getUniqueId();
        var offline = offlinePlayer(name);
        return offline == null ? null : offline.getUniqueId();
    }

    /** 只有指令才会走到这里；遍历离线玩家较慢，不要用于 Tab 补全。 */
    private static OfflinePlayer offlinePlayer(String name) {
        var offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline != null) return offline;
        return Arrays.stream(Bukkit.getOfflinePlayers())
            .filter(known -> name.equalsIgnoreCase(known.getName())).findFirst().orElse(null);
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { return -1; }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (fake(sender) || args.length == 0) return List.of();
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        String first = args[0].toLowerCase(Locale.ROOT);
        Player self = sender instanceof Player player && player.hasPermission(USE) ? player : null;
        boolean teleport = self != null && self.hasPermission(TELEPORT);
        List<Roster.Entry> own = self == null ? List.of()
            : roster.owned(self.getUniqueId()).stream().filter(entry -> allowed(self, entry)).toList();
        if (args.length == 1) {
            options.add("help");
            if (self != null) options.addAll(List.of("spawn", "despawn", "list", "b", "dir", "rename", "delete"));
            if (teleport) options.addAll(List.of("tp", "tph"));
            if (admin) {
                options.addAll(List.of("admin", "grant", "set", "card", "despawn", "delete", "clear", "servers", "reload", "test"));
                if (sender instanceof Player) options.add("items");
            }
        } else if (args.length == 2) {
            switch (first) {
                case "admin" -> { if (admin) options.add("help"); }
                case "spawn" -> {
                    if (self != null) options.addAll(TIER_NAMES);
                    own.forEach(entry -> options.add(String.valueOf(entry.key.slot())));
                }
                case "b" -> {
                    own.forEach(entry -> options.add(String.valueOf(entry.key.slot())));
                    if (admin && sender instanceof Player) for (Player target : Bukkit.getOnlinePlayers()) if (!fake(target)) options.add(target.getName());
                }
                case "dir", "rename" -> own.forEach(entry -> options.add(String.valueOf(entry.key.slot())));
                case "tp", "tph" -> {
                    if (teleport) for (Roster.Entry entry : own) {
                        if (!running(entry)) continue;
                        options.add(String.valueOf(entry.key.slot()));
                        options.add(entry.name());
                    }
                }
                case "despawn", "delete" -> {
                    boolean despawn = first.equals("despawn");
                    for (Roster.Entry entry : own) {
                        if (despawn && !running(entry)) continue;
                        options.add(String.valueOf(entry.key.slot()));
                        options.add(entry.name());
                    }
                    if (admin) for (Roster.Entry entry : roster.entries.values()) {
                        if (options.size() >= MAX_COMPLETIONS) break;
                        if (despawn && !running(entry)) continue;
                        if (entry.name().toLowerCase(Locale.ROOT).startsWith(prefix)) options.add(entry.name());
                    }
                }
                // 只补全在线玩家：遍历全部离线玩家会在玩家多的服务器上卡顿；离线玩家仍可手动输入名字
                case "grant", "set", "card" -> {
                    if (admin) for (Player target : Bukkit.getOnlinePlayers()) if (!fake(target)) options.add(target.getName());
                }
                case "clear" -> {
                    if (admin) {
                        options.add(serverId);
                        if (network.enabled()) {
                            options.add("all");
                            roster.servers().forEach(info -> options.add(info.id()));
                        }
                    }
                }
                case "test" -> {
                    if (admin) options.addAll(List.of("stop", "10", "25", "50", String.valueOf(TabBenchmark.MAX_ENTRIES)));
                }
                default -> { }
            }
        } else if (args.length == 3 && self != null && first.equals("spawn")) options.addAll(TIER_NAMES);
        else if (args.length == 3 && admin && sender instanceof Player && first.equals("b")) {
            // 只查在线玩家和本服缓存，不遍历离线玩家
            Player online = Bukkit.getPlayerExact(args[1]);
            var target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target != null) roster.owned(target.getUniqueId()).forEach(entry -> options.add(String.valueOf(entry.key.slot())));
        }
        return options.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
            .sorted().limit(MAX_COMPLETIONS).toList();
    }

    /** 在本服或其他子服运行中。 */
    private boolean running(Roster.Entry entry) { return summoned(entry) || roster.remoteHolder(entry) != null; }

    // ══ 重载 ══════════════════════════════════════════════

    /**
     * reload：重新读取 config.yml、菜单和语言文件。全部校验通过后才开始重载：先关闭玩家打开的插件菜单和假人背包，
     * 再把本服已召唤的假人暂时移出世界（保存背包、保留运行租约），应用新设置后在原位置重新召唤。
     * 文件有错误时不做任何改动。database.yml、server_id 和 server_name 需要重启服务器才会生效。
     */
    private void reloadCommand(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg(sender, "no_permission"); return; }
        synchronized (admissions) {
            if (reloading || Bukkit.isStopping() || !isEnabled()) { msg(sender, "reload_busy"); return; }
            reloading = true;
        }
        String oldId = serverId, oldName = serverName;
        Runnable apply;
        try {
            apply = loadSettings(new ConfigUpgrade(this));
        } catch (Exception | LinkageError error) {
            reloading = false;
            getLogger().log(Level.SEVERE, "Reload failed; the current settings are kept", error);
            String reason = String.valueOf(error.getMessage()).strip().lines().findFirst().orElse(error.getClass().getSimpleName());
            msg(sender, "reload_failed", "error", reason);
            return;
        }
        String newId = getConfig().getString("server_id", "server").strip();
        if (!newId.equals(oldId) || !serverName(getConfig(), newId).equals(oldName)) msg(sender, "reload_restart");
        menus.closeAll();
        rosterMenus.closeAll();
        var parking = new ArrayList<CompletableFuture<?>>();
        for (Live active : List.copyOf(live.values())) {
            if (!isSummoned(active)) continue;
            var done = new CompletableFuture<Void>();
            Runnable gone = () -> done.complete(null);
            if (!active.dummy.getBukkitEntity().getScheduler().execute(this, () -> park(active, done, 0), gone, 1)) gone.run();
            parking.add(done);
        }
        CompletableFuture.allOf(parking.toArray(CompletableFuture[]::new)).whenComplete((unused, error) -> {
            if (isEnabled()) Bukkit.getGlobalRegionScheduler().execute(this, () -> finishReload(sender, apply));
        });
    }

    /** 在假人所在线程把它暂时移出世界：保存背包，记下位置和状态，保留运行租约，其他子服仍看到它在本服运行。 */
    private void park(Live active, CompletableFuture<Void> done, int waited) {
        Roster.Entry entry = active.entry;
        if (live.get(entry.id) != active || !isSummoned(active)) { done.complete(null); return; }
        Player body = active.dummy.getBukkitEntity();
        // 正在被拉到玩家身边的假人，等传送结束再移除，最多等 5 秒
        if (active.relocating && waited < 100) {
            Runnable gone = () -> done.complete(null);
            if (!body.getScheduler().execute(this, () -> park(active, done, waited + 1), gone, 1)) gone.run();
            return;
        }
        try {
            snapshot(active);
            var state = new Parked(entry, body.getLocation(), body.getHealth(), body.getFoodLevel(), body.getSaturation(),
                body.getExhaustion(), List.copyOf(body.getActivePotionEffects()));
            synchronized (admissions) { pending.add(entry.id); }
            parked.put(entry.id, state);
            // 先移出 live，实体移除时就不会释放租约
            live.remove(entry.id, active);
            detach(active);
            roster.save();
        } catch (RuntimeException | LinkageError error) {
            getLogger().log(Level.SEVERE, "Could not take " + entry.profile() + " out of the world for reload", error);
        } finally {
            done.complete(null);
        }
    }

    /** 全局线程：应用新设置，再把暂时移出的假人召唤回原位置。全部完成后才结束重载。 */
    private void finishReload(CommandSender sender, Runnable apply) {
        int previousSaveInterval = saveInterval;
        try {
            apply.run();
            if (saveInterval != previousSaveInterval) {
                saveTask.cancel();
                saveTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> roster.save(), saveInterval, saveInterval);
            }
            checkServices();
        } catch (RuntimeException | LinkageError error) {
            getLogger().log(Level.SEVERE, "Could not apply the reloaded settings", error);
        }
        List<Parked> waiting = List.copyOf(parked.values());
        if (waiting.isEmpty()) { endReload(sender, 0, 0); return; }
        var restored = new AtomicInteger();
        var remaining = new AtomicInteger(waiting.size());
        for (Parked state : waiting) restore(state, spawned -> {
            if (spawned) restored.incrementAndGet();
            if (remaining.decrementAndGet() == 0) endReload(sender, restored.get(), waiting.size());
        });
    }

    private void endReload(CommandSender sender, int restored, int total) {
        reloading = false;
        getLogger().info("Reloaded configuration; re-summoned " + restored + "/" + total + " fake player(s).");
        if (isEnabled()) tell(sender, () -> msg(sender, "reload_done", "restored", restored, "total", total));
    }

    /**
     * 按新设置在原位置重新召唤。先更新 LuckPerms 权限组，更新失败时沿用原来的组继续召唤。
     * 期间假人被删除、被其他子服接管或被清除时不再召唤；召唤失败时释放租约，背包已经保存。
     */
    private void restore(Parked state, Consumer<Boolean> done) {
        Roster.Entry entry = state.entry;
        groups.prepare(entry, luckPermsGroup).orTimeout(10, TimeUnit.SECONDS).whenComplete((unused, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not update the LuckPerms group of " + entry.profile() + " after reload", error);
            }
            Runnable respawn = () -> {
                boolean ours = parked.remove(entry.id, state), spawned = false;
                try {
                    // 重载后本服禁止召唤（max_active_server 改为 0）时不再召唤回来，背包已经保存
                    if (ours && !Bukkit.isStopping() && maxActive > 0 && roster.entries.get(entry.key) == entry && roster.holds(entry)) {
                        resume(spawn(entry, state.position), state);
                        roster.save();
                        spawned = true;
                    }
                } catch (RuntimeException | LinkageError problem) {
                    getLogger().log(Level.SEVERE, "Could not re-summon " + entry.profile() + " after reload", problem);
                } finally {
                    clearPending(entry);
                    if (ours && !spawned) releaseLease(entry);
                    done.accept(spawned);
                }
            };
            try { Bukkit.getRegionScheduler().execute(this, state.position, respawn); }
            catch (RuntimeException problem) {
                getLogger().log(Level.SEVERE, "Could not schedule re-summoning " + entry.profile() + " after reload", problem);
                if (parked.remove(entry.id, state)) releaseLease(entry);
                clearPending(entry);
                done.accept(false);
            }
        });
    }

    /** 恢复移出前的生命值、饥饿值和药水效果。先加药水效果，生命提升效果才会先提高生命上限。 */
    private static void resume(Live active, Parked state) {
        Player body = active.dummy.getBukkitEntity();
        body.addPotionEffects(state.effects);
        var maxHealth = body.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? body.getHealth() : maxHealth.getValue();
        if (state.health > 0) body.setHealth(Math.min(state.health, max));
        body.setFoodLevel(state.food);
        body.setSaturation(state.saturation);
        body.setExhaustion(state.exhaustion);
    }

    // ══ 关闭 ══════════════════════════════════════════════

    @Override public void onDisable() {
        shuttingDown = true;
        synchronized (admissions) { pending.clear(); }
        boolean stopping = Bukkit.isStopping();
        int removed = 0;
        if (folia != null) for (Live active : List.copyOf(live.values())) {
            try {
                if (stopping) snapshot(active);
                if (stopping && folia.regionThreaded()) {
                    if (!folia.inShutdownRegion(active.dummy, () -> unlink(active, false))) {
                        active.inWorld = false;
                        active.hiddenTag.clear();
                        active.dummy.link.dispose();
                        if (live.remove(active.entry.id, active)) releaseLease(active.entry);
                        if (!active.dummy.isRemoved()) {
                            getLogger().warning("Fake player was not attached to a shutdown region: " + active.entry.profile());
                        }
                        continue;
                    }
                    removed++;
                } else if (Bukkit.isOwnedByCurrentRegion(active.dummy.getBukkitEntity())) {
                    unlink(active, !stopping);
                    removed++;
                }
            }
            catch (RuntimeException | LinkageError error) { getLogger().log(Level.SEVERE, "Fake player cleanup failed", error); }
        }
        if (stopping && removed > 0) getLogger().info("Recalled " + removed + " summoned fake player(s).");
        try {
            benchmark.close();
            shareTests(null); // 通知其他子服删除本服的测试条目；没送达时，它们会在本服心跳超时后删除
            remoteTab.close();
            if (placeholders != null) OnlinePlaceholders.unregister(placeholders);
        } catch (RuntimeException | LinkageError error) {
            getLogger().log(Level.WARNING, "Cleanup failed", error);
        } finally {
            try { CraftEngineCompat.restore(); }
            finally {
                try { if (roster != null) roster.close(); }
                finally {
                    try { if (network != null) network.close(); }
                    finally { if (database != null) database.close(); }
                }
            }
        }
    }
}
