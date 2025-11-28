import java.sql.*;

public class CheckDbRaw {
    public static void main(String[] args) {
        String url = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            checkTable(c, "ITEM_TEMPLATE");
            checkTable(c, "ITEM_INSTANCE");
            checkTable(c, "CHARACTER_EQUIPMENT");
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
        }
    }

    private static void checkTable(Connection c, String table) {
        String sql = "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\nColumns for table " + table + ":");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.println("  " + rs.getString("COLUMN_NAME") + " (" + rs.getString("TYPE_NAME") + ")");
                }
                if (!any) System.out.println("  <no columns or table does not exist>");
            }
        } catch (SQLException e) {
            System.out.println("Error querying table " + table + ": " + e.getMessage());
        }
    }
}
