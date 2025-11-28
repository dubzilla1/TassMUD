package com.example.tassmud.util;

import com.example.tassmud.persistence.CharacterDAO;

public class MakeCheckGM {
    public static void main(String[] args) {
        try {
            CharacterDAO dao = new CharacterDAO();
            String[] names = new String[] {"Tass", "tass"};
            for (String n : names) {
                String v = dao.getCharacterFlagByName(n, "is_gm");
                System.out.println("Flag for '" + n + "' is_gm = " + v);
                boolean isTrue = dao.isCharacterFlagTrueByName(n, "is_gm");
                System.out.println("isCharacterFlagTrueByName('" + n + "') -> " + isTrue);
            }
        } catch (Exception e) {
            System.err.println("Error checking GM flag: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
