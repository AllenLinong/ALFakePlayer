package io.github.dummyplayers;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * 测试假人的小队（tab_chat.yml 的 teams）。一部分测试假人随机组成几个固定的小队，记在测试假人档案里（跨服共用），
 * 之后一直不变；名字表里加了人、比例调大时只给新的人分队，已有的小队不动。
 *
 * <p>同一个小队的人互相认识：冷场时小队内部聊天（分工、说进度、互损、约时间），一个人上线、下线时队友先接话；
 * 开了 AI 时 AI 知道谁和谁是一队、他们一起在做什么。</p>
 */
final class TestTeams {
    private TestTeams() { }

    /** tab_chat.yml 没写 teams.names 时的小队名。 */
    static final List<String> DEFAULT_NAMES = List.of("摸鱼联盟", "养老院", "肝帝小队", "红石研究所", "下界探险队", "钓鱼协会",
        "建筑工地", "挖矿三人组", "咕咕团", "夜猫子", "种田大户", "开荒队", "躺平小组", "苦力怕受害者协会", "铁傀儡后援会", "早八人");
    /** tab_chat.yml 没写 teams.projects 时小队一起在做的事。 */
    static final List<String> DEFAULT_PROJECTS = List.of("一起盖主城", "合伙弄刷铁机", "组队准备打末影龙", "一起去远处开荒",
        "合建村民交易所", "一起刷凋零骷髅头", "一起修下界铁路", "合伙搞刷怪塔", "一起找远古城市", "一起挖下界合金",
        "一起装修小镇", "合伙开商店", "一起建海底基地", "一起搞自动农场", "一起刷猪灵金块", "约好今晚打凋零", "一起整理公共仓库",
        "一起修被苦力怕炸坏的房子");
    /** tab_chat.yml 没写 teams.dialogues 时小队内部的对话（没开 AI 或 AI 出错时用）。 */
    static final List<String> DEFAULT_DIALOGUES = List.of(
        "A: %B% 你那边弄好没|%B% 进度咋样了", "B: 还差一点|快了快了|别催 在弄", "A: 快点 就等你了|行 我先去挖点石头",
        "---",
        "A: 我上线了 你们在哪", "B: 老地方|基地", "C: 我在下界 等会回来", "A: 好",
        "---",
        "A: 箱子里的铁谁拿了", "B: 我拿去做铁轨了|不是我", "C: 哈哈哈", "A: 下次说一声",
        "---",
        "A: 今晚还打不打", "B: 打|看情况 我先写作业", "C: 我装备还没好", "A: 那明天吧",
        "---",
        "A: %C% 你又挂机", "C: 没挂 在看|刚去倒水了", "B: 他天天挂", "C: 乱说",
        "---",
        "A: 材料不太够了", "B: 我去砍树|我下矿看看", "A: 那我去弄石头", "B: ok",
        "---",
        "A: 刚才谁炸的", "B: 苦力怕|不是我 真不是", "A: 墙都没了", "C: 补一下就好了",
        "---",
        "A: 你们困不困", "B: 还行|困死了", "A: 再肝一会就睡", "B: 你每次都这么说");

    /** 小队名用完时拼名字用。 */
    private static final List<String> HEADS = List.of("摸鱼", "肝帝", "躺平", "养老", "夜猫", "红石", "挖矿", "种田", "钓鱼", "建筑",
        "咕咕", "开荒", "下界", "末地", "干饭", "熬夜", "佛系", "萌新");
    private static final List<String> TAILS = List.of("小队", "联盟", "团", "组", "帮", "工会", "俱乐部");

    record Settings(boolean enabled, int percent, int sizeMin, int sizeMax, int chatPercent, List<String> names, List<String> projects,
                    List<List<TestChat.Step>> dialogues) {

        static Settings read(ConfigurationSection yaml) {
            int percent = yaml.getInt("percent", 40);
            if (percent < 0 || percent > 100) throw new IllegalArgumentException("tab_chat.yml: teams.percent must be 0-100");
            List<Integer> size = yaml.contains("size") ? yaml.getIntegerList("size") : List.of(2, 4);
            if (size.isEmpty() && yaml.isInt("size")) size = List.of(yaml.getInt("size"));
            if (size.isEmpty() || size.size() > 2 || size.getFirst() < 2 || size.getLast() > 8 || size.getFirst() > size.getLast()) {
                throw new IllegalArgumentException("tab_chat.yml: teams.size must be [min, max] between 2 and 8");
            }
            int chat = yaml.getInt("chat_percent", 40);
            if (chat < 0 || chat > 100) throw new IllegalArgumentException("tab_chat.yml: teams.chat_percent must be 0-100");
            List<String> names = strings(yaml, "names", DEFAULT_NAMES);
            List<String> projects = strings(yaml, "projects", DEFAULT_PROJECTS);
            var dialogues = TestChat.Settings.dialogues(yaml.contains("dialogues") ? yaml.getList("dialogues", List.of())
                : split(DEFAULT_DIALOGUES), "tab_chat.yml: teams.dialogues");
            return new Settings(yaml.getBoolean("enabled", true), percent, size.getFirst(), size.getLast(), chat, names, projects, dialogues);
        }

        private static List<String> strings(ConfigurationSection yaml, String path, List<String> fallback) {
            if (!yaml.contains(path)) return fallback;
            var result = new ArrayList<String>();
            for (String item : yaml.getStringList(path)) if (!item.isBlank()) result.add(item.strip());
            return List.copyOf(result);
        }

        /** 内置对话用 --- 分隔。 */
        private static List<Object> split(List<String> lines) {
            var result = new ArrayList<Object>();
            var current = new ArrayList<String>();
            for (String line : lines) {
                if (line.equals("---")) { result.add(List.copyOf(current)); current.clear(); }
                else current.add(line);
            }
            if (!current.isEmpty()) result.add(List.copyOf(current));
            return result;
        }
    }

    /**
     * 算出要改的小队（小写名字 → 小队名，空字符串为退出小队）。keys 为名字表里的人（小写，按配置顺序），只给他们分队；
     * 名字表里已经没有的人保持原样。纯函数，seed 相同时结果相同（数据库事务重试时用）。
     */
    static Map<String, String> plan(Map<String, Roster.TestProfile> stored, List<String> keys, Settings settings, long seed) {
        var random = new Random(seed);
        var changes = new HashMap<String, String>();
        var teams = new LinkedHashMap<String, List<String>>();
        var free = new ArrayList<String>();
        int total = 0;
        for (String key : keys) {
            Roster.TestProfile profile = stored.get(key);
            if (profile == null) continue;
            total++;
            if (profile.team().isEmpty()) free.add(key);
            else teams.computeIfAbsent(profile.team(), unused -> new ArrayList<>()).add(key);
        }
        // 调小了人数上限：多出来的人退出；队友都不在名字表里了、只剩一个人的小队解散
        for (var team : teams.values()) {
            while (team.size() > settings.sizeMax()) { String key = team.removeLast(); free.add(key); changes.put(key, ""); }
            if (team.size() < 2) { for (String key : team) { free.add(key); changes.put(key, ""); } team.clear(); }
        }
        teams.values().removeIf(List::isEmpty);
        int target = settings.enabled() ? (int) Math.round(total * settings.percent() / 100.0) : 0;
        int members = teams.values().stream().mapToInt(List::size).sum();
        // 调小了比例：随机解散几个小队
        var names = new ArrayList<>(teams.keySet());
        Collections.shuffle(names, random);
        for (String name : names) {
            if (members <= target) break;
            for (String key : teams.remove(name)) { free.add(key); changes.put(key, ""); }
            members = teams.values().stream().mapToInt(List::size).sum();
        }
        // 调大了比例或来了新的人：组新的小队，零头加进没满的小队
        Collections.shuffle(free, random);
        var unused = new ArrayList<String>();
        for (String name : settings.names()) if (!teams.containsKey(name) && name.length() <= 64) unused.add(name);
        Collections.shuffle(unused, random);
        // 名字用完了：拼一个（摸鱼 + 小队），再不够才编号
        var extra = new ArrayList<String>();
        for (String head : HEADS) for (String tail : TAILS) if (!teams.containsKey(head + tail) && !unused.contains(head + tail)) extra.add(head + tail);
        Collections.shuffle(extra, random);
        unused.addAll(extra);
        int number = 1;
        while (members < target && !free.isEmpty()) {
            int need = Math.min(target - members, free.size());
            if (need >= 2) {
                int size = Math.min(need, settings.sizeMin() + random.nextInt(settings.sizeMax() - settings.sizeMin() + 1));
                if (size < 2) break;
                String name = !unused.isEmpty() ? unused.removeFirst() : "小队" + number;
                while (teams.containsKey(name)) name = "小队" + ++number;
                number++;
                var team = new ArrayList<String>();
                for (int index = 0; index < size; index++) {
                    String key = free.removeFirst();
                    team.add(key);
                    changes.put(key, name);
                }
                teams.put(name, team);
                members += size;
            } else {
                String room = null;
                for (var team : teams.entrySet()) if (team.getValue().size() < settings.sizeMax()) { room = team.getKey(); break; }
                if (room == null) break;
                String key = free.removeFirst();
                teams.get(room).add(key);
                changes.put(key, room);
                members++;
            }
        }
        // 只写真正变了的
        changes.entrySet().removeIf(change -> stored.get(change.getKey()).team().equals(change.getValue()));
        return changes;
    }

    /** 小队现在一起在做的事：每个小队每 2～4 小时换一件，由小队名和时间算出，不用存。 */
    static String project(String team, List<String> projects) {
        if (projects.isEmpty() || team.isEmpty()) return "";
        long hash = mix(team.hashCode());
        long period = 120 + Math.floorMod(hash, 121L);
        long bucket = (System.currentTimeMillis() / 60_000 + Math.floorMod(hash >>> 20, 120L)) / period;
        return projects.get((int) Math.floorMod(mix(hash ^ bucket), (long) projects.size()));
    }

    /** splitmix64：相近的输入（小队22、小队23）也会得到差别很大的结果。 */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }
}
