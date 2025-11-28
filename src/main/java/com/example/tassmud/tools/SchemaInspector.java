package com.example.tassmud.tools;

import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;

import java.io.File;
import java.util.List;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class SchemaInspector {
    public static void main(String[] args) {
        try {
            System.out.println("Checking data directory and tables via in-process DAOs...");

            File dataDir = new File("data");
            System.out.println("Data directory: " + dataDir.getAbsolutePath());
            if (dataDir.exists() && dataDir.isDirectory()) {
                File[] files = dataDir.listFiles();
                if (files != null) {
                    for (File f : files) System.out.println(" - " + f.getName());
                }
            } else {
                System.out.println(" - <no data directory present>");
            }

            CharacterDAO dao = new CharacterDAO();
            // Instantiate ItemDAO to run its ensureTables() and migrations (item tables live there)
            ItemDAO itemDao = new ItemDAO();
            System.out.println("ItemDAO instantiated to apply item table migrations and ensure tables.");

            printCols("item_template", dao.listTableColumns("item_template"));
            printCols("item_instance", dao.listTableColumns("item_instance"));
            printCols("character_equipment", dao.listTableColumns("character_equipment"));

            // Also print all tables in INFORMATION_SCHEMA for debugging
            System.out.println("\nListing tables in INFORMATION_SCHEMA (PUBLIC schema):");
            try (Connection c = DriverManager.getConnection("jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
                 PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.println("TABLE: " + rs.getString(1));
                }
            } catch (Exception e) {
                System.out.println("Failed to query INFORMATION_SCHEMA.TABLES: " + e.getMessage());
            }

            // Print columns for the interesting tables directly from INFORMATION_SCHEMA
            System.out.println("\nListing columns for ITEM_* and CHARACTER_EQUIPMENT from INFORMATION_SCHEMA.COLUMNS:");
            try (Connection c = DriverManager.getConnection("jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
                 PreparedStatement ps2 = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME IN ('ITEM_TEMPLATE','ITEM_INSTANCE','CHARACTER_EQUIPMENT') ORDER BY TABLE_NAME, ORDINAL_POSITION");
                 ResultSet rs2 = ps2.executeQuery()) {
                boolean any = false;
                while (rs2.next()) {
                    any = true;
                    System.out.println(rs2.getString("TABLE_NAME") + " -> " + rs2.getString("COLUMN_NAME"));
                }
                if (!any) System.out.println("  <no rows returned from INFORMATION_SCHEMA.COLUMNS for those tables>");
            } catch (Exception e) {
                System.out.println("Failed to query INFORMATION_SCHEMA.COLUMNS: " + e.getMessage());
            }

            System.out.println("Done.");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }

    private static void printCols(String table, List<String> cols) {
        System.out.println("Table: " + table);
        if (cols == null || cols.isEmpty()) {
            System.out.println("  <no columns or table does not exist>");
            return;
        }
        for (String c : cols) System.out.println("  " + c);
    }
}
