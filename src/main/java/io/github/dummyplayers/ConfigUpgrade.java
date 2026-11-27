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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取插件目录下的配置文件。文件不存在时写入默认文件；插件更新提高了某个文件的 config_version 时，
 * 按新的默认文件升级：保留改过的值，未改动的默认值换成新默认值，原文件备份到 config-backups/。
 *
 * <p>config_version 是每个文件自己的整数版本，从 1 开始，只在这个文件的内容变了时加一。
 * 1.0.4 写出的文件是插件版本号 '1.0.4'，内容和版本 1 相同，读取时当作 1，只把这一行改成 1。</p>
 */
final class ConfigUpgrade {
    private static final String VERSION = "config_version";
    /** 1.0.4 用插件版本号作为 config_version（如 '1.0.4'）。 */
    private static final Pattern DOTTED = Pattern.compile("\\d+(\\.\\d+)+");
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^config_version:[ \\t]*['\"]?\\d+(\\.\\d+)+['\"]?[ \\t]*$");
    /**
     * 键由用户自己定的段落（如测试假人的权限组和比例）：改过就整段沿用，不和新默认值逐项合并，
     * 否则用户删掉的默认项（如 default 组）会被加回来。
     */
    private static final Map<String, Set<String>> WHOLE_SECTIONS = Map.of("tab_test.yml", Set.of("groups"), "tab_chat.yml", Set.of("channels"));

    /** 配置版本。dotted 为 1.0.4 写出的插件版本号，当作 1。 */
    record Version(int number, boolean dotted) {
        static Version of(Object raw, String name) throws IOException {
            if (raw == null) return new Version(0, false);
            if (raw instanceof Integer number && number >= 0) return new Version(number, false);
            String text = String.valueOf(raw).strip();
            if (raw instanceof String && text.matches("\\d{1,9}")) return new Version(Integer.parseInt(text), false);
            if (raw instanceof String && DOTTED.matcher(text).matches()) return new Version(1, true);
            throw new IOException(name + ": config_version must be a whole number like 1");
        }

        @Override public String toString() { return String.valueOf(number); }
    }
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
        if (current == null) throw new IOException("Missing bundled configuration: " + name);
        if (!current.contains(VERSION)) throw new IOException("Missing bundled version: " + name);
        Version targetVersion = Version.of(current.get(VERSION), name);
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
        Version sourceVersion = Version.of(saved.get(VERSION), name);
        if (sourceVersion.number() > targetVersion.number()) {
            throw new IOException(name + " is from a newer plugin (version " + sourceVersion + "); file left unchanged");
        }
        if (sourceVersion.number() == targetVersion.number()) {
            if (!sourceVersion.dotted()) return new Loaded(saved, null);
            // 1.0.4 的文件：内容就是版本 1，只改版本号这一行，其余内容和写法原样保留
            saved.set(VERSION, targetVersion.number());
            return new Loaded(saved, () -> {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher line = VERSION_LINE.matcher(text);
                if (!line.find()) return;
                writeText(file, text.substring(0, line.start()) + VERSION + ": " + targetVersion + text.substring(line.end()));
            });
        }
        merge(current, saved, history(name, sourceVersion), menu, WHOLE_SECTIONS.getOrDefault(name, Set.of()));
        return new Loaded(current, () -> {
            Path backup = backup(name, file, sourceVersion);
            write(file, flattened(current, WHOLE_SECTIONS.getOrDefault(name, Set.of())));
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

    private Path backup(String name, Path file, Version sourceVersion) throws IOException {
        Path backups = plugin.getDataFolder().toPath().resolve("config-backups");
        Files.createDirectories(backups);
        Path backup = Files.createTempFile(backups, name.replace('/', '_') + ".v" + sourceVersion + "-", ".bak");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    /**
     * 旧版本的默认配置，用于区分改过的值和未改动的默认值（未改动的会换成新默认值）。
     * 提高某个文件的 config_version 时，把上一个版本的文件原样放到 config-history/v旧版本号/ 下，
     * 例如 config.yml 从 1 升到 2 时，把版本 1 的 config.yml 放到 config-history/v1/config.yml。
     */
    private List<YamlConfiguration> history(String name, Version version) throws Exception {
        var defaults = resource("config-history/v" + version + "/" + name);
        return defaults == null ? List.of() : List.of(defaults);
    }

    static void merge(YamlConfiguration current, YamlConfiguration saved,
                      List<YamlConfiguration> history, boolean menu, Set<String> wholeSections) {
        var keys = saved.getKeys(true);
        var whole = new HashSet<String>();
        for (String section : wholeSections) {
            if (!saved.isConfigurationSection(section)) continue;
            whole.add(section);
            Map<String, Object> mine = leaves(saved, section);
            if (history.stream().anyMatch(defaults -> defaults.isConfigurationSection(section) && leaves(defaults, section).equals(mine))) continue;
            var comments = current.getComments(section);
            var inline = current.getInlineComments(section);
            current.set(section, null);
            current.createSection(section);
            current.setComments(section, comments);
            current.setInlineComments(section, inline);
            mine.forEach((key, value) -> current.set(section + "." + key, value));
        }
        var customShapeItems = new HashSet<String>();
        if (menu) {
            for (String key : keys) {
                if (neededByCustomShape(current, saved, history, key)) customShapeItems.add(key);
            }
        }
        for (String key : keys) {
            if (key.equals(VERSION) || key.startsWith(VERSION + ".") || saved.isConfigurationSection(key)) continue;
            if (whole.stream().anyMatch(section -> key.startsWith(section + "."))) continue;
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

    /**
     * 整段沿用的段落里带点的键（如权限组 lv.1）写回成一个键。Bukkit 默认把它写成 lv: {'1': 50} 的嵌套写法，
     * 读出来一样，但看起来像是坏了。
     */
    private static YamlConfiguration flattened(YamlConfiguration yaml, Set<String> whole) {
        if (whole.isEmpty()) return yaml;
        char separator = '\u001F';
        var out = new YamlConfiguration();
        out.options().pathSeparator(separator);
        out.options().setHeader(yaml.options().getHeader());
        out.options().setFooter(yaml.options().getFooter());
        for (String key : yaml.getKeys(true)) {
            String owner = whole.stream().filter(section -> key.startsWith(section + ".")).findFirst().orElse(null);
            if (owner != null && yaml.isConfigurationSection(key)) continue;
            String path = owner == null ? key.replace('.', separator)
                : owner.replace('.', separator) + separator + key.substring(owner.length() + 1);
            if (yaml.isConfigurationSection(key)) out.createSection(path);
            else out.set(path, yaml.get(key));
            out.setComments(path, yaml.getComments(key));
            out.setInlineComments(path, yaml.getInlineComments(key));
        }
        return out;
    }

    /** 段落里所有的值（不含子段落本身），键为相对路径。 */
    private static Map<String, Object> leaves(ConfigurationSection yaml, String section) {
        var result = new LinkedHashMap<String, Object>();
        ConfigurationSection from = yaml.getConfigurationSection(section);
        if (from != null) for (String key : from.getKeys(true)) if (!from.isConfigurationSection(key)) result.put(key, from.get(key));
        return result;
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

    private static void write(Path file, YamlConfiguration yaml) throws IOException { writeText(file, yaml.saveToString()); }

    private static void writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".afp-config-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
