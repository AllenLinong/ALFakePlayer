package io.github.dummyplayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class RosterMenus implements Listener {
    private record Icon(MenuItem item, String name, List<String> lore, String action) { }
    private record Layout(String title, int size, Map<Integer, Icon> icons, int[] dummySlots, Icon dummyIcon) { }

    private enum Kind { MAIN, TIER, SPAWN_CONFIRM, BRING_CONFIRM, PURCHASE, PURCHASE_ITEMS }

    private static final class Panel implements InventoryHolder {
        final UUID player;
        final Kind kind;
        final int page, slot;
        final Roster.Entry entry;
        final DummyTier tier;
        final Layout layout;
        final Inventory inventory;
        final PurchasePrice price;
        /** 正在处理购买，忽略后续点击。只在玩家线程读写。 */
        boolean busy;

        Panel(DummyPlayers plugin, UUID player, Kind kind, int page, int pages, Roster.Entry entry, int slot, DummyTier tier, Layout layout) {
            this.player = player;
            this.kind = kind;
            this.page = page;
            this.entry = entry;
            this.slot = slot;
            this.tier = tier;
            this.layout = layout;
            this.price = kind == Kind.PURCHASE || kind == Kind.PURCHASE_ITEMS ? plugin.purchasePrice() : null;
            inventory = Bukkit.createInventory(this, layout.size, text(layout.title, plugin, entry, placeholders(plugin, page, pages, slot, tier)));
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private final DummyPlayers plugin;
    private final NamespacedKey actionKey, objectKey;
    private volatile Map<String, Layout> layouts;
    private volatile String summoned, summonedRemote, notSummoned;

    RosterMenus(DummyPlayers plugin, YamlConfiguration yaml) {
        this.plugin = plugin;
        actionKey = new NamespacedKey(plugin, "gui_action");
        objectKey = new NamespacedKey(plugin, "object_id");
        reload(yaml);
    }

    static void validate(YamlConfiguration yaml) { readLayouts(yaml); }

    /** 换用新的菜单配置（已校验）。已打开的菜单仍使用打开时的布局。 */
    void reload(YamlConfiguration yaml) {
        layouts = readLayouts(yaml);
        summoned = yaml.getString("states.summoned", "&a已召唤");
        summonedRemote = yaml.getString("states.summoned_remote", "&b在 %server% 运行");
        notSummoned = yaml.getString("states.not_summoned", "&7未召唤");
    }

    /** 关闭所有玩家打开的主菜单、档位选择、确认和购买物品菜单。 */
    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Panel) player.closeInventory();
            }, null, 1);
        }
    }

    private static Map<String, Layout> readLayouts(YamlConfiguration yaml) {
        Map<String, Layout> layouts = new HashMap<>();
        for (String name : List.of("main", "tier_select", "spawn_confirm", "teleport_confirm", "purchase_confirm", "purchase_items")) {
            ConfigurationSection section = yaml.getConfigurationSection(name);
            if (section == null) throw bad(name, "missing section");
            List<String> rows = section.getStringList("shape");
            if (rows.isEmpty() || rows.size() > 6 || rows.stream().anyMatch(row -> row.length() != 9)) {
                throw bad(name, "shape must have 1-6 rows with 9 columns");
            }
            int size = rows.size() * 9;
            Map<Integer, Icon> icons = new HashMap<>();
            List<Integer> dummySlots = new ArrayList<>();
            Icon dummyIcon = null;
            String shape = String.join("", rows);
            for (int slot = 0; slot < shape.length(); slot++) {
                char token = shape.charAt(slot);
                if (token == '.') continue;
                ConfigurationSection item = section.getConfigurationSection("items." + token);
                if (item == null) throw bad(name, "undefined symbol '" + token + "'");
                MenuItem look;
                try { look = MenuItem.read(item); }
                catch (IllegalArgumentException invalid) { throw bad(name, "'" + token + "': " + invalid.getMessage()); }
                String action = item.getString("action", "");
                if (!validAction(name, action)) throw bad(name, "unknown action for '" + token + "': " + action);
                Icon icon = new Icon(look, item.getString("name", " "), item.getStringList("lore"), action);
                if (name.equals("main") && token == 'A') {
                    if (dummyIcon == null) dummyIcon = icon;
                    else if (!dummyIcon.equals(icon)) throw bad(name, "all A slots must use the same item");
                    dummySlots.add(slot);
                } else icons.put(slot, icon);
            }
            if (name.equals("main") && (dummySlots.isEmpty() || dummyIcon == null)) throw bad(name, "requires at least one A slot");
            if (name.equals("purchase_items") && icons.values().stream().filter(icon -> icon.action.equals("purchase_sample")).count() != 3) {
                throw bad(name, "requires exactly three purchase_sample slots");
            }
            layouts.put(name, new Layout(section.getString("title", " "), size, Map.copyOf(icons),
                dummySlots.stream().mapToInt(Integer::intValue).toArray(), dummyIcon));
        }
        return Map.copyOf(layouts);
    }

    private static boolean validAction(String menu, String action) {
        if (action.isEmpty() || action.equals("close") || action.equals("none")) return true;
        if (menu.equals("main")) return Set.of("none", "dummy", "page:previous", "page:next", "refresh", "new_dummy").contains(action);
        if (menu.equals("tier_select")) return action.equals("cancel") || Set.of("tier:normal", "tier:pro", "tier:promax").contains(action);
        if (menu.equals("purchase_items")) return Set.of("purchase_sample", "save_purchase_items").contains(action);
        return Set.of("confirm", "cancel").contains(action);
    }

    private static IllegalArgumentException bad(String section, String reason) {
        return new IllegalArgumentException(ConfigUpgrade.menuFile(section) + ": " + reason);
    }

    void openMain(Player player, int requestedPage) {
        if (!player.hasPermission(DummyPlayers.USE)) { plugin.msg(player, "no_permission"); return; }
        Layout layout = layouts.get("main");
        List<Roster.Entry> owned = owned(player);
        int pages = Math.max(1, Math.ceilDiv(owned.size(), layout.dummySlots.length));
        int page = Math.clamp(requestedPage, 0, pages - 1);
        Panel panel = new Panel(plugin, player.getUniqueId(), Kind.MAIN, page, pages, null, 0, null, layout);
        PurchasePrice price = plugin.purchasePrice();
        for (var item : layout.icons.entrySet()) {
            Icon icon = item.getValue();
            ItemStack stack = render(icon, null, page, pages, 0, null, icon.action.equals("new_dummy") ? price : null);
            panel.inventory.setItem(item.getKey(), stack);
        }
        int start = page * layout.dummySlots.length;
        for (int index = 0; index < layout.dummySlots.length && start + index < owned.size(); index++) {
            Roster.Entry entry = owned.get(start + index);
            ItemStack stack = render(layout.dummyIcon, entry, page, pages, 0, null, null);
            stack.editMeta(meta -> meta.getPersistentDataContainer().set(objectKey, PersistentDataType.STRING, entry.id.toString()));
            panel.inventory.setItem(layout.dummySlots[index], stack);
        }
        open(player, panel);
    }

    private List<Roster.Entry> owned(Player player) {
        int slots = plugin.slots(player.getUniqueId());
        return plugin.roster.owned(player.getUniqueId()).stream().filter(entry -> entry.key.slot() <= slots).toList();
    }

    private void open(Player player, Panel panel) {
        if (!player.isOnline()) return;
        player.openInventory(panel.inventory);
    }

    private void openLater(Player player, Runnable action) {
        player.getScheduler().execute(plugin, action, null, 1);
    }

    private void openTier(Player player, Roster.Entry entry, int slot, int page) {
        openPanel(player, Kind.TIER, page, entry, slot, null, "tier_select");
    }

    private void openSpawnConfirm(Player player, Roster.Entry entry, int slot, int page, DummyTier tier) {
        openPanel(player, Kind.SPAWN_CONFIRM, page, entry, slot, tier, "spawn_confirm");
    }

    private void openBringConfirm(Player player, Roster.Entry entry, int page) {
        openPanel(player, Kind.BRING_CONFIRM, page, entry, entry.key.slot(), null, "teleport_confirm");
    }

    void openPurchaseItems(Player player) {
        openPanel(player, Kind.PURCHASE_ITEMS, 0, null, 0, null, "purchase_items");
    }

    private void openPanel(Player player, Kind kind, int page, Roster.Entry entry, int slot, DummyTier tier, String layoutName) {
        Layout layout = layouts.get(layoutName);
        Panel panel = new Panel(plugin, player.getUniqueId(), kind, page, 1, entry, slot, tier, layout);
        var samples = panel.price == null ? Collections.<ItemStack>emptyIterator() : panel.price.items().iterator();
        for (int index = 0; index < layout.size; index++) {
            Icon icon = layout.icons.get(index);
            if (icon == null) continue;
            if (kind == Kind.PURCHASE_ITEMS && icon.action.equals("purchase_sample")) {
                if (samples.hasNext()) panel.inventory.setItem(index, samples.next().clone());
                continue;
            }
            DummyTier iconTier = kind == Kind.TIER && icon.action.startsWith("tier:") ? DummyTier.fromName(icon.action.substring(5)) : tier;
            ItemStack stack = render(icon, entry, page, 1, slot, iconTier,
                kind == Kind.PURCHASE && icon.action.equals("confirm") ? panel.price : null);
            panel.inventory.setItem(index, stack);
        }
        open(player, panel);
    }

    /** 购买价格：金币、点券、物品各占一行，全部为空时显示免费。 */
    private List<Component> purchaseLore(PurchasePrice price) {
        var cost = new ArrayList<Component>();
        if (price.free()) {
            cost.add(plugin.menuMessage("purchase_cost_free", ""));
            return cost;
        }
        if (price.coins() > 0) {
            cost.add(plugin.menuMessage("purchase_cost_coins", java.math.BigDecimal.valueOf(price.coins()).stripTrailingZeros().toPlainString()));
        }
        if (price.points() > 0) cost.add(plugin.menuMessage("purchase_cost_points", String.valueOf(price.points())));
        if (!price.items().isEmpty()) cost.add(plugin.menuMessage("purchase_cost_items", ""));
        for (ItemStack needed : price.items()) {
            var itemMeta = needed.getItemMeta();
            Component name = itemMeta.customName();
            if (name == null) name = itemMeta.itemName();
            if (name == null) name = Component.translatable(needed.translationKey());
            cost.add(Component.text("  ").append(name).append(Component.text(" ×" + needed.getAmount()))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return cost;
    }

    private ItemStack render(Icon icon, Roster.Entry entry, int page, int pages, int slot, DummyTier tier, PurchasePrice price) {
        ItemStack stack = icon.item.create();
        var values = placeholders(plugin, page, pages, entry != null ? entry.key.slot() : slot, tier != null ? tier : entry == null ? null : entry.tier);
        values.put("status", status(entry));
        stack.editMeta(meta -> {
            meta.customName(text(icon.name, plugin, entry, values));
            var lore = new ArrayList<Component>();
            boolean inserted = false;
            for (String line : icon.lore) {
                if (price != null && line.equals("%purchase_cost%")) {
                    if (!inserted) lore.addAll(purchaseLore(price));
                    inserted = true;
                } else lore.add(text(line, plugin, entry, values));
            }
            if (price != null && !inserted) lore.addAll(purchaseLore(price));
            meta.lore(lore);
            var data = meta.getPersistentDataContainer();
            data.set(actionKey, PersistentDataType.STRING, icon.action);
            if (entry != null) data.set(objectKey, PersistentDataType.STRING, entry.id.toString());
        });
        return stack;
    }

    private String status(Roster.Entry entry) {
        if (entry == null) return notSummoned;
        if (plugin.summoned(entry)) return summoned;
        String remote = plugin.roster.remoteHolder(entry);
        return remote == null ? notSummoned : summonedRemote.replace("%server%", plugin.serverLabel(remote));
    }

    private static Map<String, String> placeholders(DummyPlayers plugin, int page, int pages, int slot, DummyTier tier) {
        Map<String, String> values = new HashMap<>();
        values.put("current_page", String.valueOf(page + 1));
        values.put("total_pages", String.valueOf(pages));
        values.put("page", String.valueOf(page + 1)); // 旧写法，兼容已有的自定义菜单
        values.put("slot", String.valueOf(slot));
        values.put("tier", tier == null ? "" : plugin.menuTierName(tier));
        values.put("price", tier == null ? "" : plugin.menuPrice(tier));
        return values;
    }

    private static Component text(String source, DummyPlayers plugin, Roster.Entry entry, Map<String, String> values) {
        String rendered = source;
        for (var value : values.entrySet()) rendered = rendered.replace("%" + value.getKey() + "%", value.getValue());
        Component component = DummyPlayers.TEXT.deserialize(rendered).replaceText(builder -> builder
            .matchLiteral("%name%").replacement(Component.text(entry != null ? entry.name() : values.getOrDefault("slot", ""))));
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /** 菜单图标不应出现在玩家身上，关闭菜单后检查一次（被其他插件或漏洞带出来时删除）。 */
    @EventHandler public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Panel && event.getPlayer() instanceof Player player) {
            plugin.menus.purgeLater(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Panel panel
            && (panel.kind != Kind.PURCHASE_ITEMS || event.getRawSlots().stream().anyMatch(slot -> slot < panel.inventory.getSize()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Panel panel)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !panel.player.equals(player.getUniqueId())
            || event instanceof InventoryCreativeEvent || panel.busy) return;
        int raw = event.getRawSlot();
        if (panel.kind == Kind.PURCHASE_ITEMS) {
            if (!player.hasPermission(DummyPlayers.ADMIN)) return;
            if (raw >= panel.inventory.getSize()) {
                if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) event.setCancelled(false);
                return;
            }
            Icon target = panel.layout.icons.get(raw);
            if (target == null) return;
            if (target.action.equals("purchase_sample") && event.getClick() == ClickType.RIGHT) panel.inventory.setItem(raw, null);
            if (target.action.equals("purchase_sample") && event.getClick() == ClickType.LEFT && !event.getCursor().getType().isAir()) {
                panel.inventory.setItem(raw, event.getCursor().clone());
            }
            if (target.action.equals("save_purchase_items") && event.getClick() == ClickType.LEFT) openLater(player, () -> {
                if (!player.hasPermission(DummyPlayers.ADMIN) || player.getOpenInventory().getTopInventory() != panel.inventory) return;
                var items = panel.layout.icons.entrySet().stream().filter(item -> item.getValue().action.equals("purchase_sample"))
                    .sorted(Map.Entry.comparingByKey()).map(item -> panel.inventory.getItem(item.getKey()))
                    .filter(Objects::nonNull).filter(item -> !item.getType().isAir()).map(ItemStack::clone).toList();
                if (panel.price != plugin.purchasePrice()) { plugin.msg(player, "purchase_changed"); player.closeInventory(); return; }
                player.closeInventory();
                plugin.savePurchaseItems(items).thenAccept(saved -> openLater(player,
                    () -> plugin.msg(player, saved ? "purchase_items_saved" : "storage_error")));
            });
            return;
        }
        if (!player.hasPermission(DummyPlayers.USE)) return;
        if (raw < 0 || raw >= panel.inventory.getSize()) return;
        if (panel.kind == Kind.MAIN && Arrays.stream(panel.layout.dummySlots).anyMatch(slot -> slot == raw)) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;
            String id = clicked.getItemMeta().getPersistentDataContainer().get(objectKey, PersistentDataType.STRING);
            if (id == null) return;
            Roster.Entry entry;
            try { entry = plugin.roster.byId.get(UUID.fromString(id)); }
            catch (IllegalArgumentException invalid) { return; }
            if (entry == null || !plugin.allowed(player, entry)) { openLater(player, () -> openMain(player, panel.page)); return; }
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                openLater(player, () -> plugin.menus.open(player, entry, "inventory"));
            } else if (event.getClick() == ClickType.SHIFT_LEFT) {
                // 收回后刷新菜单，显示新的状态；玩家已经关闭或切换了菜单时不再打开
                openLater(player, () -> plugin.despawnFromMenu(player, entry, () -> {
                    if (player.getOpenInventory().getTopInventory() == panel.inventory) openMain(player, panel.page);
                }));
            } else if (event.getClick() == ClickType.RIGHT) {
                plugin.requestTeleportFromMenu(player, entry.key.slot(), false);
            } else if (event.getClick() == ClickType.LEFT) {
                openLater(player, () -> {
                    if (plugin.summoned(entry) || plugin.roster.remoteHolder(entry) != null) openBringConfirm(player, entry, panel.page);
                    else openTier(player, entry, entry.key.slot(), panel.page);
                });
            }
            return;
        }
        Icon icon = panel.layout.icons.get(raw);
        ItemStack clicked = event.getCurrentItem();
        if (icon == null || clicked == null) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (!icon.action.equals(action)) return;
        if (panel.entry != null) {
            String id = clicked.getItemMeta().getPersistentDataContainer().get(objectKey, PersistentDataType.STRING);
            if (!panel.entry.id.toString().equals(id) || !plugin.allowed(player, panel.entry)) return;
        }
        if (event.getClick() == ClickType.LEFT) openLater(player, () -> {
            if (player.getOpenInventory().getTopInventory() != panel.inventory) return;
            if (!player.hasPermission(DummyPlayers.USE) || panel.entry != null && !plugin.allowed(player, panel.entry)) return;
            handle(player, panel, action);
        });
    }

    private void handle(Player player, Panel panel, String action) {
        switch (action) {
            case "none", "" -> { }
            case "close" -> player.closeInventory();
            case "page:previous" -> openLater(player, () -> openMain(player, panel.page - 1));
            case "page:next" -> openLater(player, () -> openMain(player, panel.page + 1));
            case "refresh" -> openLater(player, () -> openMain(player, panel.page));
            case "new_dummy" -> openPanel(player, Kind.PURCHASE, panel.page, null, 0, null, "purchase_confirm");
            case "cancel" -> openLater(player, () -> {
                if (panel.kind == Kind.SPAWN_CONFIRM) openTier(player, panel.entry, panel.slot, panel.page);
                else openMain(player, panel.page);
            });
            case "tier:normal", "tier:pro", "tier:promax" -> {
                DummyTier tier = DummyTier.fromName(action.substring(5));
                if (plugin.summonNeedsConfirm(tier)) {
                    openLater(player, () -> openSpawnConfirm(player, panel.entry, panel.slot, panel.page, tier));
                    return;
                }
                player.closeInventory();
                plugin.summonFromMenu(player, panel.entry, panel.slot, tier);
            }
            case "confirm" -> {
                if (panel.kind == Kind.PURCHASE) {
                    // 不先关闭界面：先关再开会让客户端把鼠标重置到屏幕中间。
                    // 处理期间忽略点击（防止重复购买），完成后直接切换到主菜单，鼠标保持原位
                    if (panel.busy) return;
                    panel.busy = true;
                    plugin.buyDummy(player, panel.price).thenAccept(number -> openLater(player, () -> {
                        if (player.getOpenInventory().getTopInventory() != panel.inventory) return;
                        int page = panel.page;
                        if (number > 0) {
                            long preceding = owned(player).stream().filter(entry -> entry.key.slot() < number).count();
                            page = (int) preceding / layouts.get("main").dummySlots.length;
                        }
                        openMain(player, page);
                    }));
                    return;
                }
                player.closeInventory();
                if (panel.kind == Kind.SPAWN_CONFIRM) plugin.summonFromMenu(player, panel.entry, panel.slot, panel.tier);
                else if (panel.kind == Kind.BRING_CONFIRM) plugin.requestTeleportFromMenu(player, panel.slot, true);
            }
            default -> { }
        }
    }

}
