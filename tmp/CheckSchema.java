import java.sql.*;
public class CheckSchema {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:C:/Users/jason/dev/TassMUD/data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            ResultSet rs = c.getMetaData().getColumns(null, null, "CHARACTERS", null);
            while (rs.next()) System.out.println(rs.getString("COLUMN_NAME"));
        }
    }
}
