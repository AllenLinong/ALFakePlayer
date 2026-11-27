package io.github.dummyplayers;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.adventure.ChatProcessor;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * TAB 测试假人的聊天。所有发言进同一个队列，由一个定时器一句一句发出，不会几个人同时说话。
 *
 * <p>限流是自适应的：每个时间窗口允许的句数随在线真人数和真人的发言量变化，测试假人的发言不超过
 * 全部聊天的一定比例；聊天区热闹时句间间隔缩短；真人之间正在聊天时不插话；被 @ 的回复总留一个名额。</p>
 *
 * <p>内容来自 tab_chat.yml 的关键词规则和语料；开启 ai 时，回复真人和冷场闲聊交给 AI 决定谁说、说什么，
 * 出错或用完次数时改用规则。发送方式可以是本插件的格式，也可以交给服务器的聊天流程（聊天插件来排版）。</p>
 */
final class TestChat implements Listener {
    private static final int MENTION = 4, REPLY = 3, GREET = 2, IDLE = 1;
    /** 会在聊天事件里取消原版聊天、自己重新发送的聊天插件；装了它们时，被取消的聊天也要听。 */
    private static final List<String> REBROADCASTING = List.of("TrChat", "ChatControl", "ChatControlRed", "VentureChat",
        "CarbonChat", "UltraChat", "HeroChat", "ChatManager", "DeluxeChat");
    /** 负责排版的聊天插件；装了它们时，send_mode: auto 交给服务器的聊天流程。 */
    private static final List<String> FORMATTING = List.of("TrChat", "ChatControl", "ChatControlRed", "VentureChat",
        "CarbonChat", "UltraChat", "HeroChat", "ChatManager", "DeluxeChat", "EssentialsChat", "LPC", "ChatEx", "ChatFormatter");

    record Keyword(String text, Pattern word) {
        static Keyword of(String raw) {
            String text = raw.strip().toLowerCase(Locale.ROOT);
            // 英文和数字按整词匹配：hi 不匹配 this，88 不匹配 1888
            boolean ascii = text.chars().allMatch(c -> c < 128);
            return new Keyword(text, ascii ? Pattern.compile("(?<![a-z0-9])" + Pattern.quote(text) + "(?![a-z0-9])") : null);
        }

        boolean in(String lower) { return word != null ? word.matcher(lower).find() : lower.contains(text); }
    }

    record Rule(String key, List<Keyword> keywords, int chance, int minResponders, int maxResponders, long cooldown, List<String> lines) { }

    /** 冷场对话中的一行：由第 role 个人（A=0）说，texts 里随机挑一种。 */
    record Step(int role, List<String> texts) { }

    record Settings(boolean enabled, String sendMode, String format, String cancelledChat,
                    long window, double baseMessages, double perPlayer, int sharePercent, int maxMessages, long gapMin, long gapMax,
                    long speakerCooldown, long ruleCooldown, double typingBase, double typingPerChar, double typingRandom,
                    long expire, boolean dontInterrupt, int talkativePercent, long conversation,
                    long quietMin, long quietMax, int dialoguePercent,
                    int burstChance, int burstMin, int burstMax, long burstGapMin, long burstGapMax,
                    int welcome, long welcomeCooldown, int joinGreet, int farewell, int answer, int mention, int followUp, int question,
                    List<Keyword> ignore, List<Rule> rules, Map<String, List<String>> lines, List<List<Step>> dialogues,
                    TestAi.Settings ai, boolean crossServer, String forwarded, Map<String, Integer> channels, TestTeams.Settings teams) {

        static Settings read(ConfigurationSection yaml) {
            String mode = yaml.getString("send_mode", "auto").strip().toLowerCase(Locale.ROOT);
            if (!Set.of("auto", "format", "chat").contains(mode)) throw new IllegalArgumentException("tab_chat.yml: send_mode must be auto, format or chat");
            String cancelled = String.valueOf(yaml.get("include_cancelled_chat", "auto")).strip().toLowerCase(Locale.ROOT);
            if (!Set.of("auto", "true", "false").contains(cancelled)) {
                throw new IllegalArgumentException("tab_chat.yml: include_cancelled_chat must be auto, true or false");
            }
            ConfigurationSection limits = section(yaml, "limits");
            long[] gap = seconds(limits, "gap_seconds", 4, 10, 0, 600);
            long[] quiet = seconds(section(yaml, "idle"), "quiet_seconds", 40, 120, 10, 86400);
            ConfigurationSection burst = section(limits, "burst");
            int[] burstSpeakers = ints(burst.get("speakers"), 3, 4, 2, 8, "tab_chat.yml: limits.burst.speakers");
            long[] burstGap = millis(burst, "gap_seconds", 0.3, 2, 0, 10);
            ConfigurationSection chances = section(yaml, "chances");
            var rules = new ArrayList<Rule>();
            int index = 0;
            for (Map<?, ?> raw : yaml.getMapList("replies")) {
                String where = "tab_chat.yml: replies[" + index++ + "]";
                var keywords = keywords(raw.get("keywords"), where);
                var lines = strings(raw.get("lines"));
                if (keywords.isEmpty() || lines.isEmpty()) throw new IllegalArgumentException(where + " needs keywords and lines");
                int[] responders = ints(raw.get("responders"), 1, 1, 1, 5, where + ".responders");
                long cooldown = raw.get("cooldown_seconds") instanceof Number number ? number.longValue() * 1000 : -1;
                rules.add(new Rule("rule" + index, keywords, percent(raw.get("chance"), 100, where + ".chance"),
                    responders[0], responders[1], cooldown, lines));
            }
            var lines = new HashMap<String, List<String>>();
            ConfigurationSection pools = yaml.getConfigurationSection("lines");
            if (pools != null) for (String key : pools.getKeys(false)) lines.put(key, List.copyOf(pools.getStringList(key)));
            var dialogues = dialogues(yaml.getList("dialogues", List.of()), "tab_chat.yml: dialogues");
            // 频道 → 比例；频道名可能带点，取所有叶子键
            var channels = new LinkedHashMap<String, Integer>();
            ConfigurationSection channelSection = yaml.getConfigurationSection("channels");
            if (channelSection != null) for (String channel : channelSection.getKeys(true)) {
                if (channelSection.isConfigurationSection(channel)) continue;
                int weight = channelSection.getInt(channel, -1);
                if (weight < 1) throw new IllegalArgumentException("tab_chat.yml: channels." + channel + " must be a positive whole number");
                channels.merge(channel.strip(), weight, Integer::sum);
            }
            String forwarded = String.valueOf(yaml.get("cross_server.forwarded", "auto")).strip().toLowerCase(Locale.ROOT);
            if (!Set.of("auto", "true", "false").contains(forwarded)) {
                throw new IllegalArgumentException("tab_chat.yml: cross_server.forwarded must be auto, true or false");
            }
            int share = limits.getInt("max_share_percent", 40);
            if (share < 1 || share > 90) throw new IllegalArgumentException("tab_chat.yml: limits.max_share_percent must be 1-90");
            int max = limits.getInt("max_messages", 15);
            double base = limits.getDouble("base_messages", 5);
            if (max < 1 || base < 0 || base > max) throw new IllegalArgumentException("tab_chat.yml: limits.base_messages must be 0..max_messages, max_messages >= 1");
            return new Settings(yaml.getBoolean("enabled", true), mode, yaml.getString("format", "<%name%> %message%"), cancelled,
                positive(limits, "window_seconds", 60) * 1000, base, Math.max(0, limits.getDouble("messages_per_player", 1)), share, max,
                gap[0], gap[1], Math.max(0, limits.getLong("speaker_cooldown_seconds", 90)) * 1000,
                Math.max(0, limits.getLong("rule_cooldown_seconds", 30)) * 1000,
                Math.max(0, limits.getDouble("typing_base_seconds", 2)), Math.max(0, limits.getDouble("typing_per_char_seconds", 0.3)),
                Math.max(0, limits.getDouble("typing_random_seconds", 3)), positive(limits, "expire_seconds", 20) * 1000,
                limits.getBoolean("dont_interrupt", true), percent(yaml.get("talkative_percent"), 50, "tab_chat.yml: talkative_percent"),
                positive(yaml, "conversation_seconds", 60) * 1000, quiet[0], quiet[1],
                percent(yaml.get("idle.dialogue_percent"), 60, "tab_chat.yml: idle.dialogue_percent"),
                percent(burst.get("chance"), 25, "tab_chat.yml: limits.burst.chance"), burstSpeakers[0], burstSpeakers[1], burstGap[0], burstGap[1],
                percent(chances.get("welcome"), 40, "chances.welcome"), Math.max(0, chances.getLong("welcome_cooldown_seconds", 120)) * 1000,
                percent(chances.get("join_greet"), 15, "chances.join_greet"), percent(chances.get("farewell"), 20, "chances.farewell"),
                percent(chances.get("answer"), 50, "chances.answer"), percent(chances.get("mention"), 90, "chances.mention"),
                percent(chances.get("follow_up"), 45, "chances.follow_up"), percent(chances.get("question"), 25, "chances.question"),
                keywords(yaml.get("ignore_keywords"), "tab_chat.yml: ignore_keywords"), List.copyOf(rules), Map.copyOf(lines),
                dialogues, TestAi.Settings.read(section(yaml, "ai")), yaml.getBoolean("cross_server.enabled", true), forwarded,
                Collections.unmodifiableMap(channels), TestTeams.Settings.read(section(yaml, "teams")));
        }

        /** 冷场对话：每段是几行 "A: 说法1|说法2"，A B C 表示由谁说。 */
        static List<List<Step>> dialogues(List<?> all, String where) {
            var dialogues = new ArrayList<List<Step>>();
            int index = 0;
            for (Object raw : all) {
                String at = where + "[" + index++ + "]";
                if (!(raw instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(at + " must be a list of lines");
                var steps = new ArrayList<Step>();
                for (Object item : list) {
                    String text = String.valueOf(item).strip();
                    if (text.length() < 3 || text.charAt(1) != ':' || "ABC".indexOf(Character.toUpperCase(text.charAt(0))) < 0) {
                        throw new IllegalArgumentException(at + ": each line must start with A:, B: or C: (" + text + ")");
                    }
                    var texts = new ArrayList<String>();
                    for (String option : text.substring(2).split("\\|")) if (!option.isBlank()) texts.add(option.strip());
                    if (texts.isEmpty()) throw new IllegalArgumentException(at + ": empty line " + text);
                    steps.add(new Step("ABC".indexOf(Character.toUpperCase(text.charAt(0))), List.copyOf(texts)));
                }
                dialogues.add(List.copyOf(steps));
            }
            return List.copyOf(dialogues);
        }

        private static ConfigurationSection section(ConfigurationSection yaml, String path) {
            ConfigurationSection section = yaml.getConfigurationSection(path);
            return section != null ? section : yaml.createSection(path);
        }

        private static long positive(ConfigurationSection yaml, String path, long fallback) {
            long value = yaml.getLong(path, fallback);
            if (value < 1) throw new IllegalArgumentException("tab_chat.yml: " + path + " must be at least 1");
            return value;
        }

        private static int percent(Object value, int fallback, String where) {
            if (value == null) return fallback;
            if (!(value instanceof Number number) || number.intValue() < 0 || number.intValue() > 100) {
                throw new IllegalArgumentException(where + " must be a whole number 0-100");
            }
            return number.intValue();
        }

        /** [最短, 最长] 秒，转成毫秒；只写一个数时两者相同。 */
        private static long[] seconds(ConfigurationSection yaml, String path, int min, int max, int lowest, int highest) {
            int[] range = ints(yaml.get(path), min, max, lowest, highest, "tab_chat.yml: " + path);
            return new long[] {range[0] * 1000L, range[1] * 1000L};
        }

        /** [最短, 最长] 秒，可以带小数，转成毫秒；只写一个数时两者相同。 */
        private static long[] millis(ConfigurationSection yaml, String path, double min, double max, double lowest, double highest) {
            Object value = yaml.get(path);
            if (value == null) return new long[] {Math.round(min * 1000), Math.round(max * 1000)};
            List<?> list = value instanceof List<?> items ? items : List.of(value);
            String where = "tab_chat.yml: limits.burst." + path;
            if (list.isEmpty() || list.size() > 2 || !list.stream().allMatch(Number.class::isInstance)) {
                throw new IllegalArgumentException(where + " must be [min, max]");
            }
            double low = ((Number) list.getFirst()).doubleValue(), high = ((Number) list.getLast()).doubleValue();
            if (low < lowest || high > highest || low > high) {
                throw new IllegalArgumentException(where + " must be between " + lowest + " and " + highest + ", min <= max");
            }
            return new long[] {Math.round(low * 1000), Math.round(high * 1000)};
        }

        private static int[] ints(Object value, int min, int max, int lowest, int highest, String where) {
            if (value == null) return new int[] {min, max};
            List<?> list = value instanceof List<?> items ? items : List.of(value);
            if (list.isEmpty() || list.size() > 2 || !list.stream().allMatch(Number.class::isInstance)) {
                throw new IllegalArgumentException(where + " must be [min, max]");
            }
            int low = ((Number) list.getFirst()).intValue(), high = ((Number) list.getLast()).intValue();
            if (low < lowest || high > highest || low > high) {
                throw new IllegalArgumentException(where + " must be between " + lowest + " and " + highest + ", min <= max");
            }
            return new int[] {low, high};
        }

        private static List<String> strings(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            var result = new ArrayList<String>();
            for (Object item : list) if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).strip());
            return List.copyOf(result);
        }

        private static List<Keyword> keywords(Object value, String where) {
            var result = new ArrayList<Keyword>();
            for (String text : strings(value)) result.add(Keyword.of(text));
            return List.copyOf(result);
        }
    }

    /** 在线的测试假人：key 为小写名字；server 为它所在的其他子服，在本服时为 null（这时才有 group）；team 为小队，不在小队里时为空。 */
    record Speaker(String key, String name, UUID id, String group, boolean talkative, String persona, String server, String team) {
        boolean teammate(Speaker other) { return !team.isEmpty() && team.equals(other.team) && !key.equals(other.key); }
    }

    /** 聊天记录中的一条。 */
    record Said(String name, boolean real, String text, long at) { }

    /**
     * 排队中的一句话。after 在发出后调用，参数是当时在线的测试假人。farewell：其他子服的测试假人说完这句就下线。
     * burst 不为 0 时属于一次“一窝蜂”：第一句照常排队，之后同一次的几句紧跟着发出，不等句间间隔、不占名额。
     */
    private record Line(String speaker, String text, int priority, long ready, long expire, Consumer<Map<String, Speaker>> after,
                        boolean farewell, long burst) { }

    /** burst：这个人是哪次一窝蜂里第一个回的；那次后面的人不顶替它。 */
    private record Partner(String speaker, long until, long burst) { }

    private final DummyPlayers plugin;
    private final TestAi ai;
    private final TestChatNetwork relay;
    private volatile Settings settings;
    private final List<Line> pending = new ArrayList<>();
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private final ArrayDeque<Said> history = new ArrayDeque<>();
    private final ArrayDeque<String> recentTexts = new ArrayDeque<>();
    /** 测试假人最近说过的话（给 AI 看，免得重复）。 */
    private final ArrayDeque<Said> spoken = new ArrayDeque<>();
    /** 最近给过 AI 的话题方向、最近用过的冷场对话，尽量不重复。 */
    private final ArrayDeque<String> recentTopics = new ArrayDeque<>();
    private final ArrayDeque<List<Step>> recentDialogues = new ArrayDeque<>();
    /** 小队上一次内部聊天的时间。 */
    private final Map<String, Long> teamChatAt = new HashMap<>();
    /** 上一次定时器看到的在线测试假人；在 TabBenchmark 的锁里（不能调用 online()）判断队友在不在线时用。 */
    private volatile Map<String, Speaker> lastOnline = Map.of();
    private final Map<String, Long> spokeAt = new HashMap<>();
    private final Map<String, Long> ruleAt = new HashMap<>();
    private final Map<UUID, Partner> partners = new HashMap<>();
    /** 测试假人刚问过的问题（如“有人一起下矿吗”）：真人接话时由提问的人回应。 */
    private String asker;
    private long askUntil, nextAllowed, nextIdle, welcomeAt;
    /** 正在进行的一窝蜂和它的下一句最早什么时候发；bursts 为编号计数。 */
    private long activeBurst, burstNext, bursts;
    private String lastSpeaker;
    private boolean idleBusy;
    private ScheduledTask task;
    /** 交给服务器聊天流程时用的测试假人对象（不在世界里、不在玩家列表里），按 UUID 缓存。 */
    private final Map<UUID, ServerPlayer> voices = new ConcurrentHashMap<>();
    private volatile long lastUserWarning, lastChannelWarning;
    private volatile String lastChannelProblem = "";
    private volatile boolean warnedUndelivered;
    private volatile String lastNotice = "";
    private volatile long lastNoticeWarning;
    private volatile boolean chatModeBroken;
    private volatile Boolean lastVoiceCancelled;

    TestChat(DummyPlayers plugin) {
        this.plugin = plugin;
        this.ai = new TestAi(plugin);
        this.relay = new TestChatNetwork(plugin, this);
    }

    TestAi ai() { return ai; }

    TestChatNetwork relay() { return relay; }

    Settings settings() { return settings; }

    void configure(Settings next) {
        settings = next;
        ai.configure(next.ai());
        chatModeBroken = false;
    }

    void start() {
        if (task == null) task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, unused -> tick(), 1, 500, TimeUnit.MILLISECONDS);
        relay.start();
    }

    void stop() {
        if (task != null) { task.cancel(); task = null; }
        relay.stop();
        synchronized (this) { pending.clear(); }
        voices.clear();
    }

    /** 本次运行实际的发送方式：chat 交给服务器的聊天流程，否则按 format 发送。 */
    boolean viaServerChat() {
        Settings s = settings;
        if (s == null || chatModeBroken) return false;
        return s.sendMode().equals("chat") || s.sendMode().equals("auto") && formattingPlugin() != null;
    }

    /** 已启用的负责排版的聊天插件名字；没有时为 null。 */
    static String formattingPlugin() {
        for (String name : FORMATTING) if (Bukkit.getPluginManager().isPluginEnabled(name)) return name;
        return null;
    }

    private boolean listenCancelled() {
        String value = settings.cancelledChat();
        if (value.equals("auto")) {
            for (String name : REBROADCASTING) if (Bukkit.getPluginManager().isPluginEnabled(name)) return true;
            return false;
        }
        return value.equals("true");
    }

    // ══ 事件 ══════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.testProfiles().ready() && plugin.testProfiles().profile(player.getName()) != null
            && voices.containsKey(player.getUniqueId())) {
            lastVoiceCancelled = event.isCancelled(); // 测试假人自己通过聊天流程说的话
            return;
        }
        if (!real(player) || event.isCancelled() && !listenCancelled()) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        heard(player.getUniqueId(), player.getName(), text);
        relay.heard(player.getUniqueId(), player.getName(), text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!real(player)) return;
        relay.joined(player);
        boolean firstTime = !player.hasPlayedBefore();
        // 跨服时由负责安排的子服决定要不要欢迎；从别的子服换过来的不欢迎
        if (!relay.leading()) relay.realJoined(player.getUniqueId(), player.getName(), firstTime);
        else if (!relay.switched(player.getUniqueId(), plugin.serverId)) joined(player.getUniqueId(), player.getName(), firstTime);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer() instanceof CraftPlayer craft && !(craft.getHandle() instanceof NativeDummy)) relay.quit(event.getPlayer());
    }

    static boolean real(Player player) {
        return player instanceof CraftPlayer craft && !(craft.getHandle() instanceof NativeDummy) && player.isOnline();
    }

    /** 在线真人数；跨服时为全网的。 */
    private int realOnline() { return relay.realOnline(); }

    // ══ 在线的测试假人 ════════════════════════════════════

    /**
     * 由本服安排说话的测试假人：本服 TAB 中的，跨服时再加上其他子服的；跨服时由其他子服负责安排时为空。
     * 不能在持有本对象的锁时调用（TabBenchmark 会在它的锁里调用本类）。
     */
    private Map<String, Speaker> online() {
        var result = new HashMap<String, Speaker>();
        Settings s = settings;
        if (s == null || !plugin.testProfiles().ready() || !relay.leading()) return result;
        result.putAll(local());
        relay.remoteHosts().forEach((server, entries) -> entries.forEach((id, name) -> {
            String key = name.toLowerCase(Locale.ROOT);
            var profile = plugin.testProfiles().profile(name);
            result.putIfAbsent(key, new Speaker(key, name, id, "", talkative(id), persona(id), server, team(profile)));
        }));
        return result;
    }

    /** 本服 TAB 中的测试假人。 */
    private Map<String, Speaker> local() {
        var result = new HashMap<String, Speaker>();
        if (settings == null || !plugin.testProfiles().ready()) return result;
        for (var shown : plugin.benchmark().entries().values()) {
            var profile = plugin.testProfiles().profile(shown.name());
            if (profile == null) continue;
            String key = profile.name().toLowerCase(Locale.ROOT);
            result.put(key, new Speaker(key, profile.name(), profile.id(), profile.group(), talkative(profile.id()), persona(profile.id()), null,
                team(profile)));
        }
        return result;
    }

    /** 档案里的小队；teams 关闭时当作不在小队里（档案里的小队留着，重新开启后照旧）。 */
    private String team(Roster.TestProfile profile) {
        Settings s = settings;
        return profile == null || s == null || !s.teams().enabled() ? "" : profile.team();
    }

    /** 由 UUID 决定的 0–99 的固定数，salt 不同得到不同的特征。 */
    static int trait(UUID id, int salt) {
        long mixed = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits() + salt * 0xC2B2AE3D27D4EB4FL;
        mixed ^= mixed >>> 31;
        return (int) Math.floorMod(mixed, 100L);
    }

    boolean talkative(UUID id) { Settings s = settings; return s != null && trait(id, 1) < s.talkativePercent(); }

    String persona(UUID id) {
        Settings s = settings;
        var personas = s == null ? List.<String>of() : s.ai().personas();
        return personas.isEmpty() ? "" : personas.get(trait(id, 2) % personas.size());
    }

    /** 测试假人在聊天插件里说话的频道（tab_chat.yml 的 channels，按比例由 UUID 固定分配）；没设置时为 null。 */
    String channel(UUID id) {
        Settings s = settings;
        if (s == null || s.channels().isEmpty()) return null;
        int total = 0;
        for (int weight : s.channels().values()) total += weight;
        int pick = (int) Math.floorMod((long) trait(id, 11) * 7919 + trait(id, 12), (long) total);
        for (var entry : s.channels().entrySet()) {
            pick -= entry.getValue();
            if (pick < 0) return entry.getKey();
        }
        return null;
    }

    /** 测试假人现在在游戏里做什么（给 AI 看）：每人每 30～60 分钟换一件，由 UUID 和时间算出，不用存。 */
    String activity(UUID id) {
        Settings s = settings;
        var activities = s == null ? List.<String>of() : s.ai().activities();
        if (activities.isEmpty()) return "";
        long period = 30 + trait(id, 5) % 31;
        long bucket = (System.currentTimeMillis() / 60_000 + trait(id, 6)) / period;
        long mixed = (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) + bucket * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 31;
        return activities.get((int) Math.floorMod(mixed * 0xBF58476D1CE4E5B9L >>> 17, (long) activities.size()));
    }

    /** 打字习惯（给 AI 看），按 UUID 固定。 */
    static String habit(UUID id) { return TestAi.HABITS.get(trait(id, 13) % TestAi.HABITS.size()); }

    /** 心情（给 AI 看）：每人每 20～40 分钟换一种。 */
    static String mood(UUID id) {
        long period = 20 + trait(id, 14) % 21;
        long bucket = (System.currentTimeMillis() / 60_000 + trait(id, 15)) / period;
        long mixed = (id.getLeastSignificantBits() ^ id.getMostSignificantBits() >>> 7) + bucket * 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 29;
        return TestAi.MOODS.get((int) Math.floorMod(mixed * 0x94D049BB133111EBL >>> 13, (long) TestAi.MOODS.size()));
    }

    /** 给 AI 看的一个测试假人。小队一栏写上小队名、在线的队友和小队最近在做的事。 */
    private TestAi.Candidate candidate(Speaker speaker, String note, Map<String, Speaker> online) {
        String team = "";
        if (!speaker.team().isEmpty()) {
            var mates = new ArrayList<String>();
            for (Speaker other : online.values()) if (speaker.teammate(other)) mates.add(other.name());
            String project = TestTeams.project(speaker.team(), settings.teams().projects());
            team = speaker.team() + "，" + (mates.isEmpty() ? "队友都不在线" : "在线的队友：" + String.join("、", mates))
                + (project.isEmpty() ? "" : "，小队最近在" + project);
        }
        return new TestAi.Candidate(speaker.name(), speaker.persona(), habit(speaker.id()), mood(speaker.id()), activity(speaker.id()), team, note);
    }

    // ══ 定时器 ════════════════════════════════════════════

    private void tick() {
        Settings s = settings;
        if (s == null) return;
        Map<String, Speaker> online = online();
        lastOnline = online;
        int real = realOnline();
        Line deliver = null;
        Runnable idleCall = null;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (!s.enabled() || online.isEmpty()) { pending.clear(); return; }
            // 其他子服的测试假人刚进服时，它的 TAB 条目可能比要说的话晚到，打完字时还不在才作废
            pending.removeIf(line -> line.expire() < now || line.ready() <= now && !online.containsKey(line.speaker()));
            while (!sent.isEmpty() && sent.peekFirst() <= now - s.window()) sent.pollFirst();
            boolean open = now >= nextAllowed, burstOpen = activeBurst != 0 && now >= burstNext;
            if ((open || burstOpen) && !pending.isEmpty()) {
                int capacity = capacity(real, now);
                Line best = null;
                boolean bestFollows = false;
                for (Line line : pending) {
                    if (line.ready() > now) continue;
                    // 一窝蜂里跟在后面的几句：只等一窝蜂自己的短间隔，不看名额
                    boolean follows = line.burst() != 0 && line.burst() == activeBurst;
                    if (follows ? !burstOpen : !open) continue;
                    // 被 @ 的回复总留一个名额；冷场闲聊只用一半名额，给回复真人留出余地
                    int allowed = line.priority() >= MENTION ? capacity + 1 : line.priority() == IDLE ? Math.max(1, (capacity + 1) / 2) : capacity;
                    if (!follows && sent.size() >= allowed) continue;
                    boolean better = best == null || follows && !bestFollows || follows == bestFollows
                        && (line.priority() > best.priority() || line.priority() == best.priority() && line.ready() < best.ready());
                    if (better) { best = line; bestFollows = follows; }
                }
                if (best != null) {
                    pending.remove(best);
                    record(best, online.get(best.speaker()).name(), now, real);
                    deliver = best;
                }
            }
            if (real > 0 && pending.isEmpty() && !idleBusy && now >= nextIdle) {
                nextIdle = now + quiet(real);
                // 冷场闲聊的名额已经用完时不开话题，免得 AI 白白生成说不出去的话
                boolean room = sent.size() < Math.max(1, (capacity(real, now) + 1) / 2);
                if (room && !(s.dontInterrupt() && realTalking(now))) idleCall = startIdle(online, now, real);
            }
            if (real == 0) nextIdle = Math.max(nextIdle, now + s.quietMin());
        }
        if (deliver != null) {
            Speaker speaker = online.get(deliver.speaker());
            if (speaker.server() == null) send(speaker, deliver.text(), false);
            else relay.say(speaker.server(), speaker.name(), deliver.text(), deliver.farewell(), false);
            if (deliver.after() != null) synchronized (this) { deliver.after().accept(online); }
        }
        if (idleCall != null) idleCall.run();
    }

    /** 当前窗口允许的句数：基础 + 每多一个在线真人加一点；真人说得多时，按占比放宽；不超过 max_messages。 */
    private int capacity(int real, long now) {
        Settings s = settings;
        int realRecent = 0;
        for (Said said : history) if (said.real() && said.at() > now - s.window()) realRecent++;
        double base = s.baseMessages() + s.perPlayer() * Math.max(0, real - 1);
        double byShare = realRecent * s.sharePercent() / (100.0 - s.sharePercent());
        return (int) Math.max(1, Math.min(s.maxMessages(), Math.floor(Math.max(base, byShare))));
    }

    /** 真人之间正在聊天：最近 30 秒内有两个不同的真人说过话，而且没有测试假人插话。 */
    private boolean realTalking(long now) {
        String first = null;
        for (Iterator<Said> it = history.descendingIterator(); it.hasNext(); ) {
            Said said = it.next();
            if (said.at() < now - 30_000) break;
            if (!said.real()) return false;
            if (first == null) first = said.name();
            else if (!first.equals(said.name())) return true;
        }
        return false;
    }

    /** 冷场多久后开话题：在线真人越多，冷场的时间越短。 */
    private long quiet(int real) {
        Settings s = settings;
        long value = s.quietMax() > s.quietMin() ? ThreadLocalRandom.current().nextLong(s.quietMin(), s.quietMax() + 1) : s.quietMin();
        return (long) (value / Math.sqrt(Math.max(1, real)));
    }

    /** 记下即将发出的一句，并安排下一句的最早时间。聊天区热闹时间隔缩短。 */
    private void record(Line line, String name, long now, int real) {
        Settings s = settings;
        sent.addLast(now);
        int realRecent = 0;
        for (Said said : history) if (said.real() && said.at() > now - s.window()) realRecent++;
        double factor = realRecent >= 6 ? 0.5 : realRecent >= 3 ? 0.75 : 1.0;
        long gap = s.gapMax() > s.gapMin() ? ThreadLocalRandom.current().nextLong(s.gapMin(), s.gapMax() + 1) : s.gapMin();
        nextAllowed = now + (long) (gap * factor);
        if (line.burst() != 0) { activeBurst = line.burst(); burstNext = now + between(s.burstGapMin(), s.burstGapMax()); }
        nextIdle = Math.max(nextIdle, now + quiet(real));
        spokeAt.put(line.speaker(), now);
        lastSpeaker = line.speaker();
        remember(new Said(name, false, line.text(), now));
        spoke(new Said(name, false, line.text(), now));
        if (question(line.text().toLowerCase(Locale.ROOT)) && line.priority() == IDLE) { asker = line.speaker(); askUntil = now + 40_000; }
    }

    private void remember(Said said) {
        history.addLast(said);
        while (history.size() > 40) history.pollFirst();
    }

    /** 记下测试假人说过的一句：挑语料、过滤 AI 的话时避开，也告诉 AI 别再说。 */
    private void spoke(Said said) {
        recentTexts.addLast(said.text());
        while (recentTexts.size() > 40) recentTexts.pollFirst();
        spoken.addLast(said);
        while (spoken.size() > 40) spoken.pollFirst();
    }

    // ══ 发送 ══════════════════════════════════════════════

    /** 本服的测试假人说一句，再告诉其他子服。manual 为 /afp test say。 */
    private void send(Speaker speaker, String text, boolean manual) {
        if (speaker == null) return;
        plugin.getLogger().info("[TAB test] " + speaker.name() + ": " + text);
        boolean viaChat = false;
        // 聊天插件在聊天事件里就把话发出去了，要从交给它之前开始算
        long since = System.currentTimeMillis();
        if (viaServerChat()) {
            try {
                viaChat = chat(speaker, text);
            } catch (RuntimeException | LinkageError error) {
                chatModeBroken = true;
                plugin.getLogger().log(Level.WARNING, "Could not send test player chat through the server chat; using tab_chat.yml format until /afp reload", error);
            }
        }
        if (!viaChat) sendFormatted(speaker, text);
        else if (relay.watching()) {
            // 聊天插件可能悄悄没发出去（频道权限、发言条件、代理转发失败等）：2 秒内本服没有真人收到这句，改按 format 发
            Bukkit.getAsyncScheduler().runDelayed(plugin, unused -> {
                if (relay.delivered(speaker.name(), text, since)) return;
                String notice = voices.get(speaker.id()) instanceof ServerPlayer voice && voice.getBukkitEntity() instanceof VoicePlayer player
                    ? player.noticeSince(since) : "";
                long now = System.currentTimeMillis();
                if (!warnedUndelivered || !notice.isEmpty() && !notice.equals(lastNotice) && now - lastNoticeWarning > 60_000) {
                    warnedUndelivered = true;
                    lastNotice = notice;
                    lastNoticeWarning = now;
                    String chatPlugin = formattingPlugin();
                    String channel = channel(speaker.id());
                    plugin.getLogger().warning("Test player chat handed to " + (chatPlugin == null ? "the server chat" : chatPlugin)
                        + " did not reach any player within 2 seconds; sending such lines with the format in tab_chat.yml instead."
                        + (notice.isEmpty() ? "" : " " + (chatPlugin == null ? "The server" : chatPlugin) + " told " + speaker.name() + ": \"" + notice + "\".")
                        + " Check that test players may speak in " + (channel == null ? "the default channel" : "channel " + channel)
                        + " (their LuckPerms group needs its permissions), or set send_mode: format.");
                }
                sendFormatted(speaker, text);
            }, 2, TimeUnit.SECONDS);
        }
        relay.said(speaker.name(), speaker.group(), text, viaChat, manual);
    }

    /** 按 tab_chat.yml 的 format 发给本服的真人。 */
    private void sendFormatted(Speaker speaker, String text) {
        Component message = plugin.legacy(fill(settings.format(), speaker.id(), speaker.name(), speaker.group(), text));
        for (Player player : Bukkit.getOnlinePlayers()) if (real(player)) player.sendMessage(message);
    }

    /** 放消息内容的占位：PlaceholderAPI 解析完才换成内容，内容里的 % 不会被当成变量。 */
    private static final String MESSAGE = "\u0000message\u0000";

    /**
     * 填好给测试假人用的格式（tab_chat.yml 的 format、tab_test.yml 的进退服提示）：%prefix%、%name%、%message%，
     * 以及所有 PlaceholderAPI 变量。变量按这个测试假人解析，和它走聊天流程时聊天插件看到的一样（见 {@link VoicePlayer}）；
     * 解析前先准备好它的 LuckPerms 用户，%luckperms_suffix% 这类变量才有值。id 为 null 时只解析和玩家无关的变量。
     * 会等 LuckPerms 最多 3 秒，要在异步线程调用，也不能持有 TabBenchmark 的锁。
     */
    String fill(String format, UUID id, String name, String group, String message) {
        String line = format.replace("%prefix%", plugin.groupPrefix(group)).replace("%name%", name).replace("%message%", MESSAGE);
        if (line.indexOf('%') >= 0 && Papi.enabled()) {
            Player player = null;
            if (id != null) {
                if (!group.isBlank()) prepareUser(id, name, group);
                player = voice(id, name).getBukkitEntity();
            }
            line = Papi.apply(plugin.getLogger(), player, line).replace('§', '&');
        }
        return line.replace(MESSAGE, message);
    }

    /** 负责安排的子服让本服的测试假人说一句。它已经不在本服 TAB 中时不说。 */
    void speakHere(String name, String text, boolean manual) {
        Settings s = settings;
        if (s == null || !s.enabled()) return;
        send(local().get(name.toLowerCase(Locale.ROOT)), text, manual);
    }

    /** 其他子服用 /afp test say 让测试假人说了一句：本服负责安排时记下来，限流和上下文算上它。 */
    void heardTest(String name, String text) {
        if (!relay.leading()) return;
        synchronized (this) {
            long now = System.currentTimeMillis();
            sent.addLast(now);
            spokeAt.put(name.toLowerCase(Locale.ROOT), now);
            remember(new Said(name, false, text, now));
            spoke(new Said(name, false, text, now));
        }
    }

    /**
     * 交给服务器的聊天流程：和真人一样触发聊天事件，由聊天插件排版和转发。
     * 测试假人的对象不在世界里，也不在玩家列表里。消息被取消、而且没有会自己重新发送的聊天插件时，返回 false 改用 format。
     */
    private boolean chat(Speaker speaker, String text) {
        prepareUser(speaker.id(), speaker.name(), speaker.group());
        ServerPlayer voice = voice(speaker.id(), speaker.name());
        String channel = channel(speaker.id());
        if (channel != null) {
            String problem = ChatChannels.join(voice.getBukkitEntity(), channel);
            if (problem != null) warnChannel(problem);
        }
        lastVoiceCancelled = null;
        new ChatProcessor(MinecraftServer.getServer(), voice, PlayerChatMessage.unsigned(speaker.id(), text), true).process();
        Boolean cancelled = lastVoiceCancelled;
        return cancelled == null || !cancelled || listenCancelled();
    }

    /** 同一个原因每分钟最多提示一次。 */
    private void warnChannel(String problem) {
        long now = System.currentTimeMillis();
        if (problem.equals(lastChannelProblem) && now - lastChannelWarning < 60_000) return;
        lastChannelProblem = problem;
        lastChannelWarning = now;
        plugin.getLogger().warning("Could not move a test player to its chat channel (tab_chat.yml channels): " + problem
            + ". It speaks in the channel the chat plugin gives it.");
    }

    /** 测试假人说话、解析变量时用的玩家对象。它不在世界里，也不在玩家列表里。 */
    private ServerPlayer voice(UUID id, String name) {
        return voices.computeIfAbsent(id, unused -> {
            MinecraftServer server = MinecraftServer.getServer();
            var world = Bukkit.getWorlds().getFirst();
            ServerLevel level = ((CraftWorld) world).getHandle();
            var created = new ServerPlayer(server, level, plugin.skins().profile(id, name), ClientInformation.createDefault());
            // 借真人连接发插件消息、按 LuckPerms 回答权限，见 VoicePlayer；站在主世界出生点（聊天插件的坐标、世界变量）
            NativeDummy.useBukkitEntity(created, new VoicePlayer(plugin, server.server, created));
            var spawn = world.getSpawnLocation();
            created.setPos(spawn.getX(), spawn.getY(), spawn.getZ());
            return created;
        });
    }

    /**
     * 聊天插件和 PlaceholderAPI 按 LuckPerms 读取前缀：每次说话前确保测试假人的 UUID 有 LuckPerms 用户、在它的权限组里，
     * 并且已加载（不在线的用户会被 LuckPerms 定时卸载）。最多等 3 秒，失败时照样发出，只是可能没有前缀。
     */
    private void prepareUser(UUID id, String name, String group) {
        try {
            plugin.groups().prepareUser(id, name, group).get(3, TimeUnit.SECONDS);
        } catch (Exception error) {
            long now = System.currentTimeMillis();
            if (now - lastUserWarning < 60_000) return;
            lastUserWarning = now;
            plugin.getLogger().log(Level.WARNING, "Could not prepare the LuckPerms user of test player " + name, error);
        }
    }

    /** /afp test say：立即说一句，不受限流。测试假人在其他子服时由那个子服说。 */
    boolean say(String name, String text) {
        Speaker speaker = local().get(name.toLowerCase(Locale.ROOT));
        if (speaker == null) {
            String host = relay.host(name);
            if (host == null) return false;
            relay.say(host, name, text, false, true);
            return true;
        }
        if (relay.leading()) synchronized (this) {
            long now = System.currentTimeMillis();
            sent.addLast(now);
            spokeAt.put(speaker.key(), now);
            remember(new Said(speaker.name(), false, text, now));
            spoke(new Said(speaker.name(), false, text, now));
        }
        // 指令在玩家或控制台所在的线程执行；聊天流程（AsyncPlayerChatEvent）只能在异步线程触发
        Bukkit.getAsyncScheduler().runNow(plugin, unused -> send(speaker, text, true));
        return true;
    }

    /** /afp test chat 显示的状态。 */
    synchronized String[] status() {
        long now = System.currentTimeMillis();
        Settings s = settings;
        int recent = 0;
        for (long at : sent) if (at > now - s.window()) recent++;
        return new String[] {String.valueOf(pending.size()), String.valueOf(recent), String.valueOf(capacity(realOnline(), now)),
            String.valueOf(s.window() / 1000)};
    }

    // ══ 排队 ══════════════════════════════════════════════

    private long typing(String text) {
        Settings s = settings;
        double seconds = s.typingBase() + s.typingPerChar() * text.codePointCount(0, text.length())
            + ThreadLocalRandom.current().nextDouble() * s.typingRandom();
        return (long) (seconds * 1000);
    }

    /** 排队一句话：从 notBefore 开始打字，打完才能发出。 */
    private void queue(String speaker, String text, int priority, long notBefore, Consumer<Map<String, Speaker>> after) {
        queue(speaker, text, priority, notBefore, after, false);
    }

    private void queue(String speaker, String text, int priority, long notBefore, Consumer<Map<String, Speaker>> after, boolean farewell) {
        queue(speaker, text, priority, notBefore, after, farewell, 0);
    }

    /** burst 不为 0 时属于一次一窝蜂：几个人抢着说，打字时间减半。 */
    private void queue(String speaker, String text, int priority, long notBefore, Consumer<Map<String, Speaker>> after, boolean farewell,
                       long burst) {
        long typing = typing(text);
        long ready = Math.max(System.currentTimeMillis(), notBefore) + (burst != 0 ? typing / 2 : typing);
        pending.add(new Line(speaker, text, priority, ready, ready + settings.expire(), after, farewell, burst));
    }

    /** 按 limits.burst.chance 决定这次要不要一窝蜂：要时返回新的编号，否则为 0。 */
    private long rollBurst() { return roll(settings.burstChance()) ? ++bursts : 0; }

    /** 一窝蜂一共几个人说话。 */
    private int burstSize() { return (int) between(settings.burstMin(), settings.burstMax()); }

    private boolean busy(String speaker) {
        for (Line line : pending) if (line.speaker().equals(speaker)) return true;
        return false;
    }

    private boolean coolingDown(String speaker, long now) {
        Long at = spokeAt.get(speaker);
        return at != null && now - at < settings.speakerCooldown();
    }

    /** 挑一个会说话、不在冷却、没有排队的测试假人；尽量不挑上一句刚说话的人。 */
    private Speaker pick(Map<String, Speaker> online, long now, Set<String> exclude) { return pick(online, now, exclude, false); }

    /** crowd：一窝蜂时大家抢着说，不管冷却。 */
    private Speaker pick(Map<String, Speaker> online, long now, Set<String> exclude, boolean crowd) {
        var candidates = new ArrayList<Speaker>();
        for (Speaker speaker : online.values()) {
            if (!speaker.talkative() || exclude.contains(speaker.key()) || busy(speaker.key()) || !crowd && coolingDown(speaker.key(), now)) continue;
            candidates.add(speaker);
        }
        if (candidates.size() > 1) candidates.removeIf(speaker -> speaker.key().equals(lastSpeaker));
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static boolean roll(int percent) { return percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent; }

    private static long between(long low, long high) { return high > low ? ThreadLocalRandom.current().nextLong(low, high + 1) : low; }

    /** 从一组说法里挑一句，避开最近说过的。 */
    private String any(List<String> options) { return any(options, null); }

    /** taken 不为 null 时同时避开里面的（一窝蜂里别人刚挑的），挑到的加进去。 */
    private String any(List<String> options, Set<String> taken) {
        if (options.isEmpty()) return null;
        String choice = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            choice = options.get(ThreadLocalRandom.current().nextInt(options.size()));
            if (!recentTexts.contains(choice) && (taken == null || !taken.contains(choice))) break;
        }
        if (taken != null) taken.add(choice);
        return choice;
    }

    private String line(String pool) { return any(settings.lines().getOrDefault(pool, List.of())); }

    private String line(String pool, Set<String> taken) { return any(settings.lines().getOrDefault(pool, List.of()), taken); }

    private static String fill(String text, String player, String other) {
        if (text == null) return null;
        String result = text.replace("%player%", player == null ? "" : player).replace("%other%", other == null ? "" : other);
        return result.strip().replaceAll("\\s{2,}", " ");
    }

    private static boolean question(String lower) {
        return lower.contains("?") || lower.contains("？") || lower.endsWith("吗") || lower.endsWith("呢") || lower.contains("怎么")
            || lower.contains("咋") || lower.contains("哪") || lower.contains("谁") || lower.contains("什么") || lower.contains("为啥")
            || lower.contains("有没有") || lower.contains("有人");
    }

    private Speaker mentioned(String lower, Map<String, Speaker> online) {
        Speaker best = null;
        for (Speaker speaker : online.values()) {
            boolean named = lower.contains(speaker.key());
            if (!named) {
                // @ 名字的前几个字母也算，玩家经常只打一半
                int at = lower.indexOf('@');
                while (at >= 0 && !named) {
                    int end = at + 1;
                    while (end < lower.length() && (Character.isLetterOrDigit(lower.charAt(end)) || lower.charAt(end) == '_')) end++;
                    String typed = lower.substring(at + 1, end);
                    named = typed.length() >= 3 && speaker.key().startsWith(typed);
                    at = lower.indexOf('@', at + 1);
                }
            }
            if (named && (best == null || speaker.key().length() > best.key().length())) best = speaker;
        }
        return best;
    }

    // ══ 真人说话 ══════════════════════════════════════════

    void heard(UUID player, String name, String text) {
        Settings s = settings;
        if (s == null || !s.enabled() || text.isBlank()) return;
        Map<String, Speaker> online = online();
        if (online.isEmpty()) return;
        Runnable call = null;
        synchronized (this) {
            long now = System.currentTimeMillis();
            remember(new Said(name, true, text, now));
            nextIdle = Math.max(nextIdle, now + quiet(realOnline()));
            String lower = text.toLowerCase(Locale.ROOT);
            for (Keyword keyword : s.ignore()) if (keyword.in(lower)) return;
            Speaker mentioned = mentioned(lower, online);
            if (ai.usable() && ai.reserve()) call = aiReply(player, name, text, mentioned, online, now);
            else rules(player, name, lower, mentioned, online, now);
        }
        if (call != null) call.run();
    }

    private void rules(UUID player, String name, String lower, Speaker mentioned, Map<String, Speaker> online, long now) {
        Settings s = settings;
        Rule rule = null;
        for (Rule candidate : s.rules()) {
            if (candidate.keywords().stream().noneMatch(keyword -> keyword.in(lower))) continue;
            rule = candidate;
            break;
        }
        boolean ruleReady = rule != null && now - ruleAt.getOrDefault(rule.key(), 0L) >= (rule.cooldown() >= 0 ? rule.cooldown() : s.ruleCooldown());
        if (mentioned != null) {
            if (!roll(s.mention())) return;
            String text = ruleReady ? any(rule.lines()) : line("mention");
            if (ruleReady) ruleAt.put(rule.key(), now);
            reply(mentioned, fill(text, name, null), player, MENTION, now + between(500, 2500));
            return;
        }
        Partner partner = partners.get(player);
        Speaker partnerSpeaker = partner != null && partner.until() > now ? online.get(partner.speaker()) : null;
        boolean interrupting = s.dontInterrupt() && realTalking(now) && partnerSpeaker == null;
        if (rule != null) {
            if (!ruleReady) return;
            ruleAt.put(rule.key(), now);
            if (!roll(interrupting ? rule.chance() / 2 : rule.chance())) return;
            // 一窝蜂：几个人抢着回，各说各的；真人之间正在聊天时不起哄
            long burst = interrupting ? 0 : rollBurst();
            int count = burst != 0 ? Math.max(burstSize(), rule.minResponders()) : (int) between(rule.minResponders(), rule.maxResponders());
            var used = new HashSet<String>();
            var texts = new HashSet<String>();
            for (int index = 0; index < count; index++) {
                Speaker who = index == 0 && partnerSpeaker != null && !busy(partnerSpeaker.key()) ? partnerSpeaker : pick(online, now, used, burst != 0);
                if (who == null) break;
                used.add(who.key());
                long at = burst != 0 ? now + between(300, 1500) + index * between(200, 900) : now + between(500, 2000) + index * between(1500, 5000);
                reply(who, fill(any(rule.lines(), texts), name, null), player, REPLY, at, burst);
            }
            return;
        }
        if (interrupting) return;
        boolean asked = question(lower);
        if (partnerSpeaker != null && !busy(partnerSpeaker.key()) && roll(s.followUp())) {
            reply(partnerSpeaker, fill(line(asked ? "unknown_question" : "follow_up"), name, null), player, REPLY, now + between(500, 2000));
            return;
        }
        if (asker != null && askUntil > now && online.containsKey(asker) && !busy(asker) && roll(s.followUp())) {
            reply(online.get(asker), fill(line("ask_follow_up"), name, null), player, REPLY, now + between(500, 2000));
            asker = null;
            return;
        }
        if (asked && roll(s.question())) {
            Speaker who = pick(online, now, Set.of());
            if (who != null) reply(who, fill(line("unknown_question"), name, null), player, REPLY, now + between(1000, 3000));
        }
    }

    /** 回复真人：排队，并记下正在和这个真人对话的测试假人（一窝蜂时是第一个回的人）。 */
    private void reply(Speaker speaker, String text, UUID player, int priority, long notBefore) {
        reply(speaker, text, player, priority, notBefore, 0);
    }

    private void reply(Speaker speaker, String text, UUID player, int priority, long notBefore, long burst) {
        if (text == null || text.isBlank()) return;
        queue(speaker.key(), text, priority, notBefore, null, false, burst);
        Partner partner = partners.get(player);
        boolean first = burst == 0 || partner == null || partner.until() <= System.currentTimeMillis() || partner.burst() != burst;
        if (first) partners.put(player, new Partner(speaker.key(), System.currentTimeMillis() + settings.conversation(), burst));
    }

    // ══ 欢迎、进服、下线 ══════════════════════════════════

    void joined(UUID player, String name, boolean firstTime) {
        Settings s = settings;
        if (s == null || !s.enabled()) return;
        Map<String, Speaker> online = online();
        if (online.isEmpty()) return;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now - welcomeAt < s.welcomeCooldown() || !roll(s.welcome())) return;
            Speaker who = pick(online, now, Set.of());
            if (who == null) return;
            welcomeAt = now;
            String pool = firstTime ? "welcome_new" : "welcome";
            long burst = rollBurst();
            if (burst != 0) {
                // 一窝蜂：几个人一起欢迎
                long start = now + between(2000, 5000);
                var used = new HashSet<String>();
                var texts = new HashSet<String>();
                int count = burstSize();
                for (int index = 0; index < count; index++) {
                    Speaker one = index == 0 ? who : pick(online, now, used, true);
                    if (one == null) break;
                    used.add(one.key());
                    String text = fill(line(pool, texts), name, null);
                    if (text != null) queue(one.key(), text, GREET, start + index * between(200, 900), null, false, burst);
                }
                return;
            }
            String text = fill(line(pool), name, null);
            if (text == null) return;
            // 刚进服的人要先加载一下，晚几秒再欢迎；偶尔再有一个人跟着欢迎
            queue(who.key(), text, GREET, now + between(2000, 6000), next -> {
                if (!roll(s.answer() / 2)) return;
                Speaker second = pick(next, System.currentTimeMillis(), Set.of(who.key()));
                String again = fill(line(firstTime ? "welcome_new" : "welcome"), name, null);
                if (second != null && again != null) queue(second.key(), again, GREET, System.currentTimeMillis() + between(1000, 3000), null);
            });
        }
    }

    /** 测试假人自己进服：偶尔打个招呼，另一个人回应。由 TabBenchmark 调用（它持有自己的锁）；跨服时由负责安排的子服决定。 */
    void testJoined(String name, UUID id) {
        if (relay.leading()) greet(name, id);
        else relay.testJoined(name, id);
    }

    /**
     * 由本服安排测试假人进服后的招呼。其他子服转来的直接调用这里，不再转出去。
     * 有队友在线时更常打招呼（潜水的也会），说的是 lines.team_join_greet，队友先接话。
     */
    void greet(String name, UUID id) {
        Settings s = settings;
        if (s == null || !s.enabled() || !relay.leading() || realOnline() == 0) return;
        String mate = mateOnline(name);
        if (!talkative(id) && mate == null || !roll(mate != null ? Math.min(100, s.joinGreet() * 2) : s.joinGreet())) return;
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (this) {
            boolean team = mate != null && s.lines().containsKey("team_join_greet") && roll(60);
            String text = fill(line(team ? "team_join_greet" : "join_greet"), null, mate == null ? null : Similarity.nick(mate));
            if (text == null) return;
            long burst = team ? 0 : rollBurst();
            int chance = mate != null ? Math.min(100, s.answer() + 30) : s.answer();
            queue(key, text, IDLE, System.currentTimeMillis() + between(3000, 15000), online -> {
                if (burst != 0 || roll(chance)) answers(online, key, "join_reply", name, 1000, 3000, burst);
            }, false, burst);
        }
    }

    /** 一个在线的队友（名字）；没有时为 null。用上一次定时器看到的在线名单，可以在 TabBenchmark 的锁里调用。 */
    private String mateOnline(String name) {
        if (!settings.teams().enabled()) return null;
        var mates = new ArrayList<String>();
        Map<String, Speaker> online = lastOnline;
        for (var profile : plugin.testProfiles().teammates(name)) {
            if (online.containsKey(profile.name().toLowerCase(Locale.ROOT))) mates.add(profile.name());
        }
        return mates.isEmpty() ? null : mates.get(ThreadLocalRandom.current().nextInt(mates.size()));
    }

    /**
     * speaker 刚说完一句（进服打招呼、下线道别），其他人回应：平时一个人，一窝蜂时几个人抢着回。
     * 有队友在线时队友先回，说 lines 里 team_ 开头的那一类（如 team_join_reply），没有这一类时说普通的。
     * 在 tick 发出那句之后调用（持有本对象的锁）。
     */
    private void answers(Map<String, Speaker> online, String speaker, String pool, String name, long delayMin, long delayMax, long burst) {
        long now = System.currentTimeMillis();
        var used = new HashSet<String>(Set.of(speaker));
        var texts = new HashSet<String>();
        int count = burst != 0 ? burstSize() - 1 : 1;
        Speaker self = online.get(speaker);
        for (int index = 0; index < count; index++) {
            Speaker other = null;
            if (index == 0 && self != null) {
                var mates = new ArrayList<Speaker>();
                for (Speaker candidate : online.values()) if (self.teammate(candidate) && !busy(candidate.key())) mates.add(candidate);
                if (!mates.isEmpty()) other = mates.get(ThreadLocalRandom.current().nextInt(mates.size()));
            }
            boolean mate = other != null;
            if (other == null) other = pick(online, now, used, burst != 0);
            if (other == null) break;
            used.add(other.key());
            String chosen = mate && settings.lines().containsKey("team_" + pool) ? "team_" + pool : pool;
            String answer = fill(line(chosen, texts), mate ? Similarity.nick(name) : name, null);
            long at = burst != 0 ? now + between(200, 800) + index * between(200, 900) : now + between(delayMin, delayMax);
            if (answer != null) queue(other.key(), answer, GREET, at, null, false, burst);
        }
    }

    /**
     * 测试假人下线前说一声。说出口后调用 leave（另一个线程，几秒后），返回 false 时直接下线。
     * 由 TabBenchmark 调用（它持有自己的锁），leave 不能同步调用回去。
     */
    boolean farewell(String name, UUID id, Runnable leave) {
        Settings s = settings;
        if (s == null || !s.enabled()) return false;
        String mate = relay.leading() ? mateOnline(name) : null;
        if (!talkative(id) && mate == null || !roll(mate != null ? Math.min(100, s.farewell() * 2) : s.farewell())) return false;
        if (!relay.leading()) return relay.farewell(name, id, leave);
        if (realOnline() == 0) return false;
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (this) {
            String text = line("farewell");
            if (text == null) return false;
            long burst = mate != null ? 0 : rollBurst();
            int chance = mate != null ? Math.min(100, s.answer() + 30) : s.answer();
            queue(key, text, GREET, System.currentTimeMillis(), online -> {
                Bukkit.getAsyncScheduler().runDelayed(plugin, unused -> leave.run(), between(3000, 9000), TimeUnit.MILLISECONDS);
                if (burst != 0 || roll(chance)) answers(online, key, "farewell_reply", name, 500, 2000, burst);
            }, false, burst);
            return true;
        }
    }

    /** 其他子服的测试假人要下线，请本服安排它说一声；说出口时它所在的子服让它下线。结果为是否排上了队。 */
    boolean remoteFarewell(String name, UUID id) {
        Settings s = settings;
        if (s == null || !s.enabled() || !relay.leading() || realOnline() == 0) return false;
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (this) {
            String text = line("farewell");
            if (text == null) return false;
            long burst = rollBurst();
            queue(key, text, GREET, System.currentTimeMillis(), online -> {
                if (burst != 0 || roll(s.answer())) answers(online, key, "farewell_reply", name, 500, 2000, burst);
            }, true, burst);
            return true;
        }
    }

    // ══ 冷场闲聊 ══════════════════════════════════════════

    /** 开一个话题：按 teams.chat_percent 先看有没有小队可以内部聊天；AI 可用时交给 AI（返回要在锁外执行的调用），否则按语料。 */
    private Runnable startIdle(Map<String, Speaker> online, long now, int real) {
        Settings s = settings;
        if (s.teams().enabled() && roll(s.teams().chatPercent())) {
            List<Speaker> team = teamCast(online, now);
            if (team != null) return teamIdle(team, online, now, real);
        }
        var talkers = new ArrayList<Speaker>();
        for (Speaker speaker : online.values()) if (speaker.talkative() && !busy(speaker.key()) && !coolingDown(speaker.key(), now)) talkers.add(speaker);
        if (talkers.isEmpty()) return null;
        if (ai.usable() && ai.reserve()) {
            idleBusy = true;
            Collections.shuffle(talkers);
            var cast = new ArrayList<TestAi.Candidate>();
            for (Speaker speaker : talkers.subList(0, Math.min(6, talkers.size()))) cast.add(candidate(speaker, "", online));
            var context = List.copyOf(history);
            var said = List.copyOf(spoken);
            var topics = topics(3);
            // 一窝蜂要先定下来：让 AI 一次给出几个人抢着说的几句
            long burst = cast.size() >= 3 ? rollBurst() : 0;
            return () -> ai.idle(cast, context, said, s.ai().maxLines(), burst != 0, topics, real).whenComplete((lines, error) -> {
                synchronized (this) {
                    idleBusy = false;
                    if (error == null && lines != null) chain(fresh(lines), 0, IDLE, null, System.currentTimeMillis(), burst);
                    else if (error != null) ruleIdle(talkers, System.currentTimeMillis());
                }
            });
        }
        ruleIdle(talkers, now);
        return null;
    }

    /** 挑一个可以内部聊天的小队：至少两个队员在线、没在排队，至少一个不在冷却；5 分钟内聊过的小队先不挑。没有时为 null。 */
    private List<Speaker> teamCast(Map<String, Speaker> online, long now) {
        var teams = new HashMap<String, List<Speaker>>();
        for (Speaker speaker : online.values()) {
            if (!speaker.team().isEmpty() && !busy(speaker.key())) teams.computeIfAbsent(speaker.team(), unused -> new ArrayList<>()).add(speaker);
        }
        var choices = new ArrayList<List<Speaker>>();
        for (var entry : teams.entrySet()) {
            var members = entry.getValue();
            if (members.size() < 2 || now - teamChatAt.getOrDefault(entry.getKey(), 0L) < 300_000) continue;
            if (members.stream().allMatch(member -> coolingDown(member.key(), now))) continue;
            choices.add(members);
        }
        if (choices.isEmpty()) return null;
        var members = new ArrayList<>(choices.get(ThreadLocalRandom.current().nextInt(choices.size())));
        Collections.shuffle(members);
        // 先说话的人不在冷却
        members.sort(Comparator.comparing(member -> coolingDown(member.key(), now)));
        return members.subList(0, Math.min(4, members.size()));
    }

    /** 小队内部聊几句：AI 可用时交给 AI，否则按 teams.dialogues。 */
    private Runnable teamIdle(List<Speaker> team, Map<String, Speaker> online, long now, int real) {
        Settings s = settings;
        String name = team.getFirst().team();
        teamChatAt.put(name, now);
        if (ai.usable() && ai.reserve()) {
            idleBusy = true;
            var cast = new ArrayList<TestAi.Candidate>();
            for (Speaker speaker : team) cast.add(candidate(speaker, "", online));
            var context = List.copyOf(history);
            var said = List.copyOf(spoken);
            String project = TestTeams.project(name, s.teams().projects());
            return () -> ai.team(cast, context, said, name, project, s.ai().maxLines(), real).whenComplete((lines, error) -> {
                synchronized (this) {
                    idleBusy = false;
                    if (error == null && lines != null) chain(fresh(lines), 0, IDLE, null, System.currentTimeMillis(), 0);
                    else if (error != null) ruleTeamIdle(team, System.currentTimeMillis());
                }
            });
        }
        ruleTeamIdle(team, now);
        return null;
    }

    private void ruleTeamIdle(List<Speaker> team, long now) {
        var cast = new ArrayList<>(team);
        cast.removeIf(speaker -> busy(speaker.key()));
        var script = script(settings.teams().dialogues(), cast.size());
        if (script == null) return;
        step(script, 0, cast.toArray(Speaker[]::new), now, 0);
    }

    /** 随机挑一段人数够的对话，避开最近用过的几段。 */
    private List<Step> script(List<List<Step>> all, int people) {
        var fits = new ArrayList<List<Step>>();
        for (var script : all) {
            int roles = 1;
            for (Step step : script) roles = Math.max(roles, step.role() + 1);
            if (roles <= people) fits.add(script);
        }
        if (fits.isEmpty()) return null;
        var fresh = new ArrayList<>(fits);
        fresh.removeIf(recentDialogues::contains);
        var pool = fresh.isEmpty() ? fits : fresh;
        var chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        recentDialogues.addLast(chosen);
        while (recentDialogues.size() > Math.min(12, all.size() / 2 + 1)) recentDialogues.pollFirst();
        return chosen;
    }

    /** 从 ai.topics 里挑几个最近没给过的话题方向。 */
    private List<String> topics(int count) {
        var all = new ArrayList<>(settings.ai().topics());
        if (all.isEmpty()) return List.of();
        Collections.shuffle(all);
        all.sort(Comparator.comparing(recentTopics::contains));
        var picked = List.copyOf(all.subList(0, Math.min(count, all.size())));
        recentTopics.addAll(picked);
        while (recentTopics.size() > Math.max(0, Math.min(15, all.size() - count))) recentTopics.pollFirst();
        return picked;
    }

    private void ruleIdle(List<Speaker> talkers, long now) {
        Settings s = settings;
        var shuffled = new ArrayList<>(talkers);
        shuffled.removeIf(speaker -> busy(speaker.key()));
        if (shuffled.isEmpty()) return;
        Collections.shuffle(shuffled);
        if (shuffled.size() >= 2 && !s.dialogues().isEmpty() && roll(s.dialoguePercent())) {
            var script = script(s.dialogues(), shuffled.size());
            if (script != null) {
                int roles = 1;
                for (Step step : script) roles = Math.max(roles, step.role() + 1);
                // 一窝蜂：这段对话几个人你一句我一句抢着说完
                step(script, 0, shuffled.subList(0, roles).toArray(Speaker[]::new), now, rollBurst());
                return;
            }
        }
        Speaker who = shuffled.getFirst();
        String raw = line("idle");
        if (raw == null) return;
        Speaker other = raw.contains("%other%") && shuffled.size() > 1 ? shuffled.get(1) : null;
        if (raw.contains("%other%") && other == null) return;
        queue(who.key(), fill(raw, null, other == null ? null : other.name()), IDLE, now, other == null ? null : next -> {
            // 被点名的人接话
            String answer = fill(line("mention"), who.name(), null);
            if (next.containsKey(other.key()) && answer != null) queue(other.key(), answer, REPLY, System.currentTimeMillis() + between(1000, 3000), null);
        });
    }

    /** 冷场对话的第 index 行；说出口后再排下一行。 */
    private void step(List<Step> script, int index, Speaker[] cast, long notBefore, long burst) {
        if (index >= script.size()) return;
        Step step = script.get(index);
        String text = any(step.texts());
        for (int role = 0; role < cast.length; role++) {
            String token = "%" + (char) ('A' + role) + "%";
            if (text.contains(token)) text = text.replace(token, roll(50) ? Similarity.nick(cast[role].name()) : cast[role].name());
        }
        queue(cast[step.role()].key(), text, IDLE, notBefore, next -> {
            for (Speaker member : cast) if (!next.containsKey(member.key())) return;
            long delay = burst != 0 ? between(200, 900) : between(500, 2500);
            step(script, index + 1, cast, System.currentTimeMillis() + delay, burst);
        }, false, burst);
    }

    // ══ AI ════════════════════════════════════════════════

    /** 准备 AI 回复真人的调用（在锁外执行）。出错时改用规则。 */
    private Runnable aiReply(UUID player, String name, String text, Speaker mentioned, Map<String, Speaker> online, long now) {
        Partner partner = partners.get(player);
        String partnerKey = partner != null && partner.until() > now ? partner.speaker() : null;
        Speaker partnerSpeaker = partnerKey == null ? null : online.get(partnerKey);
        var first = new ArrayList<TestAi.Candidate>();
        var candidates = new ArrayList<TestAi.Candidate>();
        for (Speaker speaker : online.values()) {
            boolean talking = speaker.key().equals(partnerKey);
            boolean named = mentioned != null && mentioned.key().equals(speaker.key());
            // 被叫到的人、正在聊天的人的队友，潜水的也可能顺嘴接一句
            boolean mate = mentioned != null && speaker.teammate(mentioned) || partnerSpeaker != null && speaker.teammate(partnerSpeaker);
            if (!talking && !named && (!speaker.talkative() && !mate || busy(speaker.key()) || coolingDown(speaker.key(), now) && !mate)) continue;
            String note = named ? "被叫到了" : talking ? "正在和 " + name + " 聊天"
                : mate ? "和" + (mentioned != null && speaker.teammate(mentioned) ? "被叫到的 " + mentioned.name() : "正在聊天的 " + partnerSpeaker.name())
                    + " 一个小队" : "";
            (talking || named || mate ? first : candidates).add(candidate(speaker, note, online));
        }
        Collections.shuffle(candidates);
        candidates.addAll(0, first);
        if (candidates.isEmpty()) return null;
        if (candidates.size() > 8) candidates.subList(8, candidates.size()).clear();
        Collections.shuffle(candidates);
        var context = List.copyOf(history);
        var said = List.copyOf(spoken);
        boolean interrupting = settings.dontInterrupt() && realTalking(now) && partnerKey == null && mentioned == null;
        // 一窝蜂要先定下来：让 AI 一次给出几个人抢着回的几句；被叫到名字、别人正在聊时不起哄
        long burst = interrupting || mentioned != null ? 0 : rollBurst();
        int crowd = burst != 0 ? Math.min(burstSize(), candidates.size()) : 0;
        if (crowd < 2) { burst = 0; crowd = 0; }
        long chosen = burst;
        int size = crowd, others = realOnline();
        String lower = text.toLowerCase(Locale.ROOT);
        return () -> ai.reply(candidates, context, said, name, text, mentioned == null ? null : mentioned.name(), interrupting, size, others)
            .whenComplete((lines, error) -> {
                Map<String, Speaker> current = error != null ? online() : null;
                synchronized (this) {
                    long at = System.currentTimeMillis();
                    if (error != null) { rules(player, name, lower, mentioned, current, at); return; }
                    chain(fresh(lines), 0, mentioned != null ? MENTION : REPLY, player, at, chosen);
                }
            });
    }

    /**
     * AI 给的话里去掉和最近说过的太像的（AI 常常换个标点、加个字又说一遍）：一两个字的短句和最近 8 句比，长的和最近 40 句比；
     * 同一批里也不能两句太像。
     */
    private List<TestAi.Reply> fresh(List<TestAi.Reply> lines) {
        var result = new ArrayList<TestAi.Reply>();
        var recent = new ArrayList<>(recentTexts);
        for (TestAi.Reply reply : lines) {
            boolean shortText = Similarity.normalize(reply.text()).codePointCount(0, Similarity.normalize(reply.text()).length()) < 3;
            var against = shortText ? recent.subList(Math.max(0, recent.size() - 8), recent.size()) : recent;
            if (against.stream().anyMatch(old -> Similarity.similar(old, reply.text()))) continue;
            if (result.stream().anyMatch(kept -> Similarity.similar(kept.text(), reply.text()))) continue;
            result.add(reply);
        }
        return result;
    }

    /** 依次排队 AI 给出的几句话：前一句说出口后，再排下一句。一窝蜂时一句紧跟一句。 */
    private void chain(List<TestAi.Reply> lines, int index, int priority, UUID player, long notBefore, long burst) {
        if (index >= lines.size()) return;
        TestAi.Reply reply = lines.get(index);
        String key = reply.speaker().toLowerCase(Locale.ROOT);
        queue(key, reply.text(), priority, notBefore, next -> chain(lines, index + 1, priority, player,
            System.currentTimeMillis() + (burst != 0 ? between(200, 900) : between(500, 2000)), burst), false, burst);
        if (player != null && index == 0) partners.put(player, new Partner(key, System.currentTimeMillis() + settings.conversation(), 0));
    }
}
