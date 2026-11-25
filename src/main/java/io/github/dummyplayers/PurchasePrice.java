package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 购买一个假人的价格。金币、点券和物品可以同时要求，全部满足才扣除。
 * 扣费顺序：先检查物品，再扣金币，再扣点券（失败时退还金币），最后扣物品。
 */
record PurchasePrice(double coins, int points, List<ItemStack> items) {
    PurchasePrice {
        items = List.copyOf(items);
    }

    boolean free() { return coins <= 0 && points <= 0 && items.isEmpty(); }

    /** 只能在玩家所在线程调用。余额或物品不足时返回 false；服务不可用时抛出异常。 */
    boolean take(Player player) throws ReflectiveOperationException {
        ItemStack[] remaining = items.isEmpty() ? null : withoutItems(player);
        if (!items.isEmpty() && remaining == null) return false;
        SpawnFee fee = null;
        if (coins > 0) {
            fee = SpawnFee.find(coins);
            if (fee == null) throw new IllegalStateException("Vault economy unavailable");
            if (!fee.debit(player)) return false;
        }
        if (points > 0) {
            boolean paid;
            try { paid = takePoints(player.getUniqueId()); }
            catch (ReflectiveOperationException | RuntimeException error) {
                refundCoins(player, fee);
                throw error;
            }
            if (!paid) {
                refundCoins(player, fee);
                return false;
            }
        }
        if (remaining != null) player.getInventory().setStorageContents(remaining);
        return true;
    }

    private static void refundCoins(Player player, SpawnFee fee) {
        if (fee == null) return;
        try {
            if (fee.refund(player)) return;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Bukkit.getLogger().warning("[ALFakePlayer] Vault refund threw for " + player.getUniqueId() + ": " + error);
        }
        Bukkit.getLogger().severe("[ALFakePlayer] Manual refund required: " + player.getUniqueId() + ", amount " + fee.amount());
    }

    private boolean takePoints(UUID player) throws ReflectiveOperationException {
        var plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("PlayerPoints unavailable");
        var api = plugin.getClass().getMethod("getAPI").invoke(plugin);
        return Boolean.TRUE.equals(api.getClass().getMethod("take", UUID.class, int.class).invoke(api, player, points));
    }

    /** 返回扣除所需物品后的背包内容；物品不足时返回 null。不修改玩家背包。 */
    private ItemStack[] withoutItems(Player player) {
        ItemStack[] contents = Arrays.stream(player.getInventory().getStorageContents())
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
            if (remaining > 0) return null;
        }
        return contents;
    }
}
