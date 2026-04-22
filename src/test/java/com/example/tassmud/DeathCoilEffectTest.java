package com.example.tassmud;

import com.example.tassmud.effect.DeathCoilDotEffect;
import com.example.tassmud.effect.DeathCoilEffect;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Death Coil Effects")
class DeathCoilEffectTest {

    @Test
    @DisplayName("EffectDefinition.Type includes Death Coil custom types")
    void effectTypeIncludesDeathCoilCustomTypes() {
        assertEquals(EffectDefinition.Type.DEATH_COIL, EffectDefinition.Type.valueOf("DEATH_COIL"));
        assertEquals(EffectDefinition.Type.DEATH_COIL_DOT, EffectDefinition.Type.valueOf("DEATH_COIL_DOT"));
    }

    @Test
    @DisplayName("Death Coil effect apply returns null for missing caster or target")
    void deathCoilApplyGuardsNulls() {
        DeathCoilEffect handler = new DeathCoilEffect();
        EffectDefinition def = new EffectDefinition(
                "test_death_coil_null_guard",
                "Death Coil",
                EffectDefinition.Type.DEATH_COIL,
                Map.of(),
                0,
                15,
                "10d6",
                0,
                Set.of(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER),
                EffectDefinition.StackPolicy.UNIQUE,
                false,
                0
        );

        assertNull(handler.apply(def, null, -9001, Map.of("proficiency", "100")));
        assertNull(handler.apply(def, 1, null, Map.of("proficiency", "100")));
    }

    @Test
    @DisplayName("Death Coil DOT apply validates required params")
    void deathCoilDotApplyValidatesParams() {
        DeathCoilDotEffect handler = new DeathCoilDotEffect();
        EffectDefinition dotDef = new EffectDefinition(
                "test_death_coil_dot_validate",
                "Death Coil Residue",
                EffectDefinition.Type.DEATH_COIL_DOT,
                Map.of(),
                20,
                0,
                "0",
                0,
                Set.of(),
                EffectDefinition.StackPolicy.REFRESH,
                true,
                0
        );

        assertNull(handler.apply(dotDef, 1, -123, Map.of("dot_damage", "0", "ticks_remaining", "5")));
        assertNull(handler.apply(dotDef, 1, -123, Map.of("dot_damage", "5", "ticks_remaining", "0")));
        assertNotNull(handler.apply(dotDef, 1, -123, Map.of("dot_damage", "5", "ticks_remaining", "5")));
    }

    @Test
    @DisplayName("Death Coil DOT ticks exactly five times then removes itself")
    void deathCoilDotTicksFiveTimesThenExpires() {
        String defId = "test_death_coil_dot_lifecycle_" + UUID.randomUUID();

        EffectDefinition def = new EffectDefinition(
                defId,
                "Death Coil Residue",
                EffectDefinition.Type.DEATH_COIL_DOT,
                Map.of("tick_interval", "3", "ticks_remaining", "5"),
                20,
                0,
                "0",
                0,
                Set.of(),
                EffectDefinition.StackPolicy.REFRESH,
                true,
                0
        );

        EffectRegistry.registerDefinition(def);
        EffectRegistry.registerHandler("DEATH_COIL_DOT", new DeathCoilDotEffect());

        Integer casterId = 1;
        Integer mobTargetId = -424242;

        EffectInstance instance = EffectRegistry.apply(defId, casterId, mobTargetId,
                Map.of("dot_damage", "7", "ticks_remaining", "5", "tick_interval", "3"));

        assertNotNull(instance);
        assertTrue(containsActive(instance.getId()));

        DeathCoilDotEffect handler = new DeathCoilDotEffect();
        long now = System.currentTimeMillis();

        // Tick 1..4: should still be active
        for (int i = 1; i <= 4; i++) {
            handler.tick(instance, now + (i * 3001L));
            assertTrue(containsActive(instance.getId()), "Should still be active after tick " + i);
        }

        // Tick 5: should remove itself
        handler.tick(instance, now + (5 * 3001L));
        assertFalse(containsActive(instance.getId()));
    }

    @Test
    @DisplayName("Death Coil crit applies residue DOT effect")
    void deathCoilCritAppliesResidueDot() {
        // Register residue effect used by DeathCoilEffect on crit.
        EffectDefinition residueDef = new EffectDefinition(
                "1019",
                "Death Coil Residue",
                EffectDefinition.Type.DEATH_COIL_DOT,
                Map.of("tick_interval", "3", "ticks_remaining", "5"),
                20,
                0,
                "0",
                0,
                Set.of(),
                EffectDefinition.StackPolicy.REFRESH,
                true,
                0
        );
        EffectRegistry.registerDefinition(residueDef);
        EffectRegistry.registerHandler("DEATH_COIL_DOT", new DeathCoilDotEffect());

        EffectDefinition deathCoilDef = new EffectDefinition(
                "1018_test",
                "Death Coil",
                EffectDefinition.Type.DEATH_COIL,
                Map.of(),
                0,
                15,
                "10d6",
                0,
                Set.of(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER),
                EffectDefinition.StackPolicy.UNIQUE,
                false,
                0
        );

        DeathCoilEffect handler = new DeathCoilEffect();
        Integer casterId = 1;
        Integer mobTargetId = -515151;

        int beforeResidues = countActiveResidueEffectsForTarget(mobTargetId);

        // Crit chance is ~5% by default; loop to make test robust without mocking.
        boolean critAppliedResidue = false;
        for (int i = 0; i < 500; i++) {
            handler.apply(deathCoilDef, casterId, mobTargetId, Map.of("proficiency", "100"));
            int after = countActiveResidueEffectsForTarget(mobTargetId);
            if (after > beforeResidues) {
                critAppliedResidue = true;
                break;
            }
        }

        assertTrue(critAppliedResidue, "Expected at least one crit-triggered residue within 500 casts");

        // Cleanup any residue instances created by this test target.
        List<EffectInstance> snapshot = List.copyOf(EffectRegistry.getAllActiveInstances());
        for (EffectInstance ei : snapshot) {
            if (mobTargetId.equals(ei.getTargetId()) && "1019".equals(ei.getDefId())) {
                EffectRegistry.removeInstance(ei.getId());
            }
        }
    }

    private boolean containsActive(UUID id) {
        for (EffectInstance ei : EffectRegistry.getAllActiveInstances()) {
            if (ei.getId().equals(id)) return true;
        }
        return false;
    }

    private int countActiveResidueEffectsForTarget(Integer targetId) {
        int count = 0;
        for (EffectInstance ei : EffectRegistry.getAllActiveInstances()) {
            if (targetId.equals(ei.getTargetId()) && "1019".equals(ei.getDefId())) {
                count++;
            }
        }
        return count;
    }
}
