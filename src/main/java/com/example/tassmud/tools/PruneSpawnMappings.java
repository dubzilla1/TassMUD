package com.example.tassmud.tools;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintenance utility to report and optionally prune duplicate spawn_mapping rows.
 * Usage:
 *  - dry-run (default): no args -> reports groups with >1 mappings
 *  - apply: pass "apply" to delete extra mappings leaving one per room/template
 */
public class PruneSpawnMappings {

    // Use embedded file URL (do not start/require H2 TCP server) to avoid classpath/server conflicts.
    private static final String URL = "jdbc:h2:file:./data/tassmud;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";

    public static void main(String[] args) throws Exception {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PruneSpawnMappings.class);
        boolean apply = args != null && args.length > 0 && "apply".equalsIgnoreCase(args[0]);
        logger.info("PruneSpawnMappings: starting in {} mode", (apply ? "APPLY" : "DRY-RUN"));

        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            List<Group> groups = findDuplicateGroups(c);
            if (groups.isEmpty()) {
                logger.info("No duplicate spawn_mapping groups found.");
                return;
            }

            logger.info("Found {} room/template groups with >1 mappings:", groups.size());
            for (Group g : groups) {
                logger.info("  room={} template={} count={}", g.roomId, g.templateId, g.count);
            }

            for (Group g : groups) {
                List<String> uuids = getUuidsForGroup(c, g.roomId, g.templateId);
                if (uuids.size() <= 1) continue;
                // Keep first, delete the rest
                List<String> toDelete = uuids.subList(1, uuids.size());
                logger.info("Group room={} template={} will delete {} mappings", g.roomId, g.templateId, toDelete.size());
                if (apply) {
                    deleteUuids(c, g.roomId, g.templateId, toDelete);
                    logger.info("  Deleted {} rows for room={} template={}", toDelete.size(), g.roomId, g.templateId);
                } else {
                    for (String u : toDelete) logger.info("  Would delete: {}", u);
                }
            }
        }

        org.slf4j.LoggerFactory.getLogger(PruneSpawnMappings.class).info("PruneSpawnMappings: finished");
    }

    private static List<Group> findDuplicateGroups(Connection c) throws SQLException {
        List<Group> out = new ArrayList<>();
        String sql = "SELECT room_id, template_id, COUNT(*) AS cnt FROM spawn_mapping GROUP BY room_id, template_id HAVING COUNT(*) > 1";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Group(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
            }
        }
        return out;
    }

    private static List<String> getUuidsForGroup(Connection c, int roomId, int templateId) throws SQLException {
        List<String> uuids = new ArrayList<>();
        String sql = "SELECT orig_uuid FROM spawn_mapping WHERE room_id = ? AND template_id = ? ORDER BY orig_uuid";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setInt(2, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) uuids.add(rs.getString(1));
            }
        }
        return uuids;
    }

    private static void deleteUuids(Connection c, int roomId, int templateId, List<String> uuids) throws SQLException {
        String sql = "DELETE FROM spawn_mapping WHERE room_id = ? AND template_id = ? AND orig_uuid = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (String u : uuids) {
                ps.setInt(1, roomId);
                ps.setInt(2, templateId);
                ps.setString(3, u);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static class Group {
        int roomId;
        int templateId;
        int count;
        Group(int r, int t, int c) { this.roomId = r; this.templateId = t; this.count = c; }
    }
}
