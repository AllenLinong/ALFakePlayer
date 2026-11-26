package io.github.dummyplayers;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 假人存档的内存缓存与数据库读写。
 *
 * <p>所有数据库操作都在单独的存储线程上排队执行，游戏线程只修改内存并标记变更。</p>
 *
 * <p>跨服一致性依靠租约（sessions 表）：同一时间只有持有租约的子服可以修改假人背包。
 * 假人在本服召唤期间持有“运行”租约；在本服打开离线假人的背包时持有“编辑”租约。
 * 释放租约前一定先写入背包，获取租约后一定从数据库重新读取，因此换服不会复制物品。</p>
 */
final class Roster {
    record Key(UUID owner, int slot) { }

    static final class Entry {
        private static final String[] NAME_STEMS = {
            "Ash", "Blaze", "Cove", "Dusk", "Echo", "Fern", "Frost", "Kite",
            "Lynx", "Moss", "Nova", "Onyx", "Pine", "Rook", "Sage", "Vale"
        };
        final Key key;
        final UUID id;
        private final String profile, defaultName;
        final ItemStack[] items = new ItemStack[41];
        final AtomicInteger workRevision = new AtomicInteger();
        volatile long inventoryRevision;
        /** 已写入数据库的背包版本，受 Roster 锁保护。 */
        long savedInventoryRevision;
        volatile String alias = "";
        volatile boolean mine, attack, use, autoUnload = true;
        volatile boolean pushable = true, sneaking, fishing;
        volatile int attackIntervalTicks = 20;
        volatile float workYaw;
        volatile boolean directionSet;
        volatile DummyTier tier = DummyTier.NORMAL;
        volatile WorkTool tool = WorkTool.PICKAXE;
        volatile Material placementBlock;

        Entry(Key key, UUID id) {
            this.key = key;
            this.id = id;
            String token = id.toString().replace("-", "").substring(0, 12);
            profile = "AFP~" + token;
            defaultName = defaultName(id);
        }

        static String defaultName(UUID id) {
            String stem = NAME_STEMS[(int) (id.getLeastSignificantBits() & 15)];
            String suffix = Long.toUnsignedString(id.getMostSignificantBits() >>> 32, 36);
            return stem + suffix;
        }
        String profile() { return profile; }
        String nativeName() { return name().matches("[A-Za-z0-9_]{1,16}") ? name() : defaultName; }
        String name() { return alias.isBlank() ? defaultName : alias; }
        boolean matchesName(String name) {
            return this.name().equalsIgnoreCase(name) || profile.equalsIgnoreCase(name);
        }
        void wakeWork() { workRevision.incrementAndGet(); }
    }

    enum Claim { ACQUIRED, HELD, MISSING }
    record ClaimResult(Claim status, String holder) {
        static final ClaimResult ACQUIRED = new ClaimResult(Claim.ACQUIRED, null);
        static final ClaimResult MISSING = new ClaimResult(Claim.MISSING, null);
    }
    /** /afp test 测试假人的固定档案：名字、UUID 和权限组，第一次用到时生成，之后不变。 */
    record TestProfile(String name, UUID id, String group) { }

    /** 正常关服时在本服运行的假人：开服后在这个位置召唤回来，并恢复生命和饱食度。 */
    record RestorePoint(UUID dummy, String world, double x, double y, double z, float yaw, float pitch,
                        double health, int food, float saturation, float exhaustion) { }
    /** live：已召唤的假人数；players：真实玩家数。 */
    record ServerInfo(String id, String name, int live, int players, long silentMillis) { }

    private record Row(UUID id, Key key, String alias, DummyTier tier, WorkTool tool, boolean mine, boolean attack,
                       boolean use, boolean autoUnload, boolean pushable, boolean sneaking, int attackInterval,
                       Material placement, Float workYaw, boolean fishing) { }
    private record Snapshot(Entry entry, String alias, DummyTier tier, WorkTool tool, boolean mine, boolean attack,
                            boolean use, boolean autoUnload, boolean pushable, boolean sneaking, int attackInterval,
                            Material placement, Float workYaw, boolean fishing, ItemStack[] items, long revision) { }
    private record Outcome(Set<UUID> missing, Set<UUID> lost) { }

    private static final String COLUMNS = "id, owner, slot, alias, tier, tool, mine, attack, use_item, auto_unload,"
        + " pushable, sneaking, attack_interval_ticks, placement_block, work_yaw, fishing";

    final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    final Map<UUID, Entry> byId = new ConcurrentHashMap<>();
    final Map<UUID, Integer> allowances = new ConcurrentHashMap<>();
    /** 本服持有的租约：假人 ID → 是否为运行租约。 */
    private final Map<UUID, Boolean> leases = new ConcurrentHashMap<>();
    /** 其他子服正在运行的假人：假人 ID → 子服 ID。 */
    private final Map<UUID, String> remoteLive = new ConcurrentHashMap<>();
    private volatile List<ServerInfo> servers = List.of();
    private volatile int highestSlot = 1;

    private final DummyPlayers plugin;
    private final Database db;
    private final String serverId, serverName, instance = UUID.randomUUID().toString();
    private final long timeoutMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ALFakePlayer-storage");
        thread.setDaemon(true);
        return thread;
    });

    // 待写入的变更，全部受 this 锁保护
    private final Set<UUID> changed = new HashSet<>();
    private final Set<UUID> changedAllowances = new HashSet<>();
    private final Map<UUID, List<CompletableFuture<Void>>> releasing = new HashMap<>();
    private boolean flushQueued, closing, duplicateWarned;

    Roster(DummyPlayers plugin, Database db, String serverId, String serverName, long timeoutMillis,
           long heartbeatMillis) throws Exception {
        this.plugin = plugin;
        this.db = db;
        this.serverId = serverId;
        this.serverName = serverName;
        this.timeoutMillis = timeoutMillis;
        register(heartbeatMillis);
        loadAll();
    }

    private String t(String name) { return db.table(name); }

    // ── 启动 ──────────────────────────────────────────────

    private void register(long heartbeatMillis) throws Exception {
        if (db.mysql()) {
            long[] seen = heartbeat(serverId);
            if (seen != null && seen[1] < timeoutMillis) {
                plugin.getLogger().info("server_id '" + serverId + "' reported a heartbeat " + seen[1] / 1000
                    + "s ago; checking that no other running server uses it...");
                Thread.sleep(Math.min(heartbeatMillis + 2000, timeoutMillis));
                long[] again = heartbeat(serverId);
                if (again != null && again[0] != seen[0]) {
                    throw new IllegalStateException("Another running server already uses server_id '" + serverId
                        + "'. Every server needs a unique server_id in config.yml.");
                }
            }
        }
        db.transaction(connection -> {
            // 本服刚启动，没有任何在线假人；上次异常关闭留下的租约直接清除
            Database.update(connection, "DELETE FROM " + t("sessions") + " WHERE server_id = ?", serverId);
            Database.update(connection, "INSERT INTO " + t("servers") + " (server_id, name, instance, live_count, heartbeat)"
                + " VALUES (?, ?, ?, 0, " + db.now() + ")"
                + db.onConflict("server_id", "name", "instance", "live_count", "heartbeat"), serverId, serverName, instance);
            return null;
        });
    }

    /** 返回 {心跳时间, 距今毫秒}，没有记录时返回 null。 */
    private long[] heartbeat(String server) throws SQLException {
        return db.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT heartbeat, " + db.now()
                    + " - heartbeat FROM " + t("servers") + " WHERE server_id = ?")) {
                statement.setString(1, server);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? new long[] {rows.getLong(1), rows.getLong(2)} : null;
                }
            }
        });
    }

    private void loadAll() throws SQLException {
        db.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT owner, slots FROM " + t("allowances"));
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) allowances.put(UUID.fromString(rows.getString(1)), rows.getInt(2));
            }
            List<Row> rows = readRows(connection, "", null);
            Map<UUID, ItemStack[]> items = readItems(connection, "", null);
            synchronized (this) {
                for (Row row : rows) {
                    Entry entry = new Entry(row.key, row.id);
                    apply(entry, row);
                    setItems(entry, items.getOrDefault(row.id, new ItemStack[41]));
                    index(entry);
                }
            }
            return null;
        });
    }

    // ── 读取 ──────────────────────────────────────────────

    private List<Row> readRows(Connection connection, String where, String value) throws SQLException {
        List<Row> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + t("dummies") + where)) {
            if (value != null) statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String placement = rows.getString(14);
                    Object yaw = rows.getObject(15);
                    result.add(new Row(UUID.fromString(rows.getString(1)),
                        new Key(UUID.fromString(rows.getString(2)), rows.getInt(3)),
                        Objects.requireNonNullElse(rows.getString(4), ""),
                        parse(DummyTier.class, rows.getString(5), DummyTier.NORMAL),
                        parse(WorkTool.class, rows.getString(6), WorkTool.PICKAXE),
                        rows.getBoolean(7), rows.getBoolean(8), rows.getBoolean(9), rows.getBoolean(10),
                        rows.getBoolean(11), rows.getBoolean(12), Math.clamp(rows.getInt(13), 20, 200),
                        placement == null ? null : Material.matchMaterial(placement),
                        yaw instanceof Number number ? number.floatValue() : null, rows.getBoolean(16)));
                }
            }
        }
        return result;
    }

    private Map<UUID, ItemStack[]> readItems(Connection connection, String where, String value) throws SQLException {
        Map<UUID, ItemStack[]> result = new HashMap<>();
        String sql = "SELECT i.dummy_id, i.slot, i.item FROM " + t("inventory") + " i"
            + (where.isEmpty() ? "" : " JOIN " + t("dummies") + " d ON d.id = i.dummy_id" + where);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (value != null) statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int slot = rows.getInt(2);
                    if (slot < 0 || slot > 40) continue;
                    UUID id = UUID.fromString(rows.getString(1));
                    result.computeIfAbsent(id, unused -> new ItemStack[41])[slot] = ItemStack.deserializeBytes(rows.getBytes(3));
                }
            }
        }
        return result;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try { return value == null ? fallback : Enum.valueOf(type, value); }
        catch (IllegalArgumentException invalid) { return fallback; }
    }

    private static void apply(Entry entry, Row row) {
        entry.alias = row.alias;
        entry.tier = row.tier;
        entry.tool = row.tool;
        entry.mine = row.mine;
        entry.attack = row.attack;
        entry.use = row.use;
        entry.autoUnload = row.autoUnload;
        entry.pushable = row.pushable;
        entry.sneaking = row.sneaking;
        entry.fishing = row.fishing;
        entry.attackIntervalTicks = row.attackInterval;
        entry.placementBlock = row.placement;
        if (row.workYaw != null) entry.workYaw = row.workYaw;
        entry.directionSet = row.workYaw != null;
    }

    /** 用数据库中的背包覆盖内存；调用方持有锁。 */
    private static void setItems(Entry entry, ItemStack[] items) {
        if (!Arrays.equals(entry.items, items)) {
            System.arraycopy(items, 0, entry.items, 0, entry.items.length);
            entry.inventoryRevision++;
        }
        entry.savedInventoryRevision = entry.inventoryRevision;
    }

    private void index(Entry entry) {
        entries.put(entry.key, entry);
        byId.put(entry.id, entry);
        if (entry.key.slot() > highestSlot) highestSlot = entry.key.slot();
    }

    private void unindex(Entry entry) {
        entries.remove(entry.key, entry);
        byId.remove(entry.id, entry);
    }

    /** 按编号顺序列出某个玩家的假人，不遍历全服数据。 */
    List<Entry> owned(UUID owner) {
        List<Entry> result = new ArrayList<>();
        int highest = Math.max(highestSlot, 100);
        for (int slot = 1; slot <= highest; slot++) {
            Entry entry = entries.get(new Key(owner, slot));
            if (entry != null) result.add(entry);
        }
        return result;
    }

    // ── 租约 ──────────────────────────────────────────────

    boolean holds(Entry entry) { return leases.containsKey(entry.id); }

    /** 假人在其他子服运行时返回该子服 ID。 */
    String remoteHolder(Entry entry) { return leases.containsKey(entry.id) ? null : remoteLive.get(entry.id); }

    /** 正在其他子服运行的假人（假人 → 子服 ID）的快照。 */
    Map<UUID, String> remoteSessions() {
        Map<UUID, String> copy = new HashMap<>(remoteLive);
        copy.keySet().removeIf(leases::containsKey);
        return copy;
    }

    /** 收到其他子服的租约变更通知：live 为 false 表示该子服已收回或降为编辑。 */
    void remoteSession(UUID dummy, String server, boolean live) {
        if (!live) remoteLive.remove(dummy, server);
        else if (!leases.containsKey(dummy)) remoteLive.put(dummy, server);
    }

    List<ServerInfo> servers() { return servers; }

    String serverName(String id) {
        if (id == null) return "?";
        if (id.equals(serverId)) return serverName;
        for (ServerInfo server : servers) if (server.id.equals(id)) return server.name;
        return id;
    }

    /** 获取租约。成功且之前未持有时，会先写入本地变更，再从数据库重新读取该假人。 */
    CompletableFuture<ClaimResult> acquire(Entry entry, boolean live) {
        return submit(() -> {
            if (byId.get(entry.id) != entry) return ClaimResult.MISSING;
            Boolean held = leases.get(entry.id);
            if (held != null) {
                if (live && !held) {
                    db.withConnection(connection -> Database.update(connection, "UPDATE " + t("sessions")
                        + " SET live = 1, heartbeat = " + db.now() + " WHERE dummy_id = ? AND server_id = ?",
                        entry.id.toString(), serverId));
                    leases.put(entry.id, true);
                    plugin.sessionChanged(entry.id, true);
                }
                return ClaimResult.ACQUIRED;
            }
            if (!flush()) throw new SQLException("Pending fake player changes could not be saved");
            ClaimResult result = db.withConnection(connection -> claim(connection, entry, live));
            if (result.status != Claim.ACQUIRED) return result;
            Row row;
            ItemStack[] items;
            try {
                var loaded = db.withConnection(connection -> Map.entry(
                    readRows(connection, " WHERE id = ?", entry.id.toString()),
                    readItems(connection, " WHERE d.id = ?", entry.id.toString())));
                if (loaded.getKey().isEmpty()) {
                    releaseNow(entry);
                    return ClaimResult.MISSING;
                }
                row = loaded.getKey().getFirst();
                items = loaded.getValue().getOrDefault(entry.id, new ItemStack[41]);
            } catch (SQLException | RuntimeException error) {
                releaseNow(entry);
                throw error;
            }
            synchronized (this) {
                if (byId.get(entry.id) != entry) {
                    releaseNow(entry);
                    return ClaimResult.MISSING;
                }
                apply(entry, row);
                setItems(entry, items);
                leases.put(entry.id, live);
            }
            remoteLive.remove(entry.id);
            if (live) plugin.sessionChanged(entry.id, true);
            return result;
        });
    }

    private ClaimResult claim(Connection connection, Entry entry, boolean live) throws SQLException {
        String id = entry.id.toString();
        for (int attempt = 0; attempt < 3; attempt++) {
            try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM " + t("dummies") + " WHERE id = ?")) {
                exists.setString(1, id);
                try (ResultSet rows = exists.executeQuery()) { if (!rows.next()) return ClaimResult.MISSING; }
            }
            if (Database.update(connection, db.insertIgnore() + t("sessions") + " (dummy_id, server_id, live, heartbeat)"
                + " VALUES (?, ?, ?, " + db.now() + ")", id, serverId, live) == 1) return ClaimResult.ACQUIRED;
            if (Database.update(connection, "UPDATE " + t("sessions") + " SET server_id = ?, live = ?, heartbeat = "
                + db.now() + " WHERE dummy_id = ? AND (server_id = ? OR heartbeat < " + db.now() + " - ?)",
                serverId, live, id, serverId, timeoutMillis) == 1) return ClaimResult.ACQUIRED;
            try (PreparedStatement holder = connection.prepareStatement(
                    "SELECT server_id FROM " + t("sessions") + " WHERE dummy_id = ?")) {
                holder.setString(1, id);
                try (ResultSet rows = holder.executeQuery()) {
                    if (rows.next()) return new ClaimResult(Claim.HELD, rows.getString(1));
                }
            }
        }
        return new ClaimResult(Claim.HELD, null);
    }

    private void releaseNow(Entry entry) {
        try {
            db.withConnection(connection -> Database.update(connection, "DELETE FROM " + t("sessions")
                + " WHERE dummy_id = ? AND server_id = ?", entry.id.toString(), serverId));
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Could not release lease for " + entry.profile(), error);
        }
    }

    /**
     * 释放租约：先把背包和设置写入数据库，再删除租约。
     * onlyEdit 为 true 时只释放编辑租约，假人仍在本服运行时什么都不做。
     */
    CompletableFuture<Void> release(Entry entry, boolean onlyEdit) {
        var done = new CompletableFuture<Void>();
        synchronized (this) {
            Boolean live = leases.get(entry.id);
            if (live == null || onlyEdit && live) return CompletableFuture.completedFuture(null);
            leases.remove(entry.id);
            if (byId.get(entry.id) == entry) changed.add(entry.id);
            releasing.computeIfAbsent(entry.id, unused -> new ArrayList<>()).add(done);
        }
        save();
        return done;
    }

    /** 假人离开本服但背包编辑界面仍打开时，把运行租约降为编辑租约。 */
    void downgrade(Entry entry) {
        if (leases.replace(entry.id, true, false)) {
            submit(() -> db.withConnection(connection -> Database.update(connection, "UPDATE " + t("sessions")
                + " SET live = 0 WHERE dummy_id = ? AND server_id = ?", entry.id.toString(), serverId)));
            plugin.sessionChanged(entry.id, false);
        }
    }

    // ── 写入 ──────────────────────────────────────────────

    void dirty(Entry entry) {
        synchronized (this) { changed.add(entry.id); }
    }

    synchronized void setAllowance(UUID owner, int slots) {
        allowances.put(owner, slots);
        changedAllowances.add(owner);
    }

    void save() {
        synchronized (this) {
            if (flushQueued || changed.isEmpty() && changedAllowances.isEmpty() && releasing.isEmpty()) return;
            flushQueued = true;
        }
        try { worker.execute(this::flush); }
        catch (RejectedExecutionException stopped) { synchronized (this) { flushQueued = false; } }
    }

    /** 等待此前所有变更写入数据库。结果为 false 表示写入失败，变更会在下次保存时重试。 */
    CompletableFuture<Boolean> commit() { return submit(this::flush); }

    /** 在存储线程上把所有待写变更写入数据库。 */
    private boolean flush() {
        while (true) {
            List<Snapshot> writes = new ArrayList<>();
            Map<UUID, Integer> slots = new HashMap<>();
            Map<UUID, List<CompletableFuture<Void>>> releases;
            synchronized (this) {
                if (changed.isEmpty() && changedAllowances.isEmpty() && releasing.isEmpty()) {
                    flushQueued = false;
                    return true;
                }
                for (UUID id : changed) {
                    Entry entry = byId.get(id);
                    if (entry != null) writes.add(snapshot(entry));
                }
                for (UUID owner : changedAllowances) {
                    Integer value = allowances.get(owner);
                    if (value != null) slots.put(owner, value);
                }
                releases = new HashMap<>(releasing);
                changed.clear();
                changedAllowances.clear();
                releasing.clear();
            }
            Outcome outcome;
            try {
                outcome = db.transaction(connection -> write(connection, slots, writes, releases.keySet()));
            } catch (SQLException | RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "Could not save fake player data; changes will be retried.", error);
                synchronized (this) {
                    for (Snapshot snapshot : writes) changed.add(snapshot.entry.id);
                    changedAllowances.addAll(slots.keySet());
                    releases.forEach((id, futures) -> releasing.computeIfAbsent(id, unused -> new ArrayList<>()).addAll(futures));
                    flushQueued = false;
                }
                return false;
            }
            synchronized (this) {
                for (Snapshot snapshot : writes) {
                    if (snapshot.items != null && !outcome.lost.contains(snapshot.entry.id)) {
                        snapshot.entry.savedInventoryRevision = Math.max(snapshot.entry.savedInventoryRevision, snapshot.revision);
                    }
                }
            }
            releases.forEach((id, futures) -> {
                futures.forEach(future -> future.complete(null));
                plugin.sessionChanged(id, null);
            });
            for (UUID id : outcome.missing) {
                Entry entry = byId.get(id);
                if (entry != null) {
                    forget(entry);
                    plugin.entryRemoved(entry);
                }
            }
            for (UUID id : outcome.lost) {
                Entry entry = byId.get(id);
                // 心跳可能已经处理过这次接管，避免重复移除和重复警告
                if (leases.remove(id) != null && entry != null) plugin.leaseLost(entry);
            }
        }
    }

    /** 调用方持有锁。背包未变化时不复制、不写入。 */
    private static Snapshot snapshot(Entry entry) {
        boolean inventoryChanged = entry.inventoryRevision != entry.savedInventoryRevision;
        return new Snapshot(entry, entry.alias, entry.tier, entry.tool, entry.mine, entry.attack, entry.use,
            entry.autoUnload, entry.pushable, entry.sneaking, entry.attackIntervalTicks, entry.placementBlock,
            entry.directionSet ? entry.workYaw : null, entry.fishing, inventoryChanged ? entry.items.clone() : null, entry.inventoryRevision);
    }

    private Outcome write(Connection connection, Map<UUID, Integer> slots, List<Snapshot> writes,
                          Set<UUID> releases) throws SQLException {
        Set<UUID> missing = new HashSet<>(), lost = new HashSet<>();
        if (!slots.isEmpty()) {
            try (PreparedStatement allowance = connection.prepareStatement("INSERT INTO " + t("allowances")
                    + " (owner, slots) VALUES (?, ?)" + db.onConflict("owner", "slots"))) {
                for (var entry : slots.entrySet()) {
                    allowance.setString(1, entry.getKey().toString());
                    allowance.setInt(2, entry.getValue());
                    allowance.addBatch();
                }
                allowance.executeBatch();
            }
        }
        try (PreparedStatement dummy = connection.prepareStatement("UPDATE " + t("dummies") + " SET alias = ?, tier = ?,"
                 + " tool = ?, mine = ?, attack = ?, use_item = ?, auto_unload = ?, pushable = ?, sneaking = ?,"
                 + " attack_interval_ticks = ?, placement_block = ?, work_yaw = ?, fishing = ?, updated_at = " + db.now() + " WHERE id = ?");
             PreparedStatement lease = connection.prepareStatement("SELECT server_id FROM " + t("sessions")
                 + " WHERE dummy_id = ?" + db.forUpdate());
             PreparedStatement clearItems = connection.prepareStatement("DELETE FROM " + t("inventory") + " WHERE dummy_id = ?");
             PreparedStatement item = connection.prepareStatement("INSERT INTO " + t("inventory")
                 + " (dummy_id, slot, item) VALUES (?, ?, ?)")) {
            for (Snapshot snapshot : writes) {
                String id = snapshot.entry.id.toString();
                dummy.setString(1, snapshot.alias);
                dummy.setString(2, snapshot.tier.name());
                dummy.setString(3, snapshot.tool.name());
                dummy.setInt(4, snapshot.mine ? 1 : 0);
                dummy.setInt(5, snapshot.attack ? 1 : 0);
                dummy.setInt(6, snapshot.use ? 1 : 0);
                dummy.setInt(7, snapshot.autoUnload ? 1 : 0);
                dummy.setInt(8, snapshot.pushable ? 1 : 0);
                dummy.setInt(9, snapshot.sneaking ? 1 : 0);
                dummy.setInt(10, snapshot.attackInterval);
                dummy.setString(11, snapshot.placement == null ? null : snapshot.placement.getKey().toString());
                if (snapshot.workYaw == null) dummy.setNull(12, Types.DOUBLE);
                else dummy.setDouble(12, snapshot.workYaw);
                dummy.setInt(13, snapshot.fishing ? 1 : 0);
                dummy.setString(14, id);
                if (dummy.executeUpdate() == 0) {
                    missing.add(snapshot.entry.id);
                    continue;
                }
                if (snapshot.items == null) continue;
                // 只有持有租约的子服才能写背包，防止两个子服互相覆盖
                lease.setString(1, id);
                try (ResultSet rows = lease.executeQuery()) {
                    if (!rows.next() || !serverId.equals(rows.getString(1))) {
                        lost.add(snapshot.entry.id);
                        continue;
                    }
                }
                clearItems.setString(1, id);
                clearItems.executeUpdate();
                boolean any = false;
                for (int slot = 0; slot < snapshot.items.length; slot++) {
                    ItemStack stack = snapshot.items[slot];
                    if (stack == null || stack.getType().isAir()) continue;
                    item.setString(1, id);
                    item.setInt(2, slot);
                    item.setBytes(3, stack.serializeAsBytes());
                    item.addBatch();
                    any = true;
                }
                if (any) item.executeBatch();
            }
        }
        if (!releases.isEmpty()) {
            try (PreparedStatement release = connection.prepareStatement("DELETE FROM " + t("sessions")
                    + " WHERE dummy_id = ? AND server_id = ?")) {
                for (UUID id : releases) {
                    release.setString(1, id.toString());
                    release.setString(2, serverId);
                    release.addBatch();
                }
                release.executeBatch();
            }
        }
        return new Outcome(missing, lost);
    }

    // ── 新建、发放、删除 ────────────────────────────────────

    /** 在数据库中占用该玩家最小的空闲编号；没有空位时结果为 null。 */
    CompletableFuture<Entry> create(UUID owner, int maxSlots) {
        return submit(() -> {
            for (int attempt = 1; ; attempt++) {
                UUID id = UUID.randomUUID();
                try {
                    Integer slot = db.transaction(connection -> {
                        Set<Integer> used = usedSlots(connection, owner);
                        int number = 1;
                        while (number <= maxSlots && used.contains(number)) number++;
                        if (number > maxSlots) return null;
                        insertDummy(connection, id, owner, number);
                        return number;
                    });
                    if (slot == null) return null;
                    Entry entry = new Entry(new Key(owner, slot), id);
                    synchronized (this) { index(entry); }
                    return entry;
                } catch (SQLException error) {
                    if (attempt >= 3 || !Database.duplicateKey(error)) throw error;
                }
            }
        });
    }

    /** grant：增加数量；setTotal：补到指定总数。结果为该玩家拥有的假人总数。 */
    CompletableFuture<Integer> grant(UUID owner, int amount, boolean setTotal, int maxSlots, int baseSlots) {
        return submit(() -> {
            int total = db.transaction(connection -> {
                Set<Integer> used = usedSlots(connection, owner);
                int owned = used.size();
                int target = setTotal ? Math.max(owned, amount) : Math.min(maxSlots, owned + amount);
                int highest = 0;
                for (int number = 1; number <= maxSlots && owned < target; number++) {
                    if (used.contains(number)) continue;
                    insertDummy(connection, UUID.randomUUID(), owner, number);
                    owned++;
                    highest = number;
                }
                Integer allowance = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT slots FROM " + t("allowances") + " WHERE owner = ?")) {
                    statement.setString(1, owner.toString());
                    try (ResultSet rows = statement.executeQuery()) { if (rows.next()) allowance = rows.getInt(1); }
                }
                int effective = Math.min(maxSlots, Math.max(baseSlots, allowance == null ? baseSlots : allowance));
                if (highest > effective) {
                    Database.update(connection, "INSERT INTO " + t("allowances") + " (owner, slots) VALUES (?, ?)"
                        + db.onConflict("owner", "slots"), owner.toString(), highest);
                }
                return owned;
            });
            reloadOwnerNow(owner);
            return total;
        });
    }

    private Set<Integer> usedSlots(Connection connection, UUID owner) throws SQLException {
        Set<Integer> used = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT slot FROM " + t("dummies") + " WHERE owner = ?" + db.forUpdate())) {
            statement.setString(1, owner.toString());
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) used.add(rows.getInt(1)); }
        }
        return used;
    }

    private void insertDummy(Connection connection, UUID id, UUID owner, int slot) throws SQLException {
        Database.update(connection, "INSERT INTO " + t("dummies") + " (id, owner, slot, updated_at) VALUES (?, ?, ?, "
            + db.now() + ")", id.toString(), owner.toString(), slot);
    }

    /** 永久删除假人及其背包。内存立即移除，数据库异步删除。 */
    CompletableFuture<Void> delete(Entry entry) {
        forget(entry);
        return submit(() -> {
            db.withConnection(connection -> Database.update(connection,
                "DELETE FROM " + t("dummies") + " WHERE id = ?", entry.id.toString()));
            return null;
        });
    }

    private synchronized void forget(Entry entry) {
        unindex(entry);
        changed.remove(entry.id);
        leases.remove(entry.id);
        remoteLive.remove(entry.id);
    }

    // ── 跨服刷新 ──────────────────────────────────────────

    /** 从数据库重新读取某个玩家的栏位和假人；本服持有租约的假人以内存为准。 */
    CompletableFuture<Void> reloadOwner(UUID owner) {
        return submit(() -> { reloadOwnerNow(owner); return null; });
    }

    private void reloadOwnerNow(UUID owner) throws SQLException {
        String value = owner.toString();
        var loaded = db.withConnection(connection -> {
            Integer allowance = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT slots FROM " + t("allowances") + " WHERE owner = ?")) {
                statement.setString(1, value);
                try (ResultSet rows = statement.executeQuery()) { if (rows.next()) allowance = rows.getInt(1); }
            }
            return new Object[] {allowance, readRows(connection, " WHERE owner = ?", value),
                readItems(connection, " WHERE d.owner = ?", value)};
        });
        Integer allowance = (Integer) loaded[0];
        @SuppressWarnings("unchecked") List<Row> rows = (List<Row>) loaded[1];
        @SuppressWarnings("unchecked") Map<UUID, ItemStack[]> items = (Map<UUID, ItemStack[]>) loaded[2];
        List<Entry> removed = new ArrayList<>();
        synchronized (this) {
            if (!changedAllowances.contains(owner)) {
                if (allowance == null) allowances.remove(owner);
                else allowances.put(owner, allowance);
            }
            Set<UUID> seen = new HashSet<>();
            for (Row row : rows) {
                seen.add(row.id);
                Entry current = byId.get(row.id);
                if (current != null && busy(current.id)) continue;
                if (current == null || !current.key.equals(row.key)) {
                    if (current != null) {
                        unindex(current);
                        removed.add(current);
                    }
                    Entry occupant = entries.get(row.key);
                    if (occupant != null) {
                        if (busy(occupant.id)) continue;
                        unindex(occupant);
                        removed.add(occupant);
                    }
                    current = new Entry(row.key, row.id);
                    index(current);
                }
                apply(current, row);
                setItems(current, items.getOrDefault(row.id, new ItemStack[41]));
            }
            for (Entry entry : owned(owner)) {
                if (seen.contains(entry.id) || busy(entry.id)) continue;
                unindex(entry);
                removed.add(entry);
            }
        }
        removed.forEach(plugin::entryRemoved);
    }

    private boolean busy(UUID id) {
        return leases.containsKey(id) || changed.contains(id) || releasing.containsKey(id);
    }

    /** 重新读取其他子服正在使用的假人的背包，用于只读查看。 */
    CompletableFuture<Void> refreshItems(Entry entry) {
        return submit(() -> {
            if (leases.containsKey(entry.id)) return null;
            var items = db.withConnection(connection -> readItems(connection, " WHERE d.id = ?", entry.id.toString()));
            synchronized (this) {
                if (!leases.containsKey(entry.id) && byId.get(entry.id) == entry) {
                    setItems(entry, items.getOrDefault(entry.id, new ItemStack[41]));
                }
            }
            return null;
        });
    }

    // ── 购买所需物品 ──────────────────────────────────────

    /** 启动时读取购买物品。购买物品保存在数据库中，跨服时所有子服共用。 */
    List<ItemStack> loadPurchaseItems() throws SQLException {
        return db.withConnection(this::readPurchaseItems);
    }

    CompletableFuture<List<ItemStack>> reloadPurchaseItems() {
        return submit(() -> db.withConnection(this::readPurchaseItems));
    }

    CompletableFuture<Void> savePurchaseItems(List<ItemStack> items) {
        List<ItemStack> copy = items.stream().map(ItemStack::clone).toList();
        return submit(() -> db.transaction(connection -> { writePurchaseItems(connection, copy); return null; }));
    }

    private List<ItemStack> readPurchaseItems(Connection connection) throws SQLException {
        List<ItemStack> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT item FROM " + t("purchase_items") + " ORDER BY position");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next() && items.size() < 3) items.add(ItemStack.deserializeBytes(rows.getBytes(1)));
        }
        return items;
    }

    private void writePurchaseItems(Connection connection, List<ItemStack> items) throws SQLException {
        Database.update(connection, "DELETE FROM " + t("purchase_items"));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + t("purchase_items") + " (position, item) VALUES (?, ?)")) {
            for (int index = 0; index < items.size() && index < 3; index++) {
                statement.setInt(1, index);
                statement.setBytes(2, items.get(index).serializeAsBytes());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    // ── 心跳 ──────────────────────────────────────────────

    /** 刷新本服和本服租约的心跳（同时上报假人数和真实玩家数），并读取其他子服的状态。 */
    void heartbeat(int liveCount, int playerCount) {
        synchronized (this) { if (closing) return; }
        submit(() -> {
            Set<UUID> held = new HashSet<>(leases.keySet());
            Map<UUID, String> remote = new HashMap<>();
            List<ServerInfo> list = new ArrayList<>();
            Set<UUID> confirmed = new HashSet<>();
            db.withConnection(connection -> {
                if (Database.update(connection, "UPDATE " + t("servers") + " SET name = ?, live_count = ?, player_count = ?, heartbeat = "
                        + db.now() + " WHERE server_id = ? AND instance = ?", serverName, liveCount, playerCount, serverId, instance) == 0) {
                    boolean taken;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT instance FROM " + t("servers") + " WHERE server_id = ?")) {
                        statement.setString(1, serverId);
                        try (ResultSet rows = statement.executeQuery()) { taken = rows.next(); }
                    }
                    if (taken && !duplicateWarned) {
                        duplicateWarned = true;
                        plugin.getLogger().severe("Another server registered with server_id '" + serverId
                            + "'. Each server needs a unique server_id in config.yml, or fake players may be duplicated.");
                    }
                    if (!taken) Database.update(connection, "INSERT INTO " + t("servers")
                        + " (server_id, name, instance, live_count, player_count, heartbeat) VALUES (?, ?, ?, ?, ?, " + db.now() + ")",
                        serverId, serverName, instance, liveCount, playerCount);
                }
                Database.update(connection, "UPDATE " + t("sessions") + " SET heartbeat = " + db.now()
                    + " WHERE server_id = ?", serverId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT dummy_id FROM " + t("sessions") + " WHERE server_id = ?")) {
                    statement.setString(1, serverId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) confirmed.add(UUID.fromString(rows.getString(1)));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT dummy_id, server_id FROM "
                        + t("sessions") + " WHERE server_id <> ? AND live = 1 AND heartbeat >= " + db.now() + " - ?")) {
                    statement.setString(1, serverId);
                    statement.setLong(2, timeoutMillis);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) remote.put(UUID.fromString(rows.getString(1)), rows.getString(2));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT server_id, name, live_count, player_count, "
                        + db.now() + " - heartbeat FROM " + t("servers") + " ORDER BY server_id")) {
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) list.add(new ServerInfo(rows.getString(1), rows.getString(2), rows.getInt(3),
                            rows.getInt(4), Math.max(0, rows.getLong(5))));
                    }
                }
                Database.update(connection, "DELETE FROM " + t("servers") + " WHERE heartbeat < " + db.now() + " - ?",
                    86_400_000L);
                return null;
            });
            remoteLive.keySet().retainAll(remote.keySet());
            remote.forEach((id, server) -> { if (!leases.containsKey(id)) remoteLive.put(id, server); });
            servers = List.copyOf(list);
            // 数据库里已经不属于本服的租约：其他子服在本服失联期间接管了它
            held.removeAll(confirmed);
            for (UUID id : held) {
                if (!leases.containsKey(id)) continue;
                leases.remove(id);
                Entry entry = byId.get(id);
                if (entry != null) plugin.leaseLost(entry);
            }
            return null;
        });
    }

    boolean online(String server, long timeout) {
        if (server.equals(serverId)) return true;
        for (ServerInfo info : servers) if (info.id.equals(server)) return info.silentMillis < timeout;
        return false;
    }

    // ── 工具 ──────────────────────────────────────────────

    @FunctionalInterface private interface Task<T> { T call() throws Exception; }

    private <T> CompletableFuture<T> submit(Task<T> task) {
        var future = new CompletableFuture<T>();
        try {
            worker.execute(() -> {
                try { future.complete(task.call()); }
                catch (Throwable error) { future.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException stopped) {
            future.completeExceptionally(stopped);
        }
        return future;
    }

    /** 停服时调用：写入全部变更，删除本服的租约和心跳记录。 */
    /**
     * 关服时记下本服正在运行的假人，替换本服之前的记录。同一个假人只保留最新的一条（它之后可能在别的子服运行过）。
     * 在 close() 之前调用：存储线程按顺序执行，关闭前一定写完。
     */
    void saveRestores(List<RestorePoint> points) {
        try {
            worker.execute(() -> {
                try {
                    db.transaction(connection -> {
                        Database.update(connection, "DELETE FROM " + t("restores") + " WHERE server_id = ?", serverId);
                        for (RestorePoint point : points) {
                            Database.update(connection, "DELETE FROM " + t("restores") + " WHERE dummy_id = ?", point.dummy().toString());
                            Database.update(connection, "INSERT INTO " + t("restores")
                                + " (dummy_id, server_id, world, x, y, z, yaw, pitch, health, food, saturation, exhaustion)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                point.dummy().toString(), serverId, point.world(), point.x(), point.y(), point.z(),
                                (double) point.yaw(), (double) point.pitch(), point.health(), point.food(),
                                (double) point.saturation(), (double) point.exhaustion());
                        }
                        return null;
                    });
                } catch (SQLException error) {
                    plugin.getLogger().log(Level.WARNING, "Could not save the fake players to re-summon after restart", error);
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    /** 开服时取出并删除上次关服时本服记下的假人。 */
    CompletableFuture<List<RestorePoint>> takeRestores() {
        return submit(() -> db.transaction(connection -> {
            List<RestorePoint> points = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT dummy_id, world, x, y, z, yaw, pitch,"
                    + " health, food, saturation, exhaustion FROM " + t("restores") + " WHERE server_id = ?")) {
                select.setString(1, serverId);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        try {
                            points.add(new RestorePoint(UUID.fromString(rows.getString(1)), rows.getString(2),
                                rows.getDouble(3), rows.getDouble(4), rows.getDouble(5), (float) rows.getDouble(6),
                                (float) rows.getDouble(7), rows.getDouble(8), rows.getInt(9), (float) rows.getDouble(10),
                                (float) rows.getDouble(11)));
                        } catch (IllegalArgumentException bad) {
                            plugin.getLogger().warning("Ignoring an invalid re-summon record: " + bad.getMessage());
                        }
                    }
                }
            }
            Database.update(connection, "DELETE FROM " + t("restores") + " WHERE server_id = ?", serverId);
            return points;
        }));
    }

    /**
     * 给还没有档案的名字写入档案（已有的保持不变，其他子服先写入的为准），返回全部档案：小写名字 → 档案。
     */
    CompletableFuture<Map<String, TestProfile>> ensureTestProfiles(List<TestProfile> candidates) {
        return submit(() -> db.transaction(connection -> {
            Map<String, TestProfile> stored = readTestProfiles(connection);
            boolean inserted = false;
            for (TestProfile candidate : candidates) {
                String key = candidate.name().toLowerCase(Locale.ROOT);
                if (stored.containsKey(key)) continue;
                Database.update(connection, db.insertIgnore() + t("test_profiles") + " (name_key, name, id, grp, created)"
                    + " VALUES (?, ?, ?, ?, " + db.now() + ")", key, candidate.name(), candidate.id().toString(), candidate.group());
                inserted = true;
            }
            return inserted ? readTestProfiles(connection) : stored;
        }));
    }

    private Map<String, TestProfile> readTestProfiles(Connection connection) throws SQLException {
        Map<String, TestProfile> result = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT name_key, name, id, grp FROM " + t("test_profiles"));
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                try { result.put(rows.getString(1), new TestProfile(rows.getString(2), UUID.fromString(rows.getString(3)), rows.getString(4))); }
                catch (IllegalArgumentException bad) { plugin.getLogger().warning("Ignoring an invalid test profile: " + rows.getString(1)); }
            }
        }
        return result;
    }

    /** 修改测试假人档案的权限组。 */
    CompletableFuture<Void> setTestGroup(String name, String group) {
        return submit(() -> {
            db.withConnection(connection -> Database.update(connection, "UPDATE " + t("test_profiles")
                + " SET grp = ? WHERE name_key = ?", group, name.toLowerCase(Locale.ROOT)));
            return null;
        });
    }

    void close() {
        synchronized (this) {
            if (closing) return;
            closing = true;
        }
        try {
            worker.execute(() -> {
                flush();
                try {
                    db.withConnection(connection -> {
                        Database.update(connection, "DELETE FROM " + t("sessions") + " WHERE server_id = ?", serverId);
                        Database.update(connection, "DELETE FROM " + t("servers") + " WHERE server_id = ? AND instance = ?",
                            serverId, instance);
                        return null;
                    });
                } catch (SQLException error) {
                    plugin.getLogger().log(Level.WARNING, "Could not clear this server's sessions", error);
                }
                synchronized (this) {
                    if (!changed.isEmpty() || !changedAllowances.isEmpty() || !releasing.isEmpty()) {
                        plugin.getLogger().severe("Fake player data still has unsaved changes at shutdown.");
                    }
                }
            });
        } catch (RejectedExecutionException ignored) { }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("Storage writes are still pending.");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
