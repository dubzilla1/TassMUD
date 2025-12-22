package com.example.tassmud.tools;

import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.model.Door;
import com.example.tassmud.model.Room;

import java.util.List;
import java.util.Map;

public class RoomInspector {
    public static void main(String[] args) throws Exception {
        CharacterDAO dao = new CharacterDAO();
        int[] ids = args.length == 0 ? new int[]{1001, 6200, 3001} : parseArgs(args);
        for (int id : ids) {
            System.out.println("== Room " + id + " ==");
            Room r = dao.getRoomById(id);
            if (r == null) {
                System.out.println("  Room not found in DB");
            } else {
                System.out.println("  Name: " + r.getName());
                System.out.println("  Short: " + r.getShortDesc());
                List<Door> doors = dao.getDoorsForRoom(id);
                System.out.println("  Doors: " + doors.size());
                for (Door d : doors) System.out.println("    dir=" + d.direction + " to=" + d.toRoomId + " desc='" + d.description + "'");
                Map<String,String> extras = dao.getRoomExtras(id);
                System.out.println("  Extras: " + extras.size());
                for (Map.Entry<String,String> e : extras.entrySet()) System.out.println("    key='" + e.getKey() + "' -> '''" + e.getValue().replaceAll("\n","\\n") + "'''");
            }
            System.out.println();
        }
    }

    private static int[] parseArgs(String[] args) {
        int[] out = new int[args.length];
        for (int i = 0; i < args.length; i++) out[i] = Integer.parseInt(args[i]);
        return out;
    }
}
