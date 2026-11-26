package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 领地插件兼容（config.yml 的 claims.plugins）：列出的领地插件读取假人的 UUID 和名字时得到主人的，
 * 于是领地插件按主人的成员和权限设置判断假人能否挖掘、放置、攻击、开箱子等。
 * 只看调用栈上最近的一段插件代码属于哪个插件：其他插件和服务端看到的仍是假人自己，
 * 领地插件触发的事件交给其他插件处理时也一样。
 */
final class ClaimIdentity {
    /** 往上最多查看的调用层数。领地插件读取 UUID 时，它自己的代码就在最上面几层。 */
    private static final int MAX_DEPTH = 32;
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static volatile Set<String> names = Set.of();
    /** 已启用插件的类加载器 → 是否为领地插件。构建后不再修改。 */
    private static volatile Map<ClassLoader, Boolean> loaders = Map.of();
    private static volatile boolean active;
    private static List<String> announced = List.of();

    private ClaimIdentity() { }

    /** 应用配置中的插件名（不区分大小写）。 */
    static void configure(Collection<String> pluginNames) {
        names = pluginNames.stream().map(name -> name.strip().toLowerCase(Locale.ROOT))
            .filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
        refresh(null);
    }

    /** 插件启用或停用后重新登记类加载器；leaving 为正在停用的插件。 */
    static synchronized void refresh(Plugin leaving) {
        var map = new IdentityHashMap<ClassLoader, Boolean>();
        var found = new ArrayList<String>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin == leaving || !plugin.isEnabled()) continue;
            boolean claim = names.contains(plugin.getName().toLowerCase(Locale.ROOT));
            map.merge(plugin.getClass().getClassLoader(), claim, Boolean::logicalOr);
            if (claim) found.add(plugin.getName());
        }
        loaders = map;
        active = !found.isEmpty();
        found.sort(String.CASE_INSENSITIVE_ORDER);
        if (!found.equals(announced)) {
            announced = List.copyOf(found);
            Plugin self = Bukkit.getPluginManager().getPlugin("ALFakePlayer");
            if (self != null && !found.isEmpty()) {
                self.getLogger().info("Fake players use their owner's permissions in: " + String.join(", ", found));
            }
        }
    }

    /** 当前调用是否来自领地插件的代码。 */
    static boolean claimCaller() {
        if (!active) return false;
        Map<ClassLoader, Boolean> known = loaders;
        return WALKER.walk(frames -> frames.limit(MAX_DEPTH)
            .map(StackWalker.StackFrame::getDeclaringClass)
            .filter(type -> type != ClaimIdentity.class && type != DummyCraftPlayer.class)
            .map(type -> known.get(type.getClassLoader()))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(false));
    }
}
