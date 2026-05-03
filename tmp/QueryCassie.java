import java.sql.*;
public class QueryCassie {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:C:/Users/jason/dev/TassMUD/data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // Show columns
            System.out.println("=== COLUMNS ===");
            ResultSet cols = conn.getMetaData().getColumns(null, null, "CHARACTERS", null);
            while (cols.next()) System.out.println("  " + cols.getString("COLUMN_NAME"));

            // All characters
            System.out.println("\n=== ALL CHARACTERS ===");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, current_class_id FROM characters ORDER BY name")) {
                while (rs.next())
                    System.out.printf("  id=%-3d  name=%-20s  class_id=%s%n",
                        rs.getInt("id"), rs.getString("name"), rs.getString("current_class_id"));
            }

            // character_skill columns
            System.out.println("\n=== CHARACTER_SKILL COLUMNS ===");
            ResultSet sk = conn.getMetaData().getColumns(null, null, "CHARACTER_SKILL", null);
            while (sk.next()) System.out.println("  " + sk.getString("COLUMN_NAME"));
        }
    }
}
