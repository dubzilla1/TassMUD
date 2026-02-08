package com.example.tassmud.persistence;

import java.sql.*;

/**
 * DAO for the settings table (key/value game-wide state).
 * Extracted from CharacterDAO to separate game settings from character data.
 */
public class SettingsDAO {

    private static final String URL = System.getProperty("tassmud.db.url",
            "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    public SettingsDAO() {
        MigrationManager.ensureMigration("SettingsDAO", this::ensureTable);
    }

    public void ensureTable() {
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS settings (k VARCHAR(200) PRIMARY KEY, v VARCHAR(2000))");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create settings table", e);
        }
    }

    public String getSetting(String key) {
        String sql = "SELECT v FROM settings WHERE k = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("v");
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public boolean setSetting(String key, String value) {
        String sql = "MERGE INTO settings (k, v) KEY(k) VALUES (?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
