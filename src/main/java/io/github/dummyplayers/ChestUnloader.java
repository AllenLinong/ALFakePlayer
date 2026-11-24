package io.github.dummyplayers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.bukkit.Bukkit;
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

final class ChestUnloader {
    private record Target(BlockPos position, double distance) { }

    static boolean run(NativeDummy dummy) {
        var inventory = dummy.getInventory();
        boolean hasCargo = false;
        for (int slot = 9; slot < 36; slot++) {
            if (!inventory.getItem(slot).isEmpty()) { hasCargo = true; break; }
        }
        if (!hasCargo) return false;
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
            if (!hasCapacity(source, destination)) continue;
            var interact = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, clicked, BlockFace.UP, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(interact);
            if (interact.useInteractedBlock() == Event.Result.DENY) continue;
            var provider = state.getMenuProvider(world, position);
            if (provider == null || dummy.openMenu(provider).isEmpty()) continue;
            boolean moved;
            try { moved = transfer(source, destination); }
            finally { dummy.closeContainer(); }
            if (moved) return false;
        }
        return true;
    }

    private static boolean hasCapacity(Inventory source, Inventory destination) {
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = source.getItem(slot);
            if (stack != null && !stack.isEmpty() && hasRoom(destination, stack)) return true;
        }
        return false;
    }

    private static boolean transfer(Inventory source, Inventory destination) {
        boolean moved = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = DummyPlayers.copy(source.getItem(slot));
            if (stack == null || !hasRoom(destination, stack)) continue;
            var move = new InventoryMoveItemEvent(source, stack.clone(), destination, true);
            Bukkit.getPluginManager().callEvent(move);
            if (move.isCancelled() || !stack.equals(move.getItem()) || !stack.equals(source.getItem(slot)) || !hasRoom(destination, stack)) continue;
            source.setItem(slot, null);
            var leftovers = destination.addItem(stack.clone());
            ItemStack left = leftovers.get(0);
            source.setItem(slot, left);
            moved |= left == null || left.getAmount() < stack.getAmount();
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
