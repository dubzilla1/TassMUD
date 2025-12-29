package com.example.tassmud.tools;

import com.example.tassmud.persistence.DataLoader;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool wrapper to run DataLoader outside of server; logs via SLF4J.
 */
public class DataLoaderRunner {
    private static final Logger logger = LoggerFactory.getLogger(DataLoaderRunner.class);

    public static void main(String[] args) throws Exception {
        CharacterDAO dao = new CharacterDAO();
        DataLoader.loadDefaults(dao);
        logger.info("DataLoader.run completed");
        // Debug: print area rows we expect from MERC import
        int[] check = {30,31,32};
        for (int a : check) {
            try {
                com.example.tassmud.model.Area area = dao.getAreaById(a);
                if (area != null) logger.info("Area {}: {} / {}", a, area.getName(), area.getDescription());
                else logger.info("Area {} not found", a);
            } catch (Exception e) {
                logger.warn("Area lookup failed for {}: {}", a, e.getMessage(), e);
            }
        }
    }
}
