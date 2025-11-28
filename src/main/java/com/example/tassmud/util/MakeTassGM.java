package com.example.tassmud.util;

import com.example.tassmud.persistence.CharacterDAO;

/**
 * Small helper program to mark the character 'Tass' as a GM in the game's DB.
 */
public class MakeTassGM {
    public static void main(String[] args) {
        try {
            CharacterDAO dao = new CharacterDAO();
            boolean ok = dao.setCharacterFlagByName("Tass", "is_gm", "1");
            System.out.println("setCharacterFlagByName returned: " + ok);
            if (ok) System.out.println("Tass is now a GM (is_gm=1) in the DB.");
            else System.err.println("Failed to set is_gm for Tass. Is the DB accessible and does 'Tass' exist?");
        } catch (Exception e) {
            System.err.println("Error while attempting to set Tass as GM: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
