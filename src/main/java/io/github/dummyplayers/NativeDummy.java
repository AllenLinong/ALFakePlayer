package io.github.dummyplayers;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.net.InetSocketAddress;
import java.lang.reflect.Constructor;

final class NativeDummy extends ServerPlayer {
    private static final int HOTBAR_SIZE = 9, BAG_END = 36;
    private final boolean ignoredByHostileMobs;
    private final Roster.Entry entry;
    private final ItemStack[] pickupBefore = new ItemStack[HOTBAR_SIZE + 1];
    private final int[] pickupCounts = new int[HOTBAR_SIZE + 1];
    private ItemEntity pickupSource;
    private boolean initialized;
    private ServerLevel lastLevel;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    final SinkLink link = new SinkLink();

    /** 和原版客户端默认一样显示皮肤的所有外层（帽子、外套、袖子、裤腿），否则皮肤上的头发、帽子等看不到。 */
    private static final ClientInformation OPTIONS = new ClientInformation("en_us", 2,
        net.minecraft.world.entity.player.ChatVisiblity.FULL, true, 0x7F, net.minecraft.world.entity.player.Player.DEFAULT_MAIN_HAND,
        false, false, net.minecraft.server.level.ParticleStatus.ALL);

    /** Entity.bukkitEntity：在第一次使用前换成 DummyCraftPlayer。找不到时领地兼容不可用，假人照常工作。 */
    private static final java.lang.reflect.Field BUKKIT_ENTITY = bukkitEntityField();

    NativeDummy(MinecraftServer server, ServerLevel level, GameProfile profile, boolean ignoredByHostileMobs,
                Roster.Entry entry, String ownerName) {
        super(server, level, profile, OPTIONS);
        this.ignoredByHostileMobs = ignoredByHostileMobs;
        this.entry = entry;
        if (entry != null && BUKKIT_ENTITY != null && getBukkitEntityRaw() == null) {
            try { BUKKIT_ENTITY.set(this, new DummyCraftPlayer(server.server, this, entry.key.owner(), ownerName)); }
            catch (IllegalAccessException | RuntimeException ignored) { }
        }
    }

    static boolean claimIdentitySupported() { return BUKKIT_ENTITY != null; }

    private static java.lang.reflect.Field bukkitEntityField() {
        try {
            var field = net.minecraft.world.entity.Entity.class.getDeclaredField("bukkitEntity");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    @Override public boolean isPushable() { return (entry == null || entry.pushable) && super.isPushable(); }

    @Override public void push(double x, double y, double z, net.minecraft.world.entity.Entity source) {
        if (source == null || entry == null || entry.pushable) super.push(x, y, z, source);
    }

    void ready() {
        resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        initialized = true;
    }

    void prepareForWorld(MinecraftServer server) {
        new ServerGamePacketListenerImpl(server, link, this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
            @Override public boolean hasClientLoaded() { return true; }
        };
        connection.resetPosition();
        initInventoryMenu();
        moonrise$setRealPlayer(true);
    }

    @Override public void awardStat(Stat<?> stat, int amount) {
        if (stat.getValue().equals(Stats.TIME_SINCE_REST)) return;
        super.awardStat(stat, amount);
    }

    @Override public void die(DamageSource source) {
        CraftEngineCompat.prepareDeath();
        super.die(source);
    }

    @Override
    public boolean isClientAuthoritative() { return false; }

    @Override
    public boolean canBeSeenAsEnemy() { return !ignoredByHostileMobs && super.canBeSeenAsEnemy(); }

    @Override public void giveExperiencePoints(int points) { }

    @Override public void giveExperienceLevels(int levels) { }

    @Override public void setExperiencePoints(int points) { super.setExperiencePoints(0); }

    @Override public void setExperienceLevels(int levels) { super.setExperienceLevels(0); }

    void beforePickup(ItemEntity item) {
        pickupSource = item;
        Inventory inventory = getInventory();
        for (int index = 0; index < pickupBefore.length; index++) {
            ItemStack stack = inventory.getItem(pickupSlot(index));
            pickupBefore[index] = stack;
            pickupCounts[index] = stack.getCount();
        }
    }

    @Override public void onItemPickup(ItemEntity item) {
        if (pickupSource == item) {
            pickupSource = null;
            Inventory inventory = getInventory();
            ItemStack pickedUp = item.getItem();
            for (int index = 0; index < pickupBefore.length; index++) {
                int slot = pickupSlot(index);
                ItemStack stack = inventory.getItem(slot);
                int before = pickupCounts[index];
                if (stack.getCount() > before && (before == 0 || stack == pickupBefore[index])
                    && ItemStack.isSameItemSameComponents(stack, pickedUp)) {
                    int moved = moveToBag(inventory, stack, stack.getCount() - before);
                    if (moved > 0) {
                        stack.shrink(moved);
                        if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
                        inventory.setChanged();
                    }
                }
            }
        }
        super.onItemPickup(item);
    }

    private static int pickupSlot(int index) { return index < HOTBAR_SIZE ? index : Inventory.SLOT_OFFHAND; }

    /**
     * 捡起这组物品后能否全部留在主背包：主背包的空格子，加上背包、快捷栏和副手里同种物品还能叠加的数量。
     * 开启搬运时据此决定是否捡起，避免主背包满后物品塞进快捷栏（快捷栏不卸货，会一直占着）。
     */
    boolean canStore(ItemStack stack) {
        Inventory inventory = getInventory();
        int limit = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
        int room = 0;
        for (int slot = 0; slot < BAG_END && room < stack.getCount(); slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target.isEmpty()) { if (slot >= HOTBAR_SIZE) room += limit; }
            else if (ItemStack.isSameItemSameComponents(target, stack)) room += Math.max(0, limit - target.getCount());
        }
        ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (!offhand.isEmpty() && ItemStack.isSameItemSameComponents(offhand, stack)) room += Math.max(0, limit - offhand.getCount());
        return room >= stack.getCount();
    }

    static int moveToBag(Inventory inventory, ItemStack stack, int amount) {
        int left = amount;
        int limit = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
        for (int slot = HOTBAR_SIZE; slot < BAG_END && left > 0; slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) continue;
            int count = Math.min(left, limit - target.getCount());
            if (count > 0) { target.grow(count); left -= count; }
        }
        for (int slot = HOTBAR_SIZE; slot < BAG_END && left > 0; slot++) {
            if (!inventory.getItem(slot).isEmpty()) continue;
            int count = Math.min(left, limit);
            inventory.setItem(slot, stack.copyWithCount(count));
            left -= count;
        }
        return amount - left;
    }

    @Override
    public void tick() {
        super.tick();
        if (!initialized || isRemoved()) return;
        link.acceptTeleport(this);
        doTick();
        pickupSource = null;
        if (totalExperience != 0 || experienceLevel != 0 || experienceProgress != 0) {
            totalExperience = experienceLevel = 0;
            experienceProgress = 0;
        }
        ServerLevel world = level();
        int chunkX = getBlockX() >> 4;
        int chunkZ = getBlockZ() >> 4;
        if (lastLevel != world || lastChunkX != chunkX || lastChunkZ != chunkZ) {
            world.getChunkSource().move(this);
            lastLevel = world;
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
        }
    }

    static final class SinkLink extends net.minecraft.network.Connection {
        private static final Constructor<ServerboundAcceptTeleportationPacket> TELEPORT_ACK = teleportConstructor();
        private final EmbeddedChannel embedded = new EmbeddedChannel();
        private int teleport = -1;
        private boolean disposed;

        SinkLink() {
            super(PacketFlow.SERVERBOUND);
            channel = embedded;
            address = new InetSocketAddress("127.0.0.1", 0);
            configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            if (packet instanceof ClientboundPlayerPositionPacket position) teleport = position.id();
        }

        @Override
        public void flushChannel() { }

        @Override
        public void tick() { }

        @Override
        public void handleDisconnection() {
            if (!disposed) super.handleDisconnection();
        }

        void acceptTeleport(NativeDummy dummy) {
            if (teleport < 0) return;
            int id = teleport;
            teleport = -1;
            dummy.connection.resetPosition();
            try {
                var confirmation = TELEPORT_ACK.getParameterCount() == 1 ? TELEPORT_ACK.newInstance(id)
                    : TELEPORT_ACK.newInstance(id, dummy.getX(), dummy.getY(), dummy.getZ(), dummy.getYRot(), dummy.getXRot());
                dummy.connection.handleAcceptTeleportPacket(confirmation);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot confirm dummy teleport", error); }
        }

        private static Constructor<ServerboundAcceptTeleportationPacket> teleportConstructor() {
            var type = ServerboundAcceptTeleportationPacket.class;
            try { return type.getConstructor(int.class); }
            catch (NoSuchMethodException ignored) {
                try { return type.getConstructor(int.class, double.class, double.class, double.class, float.class, float.class); }
                catch (NoSuchMethodException error) { throw new IllegalStateException("Unsupported Paper teleport acknowledgement", error); }
            }
        }

        void dispose() { disposed = true; embedded.finishAndReleaseAll(); }
    }
}
