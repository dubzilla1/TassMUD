package com.example.tassmud.util;

import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MakeCheckGM {
    private static final Logger logger = LoggerFactory.getLogger(MakeCheckGM.class);

    public static void main(String[] args) {
        try {
            CharacterDAO dao = new CharacterDAO();
            String[] names = new String[] {"Tass", "tass"};
            for (String n : names) {
                String v = dao.getCharacterFlagByName(n, "is_gm");
                logger.info("Flag for '{}' is_gm = {}", n, v);
                boolean isTrue = dao.isCharacterFlagTrueByName(n, "is_gm");
                logger.info("isCharacterFlagTrueByName('{}') -> {}", n, isTrue);
            }
        } catch (Exception e) {
            logger.error("Error checking GM flag: {}", e.getMessage(), e);
            System.exit(2);
        }
    }
}
