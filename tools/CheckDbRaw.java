import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckDbRaw {
    private static final Logger logger = LoggerFactory.getLogger(CheckDbRaw.class);
    public static void main(String[] args) {
        String url = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            // Run quick investigative queries for spawn duplication debugging
            runQuery(c, "Total mobile_instance rows", "SELECT COUNT(*) FROM mobile_instance");
            runQuery(c, "mobile_instance rows with NULL orig_uuid", "SELECT COUNT(*) FROM mobile_instance WHERE orig_uuid IS NULL");
            runQuery(c, "Live instances grouped by orig_uuid (counts >1)",
                "SELECT orig_uuid, COUNT(*) AS cnt FROM mobile_instance WHERE is_dead = FALSE GROUP BY orig_uuid HAVING COUNT(*) > 1");
            runQuery(c, "Recent mobile_instance rows (top 20)",
                "SELECT instance_id, template_id, current_room_id, orig_uuid, is_dead, spawned_at, died_at FROM mobile_instance ORDER BY instance_id DESC LIMIT 20");
            runQuery(c, "spawn_mapping counts by template for top 20",
                "SELECT template_id, COUNT(*) AS cnt FROM spawn_mapping GROUP BY template_id ORDER BY cnt DESC LIMIT 20");
        } catch (SQLException e) {
            logger.error("Connection failed: {}", e.getMessage(), e);
        }
    }

    private static void checkTable(Connection c, String table) {
        String sql = "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                logger.info("\nColumns for table {}:", table);
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    logger.info("  {} ({})", rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
                }
                if (!any) logger.info("  <no columns or table does not exist>");
            }
        } catch (SQLException e) {
            logger.warn("Error querying table {}: {}", table, e.getMessage(), e);
        }
    }

    private static void runQuery(Connection c, String label, String sql) {
        logger.info("\n--- {} ---", label);
        try (Statement s = c.createStatement()) {
            boolean hasRs = s.execute(sql);
            if (hasRs) {
                try (ResultSet rs = s.getResultSet()) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    // Print header
                    StringBuilder header = new StringBuilder();
                    for (int i = 1; i <= cols; i++) {
                        header.append(md.getColumnLabel(i));
                        if (i < cols) header.append('\t');
                    }
                    logger.info(header.toString());
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= cols; i++) {
                            Object v = rs.getObject(i);
                            row.append(String.valueOf(v));
                            if (i < cols) row.append('\t');
                        }
                        logger.info(row.toString());
                    }
                    if (rows == 0) logger.info("<no rows>");
                }
            } else {
                int updateCount = s.getUpdateCount();
                logger.info("Update count: {}", updateCount);
            }
        } catch (SQLException e) {
            logger.warn("Query failed: {}", e.getMessage(), e);
        }
    }
}
