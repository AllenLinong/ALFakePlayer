package io.github.dummyplayers;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.mojang.authlib.GameProfile;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class DummyPlayers extends JavaPlugin implements Listener, TabExecutor {
    static final String USE = "alfakeplayer.use", ADMIN = "alfakeplayer.admin", TELEPORT = "alfakeplayer.teleport";
    private static final Set<String> TELEPORT_ACTIONS = Set.of("tp", "teleport", "tph", "bring");
    private static final NamespacedKey RENAME_CARD = Objects.requireNonNull(NamespacedKey.fromString("alfakeplayer:rename_card"));
    static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacyAmpersand();
    final Map<UUID, Live> live = new ConcurrentHashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Object admissions = new Object();
    Roster roster;
    Menus menus;
    RosterMenus rosterMenus;
    private final DummyTeleport teleports = new DummyTeleport(this);
    private final TabBenchmark benchmark = new TabBenchmark(this);
    private final EnumMap<DummyTier, Integer> tierViewDistances = new EnumMap<>(DummyTier.class);
    private final EnumMap<DummyTier, Double> tierPrices = new EnumMap<>(DummyTier.class);
    private Groups groups = Groups.NONE;
    private DummyNames names;
    private volatile PurchasePrice purchasePrice;
    private FoliaBridge folia;
    private YamlConfiguration messages;
    private int maxActive, baseSlots, maxSlots, simulationDistance, viewDistance, saveInterval;
    int targetInterval, idleMaxInterval, useInterval, eatLevel, minimumDurability, unloadInterval, unloadMaxInterval;
    float passiveExhaustion;
    boolean lethalStarvation, unloadChests;
    Set<Material> foodBlacklist = Set.of();
    private boolean ignoreHostileMobs;
    private boolean paidSummoning;

    static final class Live {
        final Roster.Entry entry;
        final NativeDummy dummy;
        final Actions actions;
        final DummyNames.HiddenTag hiddenTag;
        volatile boolean dead;
        volatile boolean relocating;
        volatile boolean inWorld;
        int tick;

        Live(Roster.Entry entry, NativeDummy dummy) {
            this.entry = entry;
            this.dummy = dummy;
            actions = new Actions(dummy, entry);
            hiddenTag = new DummyNames.HiddenTag(entry);
        }
    }

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
            configs.load("config.yml", yaml -> { });
            reloadConfig();
            YamlConfiguration menuConfig = configs.loadMenus(yaml -> {
                Menus.validate(yaml);
                RosterMenus.validate(yaml);
            });
            menus = new Menus(this, menuConfig);
            rosterMenus = new RosterMenus(this, menuConfig);
            loadMessages(configs);
            maxSlots = option("slots.max-per-owner", 10, 1, 100);
            baseSlots = option("slots.default", 1, 1, maxSlots);
            var purchaseItems = new ArrayList<ItemStack>();
            for (Object item : getConfig().getList("purchase.items", List.of())) {
                if (!(item instanceof ItemStack stack) || stack.getType().isAir() || stack.getAmount() <= 0 || purchaseItems.size() == 3) {
                    throw new IllegalArgumentException("purchase.items must contain at most three valid item stacks");
                }
                purchaseItems.add(stack.clone());
            }
            String mode = getConfig().getString("purchase.mode", "free").toLowerCase(Locale.ROOT);
            double coins = getConfig().getDouble("purchase.coins", 300.0);
            int points = getConfig().getInt("purchase.points", 100);
            if (!Set.of("free", "coins", "points", "items").contains(mode) || !Double.isFinite(coins) || coins <= 0 || points <= 0) {
                throw new IllegalArgumentException("Invalid purchase mode or price");
            }
            purchasePrice = new PurchasePrice(mode, coins, points, purchaseItems);
            maxActive = option("slots.max-active-server", 20, 1, 200);
            viewDistance = option("performance.view-distance", 4, 2, 10);
            simulationDistance = option("performance.simulation-distance", 2, 2, viewDistance);
            double legacyPrice = getConfig().getDouble("economy.summon-price", 300.0);
            for (DummyTier tier : DummyTier.values()) {
                int chunks = option("performance.dummy-chunks." + tier.key, tier.defaultChunkSize, 5, 10);
                tierViewDistances.put(tier, Math.clamp((chunks - 1) / 2, 2, viewDistance));
                double price = getConfig().getDouble("economy.tier-prices." + tier.key, legacyPrice);
                if (!Double.isFinite(price) || price < 0) {
                    throw new IllegalArgumentException("economy.tier-prices." + tier.key + " must be a finite non-negative number");
                }
                tierPrices.put(tier, price);
            }
            targetInterval = option("performance.idle-target-interval-ticks", 3, 1, 20);
            idleMaxInterval = option("performance.idle-target-max-ticks", 20, targetInterval, 100);
            useInterval = option("performance.use-interval-ticks", 4, 4, 100);
            saveInterval = option("performance.save-interval-ticks", 200, 40, 12000);
            eatLevel = option("survival.auto-eat-at-food-level", 2, 0, 19);
            minimumDurability = option("survival.minimum-tool-durability", 1, 1, 1000);
            foodBlacklist = readFoodBlacklist(getConfig().getStringList("survival.food-blacklist"));
            unloadChests = getConfig().getBoolean("unloading.enabled", true);
            unloadInterval = option("unloading.interval-ticks", 40, 20, 1200);
            unloadMaxInterval = option("unloading.max-retry-ticks", 160, unloadInterval, 2400);
            passiveExhaustion = (float) Math.clamp(getConfig().getDouble("survival.passive-exhaustion-per-second"), 0.0, 4.0);
            lethalStarvation = getConfig().getBoolean("survival.lethal-starvation-any-non-peaceful-difficulty");
            ignoreHostileMobs = getConfig().getBoolean("survival.ignore-hostile-mobs", true);
            paidSummoning = getConfig().getBoolean("economy.enabled", false);
            teleports.configure(option("teleport.to-dummy-delay-seconds", 5, 0, 300),
                option("teleport.to-dummy-cooldown-seconds", 5, 0, 300));
            roster = new Roster(this);
            folia = FoliaBridge.find();
            if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) groups = new Groups.Luck();
            else getLogger().info("LuckPerms not installed; fake players still appear in the vanilla tab list.");
            names = new DummyNames(this);
            Objects.requireNonNull(getCommand("alfakeplayer")).setExecutor(this);
            Objects.requireNonNull(getCommand("alfakeplayer")).setTabCompleter(this);
            Bukkit.getPluginManager().registerEvents(this, this);
            Bukkit.getOnlinePlayers().forEach(benchmark::join);
            Bukkit.getPluginManager().registerEvents(menus, this);
            Bukkit.getPluginManager().registerEvents(rosterMenus, this);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> roster.save(), saveInterval, saveInterval);
        } catch (Exception | LinkageError error) {
            getLogger().log(Level.SEVERE, "Cannot start ALFakePlayer", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private static boolean patchAtLeastTwo(String version) {
        try { return Integer.parseInt(version.substring(5)) >= 2; }
        catch (NumberFormatException ignored) { return false; }
    }

    private void loadMessages(ConfigUpgrade configs) throws Exception {
        String locale = getConfig().getString("language", "zh_CN");
        if (!Set.of("zh_CN", "en_US").contains(locale)) {
            getLogger().warning("Unsupported language '" + locale + "'; using zh_CN.");
            locale = "zh_CN";
        }
        var chinese = configs.load("lang/zh_CN.yml", yaml -> { });
        var english = configs.load("lang/en_US.yml", yaml -> { });
        String path = "lang/" + locale + ".yml";
        messages = locale.equals("zh_CN") ? chinese : english;
        try (var defaults = Objects.requireNonNull(getTextResource(path))) {
            messages.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        }
    }

    private int option(String path, int fallback, int min, int max) {
        return Math.clamp(getConfig().getInt(path, fallback), min, max);
    }

    private Set<Material> readFoodBlacklist(List<String> names) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name.strip());
            if (material == null || !material.isItem()) getLogger().warning("Invalid survival.food-blacklist entry: " + name);
            else materials.add(material);
        }
        return Set.copyOf(materials);
    }

    String menuTierName(DummyTier tier) { return messages.getString("tier-name." + tier.key, tier.key); }
    String menuPrice(DummyTier tier) {
        return BigDecimal.valueOf(paidSummoning ? tierPrices.get(tier) : 0).stripTrailingZeros().toPlainString();
    }

    boolean summoned(Roster.Entry entry) {
        return isSummoned(live.get(entry.id));
    }

    private static boolean isSummoned(Live active) {
        return active != null && active.inWorld && !active.dead;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerListPing(PaperServerListPingEvent event) {
        if (event.shouldHidePlayers()) return;
        int summonedCount = 0;
        for (Live active : live.values()) {
            if (isSummoned(active)) summonedCount++;
        }
        if (summonedCount > 0) event.setNumPlayers(event.getNumPlayers() + summonedCount);
    }

    void requestTeleportFromMenu(Player player, int slot, boolean bring) {
        if (!player.hasPermission(TELEPORT)) msg(player, "no-permission");
        else teleports.request(player, slot, bring);
    }

    void summonFromMenu(Player player, Roster.Entry expected, int slot, DummyTier tier) {
        if (!player.hasPermission(USE) || slot < 1 || slot > slots(player.getUniqueId())) return;
        if (roster.entries.get(new Roster.Key(player.getUniqueId(), slot)) != expected) { msg(player, "not-found"); return; }
        summon(player, slot, tier);
    }

    PurchasePrice purchasePrice() { return purchasePrice; }

    Component menuMessage(String key, String price) {
        return itemText(messages.getString(key, key).replace("%price%", price));
    }

    synchronized boolean savePurchaseItems(List<ItemStack> items) {
        var previous = purchasePrice;
        getConfig().set("purchase.items", items);
        try {
            getConfig().save(new java.io.File(getDataFolder(), "config.yml"));
            purchasePrice = new PurchasePrice(previous.mode(), previous.coins(), previous.points(), items);
            return true;
        } catch (java.io.IOException error) {
            getConfig().set("purchase.items", previous.items());
            getLogger().log(Level.WARNING, "Could not save purchase items", error);
            return false;
        }
    }

    int buyDummy(Player player, PurchasePrice quoted) {
        if (!player.hasPermission(USE)) return -1;
        UUID owner = player.getUniqueId();
        int number = 1;
        while (number <= maxSlots && roster.entries.containsKey(new Roster.Key(owner, number))) number++;
        if (number > maxSlots) { msg(player, "purchase-limit"); return -1; }
        if (quoted != purchasePrice) { msg(player, "purchase-changed"); return -1; }
        try {
            if (!quoted.take(player)) { msg(player, "purchase-insufficient"); return -1; }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            getLogger().log(Level.WARNING, "Could not charge fake player purchase", error);
            msg(player, "purchase-unavailable");
            return -1;
        }
        synchronized (roster) {
            if (number > slots(owner)) roster.setAllowance(owner, number);
            roster.create(new Roster.Key(owner, number));
            roster.save();
        }
        msg(player, "purchase-success");
        return number;
    }

    void saveDirection(Roster.Entry entry, float yaw) {
        entry.workYaw = yaw;
        entry.directionSet = true;
        roster.dirty(entry);
        roster.save();
    }

    void msg(CommandSender sender, String key, Object... pairs) {
        String value = messages.getString(key, key);
        Component rendered = TEXT.deserialize(messages.getString("prefix", "") + value);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            String token = "%" + pairs[index] + "%";
            Component text = Component.text(String.valueOf(pairs[index + 1]));
            rendered = rendered.replaceText(builder -> builder.matchLiteral(token).replacement(text));
        }
        sender.sendMessage(rendered);
    }

    private void sendHelp(CommandSender sender) {
        boolean player = sender instanceof Player;
        boolean manage = player && sender.hasPermission(USE);
        boolean admin = sender.hasPermission(ADMIN);
        if (!manage && !admin) { msg(sender, "no-permission"); return; }
        String command = player ? "/afp" : "afp";
        helpLines(sender, "header", command);
        if (manage) {
            helpLines(sender, "manage", command);
            helpLines(sender, "tiers", command);
            if (sender.hasPermission(TELEPORT)) helpLines(sender, "teleport", command);
        }
        if (admin) helpLines(sender, "admin", command);
    }

    private void helpLines(CommandSender sender, String group, String command) {
        for (String line : messages.getStringList("command-help." + group)) {
            sender.sendMessage(TEXT.deserialize(line.replace("%command%", command)));
        }
    }

    int slots(UUID owner) { return Math.min(maxSlots, Math.max(baseSlots, roster.allowances.getOrDefault(owner, baseSlots))); }
    private boolean fake(CommandSender sender) { return sender instanceof Player player && live.containsKey(player.getUniqueId()); }
    boolean allowed(Player player, Roster.Entry entry) {
        return player.hasPermission(USE) && entry.key.owner().equals(player.getUniqueId())
            && entry.key.slot() <= slots(player.getUniqueId()) && roster.entries.get(entry.key) == entry;
    }

    private void summon(Player owner, int slot, DummyTier requestedTier) {
        UUID ownerId = owner.getUniqueId();
        Roster.Entry entry;
        DummyTier selectedTier;
        synchronized (admissions) {
            if (Bukkit.isStopping() || !isEnabled()) return;
            entry = roster.entries.get(new Roster.Key(ownerId, slot));
            if (entry == null) { msg(owner, "purchase-required"); return; }
            if (live.containsKey(entry.id) || pending.contains(entry.id)) { msg(owner, "already-live"); return; }
            if (live.size() + pending.size() >= maxActive) {
                msg(owner, "server-limit"); return;
            }
            selectedTier = requestedTier == null ? entry.tier : requestedTier;
            pending.add(entry.id);
        }
        DummyTier tier = selectedTier;
        Location position = owner.getLocation();
        groups.prepare(entry, getConfig().getString("luckperms.group", "default"))
            .orTimeout(10, TimeUnit.SECONDS).whenComplete((unused, error) -> {
                if (Bukkit.isStopping() || !isEnabled()) { clearPending(entry); return; }
                boolean queued = owner.getScheduler().execute(this, () -> {
                    if (error != null || !allowed(owner, entry)) {
                        clearPending(entry);
                        if (error == null) return;
                        getLogger().log(Level.WARNING, "Could not prepare LuckPerms for " + entry.profile(), error);
                        msg(owner, "spawn-failed");
                        return;
                    }
                    if (Bukkit.isOwnedByCurrentRegion(position)) finishSummon(owner, entry, position, tier);
                    else Bukkit.getRegionScheduler().execute(this, position, () -> finishSummon(owner, entry, position, tier));
                }, () -> clearPending(entry), 1);
                if (!queued) clearPending(entry);
            });
    }

    private void clearPending(Roster.Entry entry) {
        synchronized (admissions) { pending.remove(entry.id); }
    }

    private void finishSummon(Player owner, Roster.Entry entry, Location position, DummyTier tier) {
        try {
            synchronized (admissions) {
                if (Bukkit.isStopping() || !isEnabled() || !pending.contains(entry.id)
                    || roster.entries.get(entry.key) != entry) return;
            }
            if (owner.isOnline()) spawnWithFee(owner, entry, position, tier);
        } finally { clearPending(entry); }
    }

    private void spawnWithFee(Player player, Roster.Entry entry, Location position, DummyTier tier) {
        SpawnFee fee = null;
        double price = tierPrices.get(tier);
        String cost = BigDecimal.valueOf(price).stripTrailingZeros().toPlainString();
        if (paidSummoning && price > 0) {
            try {
                fee = SpawnFee.find(price);
                if (fee == null) { msg(player, "economy-unavailable"); return; }
                if (!fee.debit(player)) { msg(player, "economy-declined", "price", cost); return; }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
                getLogger().log(Level.WARNING, "Vault rejected charge for " + player.getUniqueId(), problem);
                msg(player, "economy-unavailable");
                return;
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
            msg(player, "spawn-failed");
            if (fee != null) refund(player, fee, cost);
            return;
        }
        roster.save();
        msg(player, "spawned", "name", entry.name(), "slot", entry.key.slot(),
            "tier", messages.getString("tier-name." + tier.key, tier.key),
            "chunks", tierViewDistances.get(tier) * 2 + 1);
        if (fee != null) msg(player, "economy-paid", "price", cost);
    }

    private void refund(Player player, SpawnFee fee, String cost) {
        try {
            if (fee.refund(player)) { msg(player, "economy-refunded", "price", cost); return; }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
            getLogger().log(Level.SEVERE, "Vault refund threw for " + player.getUniqueId(), problem);
        }
        getLogger().severe("Manual refund required: " + player.getUniqueId() + ", amount " + cost);
        msg(player, "economy-refund-failed", "price", cost);
    }

    void spawn(Roster.Entry entry, Location position) {
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
        NativeDummy dummy = new NativeDummy(server, level, profile, ignoreHostileMobs, entry);
        dummy.setShiftKeyDown(entry.sneaking);
        Live active = new Live(entry, dummy);
        live.put(entry.id, active);
        try {
            float yaw = entry.directionSet ? entry.workYaw : position.getYaw();
            dummy.snapTo(position.getX(), position.getY(), position.getZ(), yaw, 0);
            dummy.setYHeadRot(yaw);
            Player player = dummy.getBukkitEntity();
            player.setPersistent(false);
            int dummyViewDistance = tierViewDistances.getOrDefault(entry.tier, 2);
            player.setViewDistance(dummyViewDistance);
            player.setSimulationDistance(Math.min(simulationDistance, dummyViewDistance));
            player.setSendViewDistance(dummyViewDistance);
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
                live.remove(entry.id, active);
            }, 1, 1);
        } catch (RuntimeException | LinkageError error) {
            unlink(active, false);
            throw error;
        }
    }

    private void applyName(Live active) {
        Player player = active.dummy.getBukkitEntity();
        String prefix = groups.prefix(active.entry);
        Component label = TEXT.deserialize(prefix.replace('§', '&')).append(Component.text(active.entry.name()));
        player.displayName(label);
        names.tab(active.dummy, label, active.hiddenTag);
    }

    private void tick(Live active) {
            int tick = ++active.tick;
            if (active.dead || active.relocating) return;
            if (active.dummy.isRemoved() || !active.dummy.link.isConnected()) { unlink(active, true); return; }
            try {
                if (active.dummy.isAlive()) active.actions.tick(tick, this);
                if (tick % 100 == 0) applyName(active);
                if ((tick + active.actions.offset) % saveInterval == 0
                    || (tick + active.actions.offset) % 10 == 0 && menus.watching(active.entry)) snapshot(active);
            } catch (RuntimeException | LinkageError error) {
                getLogger().log(Level.SEVERE, "Disabling broken session " + active.entry.profile(), error);
                unlink(active, true);
            }
    }

    boolean editable(Roster.Entry entry) {
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

    void prepareRelocation(Live active) {
        active.actions.stopMining();
        active.entry.wakeWork();
        active.relocating = true;
    }

    void unlink(Live active, boolean keep) {
        if (keep) snapshot(active);
        active.hiddenTag.clear();
        active.inWorld = false;
        try {
            if (!active.dummy.isRemoved() && active.dummy.valid) {
                active.actions.stopMining();
                active.dummy.level().removePlayerImmediately(active.dummy, Entity.RemovalReason.DISCARDED);
            }
        } finally {
            try { folia.detach(active.dummy); }
            finally {
                active.dummy.link.dispose();
                live.remove(active.entry.id, active);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof CraftPlayer player && player.getHandle() instanceof NativeDummy dummy) {
            dummy.beforePickup(((CraftItem) event.getItem()).getHandle());
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalBatSpawn(PreCreatureSpawnEvent event) {
        if (event.getType() != EntityType.BAT || event.getReason() != CreatureSpawnEvent.SpawnReason.NATURAL || live.isEmpty()) return;
        Location position = event.getSpawnLocation();
        if (!(position.getWorld() instanceof CraftWorld world)) return;
        var level = world.getHandle();
        double x = position.getBlockX() + 0.5, y = position.getBlockY(), z = position.getBlockZ() + 0.5;
        double radius = MobCategory.AMBIENT.getDespawnDistance();
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

    private void rename(Player player, int slot, String name) {
        Roster.Key key = new Roster.Key(player.getUniqueId(), slot);
        Roster.Entry entry = roster.entries.get(key);
        if (entry == null) { msg(player, "rename-not-found"); return; }
        if (!name.matches("[A-Za-z0-9_]{1,16}") || Bukkit.getPlayerExact(name) != null || Bukkit.getOfflinePlayerIfCached(name) != null
            || name.matches("[0-9]+") || name.equalsIgnoreCase("all") || name.regionMatches(true, 0, "AFP_", 0, 4)
            || name.regionMatches(true, 0, "AFP~", 0, 4)) {
            msg(player, "bad-name"); return;
        }
        synchronized (roster) {
            if (roster.entries.get(key) != entry) { msg(player, "rename-not-found"); return; }
            if (entry.name().equals(name) || roster.entries.values().stream().anyMatch(other -> other != entry && other.matchesName(name))) {
                msg(player, "bad-name"); return;
            }
            if (!consumeRenameCard(player)) { msg(player, "rename-card-required"); return; }
            entry.alias = name;
            roster.dirty(entry);
            roster.save();
        }
        Live active = live.get(entry.id);
        if (active != null) active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
            if (!active.dead && live.get(entry.id) == active) applyName(active);
        }, null, 1);
        msg(player, "renamed", "name", name);
        if (active != null) msg(player, "rename-resummon");
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
        if (!sender.hasPermission(ADMIN)) { msg(sender, "no-permission"); return; }
        int amount = args.length == 2 ? 1 : args.length == 3 ? integer(args[2]) : -1;
        if (amount < 1 || amount > 64) { msg(sender, "givecard-help"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || fake(target)) { msg(sender, "givecard-offline"); return; }
        Runnable offline = () -> msg(sender, "givecard-offline");
        if (!target.getScheduler().execute(this, () -> {
            if (!target.isOnline()) { offline.run(); return; }
            ItemStack card = ItemStack.of(Material.PAPER, amount);
            card.editMeta(meta -> {
                meta.displayName(itemText(messages.getString("rename-card.name", "&6黑奴改名卡")));
                meta.lore(messages.getStringList("rename-card.lore").stream().map(DummyPlayers::itemText).toList());
                meta.getPersistentDataContainer().set(RENAME_CARD, PersistentDataType.BYTE, (byte) 1);
            });
            int leftover = target.getInventory().addItem(card).values().stream().mapToInt(ItemStack::getAmount).sum();
            if (leftover < amount) msg(sender, "givecard-done", "player", target.getName(), "amount", amount - leftover);
            if (leftover > 0) msg(sender, "givecard-full", "amount", leftover);
        }, offline, 1)) offline.run();
    }

    private static Component itemText(String value) {
        return TEXT.deserialize(value).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) {
        benchmark.join(event.getPlayer());
        live.values().forEach(active -> showDummy(active, event.getPlayer()));
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
        UUID viewer = event.getPlayer().getUniqueId();
        live.values().forEach(active -> active.hiddenTag.forget(viewer));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (fake(sender)) return true;
        String action = args.length == 0 ? sender instanceof Player ? "menu" : "help" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(ADMIN);
        if (action.equals("benchmark")) {
            if (!admin) msg(sender, "no-permission");
            else benchmark.command(sender, args);
            return true;
        }
        if (action.equals("givecard")) { giveRenameCards(sender, args); return true; }
        if (action.equals("purchaseitems")) {
            if (admin && sender instanceof Player player) rosterMenus.openPurchaseItems(player);
            else msg(sender, "no-permission");
            return true;
        }
        if (action.equals("grant") || action.equals("setslots")) {
            if (!admin) { msg(sender, "no-permission"); return true; }
            UUID owner = args.length >= 2 ? owner(args[1]) : null;
            int amount = args.length == 3 ? integer(args[2]) : -1;
            if (owner == null || amount < (action.equals("grant") ? 1 : 0) || amount > maxSlots) { msg(sender, "grant-help"); return true; }
            int total;
            synchronized (roster) {
                int owned = (int) roster.entries.keySet().stream().filter(key -> key.owner().equals(owner)).count();
                int target = action.equals("grant") ? Math.min(maxSlots, owned + amount) : Math.max(owned, amount);
                for (int number = 1; number <= maxSlots && owned < target; number++) {
                    var key = new Roster.Key(owner, number);
                    if (roster.entries.containsKey(key)) continue;
                    if (number > slots(owner)) roster.setAllowance(owner, number);
                    roster.create(key);
                    owned++;
                }
                total = owned;
                roster.save();
            }
            msg(sender, "granted", "name", args[1], "slots", total);
            return true;
        }
        if (action.equals("deleteall") || action.equals("删除全部")) {
            if (!admin) msg(sender, "no-permission");
            else if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) msg(sender, "deleteall-help");
            else {
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
                msg(sender, "despawn-all-queued", "count", queued);
            }
            return true;
        }
        if (action.equals("delete") || action.equals("kill") || action.equals("删除")) {
            if (!admin && (!(sender instanceof Player player) || !player.hasPermission(USE))) { msg(sender, "no-permission"); return true; }
            if (args.length < 2) { msg(sender, "delete-help"); return true; }
            String target = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
            int slot = integer(target);
            Roster.Entry entry = slot > 0 ? owner == null ? null : roster.entries.get(new Roster.Key(owner, slot))
                : roster.entries.values().stream().filter(bot -> (admin || bot.key.owner().equals(owner))
                    && bot.matchesName(target)).findFirst().orElse(null);
            if (entry == null) { msg(sender, "not-found"); return true; }
            if (action.equals("kill")) {
                Live active = live.get(entry.id);
                if (!isSummoned(active) || !active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
                    if (live.get(entry.id) != active || !isSummoned(active)) return;
                    unlink(active, true);
                    roster.save();
                }, null, 1)) msg(sender, "despawn-offline");
                else msg(sender, "despawn-all-queued", "count", 1);
                return true;
            }
            delete(entry);
            roster.save();
            msg(sender, "deleted", "name", entry.name());
            return true;
        }
        if (action.equals("help")) {
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) { msg(sender, "player-only"); return true; }
        if (!player.hasPermission(USE)) { msg(sender, "no-permission"); return true; }
        if (action.equals("menu")) { rosterMenus.openMain(player, 0); return true; }
        if (action.equals("despawn")) {
            int slot = args.length == 1 ? 1 : args.length == 2 ? integer(args[1]) : -1;
            if (slot < 1) { msg(player, "despawn-help"); return true; }
            Roster.Entry entry = roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
            Live active = entry == null ? null : live.get(entry.id);
            if (active == null || !summoned(entry) || !allowed(player, entry)) {
                msg(player, "despawn-offline");
                return true;
            }
            Runnable offline = () -> player.getScheduler().execute(this, () -> msg(player, "despawn-offline"), null, 1);
            if (!active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
                if (live.get(entry.id) != active || !summoned(entry)) { offline.run(); return; }
                unlink(active, true);
                roster.save();
                player.getScheduler().execute(this, () -> msg(player, "despawn-done", "name", entry.name()), null, 1);
            }, offline, 1)) offline.run();
            return true;
        }
        if (action.equals("rename")) {
            int slot = args.length >= 3 ? integer(args[1]) : -1;
            if (slot < 1) msg(player, "rename-help");
            else rename(player, slot, String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip());
            return true;
        }
        if (TELEPORT_ACTIONS.contains(action)) {
            String target = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            int slot = integer(target);
            if (slot < 1 && !target.isBlank()) slot = roster.entries.values().stream()
                .filter(entry -> allowed(player, entry) && entry.matchesName(target))
                .mapToInt(entry -> entry.key.slot()).findFirst().orElse(-1);
            boolean bring = action.equals("tph") || action.equals("bring");
            if (!player.hasPermission(TELEPORT)) msg(player, "no-permission");
            else if (slot < 1) msg(player, bring ? "bring-help" : "teleport-help");
            else teleports.request(player, slot, bring);
            return true;
        }
        if (action.equals("list")) {
            msg(player, "slots", "slots", slots(player.getUniqueId()));
            for (Roster.Entry bot : roster.entries.values()) if (bot.key.owner().equals(player.getUniqueId())) {
                msg(player, summoned(bot) ? "list-live" : "list-offline", "name", bot.name(), "slot", bot.key.slot());
            }
            return true;
        }
        boolean direction = action.equals("direction") || action.equals("dir");
        DummyTier commandTier = action.equals("spawn") || action.equals("召唤") ? null : DummyTier.fromCommand(action);
        boolean spawn = commandTier != null || action.equals("spawn") || action.equals("召唤");
        boolean open = action.equals("open") || action.equals("背包");
        DummyTier requestedTier = commandTier;
        int slot;
        if (spawn) {
            if (args.length > 3) { msg(player, "spawn-help"); return true; }
            slot = 1;
            if (action.equals("spawn") && args.length == 2) {
                DummyTier tierArgument = DummyTier.fromName(args[1]);
                if (tierArgument != null) requestedTier = tierArgument;
                else slot = integer(args[1]);
            } else if (args.length >= 2) {
                slot = integer(args[1]);
            }
            if (action.equals("spawn") && args.length == 3) {
                requestedTier = DummyTier.fromName(args[2]);
                if (requestedTier == null) { msg(player, "spawn-help"); return true; }
            } else if (!action.equals("spawn") && args.length == 3) {
                msg(player, "spawn-help"); return true;
            }
        } else {
            slot = open || direction ? args.length >= 2 ? integer(args[1]) : 1 : integer(action);
        }
        if (!spawn && !open && !direction && slot < 0) { sendHelp(player); return true; }
        if (spawn && slot < 1) { msg(player, "spawn-help"); return true; }
        if (slot < 1 || slot > slots(player.getUniqueId())) { msg(player, "locked", "slots", slots(player.getUniqueId())); return true; }
        if (direction) {
            Roster.Entry entry = roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
            Live active = entry == null ? null : live.get(entry.id);
            if (active == null || active.dead) msg(player, "direction-offline");
            else {
                int directionSlot = slot;
                Location look = player.getLocation();
                active.dummy.getBukkitEntity().getScheduler().execute(this, () -> {
                    if (active.dead || live.get(entry.id) != active) return;
                    active.actions.direction(look.getYaw());
                    saveDirection(entry, look.getYaw());
                    player.getScheduler().execute(this, () -> msg(player, "direction-set", "slot", directionSlot), null, 1);
                }, null, 1);
            }
        } else if (spawn) summon(player, slot, requestedTier);
        else {
            Roster.Entry entry = roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
            if (entry == null) msg(player, "not-found");
            else menus.open(player, entry, "inventory");
        }
        return true;
    }

    private UUID owner(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player != null && !fake(player)) return player.getUniqueId();
        var offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline == null) offline = Arrays.stream(Bukkit.getOfflinePlayers())
            .filter(known -> name.equalsIgnoreCase(known.getName())).findFirst().orElse(null);
        var id = offline == null ? null : offline.getUniqueId();
        return offline != null && (offline.hasPlayedBefore() || roster.allowances.containsKey(offline.getUniqueId()))
            && roster.entries.values().stream().noneMatch(entry -> entry.id.equals(id)) ? id : null;
    }

    private void delete(Roster.Entry entry) {
        synchronized (admissions) {
            pending.remove(entry.id);
            roster.remove(entry);
        }
        menus.close(entry);
        Live active = live.get(entry.id);
        if (active != null) active.dummy.getBukkitEntity().getScheduler().execute(this,
            () -> { if (live.get(entry.id) == active) unlink(active, false); }, null, 1);
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { return -1; }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (fake(sender) || args.length == 0) return List.of();
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            if (sender instanceof Player player && player.hasPermission(USE)) {
                options.addAll(List.of("menu", "spawn", "spawnnormal", "spawnpro", "spawnpromax", "normal", "pro", "promax",
                    "open", "rename", "list", "direction", "dir", "despawn", "delete", "kill", "help"));
                if (player.hasPermission(TELEPORT)) options.addAll(TELEPORT_ACTIONS);
                roster.entries.values().stream().filter(entry -> allowed(player, entry)).forEach(entry -> options.add(String.valueOf(entry.key.slot())));
            }
            if (admin) options.addAll(List.of("grant", "setslots", "givecard", "delete", "kill", "deleteall", "purchaseitems", "benchmark", "help"));
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("deleteall")) options.add("confirm");
        else if (args.length == 2 && admin && args[0].equalsIgnoreCase("benchmark")) options.addAll(List.of("spawn", "stop"));
        else if (args.length == 3 && admin && args[0].equalsIgnoreCase("benchmark") && args[1].equalsIgnoreCase("spawn")) {
            options.addAll(List.of("10", "25", "50", String.valueOf(TabBenchmark.MAX_ENTRIES)));
        }
        else if (args.length == 2 && admin && args[0].equalsIgnoreCase("givecard")) {
            for (Player target : Bukkit.getOnlinePlayers()) if (!fake(target)) options.add(target.getName());
        }
        else if (args.length == 2 && admin && Set.of("grant", "setslots").contains(args[0].toLowerCase(Locale.ROOT))) {
            Set<UUID> dummyIds = new HashSet<>(live.keySet());
            roster.entries.values().forEach(entry -> dummyIds.add(entry.id));
            for (OfflinePlayer target : Bukkit.getOfflinePlayers()) {
                String name = target.getName();
                if (name != null && !dummyIds.contains(target.getUniqueId())) options.add(name);
            }
            for (Player target : Bukkit.getOnlinePlayers()) if (!fake(target)) options.add(target.getName());
        }
        else if (args.length == 2 && sender instanceof Player player && player.hasPermission(USE)
            && player.hasPermission(TELEPORT) && TELEPORT_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            for (int slot = 1; slot <= slots(player.getUniqueId()); slot++) {
                Roster.Entry entry = roster.entries.get(new Roster.Key(player.getUniqueId(), slot));
                Live active = entry == null ? null : live.get(entry.id);
                if (isSummoned(active)) { options.add(String.valueOf(slot)); options.add(entry.name()); }
            }
        }
        else if (args.length == 2 && sender instanceof Player player && player.hasPermission(USE) && args[0].equalsIgnoreCase("rename")) {
            for (int slot = 1; slot <= slots(player.getUniqueId()); slot++) {
                if (roster.entries.containsKey(new Roster.Key(player.getUniqueId(), slot))) options.add(String.valueOf(slot));
            }
        }
        else if (args.length == 2 && sender instanceof Player player && player.hasPermission(USE)
            && Set.of("spawn", "open", "direction", "dir", "despawn").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (args[0].equalsIgnoreCase("spawn")) options.addAll(List.of("normal", "pro", "promax"));
            roster.entries.values().stream().filter(entry -> allowed(player, entry)).forEach(entry -> options.add(String.valueOf(entry.key.slot())));
        }
        else if (args.length == 2 && sender instanceof Player player && player.hasPermission(USE)
            && Set.of("spawnpro", "spawnpromax", "spawnnormal", "normal", "pro", "promax")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            roster.entries.values().stream().filter(entry -> allowed(player, entry)).forEach(entry -> options.add(String.valueOf(entry.key.slot())));
        }
        else if (args.length == 3 && sender instanceof Player player && player.hasPermission(USE)
            && args[0].equalsIgnoreCase("spawn")) {
            options.addAll(List.of("normal", "pro", "promax"));
        }
        else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("kill"))) {
            for (Roster.Entry entry : roster.entries.values()) {
                if (args[0].equalsIgnoreCase("kill") && !summoned(entry)) continue;
                boolean own = sender instanceof Player player && entry.key.owner().equals(player.getUniqueId()) && player.hasPermission(USE);
                if (admin || own) options.add(entry.name());
                if (own) options.add(String.valueOf(entry.key.slot()));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    @Override public void onDisable() {
        synchronized (admissions) { pending.clear(); }
        boolean stopping = Bukkit.isStopping();
        int removed = 0;
        for (Live active : List.copyOf(live.values())) {
            try {
                if (stopping) snapshot(active);
                if (stopping && folia.regionThreaded()) {
                    if (!folia.inShutdownRegion(active.dummy, () -> unlink(active, false))) {
                        active.inWorld = false;
                        active.hiddenTag.clear();
                        active.dummy.link.dispose();
                        live.remove(active.entry.id, active);
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
        try { benchmark.close(); }
        finally {
            try { CraftEngineCompat.restore(); }
            finally { if (roster != null) roster.close(); }
        }
    }
}
