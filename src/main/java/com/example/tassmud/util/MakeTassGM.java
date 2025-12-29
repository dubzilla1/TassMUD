package com.example.tassmud.util;

import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small helper program to mark the character 'Tass' as a GM in the game's DB.
 */
public class MakeTassGM {
    private static final Logger logger = LoggerFactory.getLogger(MakeTassGM.class);

    public static void main(String[] args) {
        try {
            CharacterDAO dao = new CharacterDAO();
            boolean ok = dao.setCharacterFlagByName("Tass", "is_gm", "1");
            logger.info("setCharacterFlagByName returned: {}", ok);
            if (ok) logger.info("Tass is now a GM (is_gm=1) in the DB.");
            else logger.warn("Failed to set is_gm for Tass. Is the DB accessible and does 'Tass' exist?");
        } catch (Exception e) {
            logger.error("Error while attempting to set Tass as GM: {}", e.getMessage(), e);
            System.exit(2);
        }
    }
}
