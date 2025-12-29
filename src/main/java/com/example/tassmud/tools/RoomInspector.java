package com.example.tassmud.tools;

import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.model.Door;
import com.example.tassmud.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class RoomInspector {
    private static final Logger logger = LoggerFactory.getLogger(RoomInspector.class);

    public static void main(String[] args) throws Exception {
        CharacterDAO dao = new CharacterDAO();
        int[] ids = args.length == 0 ? new int[]{1001, 6200, 3001} : parseArgs(args);
        for (int id : ids) {
            logger.info("== Room {} ==", id);
            Room r = dao.getRoomById(id);
            if (r == null) {
                logger.info("  Room not found in DB");
            } else {
                logger.info("  Name: {}", r.getName());
                logger.info("  Short: {}", r.getShortDesc());
                List<Door> doors = dao.getDoorsForRoom(id);
                logger.info("  Doors: {}", doors.size());
                for (Door d : doors) logger.info("    dir={} to={} desc='{}'", d.direction, d.toRoomId, d.description);
                Map<String,String> extras = dao.getRoomExtras(id);
                logger.info("  Extras: {}", extras.size());
                for (Map.Entry<String,String> e : extras.entrySet()) logger.info("    key='{}' -> '''{}'''", e.getKey(), e.getValue().replaceAll("\n","\\n"));
            }
            logger.info("");
        }
    }

    private static int[] parseArgs(String[] args) {
        int[] out = new int[args.length];
        for (int i = 0; i < args.length; i++) out[i] = Integer.parseInt(args[i]);
        return out;
    }
}
