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
    private static final String VERSION = "config-version";
    private static final List<String> MENUS = List.of("states", "main", "tier-select", "spawn-confirm",
        "teleport-confirm", "purchase-confirm", "purchase-items", "inventory", "settings");
    private final JavaPlugin plugin;

    ConfigUpgrade(JavaPlugin plugin) { this.plugin = plugin; }

    static String menuFile(String section) { return "menus/" + section + ".yml"; }

    YamlConfiguration loadMenus(Consumer<YamlConfiguration> validate) throws Exception {
        Path folder = plugin.getDataFolder().toPath();
        Path legacyFile = folder.resolve("menus.yml");
        boolean importing = Files.exists(legacyFile)
            && MENUS.stream().anyMatch(section -> Files.notExists(folder.resolve(menuFile(section))));
        YamlConfiguration legacy = importing ? read(legacyFile) : null;
        int legacyVersion = importing ? version(legacy, "menus.yml") : 0;
        var legacyHistory = importing ? history("menus.yml", legacyVersion) : List.<YamlConfiguration>of();
        var combined = new YamlConfiguration();
        var writes = new ArrayList<RunnableWithIo>();
        boolean hasSplitMenus = MENUS.stream().anyMatch(section -> Files.exists(folder.resolve(menuFile(section))));
        for (String section : MENUS) {
            String name = menuFile(section);
            Path path = folder.resolve(name);
            YamlConfiguration defaults = resource(name);
            int targetVersion = version(defaults, name);
            if (targetVersion < 1 || !defaults.isConfigurationSection(section)) {
                throw new IOException("Invalid bundled menu: " + name);
            }
            YamlConfiguration selected = defaults;
            if (Files.exists(path)) {
                YamlConfiguration saved = read(path);
                int savedVersion = version(saved, name);
                if (savedVersion > targetVersion) throw newer(name, savedVersion);
                if (savedVersion == targetVersion) selected = saved;
                else {
                    merge(defaults, saved, history(name, savedVersion), true);
                    writes.add(() -> {
                        backup(name, path, savedVersion);
                        write(path, defaults);
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

        YamlConfiguration saved = read(file);
        int sourceVersion = version(saved, name);
        if (sourceVersion > targetVersion) {
            throw newer(name, sourceVersion);
        }
        if (sourceVersion == targetVersion) {
            validate.accept(saved);
            return saved;
        }
        List<YamlConfiguration> history = history(name, sourceVersion);
        merge(current, saved, history, name.equals("menus.yml"));
        migrateLegacyTierPrices(name, current, saved, history);
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
        if (!name.equals("config.yml") || saved.contains("economy.tier-prices")) return;
        Object legacyValue = saved.get("economy.summon-price");
        if (!(legacyValue instanceof Number number) || matchesDefault(history, "economy.summon-price", legacyValue)) return;
        double price = number.doubleValue();
        if (!Double.isFinite(price) || price < 0) return;
        current.set("economy.tier-prices.normal", price);
        current.set("economy.tier-prices.pro", price);
        current.set("economy.tier-prices.promax", price);
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
        throw new IOException(name + ": config-version must be a non-negative integer");
    }

    private YamlConfiguration resource(String name) throws Exception {
        try (var stream = plugin.getResource(name)) {
            if (stream == null) return null;
            var yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static YamlConfiguration read(Path file) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
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
