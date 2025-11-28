package com.example.tassmud.util;

import com.example.tassmud.persistence.ItemDAO;
import java.sql.*;

public class CheckDb {
    public static void main(String[] args) {
        try {
            // Ensure migrations run
            new ItemDAO();
        } catch (Exception e) {
            System.err.println("ItemDAO init failed: " + e.getMessage());
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
                    System.out.println("\nColumns for table " + tableName + ":");
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        System.out.println("  " + rs.getString("COLUMN_NAME") + " (" + rs.getString("TYPE_NAME") + ")");
                    }
                    if (!any) System.out.println("  <no columns or table does not exist>");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking table " + tableName + ": " + e.getMessage());
        }
    }
}
