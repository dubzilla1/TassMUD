package com.example.tassmud.tools;

import java.sql.*;

/**
 * Small DB inspection tool to help debug spawn duplication.
 */
public class DbInspect {
    public static void main(String[] args) {
        String url = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            runQuery(c, "Total mobile_instance rows", "SELECT COUNT(*) AS total FROM mobile_instance");
            runQuery(c, "mobile_instance rows with NULL orig_uuid", "SELECT COUNT(*) AS null_uuid FROM mobile_instance WHERE orig_uuid IS NULL");
            runQuery(c, "Live instances grouped by orig_uuid (counts >1)",
                "SELECT orig_uuid, COUNT(*) AS cnt FROM mobile_instance WHERE is_dead = FALSE GROUP BY orig_uuid HAVING COUNT(*) > 1");
            runQuery(c, "Recent mobile_instance rows (top 20)",
                "SELECT instance_id, template_id, current_room_id, orig_uuid, is_dead, spawned_at, died_at FROM mobile_instance ORDER BY instance_id DESC LIMIT 20");
            runQuery(c, "spawn_mapping counts by template for top 20",
                "SELECT template_id, COUNT(*) AS cnt FROM spawn_mapping GROUP BY template_id ORDER BY cnt DESC LIMIT 20");
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runQuery(Connection c, String label, String sql) {
        System.out.println("\n--- " + label + " ---");
        try (Statement s = c.createStatement()) {
            boolean hasRs = s.execute(sql);
            if (hasRs) {
                try (ResultSet rs = s.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    // Print header
                    for (int i = 1; i <= cols; i++) {
                        System.out.print(md.getColumnLabel(i) + (i==cols ? "\n" : "\t"));
                    }
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                        for (int i = 1; i <= cols; i++) {
                            Object v = rs.getObject(i);
                            System.out.print(String.valueOf(v) + (i==cols ? "\n" : "\t"));
                        }
                    }
                    if (rows == 0) System.out.println("<no rows>");
                }
            } else {
                int updateCount = s.getUpdateCount();
                System.out.println("Update count: " + updateCount);
            }
        } catch (SQLException e) {
            System.out.println("Query failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
