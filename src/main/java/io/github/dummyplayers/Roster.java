package io.github.dummyplayers;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

final class Roster {
    record Key(UUID owner, int slot) { }

    static final class Entry {
        private static final String[] NAME_STEMS = {
            "Ash", "Blaze", "Cove", "Dusk", "Echo", "Fern", "Frost", "Kite",
            "Lynx", "Moss", "Nova", "Onyx", "Pine", "Rook", "Sage", "Vale"
        };
        final Key key;
        final UUID id;
        private final String legacyProfile, profile, defaultName;
        final ItemStack[] items = new ItemStack[41];
        final AtomicInteger workRevision = new AtomicInteger();
        volatile long inventoryRevision;
        volatile String alias = "";
        volatile boolean mine, attack, use, autoUnload = true;
        volatile boolean pushable = true, sneaking;
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
            legacyProfile = "AFP_" + token;
            profile = "AFP~" + token;
            String stem = NAME_STEMS[(int) (id.getLeastSignificantBits() & 15)];
            String suffix = Long.toUnsignedString(id.getMostSignificantBits() >>> 32, 36);
            defaultName = stem + suffix;
        }
        String profile() { return profile; }
        String nativeName() { return name().matches("[A-Za-z0-9_]{1,16}") ? name() : defaultName; }
        String name() { return alias.isBlank() ? defaultName : alias; }
        boolean matchesName(String name) {
            return this.name().equalsIgnoreCase(name) || profile.equalsIgnoreCase(name) || legacyProfile.equalsIgnoreCase(name);
        }
        void wakeWork() { workRevision.incrementAndGet(); }
    }

    final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    final Map<UUID, Integer> allowances = new ConcurrentHashMap<>();
    private final DummyPlayers plugin;
    private final Connection database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "ALFakePlayer-storage"));
    private record EntrySnapshot(Key key, UUID id, String alias, boolean mine, boolean attack,
                                 boolean use, boolean autoUnload, boolean pushable, boolean sneaking, int attackIntervalTicks, Float workYaw, DummyTier tier,
                                 WorkTool tool, Material placementBlock, ItemStack[] items) { }
    private final Set<Key> changedEntries = new HashSet<>();
    private final Set<UUID> changedAllowances = new HashSet<>();
    private boolean saveRequested, saving, closing;

    Roster(DummyPlayers plugin) throws Exception {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath();
        Files.createDirectories(folder);
        Driver driver = Class.forName("org.sqlite.JDBC", true, plugin.getServer().getClass().getClassLoader())
            .asSubclass(Driver.class).getConstructor().newInstance();
        database = driver.connect("jdbc:sqlite:" + folder.resolve("players.db").toAbsolutePath(), new Properties());
        try {
            execute("PRAGMA busy_timeout = 3000");
            execute("PRAGMA foreign_keys = ON");
            execute("PRAGMA journal_mode = WAL");
            int version;
            try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                version = rows.next() ? rows.getInt(1) : 0;
            }
            if (version == 0) {
                transaction(() -> {
                    execute("CREATE TABLE allowances (owner TEXT PRIMARY KEY NOT NULL, slots INTEGER NOT NULL CHECK (slots > 0))");
                    execute("""
                        CREATE TABLE dummies (
                            owner TEXT NOT NULL, slot INTEGER NOT NULL CHECK (slot > 0), id TEXT NOT NULL UNIQUE,
                            alias TEXT NOT NULL, mine INTEGER NOT NULL, attack INTEGER NOT NULL,
                            use_item INTEGER NOT NULL, tool TEXT NOT NULL, placement_block TEXT,
                            tier TEXT NOT NULL DEFAULT 'NORMAL', attack_interval_ticks INTEGER NOT NULL DEFAULT 20,
                            auto_unload INTEGER NOT NULL DEFAULT 1, work_yaw REAL,
                            PRIMARY KEY (owner, slot)
                        )
                        """);
                    execute("""
                        CREATE TABLE inventory (
                            dummy_id TEXT NOT NULL REFERENCES dummies(id) ON DELETE CASCADE,
                            slot INTEGER NOT NULL CHECK (slot BETWEEN 0 AND 40), item BLOB NOT NULL,
                            PRIMARY KEY (dummy_id, slot)
                        )
                        """);
                    execute("PRAGMA user_version = 5");
                });
            } else {
                if (version == 1) transaction(() -> {
                    execute("ALTER TABLE dummies ADD COLUMN placement_block TEXT");
                    execute("ALTER TABLE dummies ADD COLUMN tier TEXT NOT NULL DEFAULT 'NORMAL'");
                    execute("ALTER TABLE dummies ADD COLUMN attack_interval_ticks INTEGER NOT NULL DEFAULT 20");
                    execute("ALTER TABLE dummies ADD COLUMN auto_unload INTEGER NOT NULL DEFAULT 1");
                    execute("ALTER TABLE dummies ADD COLUMN work_yaw REAL");
                    execute("PRAGMA user_version = 5");
                });
                else if (version == 2) transaction(() -> {
                    execute("ALTER TABLE dummies ADD COLUMN tier TEXT NOT NULL DEFAULT 'NORMAL'");
                    execute("ALTER TABLE dummies ADD COLUMN attack_interval_ticks INTEGER NOT NULL DEFAULT 20");
                    execute("ALTER TABLE dummies ADD COLUMN auto_unload INTEGER NOT NULL DEFAULT 1");
                    execute("ALTER TABLE dummies ADD COLUMN work_yaw REAL");
                    execute("PRAGMA user_version = 5");
                });
                else if (version == 3) transaction(() -> {
                    execute("ALTER TABLE dummies ADD COLUMN attack_interval_ticks INTEGER NOT NULL DEFAULT 20");
                    execute("ALTER TABLE dummies ADD COLUMN auto_unload INTEGER NOT NULL DEFAULT 1");
                    execute("ALTER TABLE dummies ADD COLUMN work_yaw REAL");
                    execute("PRAGMA user_version = 5");
                });
                else if (version == 4) transaction(() -> {
                    execute("ALTER TABLE dummies ADD COLUMN work_yaw REAL");
                    execute("PRAGMA user_version = 5");
                });
                else if (version != 5 && version != 6) throw new SQLException("Unsupported ALFakePlayer database schema: " + version);
            }
            if (version < 6) transaction(() -> {
                execute("ALTER TABLE dummies ADD COLUMN pushable INTEGER NOT NULL DEFAULT 1");
                execute("ALTER TABLE dummies ADD COLUMN sneaking INTEGER NOT NULL DEFAULT 0");
                execute("PRAGMA user_version = 6");
            });
            loadDatabase();
        } catch (Exception error) {
            try { database.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private void loadDatabase() throws SQLException {
        Map<UUID, Entry> byId = new HashMap<>();
        try (Statement statement = database.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT owner, slots FROM allowances")) {
                while (rows.next()) allowances.put(UUID.fromString(rows.getString(1)), rows.getInt(2));
            }
            try (ResultSet rows = statement.executeQuery("SELECT owner, slot, id, alias, mine, attack, use_item, tool, placement_block, tier, attack_interval_ticks, auto_unload, work_yaw, pushable, sneaking FROM dummies")) {
                while (rows.next()) {
                    var entry = new Entry(new Key(UUID.fromString(rows.getString(1)), rows.getInt(2)), UUID.fromString(rows.getString(3)));
                    entry.alias = rows.getString(4);
                    entry.mine = rows.getBoolean(5);
                    entry.attack = rows.getBoolean(6);
                    entry.use = rows.getBoolean(7);
                    entry.tool = WorkTool.valueOf(rows.getString(8));
                    String placementBlock = rows.getString(9);
                    if (placementBlock != null) entry.placementBlock = Material.matchMaterial(placementBlock);
                    entry.tier = DummyTier.valueOf(rows.getString(10));
                    entry.attackIntervalTicks = Math.clamp(rows.getInt(11), 20, 200);
                    entry.autoUnload = rows.getBoolean(12);
                    entry.pushable = rows.getBoolean(14);
                    entry.sneaking = rows.getBoolean(15);
                    Object workYaw = rows.getObject(13);
                    if (workYaw instanceof Number number) {
                        entry.workYaw = number.floatValue();
                        entry.directionSet = true;
                    }
                    entries.put(entry.key, entry);
                    byId.put(entry.id, entry);
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT dummy_id, slot, item FROM inventory")) {
                while (rows.next()) {
                    Entry entry = byId.get(UUID.fromString(rows.getString(1)));
                    if (entry == null) throw new SQLException("Inventory belongs to a missing dummy: " + rows.getString(1));
                    entry.items[rows.getInt(2)] = ItemStack.deserializeBytes(rows.getBytes(3));
                }
            }
        }
    }

    synchronized Entry create(Key key) {
        return entries.computeIfAbsent(key, index -> {
            changedEntries.add(index);
            return new Entry(index, UUID.randomUUID());
        });
    }

    synchronized void dirty(Entry entry) { changedEntries.add(entry.key); }
    synchronized void remove(Entry entry) {
        if (entries.remove(entry.key, entry)) changedEntries.add(entry.key);
    }
    synchronized void setAllowance(UUID owner, int slots) {
        allowances.put(owner, slots);
        changedAllowances.add(owner);
    }

    synchronized void save() {
        if (closing || changedEntries.isEmpty() && changedAllowances.isEmpty()) return;
        saveRequested = true;
        if (!saving) {
            saving = true;
            writer.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            Set<Key> touched;
            Map<UUID, Integer> slots = new HashMap<>();
            List<EntrySnapshot> updates = new ArrayList<>();
            List<Key> deletes = new ArrayList<>();
            synchronized (this) {
                if (!saveRequested) { saving = false; return; }
                saveRequested = false;
                touched = Set.copyOf(changedEntries);
                changedEntries.clear();
                for (UUID owner : changedAllowances) slots.put(owner, allowances.get(owner));
                changedAllowances.clear();
                for (Key key : touched) {
                    Entry entry = entries.get(key);
                    if (entry != null) updates.add(snapshot(entry));
                    else deletes.add(key);
                }
            }
            try { transaction(() -> write(slots, updates, deletes)); }
            catch (SQLException | RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "Could not save players.db; changes will be retried.", error);
                synchronized (this) {
                    changedEntries.addAll(touched);
                    changedAllowances.addAll(slots.keySet());
                    if (!saveRequested) { saving = false; return; }
                }
            }
        }
    }

    private static EntrySnapshot snapshot(Entry entry) {
        return new EntrySnapshot(entry.key, entry.id, entry.alias, entry.mine, entry.attack, entry.use,
            entry.autoUnload, entry.pushable, entry.sneaking, entry.attackIntervalTicks, entry.directionSet ? entry.workYaw : null,
            entry.tier, entry.tool, entry.placementBlock, entry.items.clone());
    }

    private void write(Map<UUID, Integer> slots, List<EntrySnapshot> updates, List<Key> deletes) throws SQLException {
        try (PreparedStatement allowance = database.prepareStatement("""
                 INSERT INTO allowances (owner, slots) VALUES (?, ?) ON CONFLICT(owner) DO UPDATE SET slots = excluded.slots
                 """);
             PreparedStatement delete = database.prepareStatement("DELETE FROM dummies WHERE owner = ? AND slot = ?");
             PreparedStatement replaced = database.prepareStatement("DELETE FROM dummies WHERE owner = ? AND slot = ? AND id <> ?");
             PreparedStatement dummy = database.prepareStatement("""
                 INSERT INTO dummies (owner, slot, id, alias, mine, attack, use_item, tool, placement_block, tier, attack_interval_ticks, auto_unload, work_yaw, pushable, sneaking)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(owner, slot) DO UPDATE SET alias = excluded.alias, mine = excluded.mine,
                 attack = excluded.attack, use_item = excluded.use_item, tool = excluded.tool,
                 placement_block = excluded.placement_block, tier = excluded.tier,
                 attack_interval_ticks = excluded.attack_interval_ticks, auto_unload = excluded.auto_unload,
                 work_yaw = excluded.work_yaw, pushable = excluded.pushable, sneaking = excluded.sneaking
                 """);
             PreparedStatement clearItems = database.prepareStatement("DELETE FROM inventory WHERE dummy_id = ?");
             PreparedStatement item = database.prepareStatement("INSERT INTO inventory (dummy_id, slot, item) VALUES (?, ?, ?)")) {
            for (var entry : slots.entrySet()) {
                allowance.setString(1, entry.getKey().toString());
                allowance.setInt(2, entry.getValue());
                allowance.addBatch();
            }
            allowance.executeBatch();
            for (Key key : deletes) {
                bindKey(delete, key);
                delete.addBatch();
            }
            delete.executeBatch();
            for (EntrySnapshot entry : updates) {
                String id = entry.id.toString();
                bindKey(replaced, entry.key);
                replaced.setString(3, id);
                replaced.addBatch();
                bindKey(dummy, entry.key);
                dummy.setString(3, id);
                dummy.setString(4, entry.alias);
                dummy.setBoolean(5, entry.mine);
                dummy.setBoolean(6, entry.attack);
                dummy.setBoolean(7, entry.use);
                dummy.setString(8, entry.tool.name());
                dummy.setString(9, entry.placementBlock == null ? null : entry.placementBlock.getKey().toString());
                dummy.setString(10, entry.tier.name());
                dummy.setInt(11, entry.attackIntervalTicks);
                dummy.setBoolean(12, entry.autoUnload);
                if (entry.workYaw == null) dummy.setNull(13, Types.REAL);
                else dummy.setFloat(13, entry.workYaw);
                dummy.setBoolean(14, entry.pushable);
                dummy.setBoolean(15, entry.sneaking);
                dummy.addBatch();
                clearItems.setString(1, id);
                clearItems.addBatch();
                for (int slot = 0; slot < entry.items.length; slot++) {
                    ItemStack stack = entry.items[slot];
                    if (stack == null || stack.getType().isAir()) continue;
                    item.setString(1, id);
                    item.setInt(2, slot);
                    item.setBytes(3, stack.serializeAsBytes());
                    item.addBatch();
                }
            }
            replaced.executeBatch();
            dummy.executeBatch();
            clearItems.executeBatch();
            item.executeBatch();
        }
    }

    private static void bindKey(PreparedStatement statement, Key key) throws SQLException {
        statement.setString(1, key.owner().toString());
        statement.setInt(2, key.slot());
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = database.createStatement()) { statement.execute(sql); }
    }

    @FunctionalInterface private interface Transaction { void run() throws SQLException; }

    private void transaction(Transaction action) throws SQLException {
        database.setAutoCommit(false);
        try {
            action.run();
            database.commit();
        } catch (SQLException | RuntimeException error) {
            try { database.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
            throw error;
        } finally { database.setAutoCommit(true); }
    }

    void close() {
        synchronized (this) {
            closing = true;
            writer.execute(() -> {
                synchronized (this) { saveRequested = !changedEntries.isEmpty() || !changedAllowances.isEmpty(); }
                drain();
                synchronized (this) {
                    if (!changedEntries.isEmpty() || !changedAllowances.isEmpty()) plugin.getLogger().severe("players.db still has unsaved changes at shutdown.");
                }
                try { database.close(); }
                catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Could not close players.db", error); }
            });
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warning("Storage writes are still pending.");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
