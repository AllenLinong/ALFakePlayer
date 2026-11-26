package io.github.dummyplayers;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * /afp test 测试假人的设置（tab_test.yml）和固定档案。
 *
 * <p>名字表里的每个名字都有一份档案：固定的 UUID 和权限组，保存在数据库里，跨服共用。
 * 权限组按 groups 中的比例抽取；组从配置中删掉或在 LuckPerms 中不存在时，重新抽一个并写回档案。</p>
 */
final class TestProfiles {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** tab_test.yml 中的设置。groups 的键为小写组名，按配置顺序。 */
    record Settings(List<String> names, Map<String, Integer> groups, int rampMin, int rampMax,
                    int churnMin, int churnMax, int churnPercent, boolean messages, String joinFormat, String quitFormat) {

        static Settings read(ConfigurationSection yaml, Logger logger) {
            var names = new ArrayList<String>();
            var seen = new HashSet<String>();
            for (String raw : yaml.getStringList("names")) {
                String name = raw.strip();
                if (!NAME.matcher(name).matches()) logger.warning("tab_test.yml: skipping invalid name '" + raw + "'");
                else if (!seen.add(name.toLowerCase(Locale.ROOT))) logger.warning("tab_test.yml: skipping duplicate name '" + raw + "'");
                else names.add(name);
            }
            if (names.isEmpty()) throw new IllegalArgumentException("tab_test.yml: names must contain at least one valid name");
            var groups = new LinkedHashMap<String, Integer>();
            ConfigurationSection section = yaml.getConfigurationSection("groups");
            // Bukkit 把键里的 . 当作路径分隔符，lv.1: 50 读出来是 lv → 1: 50；取所有叶子键，拼回完整组名 lv.1
            if (section != null) for (String group : section.getKeys(true)) {
                if (section.isConfigurationSection(group)) continue;
                int weight = section.getInt(group, -1);
                if (weight < 1) throw new IllegalArgumentException("tab_test.yml: groups." + group + " must be a positive whole number");
                groups.merge(group.strip().toLowerCase(Locale.ROOT), weight, Integer::sum);
            }
            int[] ramp = range(yaml, "join.ramp_up_seconds", 60, 180, 0, 3600);
            int[] churn = range(yaml, "join.churn_minutes", 5, 15, 1, 1440);
            int percent = yaml.getInt("join.churn_percent", 10);
            if (percent < 0 || percent > 50) throw new IllegalArgumentException("tab_test.yml: join.churn_percent must be 0-50");
            return new Settings(List.copyOf(names), Collections.unmodifiableMap(groups), ramp[0], ramp[1], churn[0], churn[1],
                percent, yaml.getBoolean("join.messages", true), yaml.getString("join.join_format", ""),
                yaml.getString("join.quit_format", ""));
        }

        /** [最短, 最长]：只写一个数时两者相同。 */
        private static int[] range(ConfigurationSection yaml, String path, int min, int max, int lowest, int highest) {
            List<Integer> values = yaml.contains(path) ? yaml.getIntegerList(path) : List.of(min, max);
            if (values.isEmpty() && yaml.isInt(path)) values = List.of(yaml.getInt(path));
            if (values.isEmpty() || values.size() > 2) throw new IllegalArgumentException("tab_test.yml: " + path + " must be [min, max]");
            int low = values.getFirst(), high = values.getLast();
            if (low < lowest || high > highest || low > high) {
                throw new IllegalArgumentException("tab_test.yml: " + path + " must be between " + lowest + " and " + highest + ", min <= max");
            }
            return new int[] {low, high};
        }
    }

    private final DummyPlayers plugin;
    private volatile Settings settings;
    /** 小写名字 → 档案。 */
    private volatile Map<String, Roster.TestProfile> profiles = Map.of();
    /** 配置中且在 LuckPerms 中存在的组（小写），按配置顺序；都不存在时为 luckperms.group。 */
    private volatile List<String> available = List.of();
    private volatile boolean ready;

    TestProfiles(DummyPlayers plugin) { this.plugin = plugin; }

    Settings settings() { return settings; }
    boolean ready() { return ready; }
    List<String> availableGroups() { return available; }

    Roster.TestProfile profile(String name) { return profiles.get(name.toLowerCase(Locale.ROOT)); }

    /** 名字表中已有档案的名字，按配置顺序。 */
    List<Roster.TestProfile> pool() {
        var result = new ArrayList<Roster.TestProfile>();
        for (String name : settings.names()) {
            var profile = profile(name);
            if (profile != null) result.add(profile);
        }
        return result;
    }

    /**
     * 启动和重载后调用：检查哪些组存在，给新名字生成档案，再给组已失效的档案重新抽组。
     * 结果为权限组有变化的名字（小写）。
     */
    CompletableFuture<Set<String>> load(Settings next) {
        settings = next;
        return checkGroups().thenCompose(unused -> {
            var candidates = new ArrayList<Roster.TestProfile>();
            for (String name : next.names()) {
                if (profile(name) == null) candidates.add(new Roster.TestProfile(name, UUID.randomUUID(), roll()));
            }
            return plugin.roster.ensureTestProfiles(candidates);
        }).thenCompose(loaded -> {
            profiles = Map.copyOf(loaded);
            ready = true;
            return revalidate();
        });
    }

    /** 重新检查组是否存在，给组已失效的档案重新抽组。结果为权限组有变化的名字（小写）。 */
    CompletableFuture<Set<String>> recheck() {
        if (!ready) return CompletableFuture.completedFuture(Set.of());
        return checkGroups().thenCompose(unused -> revalidate());
    }

    private CompletableFuture<Void> checkGroups() {
        var configured = List.copyOf(settings.groups().keySet());
        var checks = new ArrayList<CompletableFuture<Boolean>>();
        for (String group : configured) {
            checks.add(plugin.groups().exists(group).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "Could not check LuckPerms group '" + group + "'", error);
                return available.contains(group);
            }));
        }
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenRun(() -> {
            var existing = new ArrayList<String>();
            for (int index = 0; index < configured.size(); index++) if (checks.get(index).join()) existing.add(configured.get(index));
            if (existing.isEmpty()) {
                String fallback = plugin.luckPermsGroup().strip().toLowerCase(Locale.ROOT);
                if (!configured.isEmpty() && !List.of(fallback).equals(available)) {
                    plugin.getLogger().warning("None of the groups in tab_test.yml exist in LuckPerms; test players use luckperms.group '"
                        + fallback + "'.");
                }
                existing.add(fallback);
            }
            available = List.copyOf(existing);
        });
    }

    /** 按比例从可用的组里抽一个。 */
    String roll() {
        List<String> groups = available;
        if (groups.isEmpty()) return plugin.luckPermsGroup().strip().toLowerCase(Locale.ROOT);
        int total = 0;
        for (String group : groups) total += settings.groups().getOrDefault(group, 1);
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (String group : groups) {
            pick -= settings.groups().getOrDefault(group, 1);
            if (pick < 0) return group;
        }
        return groups.getLast();
    }

    private CompletableFuture<Set<String>> revalidate() {
        var changed = new HashSet<String>();
        var next = new HashMap<>(profiles);
        var writes = new ArrayList<CompletableFuture<Void>>();
        var examples = new ArrayList<String>();
        next.replaceAll((key, profile) -> {
            if (available.contains(profile.group().toLowerCase(Locale.ROOT))) return profile;
            String group = roll();
            if (examples.size() < 5) examples.add(profile.name() + " " + profile.group() + " -> " + group);
            changed.add(key);
            writes.add(plugin.roster.setTestGroup(profile.name(), group));
            return new Roster.TestProfile(profile.name(), profile.id(), group);
        });
        if (changed.isEmpty()) return CompletableFuture.completedFuture(Set.of());
        plugin.getLogger().info("Re-assigned the group of " + changed.size() + " test player(s) whose group is no longer available: "
            + String.join(", ", examples) + (changed.size() > examples.size() ? ", ..." : "") + ".");
        profiles = Map.copyOf(next);
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).handle((unused, error) -> {
            if (error != null) plugin.getLogger().log(Level.WARNING, "Could not save the new groups of test players", error);
            return Set.copyOf(changed);
        });
    }

    /** 手动修改档案的权限组；组必须在 groups 中且在 LuckPerms 中存在。 */
    CompletableFuture<Boolean> setGroup(String name, String group) {
        String normalized = group.strip().toLowerCase(Locale.ROOT);
        var profile = profile(name);
        if (profile == null || !available.contains(normalized)) return CompletableFuture.completedFuture(false);
        var next = new HashMap<>(profiles);
        next.put(profile.name().toLowerCase(Locale.ROOT), new Roster.TestProfile(profile.name(), profile.id(), normalized));
        profiles = Map.copyOf(next);
        return plugin.roster.setTestGroup(profile.name(), normalized).thenApply(unused -> true);
    }
}
