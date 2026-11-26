package io.github.dummyplayers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

final class ChestUnloader {
    private record Target(BlockPos position, double distance) { }

    /**
     * 把主背包的货物放进同高度、水平相邻的箱子。快捷栏、副手、装备不动；主背包里的食物留下 keepFood 个
     * （快捷栏和副手里的食物也算在内），多出来的照常搬走。返回 true 表示还有货物但没能放下，稍后重试。
     */
    static boolean run(NativeDummy dummy, int keepFood, Set<Material> foodBlacklist) {
        var inventory = dummy.getInventory();
        boolean hasCargo = false;
        for (int slot = 9; slot < 36; slot++) {
            if (!inventory.getItem(slot).isEmpty()) { hasCargo = true; break; }
        }
        if (!hasCargo) return false;
        int[] movable = movable(dummy.getBukkitEntity().getInventory(), keepFood, foodBlacklist);
        if (movable == null) return false;
        var world = dummy.level();
        var eye = dummy.getEyePosition();
        var origin = dummy.blockPosition();
        var candidates = new ArrayList<Target>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = origin.relative(direction);
            if (!Bukkit.isOwnedByCurrentRegion(dummy.getBukkitEntity().getWorld(), adjacent.getX() >> 4, adjacent.getZ() >> 4)) continue;
            if (!world.hasChunk(adjacent.getX() >> 4, adjacent.getZ() >> 4)) continue;
            if (world.getBlockState(adjacent).getBlock() instanceof ChestBlock) {
                double distance = eye.distanceToSqr(adjacent.getX() + 0.5, adjacent.getY() + 0.5, adjacent.getZ() + 0.5);
                candidates.add(new Target(adjacent, distance));
            }
        }
        if (candidates.isEmpty()) return true;
        candidates.sort(Comparator.comparingDouble(Target::distance));
        var player = dummy.getBukkitEntity();
        Inventory source = player.getInventory();
        var visited = new HashSet<Inventory>();
        for (Target target : candidates) {
            BlockPos position = target.position();
            var state = world.getBlockState(position);
            if (!(state.getBlock() instanceof ChestBlock block) || !world.mayInteract(dummy, position)) continue;
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = ChestBlock.getConnectedBlockPos(position, state);
                if (!Bukkit.isOwnedByCurrentRegion(player.getWorld(), other.getX() >> 4, other.getZ() >> 4)
                    || !world.hasChunkAt(other) || !world.mayInteract(dummy, other)) continue;
            }
            if (ChestBlock.getContainer(block, state, world, position, false) == null) continue;
            var clicked = player.getWorld().getBlockAt(position.getX(), position.getY(), position.getZ());
            if (!(clicked.getState(false) instanceof Chest chest)) continue;
            Inventory destination = chest.getInventory();
            if (!visited.add(destination)) continue;
            if (!hasCapacity(source, destination, movable)) continue;
            var interact = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, clicked, BlockFace.UP, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(interact);
            if (interact.useInteractedBlock() == Event.Result.DENY) continue;
            var provider = state.getMenuProvider(world, position);
            if (provider == null || dummy.openMenu(provider).isEmpty()) continue;
            boolean moved;
            try { moved = transfer(source, destination, movable); }
            finally { dummy.closeContainer(); }
            if (moved) return false;
        }
        return true;
    }

    /**
     * 主背包每格可以搬走的数量（下标为格子编号 - 9）。食物按格子顺序留够 keepFood 个，留下的不搬。
     * 全部都要留下时返回 null。
     */
    private static int[] movable(Inventory inventory, int keepFood, Set<Material> foodBlacklist) {
        int keep = keepFood;
        if (keep > 0) {
            for (int slot = 0; slot < 9; slot++) keep -= foodAmount(inventory.getItem(slot), foodBlacklist);
            keep -= foodAmount(inventory.getItem(40), foodBlacklist);
        }
        int[] movable = new int[27];
        boolean any = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            int amount = stack.getAmount();
            if (keep > 0 && foodAmount(stack, foodBlacklist) > 0) {
                int kept = Math.min(keep, amount);
                keep -= kept;
                amount -= kept;
            }
            movable[slot - 9] = amount;
            any |= amount > 0;
        }
        return any ? movable : null;
    }

    /** 假人会吃的食物（与自动进食的判断相同）返回数量，否则返回 0。 */
    private static int foodAmount(ItemStack stack, Set<Material> foodBlacklist) {
        if (stack == null || stack.isEmpty() || foodBlacklist.contains(stack.getType())) return 0;
        return stack.hasData(DataComponentTypes.FOOD) && stack.hasData(DataComponentTypes.CONSUMABLE) ? stack.getAmount() : 0;
    }

    private static boolean hasCapacity(Inventory source, Inventory destination, int[] movable) {
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = source.getItem(slot);
            if (movable[slot - 9] > 0 && stack != null && !stack.isEmpty() && hasRoom(destination, stack)) return true;
        }
        return false;
    }

    private static boolean transfer(Inventory source, Inventory destination, int[] movable) {
        boolean moved = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = DummyPlayers.copy(source.getItem(slot));
            int amount = stack == null ? 0 : Math.min(movable[slot - 9], stack.getAmount());
            if (amount <= 0 || !hasRoom(destination, stack)) continue;
            ItemStack moving = stack.asQuantity(amount);
            var move = new InventoryMoveItemEvent(source, moving.clone(), destination, true);
            Bukkit.getPluginManager().callEvent(move);
            if (move.isCancelled() || !moving.equals(move.getItem()) || !stack.equals(source.getItem(slot)) || !hasRoom(destination, stack)) continue;
            // 先从假人背包扣除，再放进箱子；放不下的部分退回原格子
            int kept = stack.getAmount() - amount;
            source.setItem(slot, kept == 0 ? null : stack.asQuantity(kept));
            ItemStack left = destination.addItem(moving).get(0);
            int remaining = kept + (left == null ? 0 : left.getAmount());
            source.setItem(slot, remaining == 0 ? null : stack.asQuantity(remaining));
            moved |= left == null || left.getAmount() < amount;
        }
        return moved;
    }

    private static boolean hasRoom(Inventory destination, ItemStack stack) {
        for (int slot = 0; slot < destination.getSize(); slot++) {
            ItemStack other = destination.getItem(slot);
            if (other == null || other.isEmpty() || other.isSimilar(stack)
                && other.getAmount() < Math.min(other.getMaxStackSize(), destination.getMaxStackSize())) return true;
        }
        return false;
    }
}
