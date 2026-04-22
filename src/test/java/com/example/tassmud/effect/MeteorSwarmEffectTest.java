package com.example.tassmud.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Meteor Swarm Effect")
class MeteorSwarmEffectTest {

    @Test
    @DisplayName("Tick scaling maps proficiency 1..100 to 1..40 volleys")
    void tickScalingMapsOneToForty() {
        assertEquals(1, MeteorSwarmEffect.computeScaledTicksForTest(40, 1));
        assertEquals(20, MeteorSwarmEffect.computeScaledTicksForTest(40, 50));
        assertEquals(40, MeteorSwarmEffect.computeScaledTicksForTest(40, 100));
    }

    @Test
    @DisplayName("Apply seeds ticks and timing params from proficiency")
    void applySeedsTickParamsFromProficiency() {
        MeteorSwarmEffect handler = new MeteorSwarmEffect();

        EffectDefinition def = new EffectDefinition(
                "meteor_swarm_apply_test_" + UUID.randomUUID(),
                "Meteor Swarm",
                EffectDefinition.Type.METEOR_SWARM,
                Map.of("tick_interval", "3"),
                120,
                0,
                "4d6",
                0,
                Set.of(
                        EffectDefinition.ProficiencyImpact.DURATION,
                    EffectDefinition.ProficiencyImpact.TICKS_REMAINING
                ),
                EffectDefinition.StackPolicy.REFRESH,
                true,
                0
        );

        EffectInstance low = handler.apply(def, 1, 1, Map.of("proficiency", "1"));
        assertNotNull(low);
        assertEquals("1", low.getParams().get("ticks_remaining"));

        EffectInstance high = handler.apply(def, 1, 1, Map.of("proficiency", "100"));
        assertNotNull(high);
        assertEquals("40", high.getParams().get("ticks_remaining"));

        assertEquals("3000", high.getParams().get("tick_interval_ms"));
        assertNotNull(high.getParams().get("last_tick_ms"));
    }

    @Test
    @DisplayName("Tick decrements and removes instance after final volley")
    void tickDecrementsAndRemovesInstance() {
        String defId = "meteor_swarm_tick_test_" + UUID.randomUUID();

        EffectDefinition def = new EffectDefinition(
                defId,
                "Meteor Swarm",
                EffectDefinition.Type.METEOR_SWARM,
                Map.of("tick_interval", "3"),
                120,
                0,
                "4d6",
                0,
                Set.of(EffectDefinition.ProficiencyImpact.TICKS_REMAINING),
                EffectDefinition.StackPolicy.REFRESH,
                true,
                0
        );

        EffectRegistry.registerDefinition(def);
        EffectRegistry.registerHandler("METEOR_SWARM", new MeteorSwarmEffect());

        // 5% prof => round(40 * 0.05) = 2 volleys.
        EffectInstance inst = EffectRegistry.apply(defId, 1, 1, Map.of("proficiency", "5"));
        assertNotNull(inst);
        assertTrue(containsActive(inst.getId()));

        MeteorSwarmEffect handler = new MeteorSwarmEffect();
        long now = System.currentTimeMillis();

        handler.tick(inst, now + 3001L);
        assertEquals("1", inst.getParams().get("ticks_remaining"));
        assertTrue(containsActive(inst.getId()));

        handler.tick(inst, now + 6002L);
        assertFalse(containsActive(inst.getId()));
    }

    private boolean containsActive(UUID id) {
        for (EffectInstance ei : EffectRegistry.getAllActiveInstances()) {
            if (ei.getId().equals(id)) return true;
        }
        return false;
    }
}
