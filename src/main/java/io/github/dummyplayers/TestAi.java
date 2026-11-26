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
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * TAB 测试假人聊天用的 AI 接口。支持 OpenAI 格式的 /chat/completions（DeepSeek、通义千问、Kimi、本地模型等）
 * 和 Claude 的 /v1/messages。AI 返回 JSON：{"lines":[{"speaker":"名字","text":"内容"}]}，由谁说、说什么。
 */
final class TestAi {
    private static final Pattern COLOR = Pattern.compile("[&§][0-9a-fk-orxA-FK-ORX]");

    record Settings(boolean enabled, String format, String endpoint, String apiKey, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int perMinute, int perDay, boolean jsonMode, int historyLines,
                    int maxLines, String serverInfo, List<String> personas) {

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
            var personas = new ArrayList<String>();
            for (String persona : yaml.getStringList("personas")) if (!persona.isBlank()) personas.add(persona.strip());
            return new Settings(yaml.getBoolean("enabled", false), format, endpoint(format, base), yaml.getString("api_key", "").strip(),
                model, yaml.getDouble("temperature", 1.3), maxTokens, timeout, perMinute, perDay, yaml.getBoolean("json_mode", true),
                history, lines, yaml.getString("server_info", "").strip(), List.copyOf(personas));
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

    /** 可以说话的测试假人：名字、性格、说明（如“被提到了”）。 */
    record Candidate(String name, String persona, String note) { }

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

    /** 真人刚说了一句：决定要不要回、由谁回、说什么。 */
    CompletableFuture<List<Reply>> reply(List<Candidate> candidates, List<TestChat.Said> history, String player, String text,
                                         String mentioned, boolean othersTalking) {
        var task = new StringBuilder("真实玩家 ").append(player).append(" 刚说：“").append(clean(text, 120)).append("”。\n");
        if (mentioned != null) task.append("他提到了 ").append(mentioned).append("，应该由 ").append(mentioned).append(" 回复。\n");
        else if (othersTalking) task.append("真实玩家之间正在聊天，除非话题和测试玩家有关，否则不要插话，返回空列表。\n");
        task.append("决定要不要回复、由谁回复、说什么。最多 2 句，可以一个人回，也可以两个人先后接话；不是每句都要回。");
        return ask(candidates, history, task.toString(), 2);
    }

    /** 冷场了：让测试假人自然地聊几句。 */
    CompletableFuture<List<Reply>> idle(List<TestChat.Speaker> speakers, List<TestChat.Said> history, int maxLines) {
        var candidates = new ArrayList<Candidate>();
        for (TestChat.Speaker speaker : speakers) candidates.add(new Candidate(speaker.name(), speaker.persona(), ""));
        String task = "聊天区安静了一会儿。让上面的测试玩家自然地聊几句：可以是一个人随口说一句，也可以是 2 到 " + maxLines
            + " 句的一问一答或小讨论，比如约人挖矿、晒收获、吐槽死亡、问哪里有资源、聊建筑。话题不要和最近的聊天重复。";
        return ask(candidates, history, task, maxLines);
    }

    /** /afp test ai：发一个最简单的请求，确认地址、Key 和模型可用。结果为回复的文字。 */
    CompletableFuture<String> test() {
        Settings s = settings;
        return call(s, "只回复两个字：正常", "测试", false, s.maxTokens()).thenApply(text -> clean(text, 60));
    }

    private CompletableFuture<List<Reply>> ask(List<Candidate> candidates, List<TestChat.Said> history, String task, int maxLines) {
        Settings s = settings;
        var names = new HashMap<String, String>();
        var user = new StringBuilder("测试玩家（名字｜性格｜说明）：\n");
        for (Candidate candidate : candidates) {
            names.put(candidate.name().toLowerCase(Locale.ROOT), candidate.name());
            user.append("- ").append(candidate.name()).append("｜").append(candidate.persona().isEmpty() ? "普通玩家" : candidate.persona());
            if (!candidate.note().isEmpty()) user.append("｜").append(candidate.note());
            user.append('\n');
        }
        user.append("\n最近的聊天（从旧到新，[玩家] 是真实玩家，[测试] 是测试玩家）：\n");
        var recent = history.subList(Math.max(0, history.size() - s.historyLines()), history.size());
        if (recent.isEmpty()) user.append("（没有）\n");
        for (TestChat.Said said : recent) {
            user.append(said.real() ? "[玩家] " : "[测试] ").append(said.name()).append("：").append(clean(said.text(), 120)).append('\n');
        }
        user.append("\n现在：").append(task).append("\n只输出 JSON。");
        return call(s, system(s, maxLines), user.toString(), true, s.maxTokens()).thenApply(text -> parse(text, names, maxLines));
    }

    private static String system(Settings s, int maxLines) {
        return """
            你负责一个 Minecraft 服务器聊天区里几位“测试玩家”的发言：决定谁说话、说什么，让聊天区像真人玩家在聊天。
            写法：
            - 每句不超过 20 个字，像国内玩家打字：常常不加标点，可以用“哈哈”“6”“笑死”“？”，不用书面语，不要每句都礼貌完整。
            - 每个测试玩家按自己的性格说话，不同的人风格要有区别。
            - 要接得上最近的聊天，不要重复别人或自己刚说过的话。
            - 不是每条消息都要回，没什么可说就返回空列表。最多 %d 句。
            - speaker 只能用给出的测试玩家名字，不能替真实玩家说话。
            必须遵守：
            - 不承诺给任何物品、金钱、权限或坐标；不冒充管理员或服主；不编造服务器介绍里没有的指令、活动或规则。
            - 不谈政治、色情、暴力、赌博、诈骗，不骂人，不发链接。
            - 有人问谁是机器人、假人、AI 或脚本时，不回答这类问题，返回空列表。
            - 不要输出颜色代码或换行。
            服务器介绍：
            %s
            只输出一个 JSON 对象，格式：{"lines":[{"speaker":"名字","text":"说的话"}]}
            """.formatted(maxLines, s.serverInfo().isEmpty() ? "（没有介绍）" : s.serverInfo());
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
            String said = line.has("text") && line.get("text").isJsonPrimitive() ? clean(line.get("text").getAsString(), 40) : "";
            String name = names.get(speaker.toLowerCase(Locale.ROOT));
            if (name == null || said.isEmpty() || said.contains("http")) continue;
            result.add(new Reply(name, said));
        }
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
