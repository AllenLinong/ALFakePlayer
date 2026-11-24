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
    private record Button(Material type, String name, List<String> lore, String action) { }
    private record Layout(String title, int[] slots, Map<Integer, Button> buttons) { }
    private final DummyPlayers plugin;
    private final Map<String, Layout> layouts = new HashMap<>();
    private final Map<UUID, View> viewers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Roster.Entry> directionInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Roster.Entry> attackInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey actionKey, objectKey;
    private final String enabled, disabled;

    private static final class View implements InventoryHolder {
        final Roster.Entry entry;
        final UUID viewer;
        final Layout layout;
        final Inventory inventory;
        long displayedRevision = -1;
        boolean local;

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
                Material type = Material.matchMaterial(item.getString("type", "PAPER"));
                if (type == null || !type.isItem() || type.isAir()) throw bad(name, "invalid material for '" + token + "'");
                String action = item.getString("action", "");
                if (!Set.of("", "open_gui:inventory", "open_gui:settings", "toggle:mine", "toggle:attack", "toggle:use", "toggle:unload", "toggle:pushable", "toggle:sneak", "select:placement", "direction", "close").contains(action)) {
                    throw bad(name, "unknown action for '" + token + "': " + action);
                }
                buttons.put(slot, new Button(type, item.getString("name", " "), item.getStringList("lore"), action));
            }
            if (name.equals("inventory") && (bag != 36 || hotbar != 9 || armor != 35 || offhand != 1)) throw bad(name, "requires exactly 27 B, 9 H, 4 E and one F");
            layouts.put(name, new Layout(section.getString("title", "%name%"), slots, Map.copyOf(buttons)));
        }
        return layouts;
    }

    private static IllegalArgumentException bad(String section, String reason) {
        return new IllegalArgumentException(ConfigUpgrade.menuFile(section) + ": " + reason);
    }

    void open(Player player, Roster.Entry entry, String menu) {
        if (!plugin.allowed(player, entry)) return;
        var view = new View(entry, player.getUniqueId(), layouts.get(menu));
        refreshItems(view);
        refreshButtons(view);
        if (player.openInventory(view.inventory) != null) {
            viewers.put(view.viewer, view);
            player.getScheduler().runAtFixedRate(plugin, task -> {
                if (viewers.get(view.viewer) != view) { task.cancel(); return; }
                if (!plugin.allowed(player, view.entry)) player.closeInventory();
                else {
                    boolean local = plugin.editable(view.entry);
                    if (local || local != view.local || view.displayedRevision != view.entry.inventoryRevision) refreshItems(view);
                }
            }, () -> viewers.remove(view.viewer, view), 10, 10);
        }
    }

    private void refreshButtons(View view) {
        view.layout.buttons.forEach((slot, button) -> {
            Material placement = view.entry.placementBlock;
            ItemStack icon = new ItemStack(switch (button.action) {
                case "select:placement" -> placement == null ? button.type : placement;
                default -> button.type;
            });
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
                .replace("%tool%", "自动匹配").replace("%attack_interval%", String.valueOf(entry.attackIntervalTicks)))
            .replaceText(builder -> builder.matchLiteral("%name%").replacement(Component.text(entry.name())))
            .replaceText(builder -> builder.matchLiteral("%placement_block%").replacement(entry.placementBlock == null
                ? Component.text("未设置") : Component.translatable(entry.placementBlock.translationKey())))
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
            if (!Objects.equals(view.inventory.getItem(index), item)) view.inventory.setItem(index, DummyPlayers.copy(item));
        }
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
            if (!plugin.editable(view.entry)) { plugin.msg(player, "remote-inventory"); return; }
            ItemStack current = DummyPlayers.copy(plugin.item(view.entry, remote));
            if (!Objects.equals(current, DummyPlayers.copy(event.getCurrentItem()))) { refreshItems(view); return; }
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
                        plugin.msg(player, "placement-sample");
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
        plugin.roster.dirty(view.entry);
        plugin.roster.save();
        view.entry.wakeWork();
        refreshButtons(view);
    }

    private void beginAttackInput(Player player, Roster.Entry entry) {
        attackInputs.put(player.getUniqueId(), entry);
        player.closeInventory();
        plugin.msg(player, "attack-interval-input", "ticks", entry.attackIntervalTicks);
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
                plugin.msg(player, "attack-interval-invalid");
                return;
            }
            entry.attackIntervalTicks = requested;
            plugin.roster.dirty(entry);
            plugin.roster.save();
            entry.wakeWork();
            plugin.msg(player, "attack-interval-set", "ticks", requested);
            open(player, entry, "settings");
        }, null, 1);
    }

    private void beginDirection(Player player, Roster.Entry entry) {
        DummyPlayers.Live active = plugin.live.get(entry.id);
        if (active == null || active.dead) { plugin.msg(player, "direction-offline"); return; }
        directionInputs.put(player.getUniqueId(), entry);
        player.closeInventory();
        plugin.msg(player, "direction-capture");
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
        if (active == null || active.dead || !plugin.allowed(player, entry)) {
            player.getScheduler().execute(plugin, () -> {
                plugin.msg(player, "direction-offline");
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
                plugin.msg(player, "direction-set", "slot", entry.key.slot());
                open(player, entry, "settings");
            }, null, 1);
        }, () -> player.getScheduler().execute(plugin, () -> {
            plugin.msg(player, "direction-offline");
            if (plugin.allowed(player, entry)) open(player, entry, "settings");
        }, null, 1), 1);
        if (!queued) directionInputs.remove(player.getUniqueId(), entry);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        viewers.remove(player);
        directionInputs.remove(player);
        attackInputs.remove(player);
    }

    private void transfer(InventoryClickEvent event, Player player, View view, int raw, int remote, ItemStack current) {
        ItemStack cursor = DummyPlayers.copy(event.getView().getCursor());
        boolean right = event.getClick() == ClickType.RIGHT;
        if (cursor != null && !accepts(remote, cursor)) { plugin.msg(player, "wrong-equipment"); return; }
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
        view.inventory.setItem(raw, DummyPlayers.copy(next));
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
        view.inventory.setItem(raw, DummyPlayers.copy(next));
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
        if (event.getInventory().getHolder() instanceof View view) viewers.remove(view.viewer, view);
    }

    void close(Roster.Entry entry) {
        for (View view : List.copyOf(viewers.values())) if (view.entry == entry) close(view);
    }

    void closeAll() { List.copyOf(viewers.values()).forEach(this::close); }

    private void close(View view) {
        Player player = Bukkit.getPlayer(view.viewer);
        if (player == null) viewers.remove(view.viewer, view);
        else player.getScheduler().execute(plugin, () -> {
            if (player.getOpenInventory().getTopInventory() == view.inventory) player.closeInventory();
            viewers.remove(view.viewer, view);
        }, () -> viewers.remove(view.viewer, view), 1);
    }
}
