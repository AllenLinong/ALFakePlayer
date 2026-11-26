package io.github.dummyplayers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.util.Set;
import java.util.function.Predicate;

final class Actions {
    private final NativeDummy dummy;
    private final Roster.Entry entry;
    final int offset;
    private BlockPos mining;
    private BlockState miningState;
    private ItemStack miningToolStack;
    private Direction face;
    private int started, sequence, nextSearch, nextUse, nextEat;
    private int nextWeaponSearch, nextToolSearch;
    private long lastAttack = -200;
    private int idleInterval, nextUnload, unloadRetry, workRevision;
    private boolean eating;
    private float yaw;

    Actions(NativeDummy dummy, Roster.Entry entry) {
        this.dummy = dummy;
        this.entry = entry;
        offset = Math.floorMod(entry.id.hashCode(), 80);
        nextUnload = offset % 40;
    }

    void direction(float yaw) {
        stopMining();
        this.yaw = yaw;
        wake();
        faceWorkDirection();
    }

    private void wake() {
        idleInterval = nextSearch = nextToolSearch = nextWeaponSearch = nextEat = nextUse = 0;
        unloadRetry = nextUnload = 0;
    }

    private void faceWorkDirection() {
        if (dummy.getYRot() != yaw || dummy.getXRot() != 0) dummy.setRot(yaw, 0);
        if (dummy.yBodyRot != yaw) dummy.setYBodyRot(yaw);
        if (dummy.getYHeadRot() != yaw) dummy.setYHeadRot(yaw);
    }

    void tick(int tick, DummyPlayers plugin) {
        faceWorkDirection();
        if (dummy.isShiftKeyDown() != entry.sneaking) dummy.setShiftKeyDown(entry.sneaking);
        int revision = entry.workRevision.get();
        if (workRevision != revision) { workRevision = revision; wake(); }
        // 有玩家正在打开这个假人的背包时不卸货，免得物品在玩家眼前消失、和玩家的操作冲突
        if (plugin.unloadChests && entry.autoUnload && tick >= nextUnload && !plugin.menus.editing(entry)) {
            if (ChestUnloader.run(dummy, plugin.keepFood, plugin.foodBlacklist)) unloadRetry = Math.min(plugin.unloadMaxInterval,
                Math.max(plugin.unloadInterval, unloadRetry * 2));
            else unloadRetry = 0;
            nextUnload = tick + Math.max(plugin.unloadInterval, unloadRetry);
        }
        if ((tick + offset) % 20 == 0 && plugin.passiveExhaustion > 0) {
            dummy.causeFoodExhaustion(plugin.passiveExhaustion);
        }
        if (plugin.lethalStarvation && (tick + offset) % 80 == 0
            && dummy.getFoodData().getFoodLevel() == 0) {
            Difficulty difficulty = dummy.level().getDifficulty();
            if ((difficulty == Difficulty.NORMAL && dummy.getHealth() <= 1)
                || (difficulty == Difficulty.EASY && dummy.getHealth() <= 10)) {
                dummy.hurtServer(dummy.level(), dummy.damageSources().starve(), 1.0F);
                if (!dummy.isAlive()) return;
            }
        }
        if (eat(tick, plugin.eatLevel, plugin.foodBlacklist)) { stopMining(); return; }
        if (dummy.isUsingItem()) { stopMining(); return; }
        boolean attackReady = entry.attack && tick - lastAttack >= entry.attackIntervalTicks;
        Material placementBlock = entry.placementBlock;
        boolean placing = entry.use && placementBlock != null;
        boolean use = placing && tick >= nextUse;
        if (!entry.mine) stopMining();
        if (!(entry.mine || entry.attack || use)) return;
        if (!entry.mine && !attackReady && !use) return;
        if (mining == null && !use && tick < nextSearch) return;
        nextSearch = tick + plugin.targetInterval;

        double blockReach = dummy.blockInteractionRange();
        double entityReach = dummy.entityInteractionRange();
        Vec3 eye = dummy.getEyePosition();
        Vec3 look = dummy.getViewVector(1.0F);
        HitResult block = use ? dummy.pick(blockReach, 1.0F, false) : null;
        EntityHitResult entity = entry.attack ? attackTarget(eye, look) : null;
        if (entity == null && use) {
            double max = Math.min(entityReach * entityReach, block.getType() == HitResult.Type.MISS
                ? Double.POSITIVE_INFINITY : eye.distanceToSqr(block.getLocation()));
            Vec3 step = look.scale(entityReach);
            var bounds = dummy.getBoundingBox().expandTowards(step).inflate(1);
            entity = ProjectileUtil.getEntityHitResult(dummy, eye, eye.add(step),
                bounds, Actions::canTarget, max);
        }
        if (entry.attack && entity != null && attackableHere(entity.getEntity())) {
            stopMining();
            if (attackReady && tick >= nextWeaponSearch) {
                if (!equip(Actions::weapon, plugin.minimumDurability)) nextWeaponSearch = tick + 10;
                entity = attackTarget(eye, look);
                if (entity == null) return;
            }
            idleInterval = 0;
            if (attackReady && dummy.getAttackStrengthScale(0.5F) >= 1.0F) {
                dummy.connection.handleAttack(new ServerboundAttackPacket(entity.getEntity().getId()));
                dummy.getBukkitEntity().swingMainHand();
                lastAttack = tick;
            }
            long remaining = entry.attackIntervalTicks - (tick - lastAttack);
            if (remaining > 0) nextSearch = Math.min(nextSearch, tick + (int) remaining);
            return;
        }
        BlockHitResult target = block instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && eye.distanceToSqr(hit.getLocation()) <= blockReach * blockReach ? hit : null;
        BlockHitResult miningTarget = entry.mine && entity == null ? miningTarget(eye, blockReach, plugin.minimumDurability) : null;
        if (miningTarget != null) {
            BlockState state = dummy.level().getBlockState(miningTarget.getBlockPos());
            boolean matches = miningTool(dummy.getMainHandItem(), state) && usable(dummy.getMainHandItem(), plugin.minimumDurability);
            if (!matches && tick >= nextToolSearch) {
                matches = equip(stack -> miningTool(stack, state), plugin.minimumDurability, true);
                if (!matches) nextToolSearch = tick + 10;
            }
            if (matches && miningTool(dummy.getMainHandItem(), state) && usable(dummy.getMainHandItem(), plugin.minimumDurability)) {
                idleInterval = 0;
                mine(tick, miningTarget);
            }
            else stopMining();
        }
        else stopMining();
        if (attackReady && entity == null && miningTarget == null && (!use || target == null)) {
            dummy.getBukkitEntity().swingMainHand();
            lastAttack = tick;
            idleInterval = 0;
        } else if (mining == null && !use && !placing) {
            idleInterval = Math.min(plugin.idleMaxInterval, Math.max(plugin.targetInterval, idleInterval * 2));
            nextSearch = tick + idleInterval;
        }
        if (use && mining == null && !dummy.isUsingItem()) {
            nextUse = tick + plugin.useInterval;
            if (entity != null || target == null || !ownedAndLoaded(target.getBlockPos())
                || !ownedAndLoaded(target.getBlockPos().relative(target.getDirection()))
                || !dummy.level().mayInteract(dummy, target.getBlockPos())) return;
            if (dummy.isCreative() || !(CraftMagicNumbers.getItem(placementBlock) instanceof BlockItem blockItem) || !equipPlacement(blockItem)) {
                nextUse = tick + Math.max(plugin.useInterval, plugin.idleMaxInterval);
                return;
            }
            ItemStack stack = dummy.getMainHandItem();
            if (dummy.getCooldowns().isOnCooldown(stack)) return;
            var result = dummy.gameMode.useItemOn(dummy, dummy.level(), stack, InteractionHand.MAIN_HAND, target);
            if (result.consumesAction()) dummy.getBukkitEntity().swingMainHand();
            if (dummy.containerMenu != dummy.inventoryMenu) dummy.closeContainer();
        }
    }

    private boolean equipPlacement(BlockItem blockItem) {
        var inventory = dummy.getInventory();
        ItemStack current = inventory.getItem(0);
        if (current.is(blockItem)) { select(0); return true; }
        for (int slot = 1; slot < 36; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (!candidate.is(blockItem)) continue;
            inventory.setItem(slot, current);
            inventory.setItem(0, candidate);
            select(0);
            return true;
        }
        return false;
    }

    private static boolean canTarget(Entity entity) { return entity.isPickable() && !entity.isSpectator(); }

    private static boolean canAttack(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand) && entity.isAlive() && canTarget(entity);
    }

    private EntityHitResult attackTarget(Vec3 eye, Vec3 look) {
        AttackRange range = dummy.getAttackRangeWith(dummy.getMainHandItem());
        if (range.getClosesetHit(dummy, 1.0F, this::attackableHere) instanceof EntityHitResult direct) return direct;
        double reach = range.effectiveMaxRange(dummy) + range.hitboxMargin();
        EntityHitResult best = null;
        double bestDistance = reach * reach;
        var level = dummy.level();
        for (Entity candidate : level.getEntities(dummy, new AABB(eye, eye).inflate(reach), this::attackableHere)) {
            AABB box = candidate.getBoundingBox();
            if (!range.isInRange(dummy, box, 0)) continue;
            Vec3 center = box.getCenter();
            double forward = (center.x - eye.x) * look.x + (center.z - eye.z) * look.z;
            if (forward <= 0) continue;
            Vec3 axis = eye.add(look.scale(forward));
            Vec3 point = new Vec3(Math.clamp(axis.x, box.minX, box.maxX),
                Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(axis.z, box.minZ, box.maxZ));
            double distance = eye.distanceToSqr(point);
            double side = Math.hypot(point.x - axis.x, point.z - axis.z);
            if (side > 0.45 || distance > bestDistance) continue;
            var obstruction = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, dummy));
            if (obstruction.getType() != HitResult.Type.MISS) continue;
            bestDistance = distance;
            best = new EntityHitResult(candidate, point);
        }
        return best;
    }

    private boolean attackableHere(Entity target) {
        return target != dummy && canAttack(target)
            && Bukkit.isOwnedByCurrentRegion(target.getBukkitEntity());
    }

    private BlockHitResult miningTarget(Vec3 eye, double reach, int minimumDurability) {
        Direction forward = Direction.fromYRot(yaw);
        BlockPos origin = dummy.blockPosition();
        double eyeOffset = (eye.x - origin.getX() - 0.5) * forward.getStepX()
            + (eye.z - origin.getZ() - 0.5) * forward.getStepZ();
        int lastColumn = (int) Math.floor(reach + eyeOffset + 0.5);
        var level = dummy.level();
        var inventory = dummy.getInventory();
        Set<Block> samples = null;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack sample = inventory.getItem(slot);
            if (sample.isEmpty() || !(sample.getItem() instanceof BlockItem blockItem)) continue;
            if (samples == null) samples = new java.util.HashSet<>();
            samples.add(blockItem.getBlock());
        }
        for (int distance = 1; distance <= lastColumn; distance++) {
            BlockPos lower = origin.relative(forward, distance);
            if (!ownedAndLoaded(lower)) break;
            BlockPos upper = lower.above();
            BlockState upperState = level.getBlockState(upper);
            if (matchesSample(samples, upperState)) {
                BlockHitResult hit = miningHit(eye, reach, upper, upperState, minimumDurability);
                if (hit != null) return hit;
            }
            BlockState lowerState = level.getBlockState(lower);
            if (matchesSample(samples, lowerState)) {
                BlockHitResult hit = miningHit(eye, reach, lower, lowerState, minimumDurability);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private boolean ownedAndLoaded(BlockPos position) {
        return Bukkit.isOwnedByCurrentRegion(dummy.getBukkitEntity().getWorld(), position.getX() >> 4, position.getZ() >> 4)
            && dummy.level().hasChunkAt(position);
    }

    private static boolean matchesSample(Set<Block> samples, BlockState state) {
        return samples == null || samples.contains(state.getBlock())
            || state.getBlock() instanceof net.minecraft.world.level.block.AnvilBlock
                && samples.stream().anyMatch(net.minecraft.world.level.block.AnvilBlock.class::isInstance);
    }

    private BlockHitResult miningHit(Vec3 eye, double reach, BlockPos target, BlockState state, int minimumDurability) {
        var level = dummy.level();
        if (state.isAir() || level.isOutsideBuildHeight(target) || !level.mayInteract(dummy, target)
            || state.getDestroySpeed(level, target) < 0) return null;
        Vec3 center = Vec3.atCenterOf(target);
        if (eye.y > center.y) {
            Direction towardDummy = Direction.fromYRot(yaw).getOpposite();
            center = center.add(towardDummy.getStepX() * 0.49, 0, towardDummy.getStepZ() * 0.49);
        }
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, dummy));
        if (hit.getType() != HitResult.Type.BLOCK || !target.equals(hit.getBlockPos())
            || eye.distanceToSqr(hit.getLocation()) > reach * reach) return null;
        var inventory = dummy.getInventory();
        for (int index = 0; index <= 36; index++) {
            ItemStack stack = inventory.getItem(index == 36 ? 40 : index);
            if (usable(stack, minimumDurability) && miningTool(stack, state)) return hit;
        }
        return null;
    }

    private static boolean miningTool(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) return false;
        var tool = stack.get(DataComponents.TOOL);
        if (tool == null) return false;
        if (state.requiresCorrectToolForDrops()) return stack.isCorrectToolForDrops(state);
        return tool.isCorrectForDrops(state) || tool.getMiningSpeed(state) > tool.defaultMiningSpeed();
    }

    private boolean eat(int tick, int threshold, Set<Material> foodBlacklist) {
        if (!dummy.getFoodData().needsFood()) { finishEating(); return false; }
        if (!eating && (dummy.getFoodData().getFoodLevel() > threshold || tick < nextEat || dummy.isUsingItem())) return false;
        if (eating && dummy.isUsingItem()) return true;
        var inventory = dummy.getInventory();
        if (!isFood(inventory.getItem(40), foodBlacklist)) {
            int source = -1;
            for (int slot = 1; slot < 36; slot++) {
                if (isFood(inventory.getItem(slot), foodBlacklist)) { source = slot; break; }
            }
            if (source < 0 && isFood(inventory.getItem(0), foodBlacklist)) source = 0;
            if (source < 0) { nextEat = tick + 20; finishEating(); return false; }
            stopMining();
            if (!isFood(inventory.getItem(source), foodBlacklist)) { nextEat = tick + 20; return false; }
            moveToHand(source, 40);
        }
        select(0);
        var player = dummy.getBukkitEntity();
        var interact = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
            player.getInventory().getItemInOffHand(), null, null, EquipmentSlot.OFF_HAND);
        Bukkit.getPluginManager().callEvent(interact);
        if (interact.useItemInHand() != Event.Result.DENY && dummy.isAlive() && isFood(dummy.getOffhandItem(), foodBlacklist)) {
            dummy.gameMode.useItem(dummy, dummy.level(), dummy.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        eating = dummy.isUsingItem();
        if (!eating) { nextEat = tick + 20; select(0); }
        return eating;
    }

    private void finishEating() {
        if (!eating) return;
        if (dummy.isUsingItem()) dummy.stopUsingItem();
        eating = false;
        select(0);
    }

    private static boolean isFood(ItemStack item, Set<Material> foodBlacklist) {
        return item.has(DataComponents.FOOD) && item.has(DataComponents.CONSUMABLE)
            && !foodBlacklist.contains(CraftMagicNumbers.getMaterial(item.getItem()));
    }

    private void select(int slot) {
        if (dummy.getInventory().getSelectedSlot() == slot) return;
        dummy.getInventory().setSelectedSlot(slot);
    }

    private static boolean weapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS) || stack.is(Items.TRIDENT) || stack.is(Items.MACE);
    }

    private static boolean usable(ItemStack stack, int minimumDurability) {
        return !stack.isEmpty() && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > minimumDurability);
    }

    private boolean equip(Predicate<ItemStack> match, int minimumDurability) {
        return equip(match, minimumDurability, false);
    }

    private boolean equip(Predicate<ItemStack> match, int minimumDurability, boolean preserveSample) {
        var inventory = dummy.getInventory();
        ItemStack current = inventory.getItem(0);
        if (match.test(current) && usable(current, minimumDurability)) return true;
        boolean sampleInMain = preserveSample && !current.isEmpty() && current.getItem() instanceof BlockItem;
        int sampleSlot = -1;
        if (sampleInMain) {
            for (int slot = 1; slot < 9; slot++) {
                if (inventory.getItem(slot).isEmpty()) { sampleSlot = slot; break; }
            }
        }
        for (int slot = 1; slot < 36; slot++) {
            if (sampleInMain && slot >= 9 && sampleSlot < 0) return false;
            ItemStack candidate = inventory.getItem(slot);
            if (!match.test(candidate) || !usable(candidate, minimumDurability)) continue;
            stopMining();
            if (!match.test(inventory.getItem(slot)) || !usable(inventory.getItem(slot), minimumDurability)) return false;
            if (sampleInMain) swapToHand(slot, 0, slot < 9 ? slot : sampleSlot);
            else moveToHand(slot, 0);
            return true;
        }
        ItemStack offhand = inventory.getItem(40);
        if (sampleInMain && sampleSlot < 0 || !match.test(offhand) || !usable(offhand, minimumDurability)) return false;
        stopMining();
        if (!match.test(inventory.getItem(40)) || !usable(inventory.getItem(40), minimumDurability)) return false;
        if (sampleInMain) swapToHand(40, 0, sampleSlot);
        else moveToHand(40, 0);
        return true;
    }

    private void moveToHand(int source, int hand) {
        var inventory = dummy.getInventory();
        ItemStack replaced = inventory.getItem(hand);
        int storage = source;
        if (!replaced.isEmpty()) {
            for (int slot = 9; slot < 36; slot++) {
                if (inventory.getItem(slot).isEmpty()) { storage = slot; break; }
            }
            if (storage == source && (hand == 40 || source < 9 || source >= 36)) {
                for (int slot = 1; slot < 9; slot++) {
                    if (inventory.getItem(slot).isEmpty()) { storage = slot; break; }
                }
            }
        }
        swapToHand(source, hand, storage);
    }

    private void swapToHand(int source, int hand, int storage) {
        var inventory = dummy.getInventory();
        ItemStack moved = inventory.getItem(source), replaced = inventory.getItem(hand);
        if (storage != source && !inventory.getItem(storage).isEmpty()) return;
        inventory.setItem(source, ItemStack.EMPTY);
        inventory.setItem(hand, moved);
        if (!replaced.isEmpty()) inventory.setItem(storage, replaced);
    }

    private void mine(int tick, BlockHitResult hit) {
        BlockPos position = hit.getBlockPos();
        BlockState state = dummy.level().getBlockState(position);
        if (state.isAir() || !dummy.level().mayInteract(dummy, position)) { stopMining(); return; }
        if (!position.equals(mining) || miningState != state || miningToolStack != dummy.getMainHandItem()) {
            stopMining();
            mining = position;
            miningState = state;
            miningToolStack = dummy.getMainHandItem();
            face = hit.getDirection();
            started = tick;
            dig(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            dummy.getBukkitEntity().swingMainHand();
        } else if (state.getDestroyProgress(dummy, dummy.level(), position) * (tick - started + 1) >= 1) {
            dig(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            mining = null;
        } else if ((tick + offset) % 5 == 0) dummy.getBukkitEntity().swingMainHand();
    }

    void stopMining() {
        if (mining == null) return;
        dig(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        mining = null;
    }

    private void dig(ServerboundPlayerActionPacket.Action action) {
        dummy.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, mining, face, ++sequence));
    }
}
