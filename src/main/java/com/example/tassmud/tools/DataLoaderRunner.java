package com.example.tassmud.tools;

import com.example.tassmud.persistence.DataLoader;
import com.example.tassmud.persistence.CharacterDAO;

public class DataLoaderRunner {
    public static void main(String[] args) throws Exception {
        CharacterDAO dao = new CharacterDAO();
        DataLoader.loadDefaults(dao);
        System.out.println("DataLoader.run completed");
        // Debug: print area rows we expect from MERC import
        int[] check = {30,31,32};
        for (int a : check) {
            try {
                com.example.tassmud.model.Area area = dao.getAreaById(a);
                if (area != null) System.out.println("Area " + a + ": " + area.getName() + " / " + area.getDescription());
                else System.out.println("Area " + a + " not found");
            } catch (Exception e) {
                System.out.println("Area lookup failed for " + a + ": " + e.getMessage());
            }
        }
    }
}
