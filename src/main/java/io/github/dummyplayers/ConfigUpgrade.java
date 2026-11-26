package io.github.dummyplayers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取插件目录下的配置文件。文件不存在时写入默认文件；插件更新提高了某个文件的 config_version 时，
 * 按新的默认文件升级：保留改过的值，未改动的默认值换成新默认值，原文件备份到 config-backups/。
 */
final class ConfigUpgrade {
    private static final String VERSION = "config_version";
    private static final List<String> MENUS = List.of("states", "main", "tier_select", "spawn_confirm",
        "teleport_confirm", "purchase_confirm", "purchase_items", "inventory", "settings");
    private final JavaPlugin plugin;

    ConfigUpgrade(JavaPlugin plugin) { this.plugin = plugin; }

    static String menuFile(String section) { return "menus/" + section + ".yml"; }

    YamlConfiguration load(String name, Consumer<YamlConfiguration> validate) throws Exception {
        Loaded loaded = prepare(name, false);
        validate.accept(loaded.yaml());
        if (loaded.save() != null) loaded.save().run();
        return loaded.yaml();
    }

    /** 读取所有菜单文件并合并：每个文件的顶层节点就是对应的菜单。全部校验通过后才写入磁盘。 */
    YamlConfiguration loadMenus(Consumer<YamlConfiguration> validate) throws Exception {
        var combined = new YamlConfiguration();
        var saves = new ArrayList<RunnableWithIo>();
        for (String section : MENUS) {
            String name = menuFile(section);
            Loaded loaded = prepare(name, true);
            if (!loaded.yaml().isConfigurationSection(section)) throw new IOException(name + ": missing section " + section);
            copySection(combined, loaded.yaml(), section);
            if (loaded.save() != null) saves.add(loaded.save());
        }
        validate.accept(combined);
        for (RunnableWithIo save : saves) save.run();
        return combined;
    }

    private interface RunnableWithIo { void run() throws IOException; }

    /** 读取结果；需要写入磁盘时（首次生成或升级）save 不为空，由调用方在校验通过后执行。 */
    private record Loaded(YamlConfiguration yaml, RunnableWithIo save) { }

    private Loaded prepare(String name, boolean menu) throws Exception {
        YamlConfiguration current = resource(name);
        int targetVersion = version(current, name);
        if (targetVersion < 1) throw new IOException("Missing bundled version: " + name);
        Path file = plugin.getDataFolder().toPath().resolve(name);
        if (Files.notExists(file)) return new Loaded(current, () -> plugin.saveResource(name, false));

        YamlConfiguration saved;
        try { saved = read(file); }
        catch (InvalidConfigurationException invalid) {
            // snakeyaml 的报错第一行不含文件名和行号，这里补上，方便在 reload 失败的提示里直接看到
            Matcher at = Pattern.compile("line (\\d+), column (\\d+)").matcher(String.valueOf(invalid.getMessage()));
            String where = at.find() ? " at line " + at.group(1) + ", column " + at.group(2) : "";
            throw new IOException(name + ": invalid YAML" + where, invalid);
        }
        int sourceVersion = version(saved, name);
        if (sourceVersion > targetVersion) {
            throw new IOException(name + " is from a newer plugin (version " + sourceVersion + "); file left unchanged");
        }
        if (sourceVersion == targetVersion) return new Loaded(saved, null);
        merge(current, saved, history(name, sourceVersion), menu);
        return new Loaded(current, () -> {
            Path backup = backup(name, file, sourceVersion);
            write(file, current);
            plugin.getLogger().info("Upgraded " + name + " from version " + sourceVersion + " to " + targetVersion
                + "; backup: " + backup.getFileName());
        });
    }

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

    private Path backup(String name, Path file, int sourceVersion) throws IOException {
        Path backups = plugin.getDataFolder().toPath().resolve("config-backups");
        Files.createDirectories(backups);
        Path backup = Files.createTempFile(backups, name.replace('/', '_') + ".v" + sourceVersion + "-", ".bak");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    /**
     * 旧版本的默认配置，用于区分改过的值和未改动的默认值（未改动的会换成新默认值）。
     * 提高某个文件的 config_version 时，把旧版本文件原样放到 config-history/v旧版本号/ 下，
     * 例如 config.yml 从 1 升到 2 时，把版本 1 的 config.yml 放到 config-history/v1/config.yml。
     */
    private List<YamlConfiguration> history(String name, int version) throws Exception {
        var defaults = resource("config-history/v" + version + "/" + name);
        return defaults == null ? List.of() : List.of(defaults);
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
