package com.example.tassmud.tools;

import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@SuppressWarnings("unused") // itemDao instantiated for side effect (ensureTables)
public class SchemaInspector {
    private static final Logger logger = LoggerFactory.getLogger(SchemaInspector.class);
    public static void main(String[] args) {
        try {
            logger.info("Checking data directory and tables via in-process DAOs...");

            File dataDir = new File("data");
            logger.info("Data directory: {}", dataDir.getAbsolutePath());
            if (dataDir.exists() && dataDir.isDirectory()) {
                File[] files = dataDir.listFiles();
                if (files != null) {
                    for (File f : files) logger.info(" - {}", f.getName());
                }
            } else {
                logger.info(" - <no data directory present>");
            }

            CharacterDAO dao = new CharacterDAO();
            // Instantiate ItemDAO to run its ensureTables() and migrations (item tables live there)
            ItemDAO itemDao = new ItemDAO();
            logger.info("ItemDAO instantiated to apply item table migrations and ensure tables.");

            printCols("item_template", dao.listTableColumns("item_template"));
            printCols("item_instance", dao.listTableColumns("item_instance"));
            printCols("character_equipment", dao.listTableColumns("character_equipment"));

            // Also print all tables in INFORMATION_SCHEMA for debugging
            logger.info("Listing tables in INFORMATION_SCHEMA (PUBLIC schema)");
            try (Connection c = DriverManager.getConnection("jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
                 PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logger.info("TABLE: {}", rs.getString(1));
                }
            } catch (Exception e) {
                logger.warn("Failed to query INFORMATION_SCHEMA.TABLES: {}", e.getMessage());
            }

            // Print columns for the interesting tables directly from INFORMATION_SCHEMA
            logger.info("Listing columns for ITEM_* and CHARACTER_EQUIPMENT from INFORMATION_SCHEMA.COLUMNS");
            try (Connection c = DriverManager.getConnection("jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
                 PreparedStatement ps2 = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME IN ('ITEM_TEMPLATE','ITEM_INSTANCE','CHARACTER_EQUIPMENT') ORDER BY TABLE_NAME, ORDINAL_POSITION");
                 ResultSet rs2 = ps2.executeQuery()) {
                boolean any = false;
                while (rs2.next()) {
                    any = true;
                    logger.info("{} -> {}", rs2.getString("TABLE_NAME"), rs2.getString("COLUMN_NAME"));
                }
                if (!any) logger.info("  <no rows returned from INFORMATION_SCHEMA.COLUMNS for those tables>");
            } catch (Exception e) {
                logger.warn("Failed to query INFORMATION_SCHEMA.COLUMNS: {}", e.getMessage());
            }

            logger.info("Done.");
        } catch (Throwable t) {
            logger.error("Fatal error in SchemaInspector", t);
            System.exit(2);
        }
    }

    private static void printCols(String table, List<String> cols) {
        logger.info("Table: {}", table);
        if (cols == null || cols.isEmpty()) {
            logger.info("  <no columns or table does not exist>");
            return;
        }
        for (String c : cols) logger.info("  {}", c);
    }
}
