package io.github.dummyplayers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class ConfigUpgrade {
    private static final String VERSION = "config_version";
    private static final List<String> MENUS = List.of("states", "main", "tier_select", "spawn_confirm",
        "teleport_confirm", "purchase_confirm", "purchase_items", "inventory", "settings");
    private final JavaPlugin plugin;

    ConfigUpgrade(JavaPlugin plugin) { this.plugin = plugin; }

    static String menuFile(String section) { return "menus/" + section + ".yml"; }

    /** 1.1 之前的菜单文件名使用连字符，例如 menus/tier-select.yml。 */
    private static String oldMenuFile(String section) { return "menus/" + section.replace('_', '-') + ".yml"; }

    YamlConfiguration loadMenus(Consumer<YamlConfiguration> validate) throws Exception {
        Path folder = plugin.getDataFolder().toPath();
        Path legacyFile = folder.resolve("menus.yml");
        boolean importing = Files.exists(legacyFile) && MENUS.stream().anyMatch(section ->
            Files.notExists(folder.resolve(menuFile(section))) && Files.notExists(folder.resolve(oldMenuFile(section))));
        YamlConfiguration legacy = importing ? read(legacyFile) : null;
        int legacyVersion = importing ? version(legacy, "menus.yml") : 0;
        var legacyHistory = importing ? history("menus.yml", legacyVersion) : List.<YamlConfiguration>of();
        var combined = new YamlConfiguration();
        var writes = new ArrayList<RunnableWithIo>();
        boolean hasSplitMenus = MENUS.stream().anyMatch(section ->
            Files.exists(folder.resolve(menuFile(section))) || Files.exists(folder.resolve(oldMenuFile(section))));
        for (String section : MENUS) {
            String name = menuFile(section);
            Path path = folder.resolve(name);
            // 旧文件名（连字符）在升级时改为下划线文件名，原文件备份后删除
            Path oldPath = folder.resolve(oldMenuFile(section));
            Path source = Files.exists(path) ? path : Files.exists(oldPath) ? oldPath : null;
            YamlConfiguration defaults = resource(name);
            int targetVersion = version(defaults, name);
            if (targetVersion < 1 || !defaults.isConfigurationSection(section)) {
                throw new IOException("Invalid bundled menu: " + name);
            }
            YamlConfiguration selected = defaults;
            if (source != null) {
                YamlConfiguration raw = readRaw(source);
                YamlConfiguration saved = underscored(raw);
                int savedVersion = version(saved, name);
                if (savedVersion > targetVersion) throw newer(name, savedVersion);
                boolean renamed = !source.equals(path) || !raw.getKeys(true).equals(saved.getKeys(true));
                String backupName = "menus/" + source.getFileName();
                YamlConfiguration result = savedVersion == targetVersion ? saved : defaults;
                if (savedVersion == targetVersion) selected = saved;
                else merge(defaults, saved, history(name, savedVersion), true);
                if (savedVersion != targetVersion || renamed) {
                    writes.add(() -> {
                        backup(backupName, source, savedVersion);
                        write(path, result);
                        if (!source.equals(path)) Files.deleteIfExists(source);
                    });
                }
            } else {
                if (importing) {
                    if (legacy.isConfigurationSection(section) && legacyVersion > targetVersion) throw newer("menus.yml", legacyVersion);
                    YamlConfiguration oldSection = new YamlConfiguration();
                    if (legacy.isConfigurationSection(section)) copySection(oldSection, legacy, section);
                    if (!hasSplitMenus || legacy.isConfigurationSection(section)) merge(defaults, oldSection, legacyHistory, true);
                    writes.add(() -> write(path, defaults));
                } else writes.add(() -> plugin.saveResource(name, false));
            }
            if (!selected.isConfigurationSection(section)) throw new IOException(name + ": missing section " + section);
            copySection(combined, selected, section);
        }
        validate.accept(combined);
        if (importing) backup("menus.yml", legacyFile, legacyVersion);
        for (RunnableWithIo save : writes) save.run();
        if (importing) {
            Files.delete(legacyFile);
            plugin.getLogger().info("Migrated menus.yml to menus/; original saved in config-backups/");
        }
        return combined;
    }

    private interface RunnableWithIo { void run() throws IOException; }

    private static void copySection(YamlConfiguration target, YamlConfiguration source, String section) {
        ConfigurationSection from = Objects.requireNonNull(source.getConfigurationSection(section));
        target.createSection(section);
        target.setComments(section, source.getComments(section));
        target.setInlineComments(section, source.getInlineComments(section));
        for (String key : from.getKeys(true)) {
            String path = section + "." + key;
            if (from.isConfigurationSection(key)) target.createSection(path);
            else target.set(path, from.get(key));
            target.setComments(path, from.getComments(key));
            target.setInlineComments(path, from.getInlineComments(key));
        }
    }

    private static IOException newer(String name, int sourceVersion) {
        return new IOException(name + " is from a newer plugin (version " + sourceVersion + "); file left unchanged");
    }

    YamlConfiguration load(String name, Consumer<YamlConfiguration> validate) throws Exception {
        YamlConfiguration current = resource(name);
        int targetVersion = version(current, name);
        if (targetVersion < 1) throw new IOException("Missing bundled version: " + name);
        Path file = plugin.getDataFolder().toPath().resolve(name);
        if (Files.notExists(file)) {
            boolean imported = name.equals("lang/zh_CN.yml") && importLegacyMessages(current);
            validate.accept(current);
            if (imported) write(file, current);
            else plugin.saveResource(name, false);
            return current;
        }

        YamlConfiguration raw = readRaw(file);
        YamlConfiguration saved = underscored(raw);
        int sourceVersion = version(saved, name);
        if (sourceVersion > targetVersion) {
            throw newer(name, sourceVersion);
        }
        if (sourceVersion == targetVersion) {
            validate.accept(saved);
            if (!raw.getKeys(true).equals(saved.getKeys(true))) {
                Path backup = backup(name, file, sourceVersion);
                write(file, saved);
                plugin.getLogger().info("Renamed hyphenated keys in " + name + " to underscores; backup: " + backup.getFileName());
            }
            return saved;
        }
        List<YamlConfiguration> history = history(name, sourceVersion);
        merge(current, saved, history, name.equals("menus.yml"));
        migrateLegacyTierPrices(name, current, saved, history);
        migratePurchaseMode(name, current, saved);
        validate.accept(current);
        Path backup = backup(name, file, sourceVersion);
        write(file, current);
        plugin.getLogger().info("Upgraded " + name + " from version " + sourceVersion + " to " + targetVersion
            + "; backup: " + backup.getFileName());
        return current;
    }

    private Path backup(String name, Path file, int sourceVersion) throws IOException {
        Path backups = plugin.getDataFolder().toPath().resolve("config-backups");
        Files.createDirectories(backups);
        Path backup = Files.createTempFile(backups, name.replace('/', '_') + ".v" + sourceVersion + "-", ".bak");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static void migrateLegacyTierPrices(String name, YamlConfiguration current, YamlConfiguration saved,
                                                List<YamlConfiguration> history) {
        if (!name.equals("config.yml") || saved.contains("economy.tier_prices")) return;
        Object legacyValue = saved.get("economy.summon_price");
        if (!(legacyValue instanceof Number number) || matchesDefault(history, "economy.summon_price", legacyValue)) return;
        double price = number.doubleValue();
        if (!Double.isFinite(price) || price < 0) return;
        current.set("economy.tier_prices.normal", price);
        current.set("economy.tier_prices.pro", price);
        current.set("economy.tier_prices.promax", price);
    }

    /**
     * 1.0.x 的 purchase.mode 只能选一种货币；新版金币、点券、物品可同时要求。
     * 按旧模式换算成对应的价格组合，未改过的默认值 free 按新默认值（金币）处理。
     */
    private static void migratePurchaseMode(String name, YamlConfiguration current, YamlConfiguration saved) {
        if (!name.equals("config.yml") || !saved.contains("purchase.mode")) return;
        String mode = String.valueOf(saved.getString("purchase.mode", "free")).toLowerCase(java.util.Locale.ROOT);
        // 购买物品改存数据库：只有旧模式为 items 时保留 purchase.items，供启动时一次性导入
        switch (mode) {
            case "coins" -> {
                current.set("purchase.points", 0);
                current.set("purchase.items", null);
            }
            case "points" -> {
                current.set("purchase.coins", 0.0);
                current.set("purchase.points", saved.getInt("purchase.points", 100));
                current.set("purchase.items", null);
            }
            case "items" -> {
                current.set("purchase.coins", 0.0);
                current.set("purchase.points", 0);
                current.set("purchase.items", saved.getList("purchase.items", List.of()));
            }
            default -> current.set("purchase.items", null);
        }
        current.set("purchase.mode", null);
    }

    private boolean importLegacyMessages(YamlConfiguration current) throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("messages.yml");
        if (Files.notExists(file)) return false;
        YamlConfiguration legacy = read(file);
        var history = history("messages.yml", 0);
        for (String key : legacy.getKeys(false)) {
            if (current.isString(key) && legacy.isString(key) && !matchesDefault(history, key, legacy.get(key))) {
                current.set(key, legacy.getString(key));
            }
        }
        return true;
    }

    /**
     * 旧版本的默认配置，用于区分玩家改过的值和未改动的默认值（未改动的会换成新默认值）。
     * 提高某个文件的 config_version 时，把旧版本文件原样放到 config-history/v旧版本号/ 下。
     */
    private List<YamlConfiguration> history(String name, int version) throws Exception {
        List<YamlConfiguration> history = new ArrayList<>();
        if (version == 0) {
            for (int index = 0; index < 4; index++) {
                var defaults = resource("config-history/legacy-" + index + "/" + name);
                if (defaults != null) history.add(defaults);
            }
            if (name.startsWith("lang/")) {
                var defaults = resource("config-history/v1/" + name);
                if (defaults != null) history.add(defaults);
            }
        } else {
            var defaults = resource("config-history/v" + version + "/" + name);
            if (defaults == null && name.startsWith("menus/")) {
                String section = name.substring("menus/".length(), name.length() - ".yml".length());
                defaults = resource("config-history/v" + version + "/" + oldMenuFile(section));
            }
            if (defaults != null) history.add(defaults);
        }
        return history;
    }

    static void merge(YamlConfiguration current, YamlConfiguration saved,
                      List<YamlConfiguration> history, boolean menu) {
        var keys = saved.getKeys(true);
        var customShapeItems = new HashSet<String>();
        if (menu) {
            for (String key : keys) {
                if (neededByCustomShape(current, saved, history, key)) customShapeItems.add(key);
            }
        }
        for (String key : keys) {
            if (key.equals(VERSION) || key.startsWith(VERSION + ".") || saved.isConfigurationSection(key)) continue;
            Object value = saved.get(key);
            if (!matchesDefault(history, key, value) || customShapeItems.contains(key)) {
                current.set(key, value);
                var comments = saved.getComments(key);
                if (!comments.isEmpty()) current.setComments(key, comments);
                var inline = saved.getInlineComments(key);
                if (!inline.isEmpty()) current.setInlineComments(key, inline);
            }
        }
    }

    private static boolean neededByCustomShape(YamlConfiguration current, YamlConfiguration saved,
                                              List<YamlConfiguration> history, String key) {
        String[] parts = key.split("\\.", 4);
        if (parts.length != 4 || !parts[1].equals("items") || parts[2].length() != 1) return false;
        String shape = parts[0] + ".shape";
        String item = parts[0] + ".items." + parts[2];
        return !current.isConfigurationSection(item)
            && !matchesDefault(history, shape, saved.get(shape))
            && saved.getStringList(shape).stream().anyMatch(row -> row.contains(parts[2]));
    }

    private static boolean matchesDefault(List<YamlConfiguration> history, String key, Object value) {
        return value != null && history.stream().anyMatch(defaults -> Objects.equals(value, defaults.get(key)));
    }

    private static int version(ConfigurationSection yaml, String name) throws IOException {
        if (yaml == null) throw new IOException("Missing bundled configuration: " + name);
        if (!yaml.contains(VERSION)) return 0;
        if (yaml.get(VERSION) instanceof Integer version && version >= 0) return version;
        throw new IOException(name + ": config_version must be a non-negative integer");
    }

    private YamlConfiguration resource(String name) throws Exception {
        try (var stream = plugin.getResource(name)) {
            if (stream == null) return null;
            var yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return underscored(yaml);
        }
    }

    /** 读取已有文件，并把旧键名转换为下划线写法。 */
    private static YamlConfiguration read(Path file) throws Exception { return underscored(readRaw(file)); }

    private static YamlConfiguration readRaw(Path file) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    /**
     * 1.1 起所有配置键名使用下划线（例如 max_per_owner、config_version）。
     * 把旧键名中的连字符换成下划线；单字符的键（菜单图标符号，例如 '-'）保持不变；值不做改动。
     */
    static YamlConfiguration underscored(YamlConfiguration source) {
        var target = new YamlConfiguration();
        target.options().setHeader(source.options().getHeader());
        target.options().setFooter(source.options().getFooter());
        for (String key : source.getKeys(true)) {
            String path = underscore(key);
            if (source.isConfigurationSection(key)) target.createSection(path);
            else target.set(path, source.get(key));
            target.setComments(path, source.getComments(key));
            target.setInlineComments(path, source.getInlineComments(key));
        }
        return target;
    }

    private static String underscore(String path) {
        String[] parts = path.split("\\.", -1);
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].length() > 1) parts[index] = parts[index].replace('-', '_');
        }
        return String.join(".", parts);
    }

    private static void write(Path file, YamlConfiguration yaml) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".afp-config-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
