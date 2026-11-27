package io.github.dummyplayers;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * EasyBot（QQ 群服互通）兼容（config.yml 的 easybot）：群里查询在线玩家时，本服召唤的假人和 TAB 测试假人也在列表里，
 * 和真人一样。EasyBot 通过它的 BridgeBehavior.getPlayerList() 回答在线列表，这里把它包一层，在结果后面加上假人。
 * 不依赖 EasyBot 的类，全部通过反射；EasyBot 的结构变了时只在后台提示一次，不影响其他功能。
 */
final class EasyBotCompat {
    private static final String NAME = "EasyBot";
    private static volatile boolean warned;

    private EasyBotCompat() { }

    /** 按设置装上或拆掉；EasyBot 没装时什么都不做。可以重复调用。 */
    static void apply(DummyPlayers plugin) { install(plugin, plugin.easyBot.enabled()); }

    /** 本插件停用时拆掉，EasyBot 换回自己原来的列表。 */
    static void remove(DummyPlayers plugin) { install(plugin, false); }

    private static void install(DummyPlayers plugin, boolean wanted) {
        Plugin easyBot = Bukkit.getPluginManager().getPlugin(NAME);
        if (easyBot == null || !easyBot.isEnabled()) return;
        try {
            Field clientField = easyBot.getClass().getDeclaredField("bridgeClient");
            clientField.setAccessible(true);
            Object client = clientField.get(null);
            if (client == null) return;
            Field behaviorField = client.getClass().getDeclaredField("behavior");
            behaviorField.setAccessible(true);
            Object behavior = behaviorField.get(client);
            Handler installed = Proxy.isProxyClass(behavior.getClass()) && Proxy.getInvocationHandler(behavior) instanceof Handler handler
                ? handler : null;
            if (!wanted) {
                if (installed != null) behaviorField.set(client, installed.original);
                return;
            }
            if (installed != null) return;
            ClassLoader loader = easyBot.getClass().getClassLoader();
            Class<?> type = Class.forName("com.springwater.easybot.bridge.BridgeBehavior", false, loader);
            Class<?> info = Class.forName("com.springwater.easybot.bridge.model.PlayerInfo", false, loader);
            Object wrapped = Proxy.newProxyInstance(loader, new Class<?>[] {type}, new Handler(plugin, behavior, info));
            behaviorField.set(client, wrapped);
            plugin.getLogger().info("EasyBot now lists this server's fake players as online (config.yml easybot)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (warned) return;
            warned = true;
            plugin.getLogger().log(Level.WARNING, "Cannot make EasyBot list fake players (unsupported EasyBot version?)", error);
        }
    }

    /** config.yml 的 easybot 设置。 */
    record Settings(boolean enabled, boolean summoned, boolean test) { }

    private static final class Handler implements InvocationHandler {
        private final DummyPlayers plugin;
        private final Object original;
        private final Class<?> info;
        private final Method getName, name, uuid, ip, skin, bedrock;

        Handler(DummyPlayers plugin, Object original, Class<?> info) throws NoSuchMethodException {
            this.plugin = plugin;
            this.original = original;
            this.info = info;
            getName = info.getMethod("getPlayerName");
            name = info.getMethod("setPlayerName", String.class);
            uuid = info.getMethod("setPlayerUuid", String.class);
            ip = info.getMethod("setIp", String.class);
            skin = info.getMethod("setSkinUrl", String.class);
            bedrock = info.getMethod("setBedrock", boolean.class);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try { result = method.invoke(original, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
            if (!method.getName().equals("getPlayerList") || method.getParameterCount() != 0 || !(result instanceof List<?> real)) return result;
            try {
                return withFakes(real);
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().log(Level.WARNING, "Cannot add fake players to EasyBot's player list", error);
                }
                return result;
            }
        }

        /** 真人的列表后面加上本服的假人；和真人重名的不加。EasyBot 在自己的线程里调用。 */
        private List<Object> withFakes(List<?> real) throws ReflectiveOperationException {
            var settings = plugin.easyBot;
            var result = new ArrayList<Object>(real);
            Set<String> names = new HashSet<>();
            for (Object entry : real) {
                Object value = getName.invoke(entry);
                if (value != null) names.add(value.toString().toLowerCase(Locale.ROOT));
            }
            if (settings.summoned()) {
                for (DummyPlayers.Live active : plugin.live.values()) {
                    if (!active.inWorld || active.dead) continue;
                    add(result, names, active.dummy.getGameProfile().name(), active.dummy.getUUID());
                }
            }
            if (settings.test()) {
                for (var shown : plugin.benchmark().entries().entrySet()) add(result, names, shown.getValue().name(), shown.getKey());
            }
            return result;
        }

        private void add(List<Object> result, Set<String> names, String player, UUID id) throws ReflectiveOperationException {
            if (!names.add(player.toLowerCase(Locale.ROOT))) return;
            Object entry = info.getConstructor().newInstance();
            name.invoke(entry, player);
            uuid.invoke(entry, id.toString());
            ip.invoke(entry, "127.0.0.1");
            skin.invoke(entry, skinUrl(id));
            bedrock.invoke(entry, false);
            result.add(entry);
        }

        /** 和 EasyBot 给真人的一样：有皮肤时是材质地址，没有时用 mc-heads 按 UUID 取。 */
        private String skinUrl(UUID id) {
            Skins.Texture texture = plugin.skins().texture(id);
            if (texture != null) {
                try {
                    String json = new String(Base64.getDecoder().decode(texture.value()), StandardCharsets.UTF_8);
                    var skin = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
                    if (skin != null && skin.has("url")) return skin.get("url").getAsString();
                } catch (RuntimeException ignored) { }
            }
            return "https://mc-heads.net/skin/" + id;
        }
    }
}
