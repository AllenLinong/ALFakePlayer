package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;

record PurchasePrice(String mode, double coins, int points, List<ItemStack> items) {
    PurchasePrice {
        items = List.copyOf(items);
    }

    boolean take(Player player) throws ReflectiveOperationException {
        return switch (mode) {
            case "free" -> true;
            case "coins" -> {
                SpawnFee fee = SpawnFee.find(coins);
                if (fee == null) throw new IllegalStateException("Vault economy unavailable");
                yield fee.debit(player);
            }
            case "points" -> {
                var plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
                if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("PlayerPoints unavailable");
                var api = plugin.getClass().getMethod("getAPI").invoke(plugin);
                yield Boolean.TRUE.equals(api.getClass().getMethod("take", java.util.UUID.class, int.class)
                    .invoke(api, player.getUniqueId(), points));
            }
            case "items" -> takeItems(player);
            default -> throw new IllegalStateException("Invalid purchase mode: " + mode);
        };
    }

    private boolean takeItems(Player player) {
        if (items.isEmpty()) throw new IllegalStateException("No purchase items configured");
        var inventory = player.getInventory();
        ItemStack[] contents = Arrays.stream(inventory.getStorageContents())
            .map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
        for (ItemStack needed : items) {
            int remaining = needed.getAmount();
            for (int index = 0; index < contents.length && remaining > 0; index++) {
                ItemStack stack = contents[index];
                if (stack == null || !needed.isSimilar(stack)) continue;
                int taken = Math.min(remaining, stack.getAmount());
                remaining -= taken;
                if (taken == stack.getAmount()) contents[index] = null;
                else stack.setAmount(stack.getAmount() - taken);
            }
            if (remaining > 0) return false;
        }
        inventory.setStorageContents(contents);
        return true;
    }
}
