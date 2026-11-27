package io.github.dummyplayers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * TAB 测试假人聊天用的 AI 接口。支持 OpenAI 格式的 /chat/completions（DeepSeek、通义千问、Kimi、本地模型等）
 * 和 Claude 的 /v1/messages。AI 返回 JSON：{"lines":[{"speaker":"名字","text":"内容"}]}，由谁说、说什么。
 */
final class TestAi {
    private static final Pattern COLOR = Pattern.compile("[&§][0-9a-fk-orxA-FK-ORX]");
    /** 每次请求从梗表里随机挑这么多个给 AI，免得总用同几个。 */
    private static final int MEMES_PER_REQUEST = 15;
    /** tab_chat.yml 没写 ai.activities 时测试假人“正在做的事”。 */
    static final List<String> DEFAULT_ACTIVITIES = List.of("在矿洞挖矿", "在下界找要塞", "在家盖房子", "在河边钓鱼", "在种田",
        "在刷怪塔挂机", "在整理箱子", "在跑图找村庄", "在附魔", "在弄红石机器", "在养动物", "在和村民交易", "在砍树",
        "在挖地下通道", "在装修房子", "在海底找遗迹", "在刷铁机旁边挂机", "人不在电脑前，挂着机");
    /** tab_chat.yml 没写 ai.memes 时的梗表。 */
    static final List<String> DEFAULT_MEMES = List.of("6", "666", "典", "典中典", "急了急了", "绷不住了", "蚌埠住了", "乐",
        "笑死", "笑不活了", "好家伙", "啊对对对", "你说得对", "破防了", "草（一种植物）", "xswl", "yyds", "u1s1", "赢麻了",
        "小丑竟是我自己", "这波血亏", "白给", "开摆", "摆烂了", "尊嘟假嘟", "我真的会谢", "家人们谁懂啊", "不是哥们",
        "有点东西", "主打一个xx", "一整个xx住了", "DNA动了", "包的", "闹麻了", "芜湖起飞", "夺笋啊", "泰裤辣", "xx，启动！",
        "苦力怕：嘶~", "挖到钻石下一秒掉岩浆", "别挖脚下的方块", "床在下界会炸", "别和末影人对视", "三十级附魔出个效率一",
        "钓鱼佬永不空军", "村民：嗯？", "红石大佬带带我", "一镐下去全是石头", "背包满了啥都舍不得扔", "狗又掉岩浆了",
        "苦力怕是我爹", "钻石！（史蒂夫音）");
    /** tab_chat.yml 没写 ai.topics 时冷场开话题的方向：每次随机挑几个给 AI，最近给过的不再给。 */
    static final List<String> DEFAULT_TOPICS = List.of("附魔出了垃圾属性", "村民交易涨价了", "刚死了一次东西掉了", "建筑卡住了不知道怎么盖",
        "红石机器出问题了", "挖到好东西想炫耀", "找不到某种资源", "被怪物偷袭", "下雨打雷", "宠物（狗、猫、马）的事", "钓鱼钓到怪东西",
        "跑图看到奇怪的地形", "背包满了要整理", "刚做好一个农场", "想换个地方住", "抱怨现实里的作业或上班", "要去吃饭了或者点外卖",
        "说自己困了想睡", "刚看了个游戏视频", "问别人在干嘛", "吐槽自己手残", "约人一起做点事", "问服务器里有没有人卖东西",
        "聊别的游戏", "说今天天气", "说自己刚上线还没想好干嘛", "炫耀装备", "问哪里有村庄或者要塞", "嫌夜晚太长", "刚从下界回来");
    /** 打字习惯，按 UUID 固定分配给测试假人，让几个人的说法明显不同。 */
    static final List<String> HABITS = List.of("打字很短，常常只回一两个字", "爱用空格代替标点断句", "偶尔打错字、用拼音缩写",
        "爱用叠词，比如“好好好”“来来来”", "爱带“hhh”", "句尾爱加“了”“啊”", "说话直，很少用语气词", "偶尔用 qwq、awa 这种颜文字",
        "爱接别人的话茬", "爱用问号反问", "喜欢连发两三条短消息说一件事", "说话慢悠悠，爱用省略号", "爱用“草”“绝了”“离谱”",
        "回别人常用“？”");
    /** 心情，每人每 20～40 分钟换一种。 */
    static final List<String> MOODS = List.of("心情不错", "有点困", "有点无聊", "挺兴奋", "有点烦", "饿了", "刚上线", "在摸鱼",
        "很专注，不太想说话", "刚死了一次有点郁闷", "闲着没事", "刚挖到好东西");
    /** 语气参考，每次随机挑两段，免得 AI 总学同一段。 */
    private static final List<String> EXAMPLES = List.of("""
            A：有人一起下矿吗
            B：等会 箱子还没整完
            C：几层
            A：-50左右
            B：来了来了""", """
            （有人说“我又死了”）
            D：笑死
            E：东西还在吗
            D：苦力怕是你爹""", """
            A：附魔台又给我出个耐久一
            B：经典
            A：三十级啊我
            C：我刚出了个锋利四 嘿嘿""", """
            A：今天作业写不完了
            B：抄
            A：没人给我抄
            C：明天早八 先睡了""", """
            A：lemon 你那边石头够没
            B：差点 你先铺地板
            A：行
            C：我去下界拿石英 谁要
            B：我要一组""", """
            A：村民又涨价了
            B：打他一顿就降了
            A：真的假的
            B：僵尸化再治好 我试过""", """
            A：谁在主城附近
            B：我
            A：帮我开下门 被关外面了
            B：你没手吗
            A：门是你装的红石啊""", """
            A：外面打雷了
            B：苦力怕要变闪电苦力怕了
            A：我在家
            B：家也不安全""");
    record Settings(boolean enabled, String format, String endpoint, String apiKey, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int perMinute, int perDay, boolean jsonMode, int historyLines,
                    int maxLines, String serverInfo, List<String> personas, List<String> activities, List<String> memes, List<String> topics,
                    ZoneId zone) {

        static Settings read(ConfigurationSection yaml) {
            String format = yaml.getString("format", "openai").strip().toLowerCase(Locale.ROOT);
            if (!format.equals("openai") && !format.equals("anthropic")) {
                throw new IllegalArgumentException("tab_chat.yml: ai.format must be openai or anthropic");
            }
            String base = yaml.getString("base_url", "").strip();
            if (base.isEmpty() || base.equalsIgnoreCase("default")) base = format.equals("openai") ? "https://api.deepseek.com" : "https://api.anthropic.com";
            String model = yaml.getString("model", "").strip();
            if (model.isEmpty() || model.equalsIgnoreCase("default")) model = format.equals("openai") ? "deepseek-chat" : "claude-haiku-5-5";
            int maxTokens = yaml.getInt("max_tokens", 400);
            int timeout = yaml.getInt("timeout_seconds", 15);
            int perMinute = yaml.getInt("max_calls_per_minute", 6);
            int perDay = yaml.getInt("daily_call_limit", 1500);
            int history = yaml.getInt("history_lines", 15);
            int lines = yaml.getInt("max_lines", 4);
            if (maxTokens < 50 || timeout < 1 || timeout > 120 || perMinute < 1 || perDay < 1 || history < 0 || history > 40 || lines < 1 || lines > 8) {
                throw new IllegalArgumentException("tab_chat.yml: ai.max_tokens >= 50, timeout_seconds 1-120, max_calls_per_minute >= 1,"
                    + " daily_call_limit >= 1, history_lines 0-40, max_lines 1-8");
            }
            ZoneId zone;
            try { zone = ZoneId.of(yaml.getString("timezone", "Asia/Shanghai").strip()); }
            catch (DateTimeException invalid) { throw new IllegalArgumentException("tab_chat.yml: ai.timezone must be a time zone like Asia/Shanghai"); }
            return new Settings(yaml.getBoolean("enabled", false), format, endpoint(format, base), yaml.getString("api_key", "").strip(),
                model, yaml.getDouble("temperature", 1.3), maxTokens, timeout, perMinute, perDay, yaml.getBoolean("json_mode", true),
                history, lines, yaml.getString("server_info", "").strip(), list(yaml, "personas", List.of()),
                list(yaml, "activities", DEFAULT_ACTIVITIES), list(yaml, "memes", DEFAULT_MEMES), list(yaml, "topics", DEFAULT_TOPICS), zone);
        }

        /** 字符串列表；没写这一项时用 fallback，写成 [] 表示不要。 */
        private static List<String> list(ConfigurationSection yaml, String path, List<String> fallback) {
            if (!yaml.contains(path)) return fallback;
            var result = new ArrayList<String>();
            for (String item : yaml.getStringList(path)) if (!item.isBlank()) result.add(item.strip());
            return List.copyOf(result);
        }

        /** 补全接口路径：https://api.deepseek.com → …/chat/completions，https://api.anthropic.com → …/v1/messages；已写全的原样使用。 */
        static String endpoint(String format, String base) {
            String url = base;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (format.equals("openai")) return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
            if (url.endsWith("/v1/messages") || url.endsWith("/messages")) return url;
            return url.endsWith("/v1") ? url + "/messages" : url + "/v1/messages";
        }
    }

    /**
     * 可以说话的测试假人：名字、性格、打字习惯、心情、正在游戏里做的事、小队（如“摸鱼联盟，和 B、C 一队，一起在盖主城”，
     * 不在小队里为空）、说明（如“被叫到了”）。
     */
    record Candidate(String name, String persona, String habit, String mood, String activity, String team, String note) { }

    /** AI 决定的一句话。 */
    record Reply(String speaker, String text) { }

    private final DummyPlayers plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile Settings settings;
    private final ArrayDeque<Long> calls = new ArrayDeque<>();
    private LocalDate day = LocalDate.now();
    private int today;
    /** 接口不支持 JSON 模式时，本次运行不再发送。 */
    private volatile boolean jsonUnsupported;
    private volatile String lastError = "";
    private long lastWarning;

    TestAi(DummyPlayers plugin) { this.plugin = plugin; }

    void configure(Settings next) {
        Settings previous = settings;
        settings = next;
        if (previous == null || !previous.endpoint().equals(next.endpoint()) || !previous.model().equals(next.model())) jsonUnsupported = false;
    }

    Settings settings() { return settings; }

    boolean usable() {
        Settings s = settings;
        return s != null && s.enabled();
    }

    /** 占用一次调用次数；本分钟或今天的次数用完时返回 false。 */
    synchronized boolean reserve() {
        Settings s = settings;
        long now = System.currentTimeMillis();
        while (!calls.isEmpty() && calls.peekFirst() <= now - 60_000) calls.pollFirst();
        if (!LocalDate.now().equals(day)) { day = LocalDate.now(); today = 0; }
        if (calls.size() >= s.perMinute() || today >= s.perDay()) return false;
        calls.addLast(now);
        today++;
        return true;
    }

    synchronized String usage() { return today + "/" + settings.perDay(); }

    String lastError() { return lastError; }

    // ══ 任务 ══════════════════════════════════════════════

    /**
     * 有玩家刚说了一句：决定要不要回、由谁回、说什么。crowd 大于 0 时是一窝蜂：让 crowd 个人各回一句。
     * spoken 为测试假人最近说过的话（从旧到新），others 为除测试假人外在线的玩家数。
     */
    CompletableFuture<List<Reply>> reply(List<Candidate> candidates, List<TestChat.Said> history, List<TestChat.Said> spoken, String player,
                                         String text, String mentioned, boolean othersTalking, int crowd, int others) {
        var task = new StringBuilder("玩家 ").append(player).append(" 刚说：“").append(clean(text, 120)).append("”。\n");
        int max = 2;
        if (mentioned != null) task.append("他叫到了 ").append(mentioned).append("，由 ").append(mentioned)
            .append(" 回他；和 ").append(mentioned).append(" 一个小队的人也可以顺嘴接一句，不接也行。");
        else if (othersTalking) task.append("别的玩家之间正在聊，除非和你扮演的人有关，否则别插话，返回空列表。");
        else if (crowd > 1) {
            max = crowd;
            task.append("这句话会让好几个人同时搭腔：让 ").append(crowd).append(" 个不同的人各回一句，说法、长短、语气都要不一样，")
                .append("有人只回一两个字，有人吐槽或接梗，也可以有人跑题说自己的事。");
        } else task.append("决定要不要回、谁回、说什么。最多 2 句，可以一个人回，也可以两个人先后接话；没什么可接的就不回。");
        return ask(candidates, history, spoken, task.toString(), max, others);
    }

    /** 冷场了：让测试假人自然地聊几句。crowd 时几个人你一句我一句很快地聊。topics 为这次可以挑的话题方向。 */
    CompletableFuture<List<Reply>> idle(List<Candidate> candidates, List<TestChat.Said> history, List<TestChat.Said> spoken, int maxLines,
                                        boolean crowd, List<String> topics, int others) {
        String hint = topics.isEmpty() ? "" : "这次可以从这几个方向里挑一个（也可以聊别的）：" + String.join("、", topics) + "。";
        String task = crowd
            ? "聊天区安静了一会儿。让 3 到 " + Math.max(3, maxLines) + " 个人你一句我一句很快地聊起来：一个人起个头，其他人抢着接，"
                + "可以吐槽、接梗、互相损两句。" + hint + "话题不要和最近聊过的重复。"
            : "聊天区安静了一会儿。让你扮演的玩家自然地开个话题：可以一个人随口说一句跟自己正在做的事或心情有关的话，也可以是 2 到 " + maxLines
                + " 句的一问一答或小讨论。" + hint + "话题和说法不要和最近聊过的重复，不要老是问“有人一起xx吗”。";
        return ask(candidates, history, spoken, task, crowd ? Math.max(3, maxLines) : maxLines, others);
    }

    /** 冷场了：让一个小队的几个人在公屏上聊他们小队的事。 */
    CompletableFuture<List<Reply>> team(List<Candidate> candidates, List<TestChat.Said> history, List<TestChat.Said> spoken, String team,
                                        String project, int maxLines, int others) {
        var names = new ArrayList<String>();
        for (Candidate candidate : candidates) names.add(candidate.name());
        int lines = Math.max(2, maxLines);
        String task = "聊天区安静了一会儿。让小队「" + team + "」的 " + String.join("、", names) + " 在公屏上聊几句他们小队的事"
            + (project.isEmpty() ? "" : "（他们最近在" + project + "）") + "，2 到 " + lines + " 句。他们是一起玩的老熟人，说话随便："
            + "可以分工（谁去挖矿谁去砍树）、说进度、催人、借东西、互损、吐槽队友、约时间、开小队内部的玩笑，叫对方用名字的一部分或外号。"
            + "不要和最近聊过的重复。";
        return ask(candidates, history, spoken, task, lines, others);
    }

    /** /afp test ai：发一个最简单的请求，确认地址、Key 和模型可用。结果为回复的文字。 */
    CompletableFuture<String> test() {
        Settings s = settings;
        return call(s, "只回复两个字：正常", "测试", false, s.maxTokens()).thenApply(text -> clean(text, 60));
    }

    private CompletableFuture<List<Reply>> ask(List<Candidate> candidates, List<TestChat.Said> history, List<TestChat.Said> spoken,
                                               String task, int maxLines, int others) {
        Settings s = settings;
        var names = new HashMap<String, String>();
        var user = new StringBuilder("现在是").append(clock(s.zone())).append("，除了你扮演的人，服务器里还有 ").append(others)
            .append(" 个玩家在线。\n\n你扮演的玩家（名字｜性格｜打字习惯｜心情｜正在做的事｜小队｜备注）：\n");
        for (Candidate candidate : candidates) {
            names.put(candidate.name().toLowerCase(Locale.ROOT), candidate.name());
            user.append("- ").append(candidate.name()).append("｜").append(candidate.persona().isEmpty() ? "普通玩家" : candidate.persona())
                .append("｜").append(candidate.habit().isEmpty() ? "随便" : candidate.habit())
                .append("｜").append(candidate.mood().isEmpty() ? "一般" : candidate.mood())
                .append("｜").append(candidate.activity().isEmpty() ? "随便逛逛" : candidate.activity())
                .append("｜").append(candidate.team().isEmpty() ? "没有" : candidate.team());
            var mine = new ArrayList<String>();
            for (int index = spoken.size() - 1; index >= 0 && mine.size() < 2; index--) {
                if (spoken.get(index).name().equalsIgnoreCase(candidate.name())) mine.addFirst(clean(spoken.get(index).text(), 40));
            }
            String note = candidate.note();
            if (!mine.isEmpty()) note = (note.isEmpty() ? "" : note + "；") + "刚才说过：" + String.join(" / ", mine);
            if (!note.isEmpty()) user.append("｜").append(note);
            user.append('\n');
        }
        // 不标谁是测试假人：模型只需要知道自己扮演谁，标出来反而容易说漏
        user.append("\n最近的聊天（从旧到新）：\n");
        var recent = history.subList(Math.max(0, history.size() - s.historyLines()), history.size());
        if (recent.isEmpty()) user.append("（没有）\n");
        for (TestChat.Said said : recent) user.append(said.name()).append("：").append(clean(said.text(), 120)).append('\n');
        // 更早说过的话：只给内容，让 AI 别再说一样的、差不多的
        var older = new ArrayList<String>();
        for (TestChat.Said said : spoken) if (!recent.contains(said)) older.add(clean(said.text(), 30));
        if (!older.isEmpty()) {
            user.append("\n更早时候说过的话（别再说一样的或差不多的）：")
                .append(String.join(" / ", older.subList(Math.max(0, older.size() - 25), older.size()))).append('\n');
        }
        user.append("\n现在：").append(task).append("\n只输出 JSON。");
        return call(s, system(s, maxLines), user.toString(), true, s.maxTokens()).thenApply(text -> parse(text, names, maxLines));
    }

    /** 例如“星期六 23:40（深夜）”。 */
    private static String clock(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        int hour = now.getHour();
        String part = hour < 5 ? "凌晨" : hour < 8 ? "早上" : hour < 11 ? "上午" : hour < 13 ? "中午" : hour < 17 ? "下午"
            : hour < 19 ? "傍晚" : hour < 23 ? "晚上" : "深夜";
        String day = "一二三四五六日".charAt(now.getDayOfWeek().getValue() - 1) + "";
        return "星期" + day + " " + String.format("%02d:%02d", hour, now.getMinute()) + "（" + part + "）";
    }

    private static String system(Settings s, int maxLines) {
        var examples = new ArrayList<>(EXAMPLES);
        Collections.shuffle(examples, ThreadLocalRandom.current());
        return """
            你在 Minecraft 服务器的聊天区里同时扮演几个普通玩家，替他们决定谁说话、说什么。
            目标：让人看不出这几个人是同一个人扮演的，更看不出是 AI。

            说话方式（最重要）：
            - 像国内玩家边玩边打字：大多数消息只有 2～10 个字，偶尔长一点，不超过 25 个字。
            - 每个人有自己的性格、打字习惯、心情和正在做的事，说话要和这些对得上；几个人的用词、长短、语气要明显不同。
            - 句末一般不加标点，很少用感叹号；别用“呢~”“哦~”“呀”“哈哈哈”这种腔调撑场面，除非这个人的性格就这样。
            - 随手打：可以省略主语、打错字、用拼音缩写（nb、xswl、u1s1），一个人也可以连发两条短消息把一句话说完。
            - 叫人时多用名字的一部分或外号（比如 Lemon_lu 叫“lemon”），很少叫全名，很少 @ 人，不要一上来就寒暄。
            - 不要客服腔、不要说教、不要总结、不要热情过头，不要每句都附和；可以敷衍、吐槽、互损、答非所问、说不知道、只回个“？”。
            - 时间要对得上：深夜可以说困了要睡，早上可以说早八，饭点可以说去吃饭。
            - 接着最近的聊天往下说，不要把对方的话复述一遍。

            不要重复（很重要）：
            - 不要说和聊天记录里、“刚才说过”、“更早时候说过的话”里一样或意思差不多的话，也别套同一个句式；
              刚有人说过“笑死”，别人就别再说“笑死”，同一个人更不要把自己的话再说一遍。
            - 同一个话题别反复提（比如一直说下矿、钻石、苦力怕），开话题时换着来，游戏里的事和现实里的事都可以聊。
            - 回别人时别老用同一个开头（“哈哈”“确实”“6”“好家伙”）。

            小队：
            - 小队一栏写了小队的人，和队友是一起玩的老熟人：说话更随便，可以互损、开小队内部的玩笑、分工、说进度、催人、约时间。
            - 和队友说话不用解释前因后果，别人看不懂也没关系。

            玩梗：
            - 国内玩家爱玩梗，大概每三四句里有一句带梗，网感强的人多用、老实人少用；同一个梗别反复用，用在点上，硬塞不如不用。
            - 这次可以挑着用的梗（不限于这些，也可以用别的常见梗；“xx”换成合适的词）：%s

            语气参考（只学语气，不要照抄里面的话）：
            %s

            %s

            什么时候不说话：
            - 不是每条消息都要有人回。和你扮演的人无关、没什么可接的，就返回空列表。
            - 有人问谁是机器人、假人、AI、脚本、托时，不回答，返回空列表。

            必须遵守：
            - speaker 只能用“你扮演的玩家”里的名字，不能替别人说话；text 里不要带说话人的名字。最多 %d 句。
            - 不承诺给任何物品、金钱、权限或坐标；不冒充管理员或服主；不编造服务器介绍里没有的指令、活动或规则。
            - 不谈政治、色情、暴力、赌博、诈骗，不骂人，不发链接，不输出颜色代码或换行。

            服务器介绍：
            %s

            只输出一个 JSON 对象，格式：{"lines":[{"speaker":"名字","text":"说的话"}]}
            """.formatted(memes(s.memes()), examples.get(0), examples.get(1), maxLines,
                s.serverInfo().isEmpty() ? "（没有介绍）" : s.serverInfo());
    }

    /** 从梗表里随机挑一些，用顿号连起来；梗表为空时说明不用梗。 */
    private static String memes(List<String> all) {
        if (all.isEmpty()) return "（这次不用梗）";
        var picked = new ArrayList<>(all);
        Collections.shuffle(picked, ThreadLocalRandom.current());
        return String.join("、", picked.subList(0, Math.min(MEMES_PER_REQUEST, picked.size())));
    }

    /** 解析 AI 的回复：去掉代码块，取第一个 { 到最后一个 }；名字必须是给出的测试假人，文字去掉颜色代码并截短。 */
    private static List<Reply> parse(String text, Map<String, String> names, int maxLines) {
        String body = text.strip();
        int start = body.indexOf('{'), end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            if (body.isEmpty() || body.equals("[]")) return List.of();
            throw new CompletionException(new IllegalStateException("AI 的回复不是 JSON：" + clean(body, 80)));
        }
        JsonElement root = JsonParser.parseString(body.substring(start, end + 1));
        JsonArray lines = root.isJsonObject() && root.getAsJsonObject().get("lines") instanceof JsonArray array ? array : new JsonArray();
        var result = new ArrayList<Reply>();
        for (JsonElement element : lines) {
            if (!element.isJsonObject() || result.size() >= maxLines) continue;
            JsonObject line = element.getAsJsonObject();
            String speaker = line.has("speaker") && line.get("speaker").isJsonPrimitive() ? line.get("speaker").getAsString().strip() : "";
            String said = line.has("text") && line.get("text").isJsonPrimitive() ? humanize(clean(line.get("text").getAsString(), 40), names) : "";
            String name = names.get(speaker.toLowerCase(Locale.ROOT));
            if (name == null || said.isEmpty() || said.contains("http")) continue;
            result.add(new Reply(name, said));
        }
        return result;
    }

    /**
     * 去掉 AI 常见的破绽：开头带上说话人的名字（“A_Ye：在的”）、整句加引号、句末的句号逗号。
     * 问号、感叹号、省略号保留，那是语气。
     */
    static String humanize(String text, Map<String, String> names) {
        String result = text.strip();
        int full = result.indexOf('：'), half = result.indexOf(':');
        int colon = full < 0 ? half : half < 0 ? full : Math.min(full, half);
        if (colon > 0 && names.containsKey(result.substring(0, colon).strip().toLowerCase(Locale.ROOT))) result = result.substring(colon + 1).strip();
        if (result.length() >= 2 && "“\"「'".indexOf(result.charAt(0)) >= 0 && "”\"」'".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(1, result.length() - 1).strip();
        }
        while (!result.isEmpty() && "。，,．".indexOf(result.charAt(result.length() - 1)) >= 0) result = result.substring(0, result.length() - 1).strip();
        return result;
    }

    /** 去掉颜色代码、换行和控制字符，截到 limit 个字。 */
    static String clean(String text, int limit) {
        String result = COLOR.matcher(text == null ? "" : text).replaceAll("").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return result.codePointCount(0, result.length()) <= limit ? result : result.substring(0, result.offsetByCodePoints(0, limit));
    }

    // ══ 请求 ══════════════════════════════════════════════

    /** 发请求，结果为模型回复的文字。接口不支持 JSON 模式时去掉再试一次；回复被截断且没有内容时加大上限再试一次。 */
    private CompletableFuture<String> call(Settings s, String system, String user, boolean json, int maxTokens) {
        boolean sendJson = json && s.jsonMode() && !jsonUnsupported;
        boolean anthropic = s.format().equals("anthropic");
        JsonObject body = anthropic ? anthropicBody(s, system, user, sendJson, maxTokens) : openAiBody(s, system, user, sendJson, maxTokens);
        var request = HttpRequest.newBuilder(URI.create(s.endpoint()))
            .timeout(Duration.ofSeconds(s.timeoutSeconds()))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json");
        if (anthropic) {
            request.header("anthropic-version", "2023-06-01");
            if (!s.apiKey().isEmpty()) request.header("x-api-key", s.apiKey());
        } else if (!s.apiKey().isEmpty()) {
            request.header("Authorization", "Bearer " + s.apiKey());
        }
        request.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .handle((response, error) -> {
                if (error != null) throw failure(network(error, s.endpoint()));
                int status = response.statusCode();
                if ((status == 400 || status == 422) && sendJson) return Optional.<String>empty(); // 去掉 JSON 模式再试
                if (status / 100 != 2) throw failure(status(status, response.body(), s.endpoint()));
                return Optional.of(response.body());
            })
            .thenCompose(raw -> {
                if (raw.isEmpty()) {
                    jsonUnsupported = true;
                    plugin.getLogger().info("The AI endpoint rejected JSON mode; test player chat no longer asks for it until /afp reload.");
                    return call(s, system, user, json, maxTokens);
                }
                JsonObject reply;
                try { reply = JsonParser.parseString(raw.get()).getAsJsonObject(); }
                catch (RuntimeException invalid) { throw failure("接口返回的不是 JSON：" + clean(raw.get(), 120)); }
                String[] content = anthropic ? anthropicText(reply) : openAiText(reply);
                if (content[0].isBlank() && content[1].equals("length") && maxTokens < s.maxTokens() * 4) {
                    return call(s, system, user, json, maxTokens * 4); // 推理模型把额度用在思考上，加大再试
                }
                if (content[1].equals("refusal")) return CompletableFuture.completedFuture("");
                return CompletableFuture.completedFuture(content[0]);
            })
            .whenComplete((text, error) -> {
                if (error == null) { lastError = ""; return; }
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                lastError = String.valueOf(cause.getMessage());
                warn(lastError);
            });
    }

    private static JsonObject openAiBody(Settings s, String system, String user, boolean json, int maxTokens) {
        var body = new JsonObject();
        body.addProperty("model", s.model());
        var messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);
        body.addProperty("temperature", s.temperature());
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", false);
        if (json) {
            var format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        return body;
    }

    private static JsonObject anthropicBody(Settings s, String system, String user, boolean json, int maxTokens) {
        var body = new JsonObject();
        body.addProperty("model", s.model());
        // Claude 的新模型默认会先思考，额度给足一些，避免思考用完额度、没有回复
        body.addProperty("max_tokens", Math.max(1024, maxTokens));
        body.addProperty("system", system);
        var messages = new JsonArray();
        messages.add(message("user", user));
        body.add("messages", messages);
        if (json) {
            var output = new JsonObject();
            output.addProperty("effort", "low");
            var format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.add("schema", JsonParser.parseString("""
                {"type":"object","properties":{"lines":{"type":"array","items":{"type":"object",
                "properties":{"speaker":{"type":"string"},"text":{"type":"string"}},
                "required":["speaker","text"],"additionalProperties":false}}},
                "required":["lines"],"additionalProperties":false}
                """));
            output.add("format", format);
            body.add("output_config", output);
        }
        return body;
    }

    private static JsonObject message(String role, String content) {
        var message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /** [内容, 结束原因]。 */
    private static String[] openAiText(JsonObject reply) {
        if (!(reply.get("choices") instanceof JsonArray choices) || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw failure("接口返回里没有 choices：" + clean(reply.toString(), 120));
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive() ? choice.get("finish_reason").getAsString() : "";
        JsonObject message = choice.get("message") instanceof JsonObject object ? object : new JsonObject();
        String content = message.has("content") && message.get("content").isJsonPrimitive() ? message.get("content").getAsString() : "";
        return new String[] {content, finish};
    }

    private static String[] anthropicText(JsonObject reply) {
        var text = new StringBuilder();
        if (reply.get("content") instanceof JsonArray blocks) {
            for (JsonElement block : blocks) {
                if (block instanceof JsonObject object && "text".equals(object.has("type") ? object.get("type").getAsString() : "")) {
                    text.append(object.get("text").getAsString());
                }
            }
        }
        String stop = reply.has("stop_reason") && reply.get("stop_reason").isJsonPrimitive() ? reply.get("stop_reason").getAsString() : "";
        return new String[] {text.toString(), stop.equals("max_tokens") ? "length" : stop};
    }

    private static CompletionException failure(String message) { return new CompletionException(new IllegalStateException(message)); }

    private static String status(int status, String body, String endpoint) {
        String hint = switch (status) {
            case 401 -> "API Key 无效或已失效";
            case 402 -> "账户余额不足";
            case 403 -> "这个 Key 没有权限使用这个模型";
            case 404 -> "接口或模型不存在，检查 base_url 和 model";
            case 429 -> "请求太频繁或额度用完";
            case 400, 422 -> "请求参数不合法，检查 model 和 format";
            default -> status >= 500 ? "AI 服务暂时不可用" : "请求失败";
        };
        String detail = "";
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.get("error") instanceof JsonObject error && error.has("message")) detail = error.get("message").getAsString();
            else if (json.has("message")) detail = json.get("message").getAsString();
        } catch (RuntimeException ignored) {
            detail = body;
        }
        return "HTTP " + status + "：" + hint + (detail.isBlank() ? "" : "；详情：" + clean(detail, 160)) + "；地址：" + endpoint;
    }

    private static String network(Throwable error, String endpoint) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException) return "请求超时：" + endpoint + " 在设定时间内没有响应";
        if (cause instanceof UnknownHostException) return "域名解析失败：" + cause.getMessage() + "，检查 base_url 或 DNS";
        if (cause instanceof ConnectException) return "连不上 " + endpoint + "，检查 base_url、网络或代理";
        return "请求失败：" + cause;
    }

    /** 同样的错误每分钟最多提示一次。 */
    private synchronized void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < 60_000) return;
        lastWarning = now;
        plugin.getLogger().warning("AI chat request failed, using the rule-based lines instead: " + message);
    }
}
