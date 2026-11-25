package io.github.dummyplayers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite / MySQL 连接、方言差异和表结构。
 * SQLite 只有一个连接，所有访问在该连接上串行；MySQL 使用 HikariCP 连接池。
 */
final class Database implements AutoCloseable {
    static final int SCHEMA = 1;
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    enum Type { SQLITE, MYSQL }

    record Settings(Type type, String file, String host, int port, String database, String username, String password,
                    String parameters, String prefix, int maximumPool, int minimumIdle, long connectionTimeout,
                    long idleTimeout, long maxLifetime) {
        static Settings read(ConfigurationSection yaml) {
            String rawType = yaml.getString("database.type", "sqlite").strip().toLowerCase(Locale.ROOT);
            Type type = switch (rawType) {
                case "sqlite" -> Type.SQLITE;
                case "mysql", "mariadb" -> Type.MYSQL;
                default -> throw new IllegalArgumentException("database.yml: database.type must be sqlite or mysql");
            };
            String file = yaml.getString("database.file", "players.db").strip();
            if (file.isEmpty() || file.contains("/") || file.contains("\\") || file.contains("..")) {
                throw new IllegalArgumentException("database.yml: database.file must be a plain file name");
            }
            String prefix = yaml.getString("database.prefix", "afp_");
            if (prefix == null || !prefix.matches("[A-Za-z0-9_]{1,32}")) {
                throw new IllegalArgumentException("database.yml: database.prefix must be 1-32 letters, digits or underscores");
            }
            int port = yaml.getInt("database.port", 3306);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("database.yml: database.port is out of range");
            int maximum = Math.clamp(yaml.getInt("database.pool.maximum_pool_size", 6), 2, 100);
            int minimum = Math.clamp(yaml.getInt("database.pool.minimum_idle", 2), 0, maximum);
            return new Settings(type, file, yaml.getString("database.host", "localhost").strip(), port,
                yaml.getString("database.database", "alfakeplayer").strip(), yaml.getString("database.username", "root"),
                yaml.getString("database.password", ""), yaml.getString("database.parameters", "").strip(), prefix,
                maximum, minimum,
                Math.max(250, yaml.getLong("database.pool.connection_timeout", 10000)),
                Math.max(10000, yaml.getLong("database.pool.idle_timeout", 600000)),
                Math.max(30000, yaml.getLong("database.pool.max_lifetime", 1800000)));
        }
    }

    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }

    final Type type;
    private final String prefix;
    private final Logger logger;
    private final HikariDataSource pool;
    private final Connection sqlite;

    private Database(Type type, String prefix, Logger logger, HikariDataSource pool, Connection sqlite) {
        this.type = type;
        this.prefix = prefix;
        this.logger = logger;
        this.pool = pool;
        this.sqlite = sqlite;
    }

    static Database open(JavaPlugin plugin, Settings settings) throws Exception {
        Path folder = plugin.getDataFolder().toPath();
        Files.createDirectories(folder);
        if (settings.type == Type.SQLITE) {
            Connection connection = openSqlite(plugin, folder.resolve(settings.file));
            return new Database(Type.SQLITE, settings.prefix, plugin.getLogger(), null, connection);
        }
        // MySQL 驱动由 Paper 自带，先用服务端类加载器注册，再交给连接池
        Class.forName(MYSQL_DRIVER, true, plugin.getServer().getClass().getClassLoader());
        var config = new HikariConfig();
        config.setPoolName("ALFakePlayer-MySQL");
        config.setDriverClassName(MYSQL_DRIVER);
        String query = settings.parameters.isEmpty() ? "" : "?" + settings.parameters;
        config.setJdbcUrl("jdbc:mysql://" + settings.host + ":" + settings.port + "/" + settings.database + query);
        config.setUsername(settings.username);
        config.setPassword(settings.password);
        config.setMaximumPoolSize(settings.maximumPool);
        config.setMinimumIdle(settings.minimumIdle);
        config.setConnectionTimeout(settings.connectionTimeout);
        config.setIdleTimeout(settings.idleTimeout);
        config.setMaxLifetime(settings.maxLifetime);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "64");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(plugin.getServer().getClass().getClassLoader());
        try {
            return new Database(Type.MYSQL, settings.prefix, plugin.getLogger(), new HikariDataSource(config), null);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    static Connection openSqlite(JavaPlugin plugin, Path file) throws Exception {
        Driver driver = Class.forName("org.sqlite.JDBC", true, plugin.getServer().getClass().getClassLoader())
            .asSubclass(Driver.class).getConstructor().newInstance();
        Connection connection = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 3000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    boolean mysql() { return type == Type.MYSQL; }

    String table(String name) { return prefix + name; }

    /** 数据库自身的当前时间（毫秒），各子服共用同一时钟。 */
    String now() {
        return mysql() ? "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)"
            : "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";
    }

    String insertIgnore() { return mysql() ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO "; }

    String forUpdate() { return mysql() ? " FOR UPDATE" : ""; }

    /** 主键冲突时覆盖指定列。 */
    String onConflict(String key, String... columns) {
        var sql = new StringBuilder(mysql() ? " ON DUPLICATE KEY UPDATE " : " ON CONFLICT(" + key + ") DO UPDATE SET ");
        for (int index = 0; index < columns.length; index++) {
            if (index > 0) sql.append(", ");
            String column = columns[index];
            sql.append(column).append(" = ").append(mysql() ? "VALUES(" + column + ")" : "excluded." + column);
        }
        return sql.toString();
    }

    <T> T withConnection(Work<T> work) throws SQLException {
        if (sqlite != null) {
            synchronized (sqlite) { return work.run(sqlite); }
        }
        try (Connection connection = pool.getConnection()) { return work.run(connection); }
    }

    /** 在事务中执行；死锁或锁等待超时时最多重试三次，因此 work 不能修改外部状态。 */
    <T> T transaction(Work<T> work) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return withConnection(connection -> {
                    connection.setAutoCommit(false);
                    try {
                        T result = work.run(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException error) {
                        try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                        throw error;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                });
            } catch (SQLException error) {
                if (attempt >= 3 || !retryable(error)) throw error;
            }
        }
    }

    private static boolean retryable(SQLException error) {
        return "40001".equals(error.getSQLState()) || error.getErrorCode() == 1213 || error.getErrorCode() == 1205
            || error.getErrorCode() == 5 || error.getErrorCode() == 6;
    }

    static boolean duplicateKey(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLIntegrityConstraintViolationException) return true;
            if (cause instanceof SQLException sql && ("23000".equals(sql.getSQLState()) || sql.getErrorCode() == 1062
                || sql.getErrorCode() == 19)) return true;
        }
        return false;
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof Boolean flag) statement.setInt(index + 1, flag ? 1 : 0);
            else statement.setObject(index + 1, value);
        }
    }

    // ── 表结构 ────────────────────────────────────────────

    void migrate() throws SQLException {
        String engine = mysql() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        String serial = mysql() ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        withConnection(connection -> {
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("meta")
                + " (meta_key VARCHAR(64) NOT NULL PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)" + engine);
            String stored = meta(connection, "schema-version");
            int version = stored == null ? 0 : Integer.parseInt(stored);
            if (version > SCHEMA) {
                throw new SQLException("Database schema " + version + " is newer than this plugin supports (" + SCHEMA + ")");
            }
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("allowances")
                + " (owner VARCHAR(36) NOT NULL PRIMARY KEY, slots INT NOT NULL)" + engine);
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("dummies") + " ("
                + "id VARCHAR(36) NOT NULL PRIMARY KEY, owner VARCHAR(36) NOT NULL, slot INT NOT NULL,"
                + " alias VARCHAR(32) NOT NULL DEFAULT '', tier VARCHAR(16) NOT NULL DEFAULT 'NORMAL',"
                + " tool VARCHAR(16) NOT NULL DEFAULT 'PICKAXE', mine TINYINT NOT NULL DEFAULT 0,"
                + " attack TINYINT NOT NULL DEFAULT 0, use_item TINYINT NOT NULL DEFAULT 0,"
                + " auto_unload TINYINT NOT NULL DEFAULT 1, pushable TINYINT NOT NULL DEFAULT 1,"
                + " sneaking TINYINT NOT NULL DEFAULT 0, attack_interval_ticks INT NOT NULL DEFAULT 20,"
                + " placement_block VARCHAR(128) NULL, work_yaw DOUBLE NULL, updated_at BIGINT NOT NULL DEFAULT 0,"
                + " UNIQUE (owner, slot))" + engine);
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("inventory") + " ("
                + "dummy_id VARCHAR(36) NOT NULL, slot INT NOT NULL, item " + (mysql() ? "MEDIUMBLOB" : "BLOB") + " NOT NULL,"
                + " PRIMARY KEY (dummy_id, slot),"
                + " FOREIGN KEY (dummy_id) REFERENCES " + table("dummies") + " (id) ON DELETE CASCADE)" + engine);
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("sessions") + " ("
                + "dummy_id VARCHAR(36) NOT NULL PRIMARY KEY, server_id VARCHAR(64) NOT NULL,"
                + " live TINYINT NOT NULL DEFAULT 0, heartbeat BIGINT NOT NULL,"
                + " FOREIGN KEY (dummy_id) REFERENCES " + table("dummies") + " (id) ON DELETE CASCADE)" + engine);
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("servers") + " ("
                + "server_id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, instance VARCHAR(36) NOT NULL,"
                + " live_count INT NOT NULL DEFAULT 0, heartbeat BIGINT NOT NULL)" + engine);
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("purchase_items") + " ("
                + "position INT NOT NULL PRIMARY KEY, item " + (mysql() ? "MEDIUMBLOB" : "BLOB") + " NOT NULL)" + engine);
            execute(connection, "CREATE TABLE IF NOT EXISTS " + table("messages") + " ("
                + "id " + serial + ", target VARCHAR(64) NOT NULL, payload TEXT NOT NULL, created BIGINT NOT NULL)" + engine);
            if (version < SCHEMA) setMeta(connection, "schema-version", String.valueOf(SCHEMA));
            return null;
        });
    }

    String meta(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meta_value FROM " + table("meta") + " WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    void setMeta(Connection connection, String key, String value) throws SQLException {
        update(connection, "INSERT INTO " + table("meta") + " (meta_key, meta_value) VALUES (?, ?)"
            + onConflict("meta_key", "meta_value"), key, value);
    }

    // ── 从 SQLite 文件导入 ─────────────────────────────────

    private record LegacyDummy(String id, String owner, int slot, String alias, String tier, String tool, boolean mine,
                               boolean attack, boolean use, boolean autoUnload, boolean pushable, boolean sneaking,
                               int attackInterval, String placement, Double workYaw) { }
    private record LegacyItem(String dummy, int slot, byte[] item) { }
    private record Imported(List<String[]> allowances, List<LegacyDummy> dummies, List<LegacyItem> items,
                            List<byte[]> purchaseItems) {
        boolean empty() { return allowances.isEmpty() && dummies.isEmpty() && purchaseItems.isEmpty(); }
    }

    /**
     * 新表为空且从未导入过时，导入一次旧数据；旧表和旧文件保留不动。
     * SQLite：导入同一文件中 1.0.x 的无前缀表（allowances / dummies / inventory）。
     * MySQL：导入本服 players.db。优先读取其中 1.1 起使用的带前缀新表（包含购买物品），
     * 没有新表时才读取 1.0.x 的旧表，因此先升级、后切换到 MySQL 不会导入过期数据。
     */
    void importLegacy(JavaPlugin plugin, String file, String serverId) {
        try {
            if (withConnection(connection -> meta(connection, "legacy-import")) != null) return;
            Path path = plugin.getDataFolder().toPath().resolve(file);
            if (!mysql() && sqlite == null || mysql() && !Files.isRegularFile(path)) return;
            Connection source = mysql() ? openSqlite(plugin, path) : sqlite;
            try {
                Imported data;
                String from;
                synchronized (source) {
                    from = mysql() && sqliteTable(source, table("dummies")) ? prefix : "";
                    if (from.isEmpty() && !sqliteTable(source, "dummies") && !sqliteTable(source, "allowances")) return;
                    data = readSource(source, from);
                }
                if (data.empty()) return;
                int[] result = transaction(connection -> {
                    if (update(connection, insertIgnore() + table("meta") + " (meta_key, meta_value) VALUES (?, ?)",
                        "legacy-import", serverId + " " + System.currentTimeMillis()) == 0) return null;
                    if (count(connection, table("dummies")) > 0 || count(connection, table("allowances")) > 0) {
                        return new int[] {-1, 0, 0};
                    }
                    writeImported(connection, data);
                    return new int[] {data.dummies.size(), data.items.size(), data.allowances.size()};
                });
                if (result == null) return;
                String origin = file + (from.isEmpty() ? " (1.0 tables)" : " (" + from + "* tables)");
                if (result[0] < 0) {
                    logger.warning("Found fake player data in " + origin + ", but the " + type
                        + " tables already contain fake players; it was not imported.");
                } else {
                    logger.info("Imported " + result[0] + " fake player(s), " + result[1] + " inventory slot(s) and "
                        + result[2] + " slot allowance(s) from " + origin + "; the original data was kept.");
                }
            } finally {
                if (mysql()) source.close();
            }
        } catch (Exception error) {
            logger.log(Level.SEVERE, "Could not import fake player data from " + file, error);
        }
    }

    private static boolean sqliteTable(Connection source, String name) throws SQLException {
        try (PreparedStatement statement = source.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static Set<String> columns(Connection source, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = source.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) columns.add(rows.getString("name").toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    /** 读取 SQLite 文件中的数据；from 为表名前缀（1.0.x 旧表为空字符串）。新旧表的列名相同。 */
    private static Imported readSource(Connection source, String from) throws SQLException {
        var data = new Imported(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        try (Statement statement = source.createStatement()) {
            if (sqliteTable(source, from + "allowances")) {
                try (ResultSet rows = statement.executeQuery("SELECT owner, slots FROM " + from + "allowances")) {
                    while (rows.next()) data.allowances.add(new String[] {rows.getString(1), String.valueOf(rows.getInt(2))});
                }
            }
            if (!from.isEmpty() && sqliteTable(source, from + "purchase_items")) {
                try (ResultSet rows = statement.executeQuery("SELECT item FROM " + from + "purchase_items ORDER BY position")) {
                    while (rows.next()) data.purchaseItems.add(rows.getBytes(1));
                }
            }
            if (!sqliteTable(source, from + "dummies")) return data;
            Set<String> present = columns(source, from + "dummies");
            try (ResultSet rows = statement.executeQuery("SELECT * FROM " + from + "dummies")) {
                while (rows.next()) {
                    Object yaw = present.contains("work_yaw") ? rows.getObject("work_yaw") : null;
                    data.dummies.add(new LegacyDummy(rows.getString("id"), rows.getString("owner"), rows.getInt("slot"),
                        Objects.requireNonNullElse(rows.getString("alias"), ""),
                        present.contains("tier") ? rows.getString("tier") : "NORMAL",
                        present.contains("tool") ? rows.getString("tool") : "PICKAXE",
                        rows.getBoolean("mine"), rows.getBoolean("attack"), rows.getBoolean("use_item"),
                        !present.contains("auto_unload") || rows.getBoolean("auto_unload"),
                        !present.contains("pushable") || rows.getBoolean("pushable"),
                        present.contains("sneaking") && rows.getBoolean("sneaking"),
                        present.contains("attack_interval_ticks") ? rows.getInt("attack_interval_ticks") : 20,
                        present.contains("placement_block") ? rows.getString("placement_block") : null,
                        yaw instanceof Number number ? number.doubleValue() : null));
                }
            }
            if (!sqliteTable(source, from + "inventory")) return data;
            try (ResultSet rows = statement.executeQuery("SELECT dummy_id, slot, item FROM " + from + "inventory")) {
                while (rows.next()) data.items.add(new LegacyItem(rows.getString(1), rows.getInt(2), rows.getBytes(3)));
            }
        }
        return data;
    }

    private void writeImported(Connection connection, Imported data) throws SQLException {
        try (PreparedStatement allowance = connection.prepareStatement(
                 "INSERT INTO " + table("allowances") + " (owner, slots) VALUES (?, ?)");
             PreparedStatement dummy = connection.prepareStatement("INSERT INTO " + table("dummies")
                 + " (id, owner, slot, alias, tier, tool, mine, attack, use_item, auto_unload, pushable, sneaking,"
                 + " attack_interval_ticks, placement_block, work_yaw, updated_at)"
                 + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + now() + ")");
             PreparedStatement item = connection.prepareStatement(
                 "INSERT INTO " + table("inventory") + " (dummy_id, slot, item) VALUES (?, ?, ?)")) {
            for (String[] row : data.allowances) {
                allowance.setString(1, row[0]);
                allowance.setInt(2, Integer.parseInt(row[1]));
                allowance.addBatch();
            }
            allowance.executeBatch();
            Set<String> ids = new HashSet<>();
            for (LegacyDummy row : data.dummies) {
                ids.add(row.id);
                bind(dummy, row.id, row.owner, row.slot, row.alias, row.tier, row.tool, row.mine, row.attack, row.use,
                    row.autoUnload, row.pushable, row.sneaking, row.attackInterval, row.placement, row.workYaw);
                dummy.addBatch();
            }
            dummy.executeBatch();
            for (LegacyItem row : data.items) {
                if (!ids.contains(row.dummy) || row.slot < 0 || row.slot > 40 || row.item == null) continue;
                item.setString(1, row.dummy);
                item.setInt(2, row.slot);
                item.setBytes(3, row.item);
                item.addBatch();
            }
            item.executeBatch();
        }
        if (!data.purchaseItems.isEmpty() && count(connection, table("purchase_items")) == 0) {
            try (PreparedStatement purchase = connection.prepareStatement(
                    "INSERT INTO " + table("purchase_items") + " (position, item) VALUES (?, ?)")) {
                for (int index = 0; index < data.purchaseItems.size() && index < 3; index++) {
                    purchase.setInt(1, index);
                    purchase.setBytes(2, data.purchaseItems.get(index));
                    purchase.addBatch();
                }
                purchase.executeBatch();
            }
            setMeta(connection, "purchase-items-imported", "sqlite");
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    @Override public void close() {
        try {
            if (pool != null) pool.close();
            if (sqlite != null) synchronized (sqlite) { sqlite.close(); }
        } catch (SQLException error) {
            logger.log(Level.SEVERE, "Could not close the database", error);
        }
    }
}
