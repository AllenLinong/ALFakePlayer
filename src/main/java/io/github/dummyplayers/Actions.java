package io.github.dummyplayers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
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
import java.util.List;
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
    /** 钓鱼时低头的角度（对准水面），其他时候假人平视。 */
    private float pitch;
    /** 鱼咬钩了（PlayerFishEvent BITE，可能来自浮漂所在区域的线程），下一 tick 收竿。 */
    private volatile boolean bite;
    private int nextCast, castTick, nextRodSearch, castFailures, dryTicks;

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
        nextCast = nextRodSearch = castFailures = 0;
    }

    void bite() { bite = true; }

    private void faceWorkDirection() {
        float pitch = entry.fishing ? this.pitch : 0;
        if (dummy.getYRot() != yaw || dummy.getXRot() != pitch) dummy.setRot(yaw, pitch);
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
        // 钓鱼占用主手：开启后只钓鱼（和进食），不挖掘、攻击、放置
        if (entry.fishing) { stopMining(); fish(tick, plugin.minimumDurability); return; }
        if (dummy.fishing != null) dummy.fishing.discard();
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
        // 放置的是树苗、种子等植物时改为种植：目标是正前方一格的地面，而不是视线对着的方块
        Block plant = plantBlock();
        HitResult block = !use ? null : plant != null ? plantTarget(plant) : dummy.pick(blockReach, 1.0F, false);
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
        BlockHitResult miningTarget = entry.mine && entity == null ? miningTarget(eye, blockReach, plugin.minimumDurability, plant) : null;
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
                || !dummy.level().mayInteract(dummy, target.getBlockPos())) {
                // 种植位置被占着（植物在生长）时不用频繁检查
                if (plant != null) nextUse = tick + Math.max(plugin.useInterval, plugin.idleMaxInterval);
                return;
            }
            if (dummy.isCreative() || !(CraftMagicNumbers.getItem(placementBlock) instanceof BlockItem blockItem)) return;
            // 主手或副手已经拿着就直接用；否则从背包换到主手
            InteractionHand hand = InteractionHand.MAIN_HAND;
            if (!dummy.getMainHandItem().is(blockItem) && dummy.getOffhandItem().is(blockItem)) hand = InteractionHand.OFF_HAND;
            else if (!equipPlacement(blockItem)) {
                nextUse = tick + Math.max(plugin.useInterval, plugin.idleMaxInterval);
                return;
            }
            ItemStack stack = dummy.getItemInHand(hand);
            if (dummy.getCooldowns().isOnCooldown(stack)) return;
            var result = dummy.gameMode.useItemOn(dummy, dummy.level(), stack, hand, target);
            if (result.consumesAction()) {
                if (hand == InteractionHand.MAIN_HAND) dummy.getBukkitEntity().swingMainHand();
                else dummy.getBukkitEntity().swingOffHand();
            }
            if (dummy.containerMenu != dummy.inventoryMenu) dummy.closeContainer();
        }
    }

    // ── 钓鱼 ──────────────────────────────────────────────

    /**
     * 主手拿鱼竿，对准正前方最近的水面抛竿；咬钩后收竿，稍等再抛。
     * 浮漂连续 3 秒不在水里（落在地上、挂在方块上、水被抽干）、钩到生物或一分钟没咬钩时收回重抛。
     */
    private void fish(int tick, int minimumDurability) {
        FishingHook hook = dummy.fishing;
        ItemStack rod = dummy.getMainHandItem();
        if (!rod.is(Items.FISHING_ROD) || !usable(rod, minimumDurability)) {
            if (hook != null) hook.discard();
            if (tick < nextRodSearch) return;
            if (!equip(stack -> stack.is(Items.FISHING_ROD), minimumDurability)) { nextRodSearch = tick + 40; return; }
            select(0);
            return;
        }
        if (hook != null && !hook.isRemoved()) {
            boolean caught = bite;
            dryTicks = hook.isInWater() ? 0 : dryTicks + 1;
            boolean stuck = dryTicks > 60;
            boolean hooked = hook.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY;
            if (!caught && !stuck && !hooked && tick - castTick < 1200) return;
            bite = false;
            useRod(rod);
            castFailures = stuck ? castFailures + 1 : 0;
            nextCast = tick + (caught ? 10 : retryDelay());
            return;
        }
        bite = false;
        if (tick < nextCast) return;
        Vec3 water = waterAhead();
        Float aim = water == null ? null : aimPitch(water);
        if (aim == null) { castFailures++; nextCast = tick + retryDelay(); return; }
        // 浮漂按抛竿时的视角飞出，先低头对准水面
        pitch = aim;
        faceWorkDirection();
        useRod(rod);
        castTick = tick;
        dryTicks = 0;
        // 抛竿被其他插件取消（例如领地禁止钓鱼）时稍后再试
        if (dummy.fishing == null) { castFailures++; nextCast = tick + retryDelay(); }
    }

    /** 连续失败时逐步放慢重试：1、2、4、8 秒，最多 10 秒。 */
    private int retryDelay() { return Math.min(200, 20 << Math.min(castFailures, 3)); }

    private void useRod(ItemStack rod) {
        if (dummy.gameMode.useItem(dummy, dummy.level(), rod, InteractionHand.MAIN_HAND).consumesAction()) {
            dummy.getBukkitEntity().swingMainHand();
        }
    }

    /**
     * 沿假人的水平朝向往前 1–12 格找最近的水面（脚下高度往上 1 格、往下 4 格），
     * 返回这格水面上沿射线的中点；没有时为 null。
     */
    private Vec3 waterAhead() {
        var level = dummy.level();
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians), dz = Math.cos(radians);
        int feet = dummy.getBlockY();
        BlockPos column = null, surface = null;
        double enter = 0;
        for (double distance = 0.6; distance <= 12.5; distance += 0.05) {
            double x = dummy.getX() + dx * distance, z = dummy.getZ() + dz * distance;
            BlockPos here = BlockPos.containing(x, feet, z);
            if (here.getX() == (column == null ? Integer.MIN_VALUE : column.getX()) && here.getZ() == column.getZ()) continue;
            if (surface != null) {
                double middle = (enter + distance) / 2;
                double height = level.getFluidState(surface).getHeight(level, surface);
                return new Vec3(dummy.getX() + dx * middle, surface.getY() + height, dummy.getZ() + dz * middle);
            }
            column = here;
            if (!ownedAndLoaded(here)) return null;
            for (int dy = 1; dy >= -4; dy--) {
                BlockPos position = here.above(dy);
                if (!level.getFluidState(position).is(FluidTags.WATER)) {
                    // 水面以上被方块挡住的格子不算
                    if (!level.getBlockState(position).getCollisionShape(level, position).isEmpty()) break;
                    continue;
                }
                surface = position;
                enter = distance;
                break;
            }
        }
        return null;
    }

    /**
     * 按原版浮漂的抛出速度和运动规律（每 tick 先加 -0.03 重力，移动后速度乘 0.92），
     * 找出让浮漂落到目标水面的低头角度；够不着时为 null。多个角度都能落到时选向下抛、飞行时间短的。
     */
    private Float aimPitch(Vec3 target) {
        double eyeY = dummy.getEyeY();
        double distance = Math.hypot(target.x - dummy.getX(), target.z - dummy.getZ()) - 0.3;
        double drop = eyeY - target.y;
        Float best = null;
        double bestError = 0.4;
        for (int angle = 85; angle >= -30; angle--) {
            double vertical = Math.clamp(-Math.tan(Math.toRadians(angle)), -5, 5);
            double speed = 0.6 / Math.sqrt(1 + vertical * vertical) + 0.5;
            double vx = speed, vy = vertical * speed, x = 0, y = 0;
            for (int step = 0; step < 200; step++) {
                vy -= 0.03;
                double nextX = x + vx, nextY = y + vy;
                if (nextY <= -drop) {
                    double landed = x + (nextX - x) * (y + drop) / (y - nextY);
                    double error = Math.abs(landed - distance);
                    if (error < bestError) { bestError = error; best = (float) angle; }
                    break;
                }
                x = nextX;
                y = nextY;
                vx *= 0.92;
                vy *= 0.92;
            }
        }
        return best;
    }

    // ── 种植 ──────────────────────────────────────────────

    /** 能长植物的方块（耕地、泥土、沙子、灵魂沙等）。自动种植时正前方一格的这些方块不会被挖掉。 */
    private static final List<TagKey<Block>> SOILS = List.of(BlockTags.SUPPORTS_VEGETATION, BlockTags.SUPPORTS_DRY_VEGETATION,
        BlockTags.SUPPORTS_CROPS, BlockTags.SUPPORTS_STEM_CROPS, BlockTags.SUPPORTS_SUGAR_CANE, BlockTags.SUPPORTS_BAMBOO,
        BlockTags.SUPPORTS_CACTUS, BlockTags.SUPPORTS_AZALEA, BlockTags.SUPPORTS_MANGROVE_PROPAGULE, BlockTags.SUPPORTS_NETHER_WART,
        BlockTags.SUPPORTS_WARPED_FUNGUS, BlockTags.SUPPORTS_CRIMSON_FUNGUS, BlockTags.SUPPORTS_NETHER_SPROUTS);

    /** 树苗、作物、种子、花、甘蔗、仙人掌、竹子等种在地上的植物。 */
    static boolean plant(Block block) {
        return block instanceof net.minecraft.world.level.block.VegetationBlock
            || block instanceof net.minecraft.world.level.block.SugarCaneBlock
            || block instanceof net.minecraft.world.level.block.CactusBlock
            || block instanceof net.minecraft.world.level.block.BambooSaplingBlock;
    }

    /** 开启放置且放置的是植物时，返回要种的植物方块；否则为 null。 */
    private Block plantBlock() {
        Material selected = entry.placementBlock;
        if (!entry.use || selected == null || !(CraftMagicNumbers.getItem(selected) instanceof BlockItem item)) return null;
        return plant(item.getBlock()) ? item.getBlock() : null;
    }

    private static boolean soil(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.FarmlandBlock) return true;
        for (TagKey<Block> tag : SOILS) if (state.is(tag)) return true;
        return false;
    }

    /**
     * 种植位置：只看正前方一格。地面高出脚下一格（种在视线高度）或与脚下平齐（种在脚下高度），
     * 地面上方是空的，并且原版规则允许这株植物长在这里（作物要耕地、树苗要泥土类、甘蔗要挨着水等）。
     * 没有可种的位置时返回 MISS。
     */
    private BlockHitResult plantTarget(Block plant) {
        var level = dummy.level();
        Direction forward = Direction.fromYRot(yaw);
        BlockPos front = dummy.blockPosition().relative(forward);
        BlockState planted = plant.defaultBlockState();
        for (BlockPos ground : List.of(front, front.below())) {
            BlockPos spot = ground.above();
            if (!ownedAndLoaded(spot)) break;
            BlockState above = level.getBlockState(spot), groundState = level.getBlockState(ground);
            // 前方脚下是空气、草丛、雪层等可替换的方块时，地面是再下面一格
            if (ground.equals(front) && groundState.canBeReplaced() && !groundState.is(plant)) continue;
            if (!above.canBeReplaced() || !above.getFluidState().isEmpty() || !planted.canSurvive(level, spot)) break;
            return new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
        }
        return BlockHitResult.miss(dummy.getEyePosition(), Direction.UP, front);
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

    /**
     * 盔甲架可以作为横扫刷怪的靶子：拿剑打盔甲架会横扫周围的怪；两次攻击间隔超过 5 tick 时盔甲架只会晃动，不会被打碎。
     * 隐形、标记和无敌的盔甲架打不动也不会触发横扫，多为其他插件的全息字或模型，跳过。
     */
    private static boolean canAttack(Entity entity) {
        if (entity instanceof ArmorStand stand && (stand.isMarker() || stand.isInvisible() || stand.isInvulnerable())) return false;
        return entity instanceof LivingEntity && entity.isAlive() && canTarget(entity);
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

    /** plant 不为 null 时正在自动种植：正前方一格的植物和土类地面不挖。 */
    private BlockHitResult miningTarget(Vec3 eye, double reach, int minimumDurability, Block plant) {
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
            boolean keep = plant != null && distance == 1;
            if (matchesSample(samples, upperState) && !(keep && (upperState.is(plant) || soil(upperState)))) {
                BlockHitResult hit = miningHit(eye, reach, upper, upperState, minimumDurability);
                if (hit != null) return hit;
            }
            BlockState lowerState = level.getBlockState(lower);
            if (matchesSample(samples, lowerState) && !(keep && (lowerState.is(plant) || soil(lowerState)))) {
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
