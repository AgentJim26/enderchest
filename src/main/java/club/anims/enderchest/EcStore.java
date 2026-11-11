package club.anims.enderchest;

import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EcStore implements AutoCloseable {
    private final Connection conn;

    public EcStore(File dataFolder) throws SQLException, ClassNotFoundException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("Cannot create data folder " + dataFolder);
        }
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dataFolder, "enderchest.db").getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS ec_players (
                    uuid TEXT PRIMARY KEY,
                    pages INTEGER NOT NULL DEFAULT 1,
                    sort_mode TEXT NOT NULL DEFAULT 'CUSTOM'
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS ec_items (
                    uuid TEXT NOT NULL,
                    page INTEGER NOT NULL,
                    slot INTEGER NOT NULL,
                    item BLOB NOT NULL,
                    PRIMARY KEY (uuid, page, slot)
                )
            """);
        }
        migratePageSize();
    }

    private void migratePageSize() throws SQLException {
        int version = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            if (rs.next()) version = rs.getInt(1);
        }
        if (version >= 2) return;

        Map<String, List<byte[]>> byPlayer = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, item FROM ec_items ORDER BY uuid, page, slot");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byPlayer.computeIfAbsent(rs.getString("uuid"), key -> new ArrayList<>())
                        .add(rs.getBytes("item"));
            }
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM ec_items");
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO ec_items(uuid, page, slot, item) VALUES(?,?,?,?)")) {
            int capacity = EcSession.MAX_PAGES * EcSession.SLOTS_PER_PAGE;
            for (Map.Entry<String, List<byte[]>> entry : byPlayer.entrySet()) {
                int index = 0;
                for (byte[] blob : entry.getValue()) {
                    if (blob == null) continue;
                    if (index >= capacity) break;
                    ins.setString(1, entry.getKey());
                    ins.setInt(2, index / EcSession.SLOTS_PER_PAGE + 1);
                    ins.setInt(3, index % EcSession.SLOTS_PER_PAGE);
                    ins.setBytes(4, blob);
                    ins.addBatch();
                    index++;
                }
            }
            ins.executeBatch();
        }
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version=2");
        }
    }

    public EcSession load(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pages, sort_mode FROM ec_players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                EcSession session = new EcSession(uuid);
                session.pages = Math.max(1, Math.min(EcSession.MAX_PAGES, rs.getInt("pages")));
                session.sortMode = SortMode.parse(rs.getString("sort_mode"));
                try (PreparedStatement ips = conn.prepareStatement("SELECT page, slot, item FROM ec_items WHERE uuid = ?")) {
                    ips.setString(1, uuid.toString());
                    try (ResultSet irs = ips.executeQuery()) {
                        while (irs.next()) {
                            int page = irs.getInt("page");
                            int slot = irs.getInt("slot");
                            byte[] blob = irs.getBytes("item");
                            if (blob == null || page < 1 || page > session.pages
                                    || slot < 0 || slot >= EcSession.SLOTS_PER_PAGE) continue;
                            try {
                                ItemStack stack = ItemStack.deserializeBytes(blob);
                                if (stack != null && !stack.getType().isAir()) {
                                    session.items.put((page - 1) * EcSession.SLOTS_PER_PAGE + slot, stack);
                                }
                            } catch (RuntimeException ignored) {
                                // A single unreadable stack must not take the whole chest down.
                            }
                        }
                    }
                }
                return session;
            }
        }
    }

    public void save(EcSession session) throws SQLException {
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ec_players(uuid, pages, sort_mode) VALUES(?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET pages = excluded.pages, sort_mode = excluded.sort_mode
            """)) {
                ps.setString(1, session.uuid.toString());
                ps.setInt(2, session.pages);
                ps.setString(3, session.sortMode.name());
                ps.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM ec_items WHERE uuid = ?")) {
                del.setString(1, session.uuid.toString());
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO ec_items(uuid, page, slot, item) VALUES(?,?,?,?)")) {
                for (var entry : session.items.entrySet()) {
                    ItemStack stack = entry.getValue();
                    if (stack == null || stack.getType().isAir()) continue;
                    int index = entry.getKey();
                    int page = index / EcSession.SLOTS_PER_PAGE + 1;
                    int slot = index % EcSession.SLOTS_PER_PAGE;
                    ins.setString(1, session.uuid.toString());
                    ins.setInt(2, page);
                    ins.setInt(3, slot);
                    ins.setBytes(4, stack.serializeAsBytes());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
