package io.github.dummyplayers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.item.BlockItem;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.*;

final class Menus implements Listener {
    private record Button(MenuItem item, String name, List<String> lore, String action) { }
    /** empty：装备栏和副手为空时显示的占位图标，键为物品栏编号（36 靴子 … 39 头盔，40 副手）。 */
    private record Layout(String name, String title, int[] slots, Map<Integer, Button> buttons, Map<Integer, Button> empty) {
        boolean inventory() { return name.equals("inventory"); }
    }
    private static final String EMPTY_SLOT = "empty_slot";
    private static final Map<String, Integer> EMPTY_SLOTS = Map.of("helmet", 39, "chestplate", 38, "leggings", 37, "boots", 36, "offhand", 40);
    private final DummyPlayers plugin;
    private volatile Map<String, Layout> layouts;
    private final Map<UUID, View> viewers = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 等待玩家输入：攻击间隔在聊天输入数字，工作方向对空气挥手。60 秒内有效，可点击提示里的 [取消]。
     * token 区分同一玩家先后发起的输入，过期任务只清除自己那一次。
     */
    private record Pending(Roster.Entry entry, long token, long expires) {
        boolean expired() { return System.currentTimeMillis() >= expires; }
    }
    private static final int INPUT_SECONDS = 60;
    private final java.util.concurrent.atomic.AtomicLong inputTokens = new java.util.concurrent.atomic.AtomicLong();
    private final Map<UUID, Pending> directionInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Pending> attackInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey actionKey, objectKey, copyKey;
    private volatile String enabled, disabled, notSet, autoTool;

    private static final class View implements InventoryHolder {
        final Roster.Entry entry;
        final UUID viewer;
        final Layout layout;
        final Inventory inventory;
        /** 每个界面格子当前显示的真实物品（不带副本标记）；空格子和占位图标为 null。 */
        final ItemStack[] shown;
        long displayedRevision = -1;
        boolean local, acquiring;
        int age;

        View(Roster.Entry entry, UUID viewer, Layout layout) {
            this.entry = entry;
            this.viewer = viewer;
            this.layout = layout;
            shown = new ItemStack[layout.slots.length];
            inventory = Bukkit.createInventory(this, layout.slots.length,
                DummyPlayers.TEXT.deserialize(layout.title).replaceText(builder -> builder
                    .matchLiteral("%name%").replacement(Component.text(entry.name()))));
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    Menus(DummyPlayers plugin, YamlConfiguration yaml) {
        this.plugin = plugin;
        actionKey = new NamespacedKey(plugin, "gui_action");
        objectKey = new NamespacedKey(plugin, "object_id");
        copyKey = new NamespacedKey(plugin, "display_copy");
        reload(yaml);
    }

    static void validate(YamlConfiguration yaml) { readLayouts(yaml); }

    /** 换用新的菜单配置（已校验）。已打开的界面仍使用打开时的布局。 */
    void reload(YamlConfiguration yaml) {
        enabled = yaml.getString("states.enabled", "&a开启");
        disabled = yaml.getString("states.disabled", "&c关闭");
        notSet = yaml.getString("states.not_set", "&7未设置");
        autoTool = yaml.getString("states.auto_tool", "&f自动匹配");
        layouts = readLayouts(yaml);
    }

    private static Map<String, Layout> readLayouts(YamlConfiguration yaml) {
        Map<String, Layout> layouts = new HashMap<>();
        for (String name : List.of("inventory", "settings")) {
            ConfigurationSection section = yaml.getConfigurationSection(name);
            if (section == null) throw bad(name, "missing section");
            List<String> rows = section.getStringList("shape");
            if (rows.isEmpty() || rows.size() > 6 || rows.stream().anyMatch(row -> row.length() != 9)) throw bad(name, "shape must have 1-6 rows with 9 columns");
            int[] slots = new int[9 * rows.size()];
            Arrays.fill(slots, -1);
            Map<Integer, Button> buttons = new HashMap<>();
            int bag = 9, hotbar = 0, armor = 39, offhand = 0;
            String shape = String.join("", rows);
            for (int slot = 0; slot < shape.length(); slot++) {
                char token = shape.charAt(slot);
                if (token == '.') continue;
                if (name.equals("inventory")) {
                    if (token == 'B') { if (bag > 35) throw bad(name, "more than 27 B"); slots[slot] = bag++; continue; }
                    if (token == 'H') { if (hotbar > 8) throw bad(name, "more than 9 H"); slots[slot] = hotbar++; continue; }
                    if (token == 'E') { if (armor < 36) throw bad(name, "more than 4 E"); slots[slot] = armor--; continue; }
                    if (token == 'F') { if (offhand++ != 0) throw bad(name, "more than one F"); slots[slot] = 40; continue; }
                }
                ConfigurationSection item = section.getConfigurationSection("items." + token);
                if (item == null) throw bad(name, "undefined symbol '" + token + "'");
                MenuItem look;
                try { look = MenuItem.read(item); }
                catch (IllegalArgumentException invalid) { throw bad(name, "'" + token + "': " + invalid.getMessage()); }
                String action = item.getString("action", "");
                if (!Set.of("", "open_gui:inventory", "open_gui:settings", "toggle:mine", "toggle:attack", "toggle:use", "toggle:unload", "toggle:pushable", "toggle:sneak", "toggle:fish", "select:placement", "direction", "close").contains(action)) {
                    throw bad(name, "unknown action for '" + token + "': " + action);
                }
                buttons.put(slot, new Button(look, item.getString("name", " "), item.getStringList("lore"), action));
            }
            if (name.equals("inventory") && (bag != 36 || hotbar != 9 || armor != 35 || offhand != 1)) throw bad(name, "requires exactly 27 B, 9 H, 4 E and one F");
            Map<Integer, Button> empty = new HashMap<>();
            ConfigurationSection emptySection = name.equals("inventory") ? section.getConfigurationSection("empty_slots") : null;
            if (emptySection != null) for (String key : emptySection.getKeys(false)) {
                Integer slot = EMPTY_SLOTS.get(key);
                ConfigurationSection item = emptySection.getConfigurationSection(key);
                if (slot == null || item == null) throw bad(name, "empty_slots supports helmet, chestplate, leggings, boots and offhand: " + key);
                try { empty.put(slot, new Button(MenuItem.read(item), item.getString("name", " "), item.getStringList("lore"), EMPTY_SLOT)); }
                catch (IllegalArgumentException invalid) { throw bad(name, "empty_slots." + key + ": " + invalid.getMessage()); }
            }
            layouts.put(name, new Layout(name, section.getString("title", "%name%"), slots, Map.copyOf(buttons), Map.copyOf(empty)));
        }
        return Map.copyOf(layouts);
    }

    private static IllegalArgumentException bad(String section, String reason) {
        return new IllegalArgumentException(ConfigUpgrade.menuFile(section) + ": " + reason);
    }

    /**
     * 打开菜单。打开背包时先获取编辑租约（从数据库读取最新背包）；
     * 假人在其他子服运行或被编辑时以只读方式打开，并定时刷新。
     */
    void open(Player player, Roster.Entry entry, String menu) {
        if (!plugin.manages(player, entry)) return;
        if (!menu.equals("inventory") || plugin.roster.holds(entry)) { show(player, entry, menu); return; }
        plugin.roster.acquire(entry, false).whenComplete((claim, error) -> {
            boolean acquired = error == null && claim.status() == Roster.Claim.ACQUIRED;
            if (!player.getScheduler().execute(plugin, () -> {
                if (!plugin.manages(player, entry)) { if (acquired) maybeRelease(entry); return; }
                if (error != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not open the inventory of " + entry.profile(), error);
                    plugin.msg(player, "storage_error");
                    return;
                }
                switch (claim.status()) {
                    case MISSING -> plugin.msg(player, "not_found");
                    case HELD -> {
                        plugin.msg(player, "remote_inventory_server", "server", plugin.serverLabel(claim.holder()));
                        show(player, entry, menu);
                    }
                    case ACQUIRED -> show(player, entry, menu);
                }
            }, () -> { if (acquired) plugin.roster.release(entry, true); }, 1) && acquired) plugin.roster.release(entry, true);
        });
    }

    private void show(Player player, Roster.Entry entry, String menu) {
        var view = new View(entry, player.getUniqueId(), layouts.get(menu));
        refreshItems(view);
        refreshButtons(view);
        // 先登记新界面，再打开：旧界面关闭时据此判断是否还需要编辑租约
        viewers.put(view.viewer, view);
        if (player.openInventory(view.inventory) == null) {
            viewers.remove(view.viewer, view);
            maybeRelease(entry);
            return;
        }
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (viewers.get(view.viewer) != view) { task.cancel(); return; }
            if (!plugin.manages(player, view.entry)) { player.closeInventory(); return; }
            purge(player);
            boolean local = plugin.editable(view.entry);
            if (local || local != view.local || view.displayedRevision != view.entry.inventoryRevision) refreshItems(view);
            if (view.layout.inventory() && !plugin.roster.holds(view.entry) && ++view.age % 4 == 0) {
                remoteRefresh(player, view);
            }
        }, () -> { if (viewers.remove(view.viewer, view)) maybeRelease(view.entry); }, 10, 10);
    }

    /** 只读背包每 2 秒刷新一次；假人不再被其他子服占用时尝试转为可编辑。 */
    private void remoteRefresh(Player player, View view) {
        if (view.acquiring) return;
        if (plugin.roster.remoteHolder(view.entry) != null) {
            plugin.roster.refreshItems(view.entry);
            return;
        }
        view.acquiring = true;
        plugin.roster.acquire(view.entry, false).whenComplete((claim, error) -> {
            boolean acquired = error == null && claim.status() == Roster.Claim.ACQUIRED;
            if (!player.getScheduler().execute(plugin, () -> {
                view.acquiring = false;
                if (acquired && viewers.get(view.viewer) != view) maybeRelease(view.entry);
                else if (!acquired) plugin.roster.refreshItems(view.entry);
            }, () -> { if (acquired) maybeRelease(view.entry); }, 1) && acquired) maybeRelease(view.entry);
        });
    }

    /** 本服是否有玩家正在背包界面中编辑该假人。 */
    boolean editing(Roster.Entry entry) {
        for (View view : viewers.values()) if (view.entry == entry && view.layout.inventory()) return true;
        return false;
    }

    private void maybeRelease(Roster.Entry entry) {
        if (!editing(entry)) plugin.roster.release(entry, true);
    }

    private void refreshButtons(View view) {
        view.layout.buttons.forEach((slot, button) -> {
            Material placement = view.entry.placementBlock;
            // 「放置方块」按钮显示当前选择的方块；其他按钮使用配置的外观（支持自定义物品）
            ItemStack icon = button.action.equals("select:placement") && placement != null
                ? new ItemStack(placement) : button.item.create();
            icon.editMeta(meta -> {
                meta.customName(text(button.name, button, view.entry));
                meta.lore(button.lore.stream().map(line -> text(line, button, view.entry)).toList());
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, button.action);
                meta.getPersistentDataContainer().set(objectKey, PersistentDataType.STRING, view.entry.id.toString());
            });
            view.inventory.setItem(slot, icon);
        });
    }

    private Component text(String source, Button button, Roster.Entry entry) {
        boolean on = switch (button.action) {
            case "toggle:mine" -> entry.mine;
            case "toggle:attack" -> entry.attack;
            case "toggle:use" -> entry.use;
            case "toggle:unload" -> plugin.unloadChests && entry.autoUnload;
            case "toggle:pushable" -> entry.pushable;
            case "toggle:sneak" -> entry.sneaking;
            case "toggle:fish" -> entry.fishing;
            default -> false;
        };
        return DummyPlayers.TEXT.deserialize(source.replace("%state%", on ? enabled : disabled)
                .replace("%tool%", autoTool).replace("%attack_interval%", String.valueOf(entry.attackIntervalTicks)))
            .replaceText(builder -> builder.matchLiteral("%name%").replacement(Component.text(entry.name())))
            .replaceText(builder -> builder.matchLiteral("%placement_block%").replacement(entry.placementBlock == null
                ? DummyPlayers.TEXT.deserialize(notSet) : Component.translatable(entry.placementBlock.translationKey())))
            .decoration(TextDecoration.ITALIC, false);
    }

    private void refreshItems(View view) {
        view.displayedRevision = view.entry.inventoryRevision;
        view.local = plugin.editable(view.entry);
        for (int index = 0; index < view.layout.slots.length; index++) {
            int remote = view.layout.slots[index];
            if (remote < 0) continue;
            ItemStack item = plugin.item(view.entry, remote);
            if (item != null && item.getType().isAir()) item = null;
            display(view, index, remote, item);
        }
    }

    /** 显示某个物品栏格子；装备栏和副手为空时显示占位图标（模拟原版背包里的盔甲、盾牌轮廓）。 */
    private void display(View view, int index, int remote, ItemStack item) {
        ItemStack shown = view.inventory.getItem(index);
        Button empty = item == null ? view.layout.empty.get(remote) : null;
        if (empty != null) {
            view.shown[index] = null;
            if (!placeholder(shown)) {
                ItemStack icon = empty.item.create();
                icon.editMeta(meta -> {
                    meta.customName(text(empty.name, empty, view.entry));
                    meta.lore(empty.lore.stream().map(line -> text(line, empty, view.entry)).toList());
                    meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, EMPTY_SLOT);
                });
                view.inventory.setItem(index, icon);
            }
            return;
        }
        // 界面里放的是带标记的副本。只在内容变化或界面格子被改动过时重新放入，避免每次刷新都复制物品
        boolean intact = item == null ? shown == null || shown.isEmpty()
            : shown != null && shown.getType() == item.getType() && shown.getAmount() == item.getAmount();
        if (intact && Objects.equals(view.shown[index], item)) return;
        view.shown[index] = DummyPlayers.copy(item);
        view.inventory.setItem(index, item == null ? null : marked(item));
    }

    /** 界面显示用的副本：真正的物品只在假人背包里，带这个标记的物品出现在玩家身上时会被删除。 */
    private ItemStack marked(ItemStack item) {
        ItemStack copy = item.clone();
        copy.editPersistentDataContainer(data -> data.set(copyKey, PersistentDataType.BYTE, (byte) 1));
        return copy;
    }

    /** 菜单图标或背包界面的显示副本。正常操作拿不到，出现在玩家身上说明被其他插件或漏洞带了出来。 */
    boolean menuItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var data = stack.getPersistentDataContainer();
        return data.has(copyKey) || data.has(actionKey);
    }

    /** 删除玩家背包和鼠标上的菜单图标、显示副本。只扫描这名玩家的 41 格和鼠标。 */
    void purge(Player player) {
        int removed = 0;
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (menuItem(contents[slot])) { inventory.setItem(slot, null); removed++; }
        }
        if (menuItem(player.getItemOnCursor())) { player.setItemOnCursor(null); removed++; }
        if (removed > 0) plugin.getLogger().warning("Removed " + removed + " copied menu item(s) from " + player.getName()
            + "; another plugin or a glitch moved them out of an ALFakePlayer menu.");
    }

    /** 关闭菜单后，服务器会把鼠标上的物品放回背包，所以下一刻再检查一次。 */
    void purgeLater(Player player) {
        purge(player);
        player.getScheduler().execute(plugin, () -> purge(player), null, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (!menuItem(event.getItemDrop().getItemStack())) return;
        event.getItemDrop().remove();
        plugin.getLogger().warning(event.getPlayer().getName() + " dropped a copied ALFakePlayer menu item; it was removed.");
    }

    private boolean placeholder(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasItemMeta()
            && EMPTY_SLOT.equals(stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING));
    }

    /** 本服是否有玩家（拥有者或管理员）打开着该假人的界面。 */
    boolean watching(Roster.Entry entry) {
        for (View view : viewers.values()) if (view.entry == entry) return true;
        return false;
    }

    /** 不忽略已取消的点击：界面里的物品全部由这里处理，其他插件取消点击不应让 Shift 等操作失效。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        int raw = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || !view.viewer.equals(player.getUniqueId())
            || !plugin.manages(player, view.entry) || event instanceof InventoryCreativeEvent) { event.setCancelled(true); return; }
        boolean simple = event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT;
        boolean quick = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;
        // 自己背包里的普通点击交给原版处理（被其他插件取消的保持取消）
        if (raw >= view.inventory.getSize() && simple) return;
        event.setCancelled(true);
        if (raw >= view.inventory.getSize() && quick) { quickDeposit(event, player, view); return; }
        if (raw < 0 || raw >= view.inventory.getSize()) return;
        int remote = view.layout.slots[raw];
        if (remote >= 0) {
            if (!simple && !quick) return;
            if (!plugin.editable(view.entry)) { notEditable(player, view); return; }
            // 以假人背包的实际内容为准：和玩家看到的不一致时只刷新界面，不做任何移动
            ItemStack current = DummyPlayers.copy(plugin.item(view.entry, remote));
            if (!Objects.equals(current, view.shown[raw])) { refreshItems(view); return; }
            if (quick) quickWithdraw(player, view, raw, remote, current);
            else transfer(event, player, view, raw, remote, current);
            return;
        }
        if (!simple && !quick) return;
        Button button = view.layout.buttons.get(raw);
        ItemStack icon = event.getCurrentItem();
        if (button == null || icon == null) return;
        var data = icon.getItemMeta().getPersistentDataContainer();
        String action = data.get(actionKey, PersistentDataType.STRING);
        if (!button.action.equals(action) || !view.entry.id.toString().equals(data.get(objectKey, PersistentDataType.STRING))) return;
        if (quick && !action.equals("toggle:attack")) return;
        if (simple && (action.equals("close") || action.startsWith("open_gui:"))) {
            player.getScheduler().execute(plugin, () -> {
                if (player.getOpenInventory().getTopInventory() != view.inventory) return;
                if (action.equals("close")) player.closeInventory();
                else open(player, view.entry, action.substring("open_gui:".length()));
            }, null, 1);
            return;
        }
        if (action.equals("direction")) {
            if (event.getClick() == ClickType.LEFT) player.getScheduler().execute(plugin, () -> {
                if (player.getOpenInventory().getTopInventory() == view.inventory && plugin.manages(player, view.entry)) beginDirection(player, view.entry);
            }, null, 1);
            return;
        }
        if (action.equals("toggle:attack")) {
            if (event.getClick() == ClickType.LEFT) view.entry.attack = !view.entry.attack;
            else if (event.getClick() == ClickType.RIGHT) {
                player.getScheduler().execute(plugin, () -> {
                    if (player.getOpenInventory().getTopInventory() == view.inventory && plugin.manages(player, view.entry)) beginAttackInput(player, view.entry);
                }, null, 1);
                return;
            }
            else if (event.getClick() == ClickType.SHIFT_LEFT) view.entry.attackIntervalTicks = Math.max(20, view.entry.attackIntervalTicks - 20);
            else if (event.getClick() == ClickType.SHIFT_RIGHT) view.entry.attackIntervalTicks = Math.min(200, view.entry.attackIntervalTicks + 20);
            else return;
        } else switch (action) {
            case "select:placement" -> {
                Material selected = null;
                if (event.getClick() == ClickType.LEFT) {
                    ItemStack sample = event.getCursor();
                    // 方块，或种子、胡萝卜等能种下的物品（它们也是放置方块的物品）
                    if (sample == null || sample.isEmpty() || !(CraftMagicNumbers.getItem(sample.getType()) instanceof BlockItem)) {
                        plugin.msg(player, "placement_sample");
                        return;
                    }
                    selected = sample.getType();
                }
                if (view.entry.placementBlock == selected) return;
                view.entry.placementBlock = selected;
            }
            case "toggle:mine" -> {
                if (event.getClick() != ClickType.LEFT) return;
                view.entry.mine = !view.entry.mine;
            }
            case "toggle:attack" -> { return; }
            case "toggle:use" -> view.entry.use = !view.entry.use;
            case "toggle:unload" -> {
                if (event.getClick() != ClickType.LEFT || !plugin.unloadChests) return;
                view.entry.autoUnload = !view.entry.autoUnload;
            }
            case "toggle:pushable" -> {
                if (event.getClick() != ClickType.LEFT) return;
                view.entry.pushable = !view.entry.pushable;
            }
            case "toggle:sneak" -> {
                if (event.getClick() != ClickType.LEFT) return;
                view.entry.sneaking = !view.entry.sneaking;
            }
            case "toggle:fish" -> {
                if (event.getClick() != ClickType.LEFT) return;
                view.entry.fishing = !view.entry.fishing;
            }
            default -> { return; }
        }
        plugin.settingsChanged(view.entry);
        refreshButtons(view);
    }

    private void beginAttackInput(Player player, Roster.Entry entry) {
        player.closeInventory();
        awaitInput(player, attackInputs, entry, "attack_interval_input", "ticks", entry.attackIntervalTicks, "seconds", INPUT_SECONDS);
    }

    /** 开始等待输入（同一玩家同时只等一项，新的替换旧的），发送带 [取消] 的提示，到期后自动取消。 */
    private void awaitInput(Player player, Map<UUID, Pending> inputs, Roster.Entry entry, String key, Object... values) {
        UUID id = player.getUniqueId();
        directionInputs.remove(id);
        attackInputs.remove(id);
        Pending pending = new Pending(entry, inputTokens.incrementAndGet(), System.currentTimeMillis() + INPUT_SECONDS * 1000L);
        inputs.put(id, pending);
        prompt(player, inputs, pending, key, values);
        player.getScheduler().runDelayed(plugin, task -> {
            if (inputs.remove(id, pending)) plugin.msg(player, "input_expired", "seconds", INPUT_SECONDS);
        }, null, INPUT_SECONDS * 20L);
    }

    /** 发送提示并附上可点击的 [取消]：点击后取消这次输入，回到行为设置。 */
    private void prompt(Player player, Map<UUID, Pending> inputs, Pending pending, String key, Object... values) {
        UUID id = player.getUniqueId();
        long lifetime = Math.max(1, pending.expires() - System.currentTimeMillis());
        Component cancel = plugin.messageText("input_cancel")
            .hoverEvent(HoverEvent.showText(plugin.messageText("input_cancel_hover")))
            .clickEvent(ClickEvent.callback(audience -> player.getScheduler().execute(plugin, () -> cancelInput(player, inputs, pending), null, 1),
                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMillis(lifetime)).build()));
        player.sendMessage(plugin.message(key, values).append(Component.space()).append(cancel));
    }

    private void cancelInput(Player player, Map<UUID, Pending> inputs, Pending pending) {
        if (!inputs.remove(player.getUniqueId(), pending)) return;
        plugin.msg(player, "input_cancelled");
        if (plugin.manages(player, pending.entry())) open(player, pending.entry(), "settings");
    }

    /**
     * 旧版聊天事件（不少聊天插件用它处理聊天）：等待输入时在最早的优先级取消，
     * 聊天插件就不会把输入的内容发出去。Paper 会把取消状态带到随后的 AsyncChatEvent。
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        if (attackInputs.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** 读取攻击间隔输入。在最早的优先级取消聊天，输入的内容（包括「取消」）不会发到聊天里。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending pending = attackInputs.remove(player.getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        Roster.Entry entry = pending.entry();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        int ticks;
        try { ticks = Integer.parseInt(text); }
        catch (NumberFormatException invalid) { ticks = -1; }
        int requested = ticks;
        player.getScheduler().execute(plugin, () -> {
            if (!plugin.manages(player, entry)) return;
            if (text.equalsIgnoreCase("cancel") || text.equals("取消")) {
                attackInputs.put(player.getUniqueId(), pending);
                cancelInput(player, attackInputs, pending);
                return;
            }
            if (requested < 20 || requested > 200) {
                if (pending.expired()) { plugin.msg(player, "input_expired", "seconds", INPUT_SECONDS); return; }
                attackInputs.put(player.getUniqueId(), pending);
                prompt(player, attackInputs, pending, "attack_interval_invalid");
                return;
            }
            entry.attackIntervalTicks = requested;
            plugin.settingsChanged(entry);
            plugin.msg(player, "attack_interval_set", "ticks", requested);
            open(player, entry, "settings");
        }, null, 1);
    }

    private void beginDirection(Player player, Roster.Entry entry) {
        DummyPlayers.Live active = plugin.live.get(entry.id);
        if ((active == null || active.dead) && plugin.roster.remoteHolder(entry) == null) {
            plugin.msg(player, "direction_offline");
            return;
        }
        player.closeInventory();
        awaitInput(player, directionInputs, entry, "direction_capture", "seconds", INPUT_SECONDS);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        Pending pending = directionInputs.remove(player.getUniqueId());
        if (pending == null) return;
        Roster.Entry entry = pending.entry();
        event.setCancelled(true);
        float yaw = player.getLocation().getYaw();
        DummyPlayers.Live active = plugin.live.get(entry.id);
        if ((active == null || active.dead) && plugin.manages(player, entry) && plugin.forwardDirection(entry, yaw)) {
            player.getScheduler().execute(plugin, () -> {
                plugin.msg(player, "direction_set", "slot", entry.key.slot());
                if (plugin.manages(player, entry)) open(player, entry, "settings");
            }, null, 1);
            return;
        }
        if (active == null || active.dead || !plugin.manages(player, entry)) {
            player.getScheduler().execute(plugin, () -> {
                plugin.msg(player, "direction_offline");
                if (plugin.manages(player, entry)) open(player, entry, "settings");
            }, null, 1);
            return;
        }
        boolean queued = active.dummy.getBukkitEntity().getScheduler().execute(plugin, () -> {
            if (active.dead || plugin.live.get(entry.id) != active) return;
            active.actions.direction(yaw);
            plugin.saveDirection(entry, yaw);
            player.getScheduler().execute(plugin, () -> {
                if (!plugin.manages(player, entry)) return;
                plugin.msg(player, "direction_set", "slot", entry.key.slot());
                open(player, entry, "settings");
            }, null, 1);
        }, () -> player.getScheduler().execute(plugin, () -> {
            plugin.msg(player, "direction_offline");
            if (plugin.manages(player, entry)) open(player, entry, "settings");
        }, null, 1), 1);
        if (!queued) directionInputs.remove(player.getUniqueId(), pending);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        View view = viewers.remove(player);
        if (view != null) maybeRelease(view.entry);
        directionInputs.remove(player);
        attackInputs.remove(player);
    }

    private void notEditable(Player player, View view) {
        if (plugin.roster.holds(view.entry)) plugin.msg(player, "remote_inventory");
        else plugin.msg(player, "remote_inventory_server", "server", plugin.serverLabel(plugin.roster.remoteHolder(view.entry)));
    }

    private void transfer(InventoryClickEvent event, Player player, View view, int raw, int remote, ItemStack current) {
        ItemStack cursor = DummyPlayers.copy(event.getView().getCursor());
        if (menuItem(cursor)) { event.getView().setCursor(null); purge(player); return; }
        boolean right = event.getClick() == ClickType.RIGHT;
        if (cursor != null && !accepts(remote, cursor)) { plugin.msg(player, "wrong_equipment"); return; }
        boolean equipment = remote >= 36 && remote < 40;
        if (cursor == null && current == null) return;
        ItemStack next;
        ItemStack hand;
        if (cursor == null) {
            hand = current.clone();
            int amount = right ? (current.getAmount() + 1) / 2 : current.getAmount();
            hand.setAmount(amount);
            next = remainder(current, amount);
        } else if (current == null || !equipment && current.isSimilar(cursor)) {
            int available = (equipment ? 1 : cursor.getMaxStackSize()) - (current == null ? 0 : current.getAmount());
            int amount = Math.min(available, right ? 1 : cursor.getAmount());
            if (amount <= 0) return;
            next = current == null ? cursor.clone() : current.clone();
            next.setAmount((current == null ? 0 : current.getAmount()) + amount);
            hand = remainder(cursor, amount);
        } else {
            if (right || cursor.getAmount() > (equipment ? 1 : cursor.getMaxStackSize())) return;
            next = cursor;
            hand = current;
        }
        // 先扣后给：放入时先改鼠标再放进假人背包，失败就还原鼠标；取出时先从假人背包扣除再给鼠标
        if (cursor != null) event.getView().setCursor(hand);
        if (!plugin.replaceItem(view.entry, remote, current, next)) {
            if (cursor != null) event.getView().setCursor(cursor);
            refreshItems(view);
            return;
        }
        if (cursor == null) event.getView().setCursor(hand);
        display(view, raw, remote, DummyPlayers.copy(next));
    }

    /**
     * Shift 点击自己背包里的物品：放进假人背包。盔甲优先穿到空着的对应装备栏；
     * 其余先叠到同种物品上，再放主背包空格，主背包满了再放快捷栏（不放进手持的第 1 格）。
     * 每一步先从玩家背包扣除，再放进假人背包，放入失败就退回，出错时最多少给，不会多出物品。
     */
    private void quickDeposit(InventoryClickEvent event, Player player, View view) {
        if (!view.layout.inventory() || !(event.getClickedInventory() instanceof PlayerInventory)) return;
        int source = event.getSlot();
        ItemStack moving = DummyPlayers.copy(event.getCurrentItem());
        if (moving == null) return;
        if (menuItem(moving)) { purge(player); return; }
        if (!plugin.editable(view.entry)) { notEditable(player, view); return; }
        var inventory = player.getInventory();
        List<Integer> targets = new ArrayList<>();
        var equippable = moving.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable != null) {
            int armor = switch (equippable.slot()) {
                case FEET -> 36; case LEGS -> 37; case CHEST -> 38; case HEAD -> 39; default -> -1;
            };
            if (armor >= 0) targets.add(armor);
        }
        for (int slot = 9; slot < 36; slot++) targets.add(slot);
        for (int slot = 0; slot < 9; slot++) targets.add(slot);
        boolean changed = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int target : targets) {
                ItemStack held = DummyPlayers.copy(inventory.getItem(source));
                if (held == null || !held.isSimilar(moving)) { pass = 2; break; }
                ItemStack current = DummyPlayers.copy(plugin.item(view.entry, target));
                boolean armor = target >= 36;
                // 第一轮只叠加到同种物品上；第二轮放空格子（装备栏只在第二轮、且物品能穿在这里时使用）
                if (pass == 0 ? current == null || armor || !current.isSimilar(held) : current != null || target == 0) continue;
                if (armor && !accepts(target, held)) continue;
                int limit = armor ? 1 : held.getMaxStackSize();
                int amount = Math.min(held.getAmount(), limit - (current == null ? 0 : current.getAmount()));
                if (amount <= 0) continue;
                ItemStack next = current == null ? held.asQuantity(amount) : current.asQuantity(current.getAmount() + amount);
                inventory.setItem(source, held.getAmount() == amount ? null : held.asQuantity(held.getAmount() - amount));
                if (!plugin.replaceItem(view.entry, target, current, next)) {
                    inventory.setItem(source, held);
                    pass = 2;
                    break;
                }
                changed = true;
            }
        }
        if (changed) refreshItems(view);
    }

    private void quickWithdraw(Player player, View view, int raw, int remote, ItemStack current) {
        if (current == null) return;
        var inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        int remaining = current.getAmount();
        int limit = Math.min(current.getMaxStackSize(), inventory.getMaxStackSize());
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.isEmpty() || !stack.isSimilar(current)) continue;
            int moved = Math.min(remaining, limit - stack.getAmount());
            if (moved <= 0) continue;
            storage[slot] = stack.clone();
            storage[slot].setAmount(stack.getAmount() + moved);
            remaining -= moved;
        }
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            if (storage[slot] != null && !storage[slot].isEmpty()) continue;
            int moved = Math.min(remaining, limit);
            storage[slot] = current.clone();
            storage[slot].setAmount(moved);
            remaining -= moved;
        }
        if (remaining == current.getAmount()) return;
        ItemStack next = remainder(current, current.getAmount() - remaining);
        if (!plugin.replaceItem(view.entry, remote, current, next)) { refreshItems(view); return; }
        inventory.setStorageContents(storage);
        display(view, raw, remote, DummyPlayers.copy(next));
    }

    private static ItemStack remainder(ItemStack item, int consumed) {
        if (item.getAmount() <= consumed) return null;
        ItemStack remaining = item.clone();
        remaining.setAmount(item.getAmount() - consumed);
        return remaining;
    }

    private static boolean accepts(int slot, ItemStack stack) {
        if (slot < 36 || slot == 40) return true;
        var equippable = stack.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) return false;
        return switch (slot) {
            case 36 -> equippable.slot() == EquipmentSlot.FEET;
            case 37 -> equippable.slot() == EquipmentSlot.LEGS;
            case 38 -> equippable.slot() == EquipmentSlot.CHEST;
            case 39 -> equippable.slot() == EquipmentSlot.HEAD;
            default -> false;
        };
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View view
            && event.getRawSlots().stream().anyMatch(slot -> slot < view.inventory.getSize())) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) return;
        viewers.remove(view.viewer, view);
        maybeRelease(view.entry);
        if (event.getPlayer() instanceof Player player) purgeLater(player);
    }

    void close(Roster.Entry entry) {
        for (View view : List.copyOf(viewers.values())) if (view.entry == entry) close(view);
    }

    void closeAll() { List.copyOf(viewers.values()).forEach(this::close); }

    private void close(View view) {
        Runnable forget = () -> { if (viewers.remove(view.viewer, view)) maybeRelease(view.entry); };
        Player player = Bukkit.getPlayer(view.viewer);
        if (player == null) forget.run();
        else if (!player.getScheduler().execute(plugin, () -> {
            if (player.getOpenInventory().getTopInventory() == view.inventory) player.closeInventory();
            forget.run();
        }, forget, 1)) forget.run();
    }
}
