package io.github.dummyplayers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
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

import java.util.*;

final class Menus implements Listener {
    private record Button(MenuItem item, String name, List<String> lore, String action) { }
    /** empty：装备栏和副手为空时显示的占位图标，键为物品栏编号（36 靴子 … 39 头盔，40 副手）。 */
    private record Layout(String title, int[] slots, Map<Integer, Button> buttons, Map<Integer, Button> empty) { }
    private static final String EMPTY_SLOT = "empty_slot";
    private static final Map<String, Integer> EMPTY_SLOTS = Map.of("helmet", 39, "chestplate", 38, "leggings", 37, "boots", 36, "offhand", 40);
    private final DummyPlayers plugin;
    private final Map<String, Layout> layouts = new HashMap<>();
    private final Map<UUID, View> viewers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Roster.Entry> directionInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Roster.Entry> attackInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey actionKey, objectKey;
    private final String enabled, disabled, notSet, autoTool;

    private static final class View implements InventoryHolder {
        final Roster.Entry entry;
        final UUID viewer;
        final Layout layout;
        final Inventory inventory;
        long displayedRevision = -1;
        boolean local, acquiring;
        int age;

        View(Roster.Entry entry, UUID viewer, Layout layout) {
            this.entry = entry;
            this.viewer = viewer;
            this.layout = layout;
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
        enabled = yaml.getString("states.enabled", "&a开启");
        disabled = yaml.getString("states.disabled", "&c关闭");
        notSet = yaml.getString("states.not_set", "&7未设置");
        autoTool = yaml.getString("states.auto_tool", "&f自动匹配");
        layouts.putAll(readLayouts(yaml));
    }

    static void validate(YamlConfiguration yaml) { readLayouts(yaml); }

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
                if (!Set.of("", "open_gui:inventory", "open_gui:settings", "toggle:mine", "toggle:attack", "toggle:use", "toggle:unload", "toggle:pushable", "toggle:sneak", "select:placement", "direction", "close").contains(action)) {
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
            layouts.put(name, new Layout(section.getString("title", "%name%"), slots, Map.copyOf(buttons), Map.copyOf(empty)));
        }
        return layouts;
    }

    private static IllegalArgumentException bad(String section, String reason) {
        return new IllegalArgumentException(ConfigUpgrade.menuFile(section) + ": " + reason);
    }

    /**
     * 打开菜单。打开背包时先获取编辑租约（从数据库读取最新背包）；
     * 假人在其他子服运行或被编辑时以只读方式打开，并定时刷新。
     */
    void open(Player player, Roster.Entry entry, String menu) {
        if (!plugin.allowed(player, entry)) return;
        if (!menu.equals("inventory") || plugin.roster.holds(entry)) { show(player, entry, menu); return; }
        plugin.roster.acquire(entry, false).whenComplete((claim, error) -> {
            boolean acquired = error == null && claim.status() == Roster.Claim.ACQUIRED;
            if (!player.getScheduler().execute(plugin, () -> {
                if (!plugin.allowed(player, entry)) { if (acquired) maybeRelease(entry); return; }
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
            if (!plugin.allowed(player, view.entry)) { player.closeInventory(); return; }
            boolean local = plugin.editable(view.entry);
            if (local || local != view.local || view.displayedRevision != view.entry.inventoryRevision) refreshItems(view);
            if (view.layout == layouts.get("inventory") && !plugin.roster.holds(view.entry) && ++view.age % 4 == 0) {
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
        Layout inventory = layouts.get("inventory");
        for (View view : viewers.values()) if (view.entry == entry && view.layout == inventory) return true;
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
        if (!Objects.equals(shown, item)) view.inventory.setItem(index, DummyPlayers.copy(item));
    }

    private boolean placeholder(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasItemMeta()
            && EMPTY_SLOT.equals(stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING));
    }

    boolean watching(Roster.Entry entry) {
        View view = viewers.get(entry.key.owner());
        return view != null && view.entry == entry;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        int raw = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || !view.viewer.equals(player.getUniqueId())
            || !plugin.allowed(player, view.entry) || event instanceof InventoryCreativeEvent) { event.setCancelled(true); return; }
        boolean simple = event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT;
        boolean quick = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;
        if (raw >= view.inventory.getSize() && simple) return;
        event.setCancelled(true);
        if (raw < 0 || raw >= view.inventory.getSize()) return;
        int remote = view.layout.slots[raw];
        if (remote >= 0) {
            if (!simple && !quick) return;
            if (!plugin.editable(view.entry)) {
                if (plugin.roster.holds(view.entry)) plugin.msg(player, "remote_inventory");
                else plugin.msg(player, "remote_inventory_server", "server", plugin.serverLabel(plugin.roster.remoteHolder(view.entry)));
                return;
            }
            ItemStack current = DummyPlayers.copy(plugin.item(view.entry, remote));
            ItemStack shown = placeholder(event.getCurrentItem()) ? null : DummyPlayers.copy(event.getCurrentItem());
            if (!Objects.equals(current, shown)) { refreshItems(view); return; }
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
                if (player.getOpenInventory().getTopInventory() == view.inventory && plugin.allowed(player, view.entry)) beginDirection(player, view.entry);
            }, null, 1);
            return;
        }
        if (action.equals("toggle:attack")) {
            if (event.getClick() == ClickType.LEFT) view.entry.attack = !view.entry.attack;
            else if (event.getClick() == ClickType.RIGHT) {
                player.getScheduler().execute(plugin, () -> {
                    if (player.getOpenInventory().getTopInventory() == view.inventory && plugin.allowed(player, view.entry)) beginAttackInput(player, view.entry);
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
                    if (sample == null || !sample.getType().isBlock() || !(CraftMagicNumbers.getItem(sample.getType()) instanceof BlockItem)) {
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
            default -> { return; }
        }
        plugin.settingsChanged(view.entry);
        refreshButtons(view);
    }

    private void beginAttackInput(Player player, Roster.Entry entry) {
        attackInputs.put(player.getUniqueId(), entry);
        player.closeInventory();
        plugin.msg(player, "attack_interval_input", "ticks", entry.attackIntervalTicks);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Roster.Entry entry = attackInputs.remove(player.getUniqueId());
        if (entry == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        int ticks;
        try { ticks = Integer.parseInt(text); }
        catch (NumberFormatException invalid) { ticks = -1; }
        int requested = ticks;
        player.getScheduler().execute(plugin, () -> {
            if (!plugin.allowed(player, entry)) return;
            if (text.equalsIgnoreCase("cancel") || text.equals("取消")) { open(player, entry, "settings"); return; }
            if (requested < 20 || requested > 200) {
                attackInputs.put(player.getUniqueId(), entry);
                plugin.msg(player, "attack_interval_invalid");
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
        directionInputs.put(player.getUniqueId(), entry);
        player.closeInventory();
        plugin.msg(player, "direction_capture");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        Roster.Entry entry = directionInputs.remove(player.getUniqueId());
        if (entry == null) return;
        event.setCancelled(true);
        float yaw = player.getLocation().getYaw();
        DummyPlayers.Live active = plugin.live.get(entry.id);
        if ((active == null || active.dead) && plugin.allowed(player, entry) && plugin.forwardDirection(entry, yaw)) {
            player.getScheduler().execute(plugin, () -> {
                plugin.msg(player, "direction_set", "slot", entry.key.slot());
                if (plugin.allowed(player, entry)) open(player, entry, "settings");
            }, null, 1);
            return;
        }
        if (active == null || active.dead || !plugin.allowed(player, entry)) {
            player.getScheduler().execute(plugin, () -> {
                plugin.msg(player, "direction_offline");
                if (plugin.allowed(player, entry)) open(player, entry, "settings");
            }, null, 1);
            return;
        }
        boolean queued = active.dummy.getBukkitEntity().getScheduler().execute(plugin, () -> {
            if (active.dead || plugin.live.get(entry.id) != active) return;
            active.actions.direction(yaw);
            plugin.saveDirection(entry, yaw);
            player.getScheduler().execute(plugin, () -> {
                if (!plugin.allowed(player, entry)) return;
                plugin.msg(player, "direction_set", "slot", entry.key.slot());
                open(player, entry, "settings");
            }, null, 1);
        }, () -> player.getScheduler().execute(plugin, () -> {
            plugin.msg(player, "direction_offline");
            if (plugin.allowed(player, entry)) open(player, entry, "settings");
        }, null, 1), 1);
        if (!queued) directionInputs.remove(player.getUniqueId(), entry);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        View view = viewers.remove(player);
        if (view != null) maybeRelease(view.entry);
        directionInputs.remove(player);
        attackInputs.remove(player);
    }

    private void transfer(InventoryClickEvent event, Player player, View view, int raw, int remote, ItemStack current) {
        ItemStack cursor = DummyPlayers.copy(event.getView().getCursor());
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
        if (!plugin.replaceItem(view.entry, remote, current, next)) { refreshItems(view); return; }
        display(view, raw, remote, DummyPlayers.copy(next));
        event.getView().setCursor(hand);
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
