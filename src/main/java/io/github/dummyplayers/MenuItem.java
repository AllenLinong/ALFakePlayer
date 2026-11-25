package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 菜单图标的物品外观。支持：
 * <ul>
 *   <li>原版物品：{@code type: BEACON}</li>
 *   <li>资源包 CustomModelData：{@code type: PAPER} + {@code custom_model_data: 10001}</li>
 *   <li>CraftEngine：{@code craftengine: "namespace:item_id"}</li>
 *   <li>ItemsAdder：{@code itemsadder: "namespace:item_id"}</li>
 *   <li>兼容写法：{@code plugin_item: "ce:namespace:item_id"} / {@code "ia:namespace:item_id"}，
 *       或直接写在 {@code type} 中</li>
 * </ul>
 * 自定义物品在打开菜单时才读取（ItemsAdder 在开服后才加载物品）；对应插件未安装或物品不存在时使用 {@code type}。
 */
record MenuItem(Material type, Integer customModelData, Source source, String id) {
    enum Source { CRAFTENGINE, ITEMSADDER }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 读取并校验图标；错误信息由调用方补上菜单文件名。 */
    static MenuItem read(ConfigurationSection item) {
        String rawType = item.getString("type", "PAPER").strip();
        Source source = null;
        String id = null;
        for (var entry : new String[][] {{"craftengine", "ce"}, {"itemsadder", "ia"}}) {
            String value = item.getString(entry[0]);
            if (value != null && !value.isBlank()) {
                source = entry[1].equals("ce") ? Source.CRAFTENGINE : Source.ITEMSADDER;
                id = value.strip();
                break;
            }
        }
        String prefixed = item.getString("plugin_item");
        if (source == null && prefixed == null && rawType.matches("(?i)(ce|ia):.+")) prefixed = rawType;
        if (source == null && prefixed != null && !prefixed.isBlank()) {
            String[] parts = prefixed.strip().split(":", 2);
            source = switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "ce", "craftengine" -> Source.CRAFTENGINE;
                case "ia", "itemsadder" -> Source.ITEMSADDER;
                default -> throw new IllegalArgumentException("plugin_item must start with ce: or ia:");
            };
            if (parts.length < 2) throw new IllegalArgumentException("plugin_item is missing the item id");
            id = parts[1].strip();
        }
        if (id != null && (id.contains("：") || !id.matches("[A-Za-z0-9_.\\-/]+:[A-Za-z0-9_.\\-/]+"))) {
            throw new IllegalArgumentException("custom item id must be namespace:item_id with an ASCII colon: " + id);
        }
        // 写了自定义物品时，type 只作为找不到物品时的备用外观
        Material type = rawType.matches("(?i)(ce|ia):.+") ? Material.PAPER : Material.matchMaterial(rawType);
        if (type == null || !type.isItem() || type.isAir()) throw new IllegalArgumentException("invalid material " + rawType);
        Integer model = item.contains("custom_model_data") ? item.getInt("custom_model_data") : null;
        return new MenuItem(type, model, source, id);
    }

    /** 生成图标物品，fallback 为原版物品类型（例如“放置方块”按钮显示当前方块）。 */
    ItemStack create(Material fallback) {
        ItemStack stack = source == null ? null : custom();
        if (stack == null) stack = new ItemStack(fallback);
        if (customModelData != null) applyModel(stack, customModelData);
        return stack;
    }

    ItemStack create() { return create(type); }

    @SuppressWarnings("deprecation")
    private static void applyModel(ItemStack stack, int model) {
        stack.editMeta(meta -> meta.setCustomModelData(model));
    }

    private ItemStack custom() {
        String pluginName = source == Source.CRAFTENGINE ? "CraftEngine" : "ItemsAdder";
        Plugin owner = Bukkit.getPluginManager().getPlugin(pluginName);
        if (owner == null || !owner.isEnabled()) {
            warn(pluginName + " is not installed; menu icon " + id + " uses its type instead.", null);
            return null;
        }
        try {
            ClassLoader loader = owner.getClass().getClassLoader();
            ItemStack stack = source == Source.CRAFTENGINE ? craftEngine(loader, id) : itemsAdder(loader, id);
            if (stack == null || stack.isEmpty()) warn(pluginName + " item " + id + " was not found; menu icon uses its type instead.", null);
            return stack == null || stack.isEmpty() ? null : stack.clone();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("Could not build " + pluginName + " item " + id + " for a menu icon", error);
            return null;
        }
    }

    /** CraftEngineItems.byId(Key.of("namespace:id")).buildItemStack() */
    private static ItemStack craftEngine(ClassLoader loader, String id) throws ReflectiveOperationException {
        Class<?> keyType = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
        Object key;
        try { key = keyType.getMethod("of", String.class).invoke(null, id); }
        catch (NoSuchMethodException missing) {
            String[] parts = id.split(":", 2);
            key = keyType.getMethod("of", String.class, String.class).invoke(null, parts[0], parts[1]);
        }
        Class<?> items = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems", true, loader);
        Method byId = items.getMethod("byId", keyType);
        Object custom = byId.invoke(null, key);
        if (custom == null) return null;
        // 优先在 API 声明的公开接口（CustomItem）上找方法：实现类不是 public 时直接反射调用会被拒绝
        for (Class<?> owner : new Class<?>[] {byId.getReturnType(), custom.getClass()}) {
            for (Method method : owner.getMethods()) {
                if (method.getName().equals("buildItemStack") && method.getParameterCount() == 0) {
                    return (ItemStack) call(method, custom);
                }
            }
            for (Method method : owner.getMethods()) {
                if (method.getName().equals("buildItemStack") && method.getParameterCount() == 1 && method.getParameterTypes()[0] == int.class) {
                    return (ItemStack) call(method, custom, 1);
                }
            }
        }
        throw new NoSuchMethodException("buildItemStack");
    }

    /** 反射调用；声明方法的类不是 public 时改用 setAccessible 再试一次。 */
    private static Object call(Method method, Object target, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(target, args); }
        catch (IllegalAccessException denied) {
            method.setAccessible(true);
            return method.invoke(target, args);
        }
    }

    /** CustomStack.getInstance("namespace:id").getItemStack() */
    private static ItemStack itemsAdder(ClassLoader loader, String id) throws ReflectiveOperationException {
        Class<?> stacks = Class.forName("dev.lone.itemsadder.api.CustomStack", true, loader);
        Object custom = call(stacks.getMethod("getInstance", String.class), null, id);
        return custom == null ? null : (ItemStack) call(stacks.getMethod("getItemStack"), custom);
    }

    /** 同一问题只记录一次日志，避免每次打开菜单刷屏。 */
    private void warn(String message, Throwable error) {
        if (!WARNED.add(message)) return;
        var plugin = Bukkit.getPluginManager().getPlugin("ALFakePlayer");
        var logger = plugin != null ? plugin.getLogger() : Bukkit.getLogger();
        if (error == null) logger.warning(message);
        else logger.log(Level.WARNING, message, error);
    }
}
