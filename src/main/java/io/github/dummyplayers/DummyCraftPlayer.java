package io.github.dummyplayers;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.UUID;

/**
 * 假人的 Bukkit 玩家对象。领地插件读取 UUID 和名字时返回主人的（见 {@link ClaimIdentity}），
 * 其余情况与普通玩家对象完全相同。权限节点（hasPermission）不变，仍是假人自己的。
 */
final class DummyCraftPlayer extends CraftPlayer {
    private final UUID owner;
    private final String ownerName;

    DummyCraftPlayer(CraftServer server, NativeDummy handle, UUID owner, String ownerName) {
        super(server, handle);
        this.owner = owner;
        this.ownerName = ownerName;
    }

    @Override public UUID getUniqueId() {
        return owner != null && ClaimIdentity.claimCaller() ? owner : super.getUniqueId();
    }

    @Override public String getName() {
        return ownerName != null && ClaimIdentity.claimCaller() ? ownerName : super.getName();
    }

    /** CraftPlayer 会缓存第一次算出的哈希值；固定按假人自己的 UUID 计算，不会在领地插件里被记成主人的。 */
    @Override public int hashCode() { return 97 * 5 + getHandle().getUUID().hashCode(); }
}
