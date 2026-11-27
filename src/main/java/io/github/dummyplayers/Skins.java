package io.github.dummyplayers;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 假人的皮肤。召唤的假人和 TAB 测试假人第一次用到时从皮肤名单里挑一项，记在数据库里（跨服共用），之后一直用它。
 * 挑的时候选用的人最少的一项，名单够用时不会有两个假人同一个皮肤；名单比假人少时尽量平均。
 * 挑中的一项被移出名单、或者确定没有这个皮肤时，重新挑一项。
 *
 * <p>名单里是正版玩家名（装了 SkinsRestorer 时还可以是皮肤图片链接）。装了 SkinsRestorer 时通过它的接口获取，
 * 用它的缓存、代理和 MineSkin 设置；没装或它的接口不可用（例如只装在代理端）时直接问 Mojang。
 * 后台一项一项地获取，不会触发 Mojang 的限流；结果保存在 skin-cache.yml，之后不再联网。
 * 挑中的一项还没获取到时先用原版默认皮肤：TAB 测试假人获取到后立即换上，召唤的假人下次召唤时换上。</p>
 */
final class Skins {
    /** 两次获取之间的间隔；获取失败后多久重试；没有正版账号的名字多久后再查一次。 */
    private static final long SPACING_MILLIS = 1_200, RETRY_MILLIS = 10 * 60_000L, MISSING_MILLIS = 7 * 86_400_000L;
    private static final int MAX_FAILURES = 3;
    /** 多久内用到过的假人算“在用”，挑皮肤时算进使用人数；多久没用过的记录删掉；最近一次用到的时间多久写一次数据库。 */
    private static final long ACTIVE_MILLIS = 7 * 86_400_000L, TOUCH_MILLIS = 12 * 3_600_000L;
    private static final int FORGET_DAYS = 60;
    /** 皮肤记录还没从数据库读出来时，ready 等的“名单项”。 */
    private static final String LOADING = "\u0000loading";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String CACHE = "skin-cache.yml";

    record Texture(String value, String signature) { }

    private final DummyPlayers plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** 名单项（名字小写，链接原样）→ 皮肤。 */
    private final Map<String, Texture> textures = new ConcurrentHashMap<>();
    /** 没有这个正版账号（或链接生成不了皮肤）的名单项 → 记下的时间。 */
    private final Map<String, Long> missing = new ConcurrentHashMap<>();
    /** 获取失败的名单项 → 失败次数；连续失败 MAX_FAILURES 次按没有处理。 */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    /** 等着获取的名单项（原样），先进先出；挑中但还没获取的会插到最前面。受 queue 锁保护。 */
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    /** 等某一项获取结果的召唤：名单项 → 获取结束后要执行的动作。 */
    private final Map<String, Queue<Runnable>> waiters = new ConcurrentHashMap<>();
    private volatile List<String> pool = List.of();
    /** pool 中各项的 key。 */
    private volatile Set<String> poolKeys = Set.of();
    /** 每个假人用的名单项（key）；受 choices 锁保护。数据库读出来之前为 null。 */
    private Map<UUID, Roster.SkinChoice> choices;
    private volatile boolean warnedStorage;
    private volatile boolean enabled;
    private volatile boolean dirty, warnedSkinsRestorer, warnedNetwork;
    /** 最近一次获取到新皮肤的时间，TAB 测试条目据此刷新。 */
    private volatile long changedAt;
    private ScheduledExecutorService worker;

    Skins(DummyPlayers plugin) { this.plugin = plugin; }

    private static String key(String entry) {
        return entry.startsWith("http://") || entry.startsWith("https://") ? entry : entry.toLowerCase(Locale.ROOT);
    }

    long changedAt() { return changedAt; }

    // ══ 设置和生命周期 ════════════════════════════════════

    /** 启动和重载后调用。names 为空时用 fallback（tab_test.yml 的名字表）。 */
    void configure(boolean on, List<String> names, List<String> fallback) {
        var next = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (String raw : names.isEmpty() ? fallback : names) {
            String entry = raw.strip();
            if (entry.isEmpty() || !seen.add(key(entry))) continue;
            next.add(entry);
        }
        pool = List.copyOf(next);
        var keys = new HashSet<String>();
        for (String entry : next) keys.add(key(entry));
        poolKeys = Set.copyOf(keys);
        enabled = on && !next.isEmpty();
        if (worker == null) {
            load();
            worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ALFakePlayer skins");
                thread.setDaemon(true);
                return thread;
            });
            worker.scheduleWithFixedDelay(this::step, SPACING_MILLIS, SPACING_MILLIS, TimeUnit.MILLISECONDS);
        }
        synchronized (queue) {
            queue.clear();
            if (!enabled) return;
            long now = System.currentTimeMillis();
            for (String entry : pool) {
                String key = key(entry);
                Long gone = missing.get(key);
                if (!textures.containsKey(key) && (gone == null || now - gone > MISSING_MILLIS)) queue.addLast(entry);
            }
        }
    }

    void close() {
        if (worker != null) { worker.shutdownNow(); worker = null; }
        save();
    }

    /** 数据库打开后调用：读出每个假人用的皮肤。读完之前假人先用默认皮肤，召唤的假人最多等 4 秒（见 ready）。 */
    void attach(Roster roster) {
        roster.loadSkins(FORGET_DAYS).whenComplete((stored, error) -> {
            if (error != null) plugin.getLogger().log(Level.WARNING, "Could not read the fake player skins from the database;"
                + " skins chosen now are not shared with other servers", error);
            synchronized (this) {
                choices = new HashMap<>(error == null ? stored : Map.of());
            }
            changedAt = System.currentTimeMillis();
            finished(LOADING);
        });
    }

    // ══ 给假人挑皮肤 ══════════════════════════════════════

    /** 这个假人的皮肤；关闭、名单为空或挑中的一项还没获取到时为 null。 */
    Texture texture(UUID id) { return pick(id, null); }

    /**
     * 召唤前等一下这个假人的皮肤：挑中的一项还没获取时把它提到最前面，获取到（或确定没有）后完成，最多等 4 秒，
     * 超时就先用默认皮肤召唤。不会失败。
     */
    CompletableFuture<Void> ready(UUID id) {
        var done = new CompletableFuture<Void>();
        await(id, done);
        return done.completeOnTimeout(null, 4, TimeUnit.SECONDS);
    }

    private void await(UUID id, CompletableFuture<Void> done) {
        if (done.isDone()) return;
        String[] waiting = new String[1];
        if (pick(id, waiting) != null || waiting[0] == null) { done.complete(null); return; }
        waiters.computeIfAbsent(waiting[0], unused -> new ConcurrentLinkedQueue<>()).add(() -> await(id, done));
        // 刚好在登记之前获取完了
        String[] again = new String[1];
        if (pick(id, again) != null || !waiting[0].equals(again[0])) await(id, done);
    }

    private void finished(String key) {
        Queue<Runnable> pending = waiters.remove(key);
        if (pending != null) pending.forEach(Runnable::run);
    }

    /** 这个假人的皮肤。挑中的一项还不知道有没有时返回 null，并把它的 key 放进 waiting[0]（waiting 不为 null 时）。 */
    private Texture pick(UUID id, String[] waiting) {
        if (!enabled || pool.isEmpty()) return null;
        String key = choice(id);
        if (key == null) {
            if (waiting != null && choices() == null) waiting[0] = LOADING;
            return null;
        }
        Texture texture = textures.get(key);
        if (texture != null) return texture;
        // 还不知道有没有：先用默认皮肤，把它提到最前面去获取
        String entry = null;
        for (String candidate : pool) if (key(candidate).equals(key)) { entry = candidate; break; }
        if (entry != null) synchronized (queue) {
            if (queue.peekFirst() == null || !key(queue.peekFirst()).equals(key)) {
                queue.removeIf(queued -> key(queued).equals(key));
                queue.addFirst(entry);
            }
        }
        if (waiting != null) waiting[0] = key;
        return null;
    }

    private synchronized Map<UUID, Roster.SkinChoice> choices() { return choices; }

    /** 名单里有、而且没确定没有这个皮肤的项。 */
    private boolean usable(String key) { return poolKeys.contains(key) && !missing.containsKey(key); }

    /** 这个假人用的名单项：记下的还能用就用它，否则重新挑一项并记下。数据库还没读出来、或名单里一项都用不了时为 null。 */
    private String choice(UUID id) {
        long now = System.currentTimeMillis();
        String chosen, previous;
        synchronized (this) {
            if (choices == null) return null;
            Roster.SkinChoice current = choices.get(id);
            if (current != null && usable(current.entry())) {
                if (now - current.used() > TOUCH_MILLIS) {
                    choices.put(id, new Roster.SkinChoice(current.entry(), now));
                    if (plugin.roster != null) plugin.roster.touchSkin(id);
                }
                return current.entry();
            }
            chosen = leastUsed(id, now);
            if (chosen == null) return null;
            previous = current == null ? null : current.entry();
            choices.put(id, new Roster.SkinChoice(chosen, now));
        }
        if (plugin.roster != null) store(id, chosen, previous);
        return chosen;
    }

    /** 用的人最少的一项；一样少时先选已经获取到的（马上能换上），再按 UUID 打散。 */
    private String leastUsed(UUID id, long now) {
        var usage = new HashMap<String, Integer>();
        for (var choice : choices.values()) if (now - choice.used() < ACTIVE_MILLIS) usage.merge(choice.entry(), 1, Integer::sum);
        String best = null;
        int bestUsage = 0;
        boolean bestFetched = false;
        long bestOrder = 0;
        for (String entry : pool) {
            String key = key(entry);
            if (missing.containsKey(key)) continue;
            int used = usage.getOrDefault(key, 0);
            boolean fetched = textures.containsKey(key);
            long order = mix(id, key);
            if (best == null || used < bestUsage || used == bestUsage && (fetched && !bestFetched || fetched == bestFetched && order < bestOrder)) {
                best = key;
                bestUsage = used;
                bestFetched = fetched;
                bestOrder = order;
            }
        }
        return best;
    }

    private static long mix(UUID id, String key) {
        long mixed = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits() ^ key.hashCode() * 0xC2B2AE3D27D4EB4FL;
        mixed ^= mixed >>> 31;
        mixed *= 0xBF58476D1CE4E5B9L;
        return mixed ^ mixed >>> 29;
    }

    /** 写进数据库。其他子服已经给这个假人挑了别的、而本服也能用时，改用那一项。 */
    private void store(UUID id, String chosen, String previous) {
        plugin.roster.claimSkin(id, chosen, previous).whenComplete((stored, error) -> {
            if (error != null) {
                if (!warnedStorage) {
                    warnedStorage = true;
                    plugin.getLogger().log(Level.WARNING, "Could not save the skin of a fake player; it may differ between servers", error);
                }
                return;
            }
            if (stored.equals(chosen) || !usable(stored)) return;
            synchronized (this) { choices.put(id, new Roster.SkinChoice(stored, System.currentTimeMillis())); }
            changedAt = System.currentTimeMillis();
            finished(chosen);
        });
    }

    PropertyMap properties(UUID id) { return properties(texture(id)); }

    static PropertyMap properties(Texture texture) {
        if (texture == null) return PropertyMap.EMPTY;
        Property property = texture.signature() == null || texture.signature().isEmpty()
            ? new Property("textures", texture.value()) : new Property("textures", texture.value(), texture.signature());
        return new PropertyMap(ImmutableMultimap.of("textures", property));
    }

    GameProfile profile(UUID id, String name) { return new GameProfile(id, name, properties(id)); }

    // ══ 获取 ══════════════════════════════════════════════

    private void step() {
        try {
            String entry;
            long now = System.currentTimeMillis();
            synchronized (queue) {
                entry = null;
                for (Iterator<String> iterator = queue.iterator(); iterator.hasNext(); ) {
                    String candidate = iterator.next();
                    if (retryAt.getOrDefault(key(candidate), 0L) > now) continue;
                    iterator.remove();
                    entry = candidate;
                    break;
                }
            }
            if (entry != null) fetch(entry);
            if (dirty) save();
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, "Skin lookup failed", error);
        }
    }

    private void fetch(String entry) {
        String key = key(entry);
        if (textures.containsKey(key)) return;
        Optional<Texture> result;
        try {
            result = viaSkinsRestorer(entry);
            if (result == null) result = viaMojang(entry);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            int count = failures.merge(key, 1, Integer::sum);
            if (count >= MAX_FAILURES) {
                failures.remove(key);
                result = Optional.empty();
            } else {
                retryAt.put(key, System.currentTimeMillis() + RETRY_MILLIS);
                synchronized (queue) { queue.addLast(entry); }
                if (!warnedNetwork) {
                    warnedNetwork = true;
                    plugin.getLogger().warning("Could not get the skin of '" + entry + "' (" + describe(error)
                        + "); fake players use default skins until it works. Retrying in the background.");
                }
                finished(key);
                return;
            }
        }
        failures.remove(key);
        if (result.isPresent()) {
            textures.put(key, result.get());
            missing.remove(key);
            changedAt = System.currentTimeMillis();
        } else {
            missing.put(key, System.currentTimeMillis());
            changedAt = System.currentTimeMillis(); // 挑中它的假人改挑别的
        }
        dirty = true;
        finished(key);
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : error;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /** 通过 SkinsRestorer 获取。没装或接口不可用时为 null（改问 Mojang）；没有这个皮肤时为 empty。 */
    private Optional<Texture> viaSkinsRestorer(String entry) throws Exception {
        Plugin restorer = Bukkit.getPluginManager().getPlugin("SkinsRestorer");
        if (restorer == null || !restorer.isEnabled()) return null;
        ClassLoader loader = restorer.getClass().getClassLoader();
        Object api;
        try {
            api = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider", true, loader).getMethod("get").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // 只装在代理端时，后端的 SkinsRestorer 不提供接口
            if (!warnedSkinsRestorer) {
                warnedSkinsRestorer = true;
                plugin.getLogger().info("SkinsRestorer API is not available here (" + describe(unavailable)
                    + "); fake player skins are fetched from Mojang directly.");
            }
            return null;
        }
        Object storage = Class.forName("net.skinsrestorer.api.SkinsRestorer", true, loader).getMethod("getSkinStorage").invoke(api);
        Optional<?> found = (Optional<?>) Class.forName("net.skinsrestorer.api.storage.SkinStorage", true, loader)
            .getMethod("findOrCreateSkinData", String.class).invoke(storage, entry);
        if (found.isEmpty()) return Optional.empty();
        Object property = Class.forName("net.skinsrestorer.api.property.InputDataResult", true, loader)
            .getMethod("getProperty").invoke(found.get());
        Class<?> type = Class.forName("net.skinsrestorer.api.property.SkinProperty", true, loader);
        return Optional.of(new Texture((String) type.getMethod("getValue").invoke(property), (String) type.getMethod("getSignature").invoke(property)));
    }

    /** 直接问 Mojang：名字 → UUID → 带签名的皮肤。没有这个正版账号时为 empty。 */
    private Optional<Texture> viaMojang(String entry) throws IOException, InterruptedException {
        if (!NAME.matcher(entry).matches()) return Optional.empty(); // 链接要 SkinsRestorer 才能生成皮肤
        var lookup = get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + URLEncoder.encode(entry, StandardCharsets.UTF_8));
        if (lookup.statusCode() == 404 || lookup.statusCode() == 204) return Optional.empty();
        if (lookup.statusCode() != 200) throw new IOException("HTTP " + lookup.statusCode() + " from the Mojang name lookup");
        String id = JsonParser.parseString(lookup.body()).getAsJsonObject().get("id").getAsString();
        var session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
        if (session.statusCode() == 404 || session.statusCode() == 204) return Optional.empty();
        if (session.statusCode() != 200) throw new IOException("HTTP " + session.statusCode() + " from the Mojang session server");
        for (var element : JsonParser.parseString(session.body()).getAsJsonObject().getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (!property.get("name").getAsString().equals("textures")) continue;
            return Optional.of(new Texture(property.get("value").getAsString(),
                property.has("signature") ? property.get("signature").getAsString() : null));
        }
        return Optional.empty();
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "ALFakePlayer/" + plugin.getPluginMeta().getVersion()).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ══ 缓存文件 ══════════════════════════════════════════

    private void load() {
        Path file = plugin.getDataFolder().toPath().resolve(CACHE);
        if (Files.notExists(file)) return;
        try {
            var yaml = YamlConfiguration.loadConfiguration(file.toFile());
            for (Map<?, ?> item : yaml.getMapList("skins")) {
                if (item.get("entry") instanceof String entry && item.get("value") instanceof String value) {
                    textures.put(key(entry), new Texture(value, item.get("signature") instanceof String signature ? signature : null));
                }
            }
            for (Map<?, ?> item : yaml.getMapList("missing")) {
                if (item.get("entry") instanceof String entry && item.get("at") instanceof Number at) missing.put(key(entry), at.longValue());
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + CACHE + "; fake player skins will be fetched again", error);
        }
    }

    private synchronized void save() {
        dirty = false;
        var yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("假人皮肤的缓存，由插件自动管理。删除后会重新获取。"));
        var skins = new ArrayList<Map<String, Object>>();
        textures.forEach((entry, texture) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("entry", entry);
            item.put("value", texture.value());
            if (texture.signature() != null) item.put("signature", texture.signature());
            skins.add(item);
        });
        var gone = new ArrayList<Map<String, Object>>();
        missing.forEach((entry, at) -> gone.add(new LinkedHashMap<>(Map.of("entry", entry, "at", at))));
        yaml.set("skins", skins);
        yaml.set("missing", gone);
        try {
            Path folder = plugin.getDataFolder().toPath();
            Files.createDirectories(folder);
            Path temporary = Files.createTempFile(folder, ".skin-cache-", ".tmp");
            try {
                Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
                Files.move(temporary, folder.resolve(CACHE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + CACHE, error);
        }
    }
}
