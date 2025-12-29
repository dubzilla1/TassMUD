package com.example.tassmud.util;

import com.example.tassmud.persistence.ItemDAO;
import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckDb {
    private static final Logger logger = LoggerFactory.getLogger(CheckDb.class);

    public static void main(String[] args) {
        try {
            // Ensure migrations run
            new ItemDAO();
        } catch (Exception e) {
            logger.warn("ItemDAO init failed: {}", e.getMessage(), e);
        }
        checkTable("ITEM_TEMPLATE");
        checkTable("ITEM_INSTANCE");
        checkTable("CHARACTER_EQUIPMENT");
    }

    private static void checkTable(String tableName) {
        String url = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            String sql = "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    logger.info("\nColumns for table {}:", tableName);
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        logger.info("  {} ({})", rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
                    }
                    if (!any) logger.info("  <no columns or table does not exist>");
                }
            }
        } catch (SQLException e) {
            logger.warn("Error checking table {}: {}", tableName, e.getMessage(), e);
        }
    }
}
