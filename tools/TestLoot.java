package com.example.tassmud.tools;

import com.example.tassmud.util.LootGenerator;
import com.example.tassmud.util.LootGenerator.GeneratedItem;
import com.example.tassmud.persistence.ItemDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestLoot {
    private static final Logger logger = LoggerFactory.getLogger(TestLoot.class);
    static class TestItemDAO extends ItemDAO {
        private long next = 100000L;
        @Override
        public long createGeneratedInstance(int templateId, Long containerInstanceId, String customName, String customDescription, int itemLevel, Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride, Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride, String spellEffect1, String spellEffect2, String spellEffect3, String spellEffect4, Integer valueOverride) {
            // Don't write to DB during test; just return a fake instance id
            return next++;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<Integer, Integer> armorTemplateCounts = new HashMap<>();
        int iterations = 20000;
        for (int i = 0; i < iterations; i++) {
            GeneratedItem gi = LootGenerator.sampleSingleItem(20);
            if (gi != null && gi.lootType == LootGenerator.LootType.ARMOR) {
                armorTemplateCounts.put(gi.templateId, armorTemplateCounts.getOrDefault(gi.templateId, 0) + 1);
            }
        }
        logger.info("Armor template distribution (top results):");
        armorTemplateCounts.entrySet().stream()
            .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(30)
            .forEach(e -> logger.info("{} -> {}", e.getKey(), e.getValue()));
    }
}
