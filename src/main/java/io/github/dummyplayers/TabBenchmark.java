package io.github.dummyplayers;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * /afp test：TAB 测试假人。只发送 TAB 数据包，服务端没有实体。
 *
 * <p>设定目标人数后，测试假人在 ramp_up_seconds 内陆续进入（或离开）；之后每隔 churn_minutes
 * 有一部分人下线、另一部分人上线，和真人一样显示进服、退服提示。名字和权限组来自 {@link TestProfiles} 的固定档案。
 * 进服后偶尔打个招呼、下线前偶尔说一声，由 {@link TestChat} 安排。</p>
 */
final class TabBenchmark {
    static final int MAX_ENTRIES = 200;
    private static final EnumSet<Action> ADD = EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED,
        Action.UPDATE_GAME_MODE, Action.UPDATE_LATENCY, Action.UPDATE_DISPLAY_NAME, Action.UPDATE_HAT);
    private static final EnumSet<Action> RENAME = EnumSet.of(Action.UPDATE_DISPLAY_NAME);

    /** 测试条目：游戏档案名，以及和真实假人一样的 TAB 显示名（权限组前缀 + 名字）。 */
    record Shown(String name, Component label) { }
    /** setTarget 的结果：实际的目标人数（名字不够时减少），以及陆续进出的总时长（秒，0 为立即）。 */
    record Plan(int target, int seconds) { }
    /** 计划中的一次进服（join 为 true）或退服；who 为 null 时随机挑一个。 */
    private record Change(long at, boolean join, UUID who) { }

    private final DummyPlayers plugin;
    private final Map<UUID, ServerGamePacketListenerImpl> viewers = new HashMap<>();
    /** 当前在 TAB 中的测试条目，按进入顺序。 */
    private final LinkedHashMap<UUID, Shown> active = new LinkedHashMap<>();
    private final PriorityQueue<Change> changes = new PriorityQueue<>(Comparator.comparingLong(Change::at));
    private int target;
    /** 已经说了再见、几秒后下线的测试假人。 */
    private final Set<UUID> leaving = new HashSet<>();
    private long nextChurn, nextGroupCheck, skinsChecked;
    /** 已经发给客户端的皮肤，null 为原版默认皮肤。 */
    private final Map<UUID, Skins.Texture> skins = new HashMap<>();
    private ScheduledTask task;

    TabBenchmark(DummyPlayers plugin) { this.plugin = plugin; }

    synchronized int count() { return active.size(); }

    synchronized int target() { return target; }

    /** 当前测试条目（UUID → 名字和显示名）的快照。 */
    synchronized Map<UUID, Shown> entries() { return Map.copyOf(active); }

    synchronized boolean shown(UUID id) { return active.containsKey(id); }

    /** 设定目标人数。立即模式（ramp_up_seconds 为 0）下直接增减，否则在随机的时长内陆续进出。 */
    synchronized Plan setTarget(int wanted) {
        var settings = plugin.testProfiles().settings();
        int usable = active.size() + freeProfiles().size();
        target = Math.min(wanted, usable - leaving.size());
        changes.clear();
        long now = System.currentTimeMillis();
        // 已经说了再见的人照样下线，不计入目标人数
        for (UUID id : leaving) changes.add(new Change(now + 30_000, false, id));
        int difference = target - (active.size() - leaving.size());
        int duration = settings.rampMax() == 0 ? 0 : ThreadLocalRandom.current().nextInt(settings.rampMin(), settings.rampMax() + 1);
        if (duration == 0) {
            // 立即模式：一次全部进出，不显示提示
            while (active.size() - leaving.size() < target && join(false)) { }
            while (active.size() - leaving.size() > target && leave(false)) { }
            shareChanges();
        } else {
            for (int index = 0; index < Math.abs(difference); index++) {
                changes.add(new Change(now + ThreadLocalRandom.current().nextLong(duration * 1000L), difference > 0, null));
            }
        }
        nextChurn = now + churnDelay();
        nextGroupCheck = now + 60_000;
        start();
        return new Plan(target, duration);
    }

    /** /afp test stop：立即清除，不显示提示。 */
    synchronized int clear() {
        int count = active.size();
        target = 0;
        changes.clear();
        leaving.clear();
        if (count != 0) send(new ClientboundPlayerInfoRemovePacket(List.copyOf(active.keySet())));
        active.clear();
        skins.clear();
        stop();
        if (count != 0) shareChanges();
        return count;
    }

    synchronized void close() {
        target = 0;
        changes.clear();
        leaving.clear();
        if (!active.isEmpty()) send(new ClientboundPlayerInfoRemovePacket(List.copyOf(active.keySet())));
        active.clear();
        skins.clear();
        stop();
        viewers.clear();
    }

    private void start() {
        if (task == null && plugin.isEnabled()) {
            task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, unused -> tick(), 1, 1, TimeUnit.SECONDS);
        }
    }

    private void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private synchronized void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        while (!changes.isEmpty() && changes.peek().at() <= now) {
            Change change = changes.poll();
            if (change.join()) changed |= join(messages());
            else if (change.who() != null) { if (active.containsKey(change.who())) { remove(change.who(), messages()); changed = true; } }
            else if (active.size() > leaving.size()) changed |= leave(messages());
        }
        // 达到目标后定时轮换：一部分人下线、一部分人上线，各自在接下来两分钟内的随机时间
        if (changes.isEmpty() && target > 0 && now >= nextChurn) {
            var settings = plugin.testProfiles().settings();
            int count = (int) Math.round(active.size() * settings.churnPercent() / 100.0);
            if (settings.churnPercent() > 0 && active.size() > 1) count = Math.max(1, count);
            for (int index = 0; index < count; index++) {
                changes.add(new Change(now + ThreadLocalRandom.current().nextLong(120_000), false, null));
                changes.add(new Change(now + ThreadLocalRandom.current().nextLong(120_000), true, null));
            }
            nextChurn = now + churnDelay();
        }
        if (changed) shareChanges();
        if (plugin.skins().changedAt() != skinsChecked) {
            skinsChecked = plugin.skins().changedAt();
            refreshSkins();
        }
        if (target > 0 && now >= nextGroupCheck) {
            nextGroupCheck = now + 60_000;
            plugin.testProfiles().recheck().thenAccept(this::refreshLabels);
        }
        if (target == 0 && active.isEmpty() && changes.isEmpty()) stop();
    }

    private boolean messages() { return plugin.testProfiles().settings().messages(); }

    private long churnDelay() {
        var settings = plugin.testProfiles().settings();
        return TimeUnit.MINUTES.toMillis(ThreadLocalRandom.current().nextInt(settings.churnMin(), settings.churnMax() + 1));
    }

    /** 还能进服的档案：不在 TAB 中，名字也没有被真人、真实假人或其他子服的测试假人占用。 */
    private List<Roster.TestProfile> freeProfiles() {
        Set<String> occupied = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) occupied.add(player.getName().toLowerCase(Locale.ROOT));
        for (Roster.Entry entry : plugin.roster.entries.values()) occupied.add(entry.nativeName().toLowerCase(Locale.ROOT));
        occupied.addAll(plugin.remoteTestNames());
        for (Shown shown : active.values()) occupied.add(shown.name().toLowerCase(Locale.ROOT));
        var result = new ArrayList<Roster.TestProfile>();
        for (Roster.TestProfile profile : plugin.testProfiles().pool()) {
            if (occupied.contains(profile.name().toLowerCase(Locale.ROOT)) || active.containsKey(profile.id())) continue;
            // 来过本服的玩家用过的名字不用，免得和真人重名
            if (Bukkit.getOfflinePlayerIfCached(profile.name()) != null) continue;
            result.add(profile);
        }
        return result;
    }

    /** 随机一个空闲档案进服。没有空闲名字时返回 false。 */
    private boolean join(boolean announce) {
        var free = freeProfiles();
        if (free.isEmpty()) return false;
        var profile = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        var shown = new Shown(profile.name(), plugin.testLabel(profile.name(), profile.group()));
        active.put(profile.id(), shown);
        send(new ClientboundPlayerInfoUpdatePacket(ADD, List.of(entry(profile.id(), shown))));
        plugin.titles().testPlayer(profile.id(), profile.name());
        if (announce) {
            announce(profile, true);
            plugin.testChat().testJoined(profile.name(), profile.id());
        }
        return true;
    }

    /**
     * 随机一个测试假人退服。显示提示时，它可能先在聊天里说一声再走：这时先留在 TAB 里，
     * 说完几秒后下线；万一一直没轮到它说，30 秒后照样下线。结果为 TAB 是否已经变化。
     */
    private boolean leave(boolean announce) {
        var ids = new ArrayList<>(active.keySet());
        ids.removeAll(leaving);
        if (ids.isEmpty()) return false;
        UUID id = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
        Shown shown = active.get(id);
        if (announce && plugin.testChat().farewell(shown.name(), id, () -> leaveNow(id))) {
            leaving.add(id);
            changes.add(new Change(System.currentTimeMillis() + 30_000, false, id));
            return false;
        }
        remove(id, announce);
        return true;
    }

    /** 说完再见后下线。 */
    private synchronized void leaveNow(UUID id) {
        if (!leaving.contains(id) || !active.containsKey(id)) return;
        remove(id, messages());
        shareChanges();
    }

    private void remove(UUID id, boolean announce) {
        leaving.remove(id);
        skins.remove(id);
        Shown shown = active.remove(id);
        if (shown == null) return;
        send(new ClientboundPlayerInfoRemovePacket(List.of(id)));
        var profile = plugin.testProfiles().profile(shown.name());
        if (announce && profile != null) announce(profile, false);
    }

    private void announce(Roster.TestProfile profile, boolean joined) {
        var settings = plugin.testProfiles().settings();
        String format = joined ? settings.joinFormat() : settings.quitFormat();
        if (format.isBlank()) {
            broadcast(Component.translatable(joined ? "multiplayer.player.joined" : "multiplayer.player.left",
                NamedTextColor.YELLOW, Component.text(profile.name())));
            return;
        }
        // 填 PlaceholderAPI 变量前要等 LuckPerms 加载测试假人的用户，不能在本对象的锁里等
        if (!plugin.isEnabled()) return;
        Bukkit.getAsyncScheduler().runNow(plugin, unused ->
            broadcast(plugin.legacy(plugin.testChat().fill(format, profile.id(), profile.name(), profile.group(), ""))));
    }

    private static void broadcast(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player instanceof CraftPlayer craft) || !(craft.getHandle() instanceof NativeDummy)) player.sendMessage(message);
        }
    }

    /** 权限组变了的测试假人：更新 TAB 显示名。 */
    synchronized void refreshLabels(Set<String> changedNames) {
        if (changedNames.isEmpty()) return;
        var renamed = new ArrayList<Entry>();
        for (var current : active.entrySet()) {
            String key = current.getValue().name().toLowerCase(Locale.ROOT);
            if (!changedNames.contains(key)) continue;
            var profile = plugin.testProfiles().profile(key);
            if (profile == null) continue;
            var shown = new Shown(profile.name(), plugin.testLabel(profile.name(), profile.group()));
            current.setValue(shown);
            renamed.add(sentEntry(current.getKey(), shown));
        }
        if (renamed.isEmpty()) return;
        send(new ClientboundPlayerInfoUpdatePacket(RENAME, renamed));
        shareChanges();
    }

    /** 名字表重载后：已经不在名字表里的测试假人离开（不显示提示），稍后由别人补上。 */
    synchronized void reloaded() {
        boolean changed = false;
        for (var current : List.copyOf(active.entrySet())) {
            if (plugin.testProfiles().profile(current.getValue().name()) != null) continue;
            remove(current.getKey(), false);
            if (target > 0) changes.add(new Change(System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(30_000, 120_000), true, null));
            changed = true;
        }
        if (changed) shareChanges();
    }

    synchronized void join(Player player) {
        if (!(player instanceof CraftPlayer viewer) || viewer.getHandle() instanceof NativeDummy) return;
        // 真人用了和测试假人一样的名字进服：测试假人悄悄离开，稍后由别人补上
        for (var current : List.copyOf(active.entrySet())) {
            if (!current.getValue().name().equalsIgnoreCase(player.getName())) continue;
            remove(current.getKey(), false);
            if (target > 0) changes.add(new Change(System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(30_000, 120_000), true, null));
            shareChanges();
        }
        var connection = viewer.getHandle().connection;
        if (connection == null || viewers.put(player.getUniqueId(), connection) == connection) return;
        if (!active.isEmpty()) {
            var entries = new ArrayList<Entry>();
            active.forEach((id, shown) -> entries.add(sentEntry(id, shown)));
            connection.send(new ClientboundPlayerInfoUpdatePacket(ADD, entries));
        }
    }

    synchronized void quit(Player player) {
        if (player instanceof CraftPlayer viewer) viewers.remove(player.getUniqueId(), viewer.getHandle().connection);
    }

    private void shareChanges() { plugin.shareTests(null); }

    /** 刚获取到皮肤的测试假人：重新加入 TAB，客户端才会换上新皮肤。 */
    private void refreshSkins() {
        var changed = new ArrayList<UUID>();
        for (UUID id : active.keySet()) if (!Objects.equals(plugin.skins().texture(id), skins.get(id))) changed.add(id);
        if (changed.isEmpty()) return;
        send(new ClientboundPlayerInfoRemovePacket(changed));
        var entries = new ArrayList<Entry>();
        for (UUID id : changed) entries.add(entry(id, active.get(id)));
        send(new ClientboundPlayerInfoUpdatePacket(ADD, entries));
    }

    /** 带上当前的皮肤，并记下发出的是哪个。 */
    private Entry entry(UUID id, Shown shown) {
        Skins.Texture texture = plugin.skins().texture(id);
        skins.put(id, texture);
        return build(id, shown, texture);
    }

    /** 带上已经发给其他人的皮肤，所有人看到的一样。 */
    private Entry sentEntry(UUID id, Shown shown) { return build(id, shown, skins.get(id)); }

    private static Entry build(UUID id, Shown shown, Skins.Texture texture) {
        return new Entry(id, new GameProfile(id, shown.name(), Skins.properties(texture)), true, 0, GameType.SURVIVAL,
            PaperAdventure.asVanilla(shown.label()), true, 0, null);
    }

    private void send(Packet<?> packet) {
        viewers.values().forEach(connection -> connection.send(packet));
    }
}
