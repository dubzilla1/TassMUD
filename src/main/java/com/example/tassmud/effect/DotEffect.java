package com.example.tassmud.effect;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.MobileDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Damage-over-time (DOT) effect handler.
 * 
 * Applies periodic damage to a target at a configurable tick interval.
 * Damage is rolled fresh each tick using the dice_multiplier (e.g., "5d6"),
 * scaled by caster proficiency when DICE_MULTIPLIER is in proficiency_impact.
 * 
 * Target ID convention:
 *   - Positive ID: player character ID (from characters table)
 *   - Negative ID: -(mobile instanceId) for mob targets
 * 
 * Params (from effect definition or extraParams):
 *   tick_interval - seconds between damage ticks (default 3)
 *   damage_type   - label for combat messages (e.g., "acid", "fire", "poison")
 *   dice          - alternative dice spec if not using dice_multiplier
 * 
 * StackPolicy should be REFRESH to reset duration without stacking.
 */
public class DotEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(DotEffect.class);
    private static final int DEFAULT_TICK_INTERVAL_MS = 3000; // 3 seconds

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Get proficiency for damage scaling
        int proficiency = 1;
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Check for existing DOT from same caster on same target with same effect
        // If REFRESH policy, remove the old one first
        if (def.getStackPolicy() == EffectDefinition.StackPolicy.REFRESH ||
            def.getStackPolicy() == EffectDefinition.StackPolicy.REPLACE_HIGHER_PRIORITY) {
            for (EffectInstance existing : EffectRegistry.getActiveForTarget(targetId)) {
                if (existing.getDefId().equals(def.getId()) && 
                    casterId != null && casterId.equals(existing.getCasterId())) {
                    EffectRegistry.removeInstance(existing.getId());
                    logger.debug("[DotEffect] Refreshed existing {} on target {}", def.getName(), targetId);
                    break;
                }
            }
        }

        // Compute duration
        long durationMs = (long) (def.getDurationSeconds() * 1000);
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DURATION)) {
            // Scale duration by proficiency (higher prof = longer duration, up to 2x at 100%)
            durationMs = (long) (durationMs * (0.5 + (proficiency / 100.0) * 0.5));
        }

        long nowMs = System.currentTimeMillis();
        long expiresAtMs = nowMs + durationMs;

        // Store proficiency and last tick time in params for tick() to use
        p.put("proficiency", String.valueOf(proficiency));
        p.put("last_tick_ms", String.valueOf(nowMs)); // First tick happens immediately on apply

        UUID id = UUID.randomUUID();
        EffectInstance inst = new EffectInstance(id, def.getId(), casterId, targetId, p, nowMs, expiresAtMs, def.getPriority());

        // Deal initial tick damage immediately
        dealTickDamage(inst, def, nowMs);

        // Notify target
        String damageType = p.getOrDefault("damage_type", "magical");
        String casterName = resolveName(casterId);
        String targetName = resolveName(targetId);
        
        // Try to notify target if it's a player
        if (targetId > 0) {
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("You are afflicted by " + def.getName() + " from " + casterName + "!");
            }
        }

        logger.debug("[DotEffect] Applied {} to {} for {}ms", def.getName(), targetName, durationMs);
        return inst;
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        EffectDefinition def = EffectRegistry.getDefinition(instance.getDefId());
        if (def == null) return;

        Map<String, String> p = instance.getParams();
        if (p == null) return;

        // Get tick interval
        int tickIntervalMs = DEFAULT_TICK_INTERVAL_MS;
        try {
            String intervalStr = p.get("tick_interval");
            if (intervalStr != null) {
                tickIntervalMs = (int) (Double.parseDouble(intervalStr) * 1000);
            }
        } catch (Exception ignored) {}

        // Check if enough time has passed since last tick
        long lastTickMs = 0;
        try {
            lastTickMs = Long.parseLong(p.getOrDefault("last_tick_ms", "0"));
        } catch (Exception ignored) {}

        if (nowMs - lastTickMs < tickIntervalMs) {
            return; // Not time for next tick yet
        }

        // Update last tick time
        p.put("last_tick_ms", String.valueOf(nowMs));

        // Deal damage
        dealTickDamage(instance, def, nowMs);
    }

    @Override
    public void expire(EffectInstance instance) {
        EffectDefinition def = EffectRegistry.getDefinition(instance.getDefId());
        String effectName = def != null ? def.getName() : "effect";
        Integer targetId = instance.getTargetId();

        // Notify target the DOT has worn off (only for players)
        if (targetId != null && targetId > 0) {
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("The " + effectName + " fades away.");
            }
        }

        logger.debug("[DotEffect] {} expired on target {}", effectName, targetId);
    }

    private void dealTickDamage(EffectInstance instance, EffectDefinition def, long nowMs) {
        Integer targetId = instance.getTargetId();
        Integer casterId = instance.getCasterId();
        Map<String, String> p = instance.getParams();

        // Parse dice spec
        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p != null && p.containsKey("dice")) {
            raw = p.get("dice");
        }
        if (raw == null || raw.isEmpty()) return;

        // Get proficiency
        int prof = 1;
        try {
            prof = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        prof = Math.max(1, Math.min(100, prof));

        // Parse NdM
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return;
        String nStr = raw.substring(0, dIdx);
        String mStr = raw.substring(dIdx + 1);
        int baseN = 0, dieM = 0;
        try {
            baseN = Integer.parseInt(nStr);
        } catch (Exception e) {
            return;
        }
        try {
            dieM = Integer.parseInt(mStr);
        } catch (Exception e) {
            return;
        }

        // Scale N by proficiency if configured
        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (prof / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        // Roll damage
        int total = 0;
        for (int i = 0; i < scaledN; i++) {
            total += (int) (Math.random() * dieM) + 1;
        }

        if (total <= 0) return;

        // Get damage type for messaging
        String damageType = p != null ? p.getOrDefault("damage_type", "magical") : "magical";

        String casterName = resolveName(casterId);
        String targetName = resolveName(targetId);

        // Determine if target is a mob (negative ID) or player (positive ID)
        boolean isMobTarget = targetId != null && targetId < 0;
        long mobInstanceId = isMobTarget ? -targetId : 0;

        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = null;

        if (isMobTarget) {
            // Find combatant by mobile instance ID
            for (Combat combat : cm.getAllActiveCombats()) {
                targetCombatant = combat.findByMobileInstanceId(mobInstanceId);
                if (targetCombatant != null) break;
            }
        } else if (targetId != null && targetId > 0) {
            targetCombatant = cm.getCombatantForCharacter(targetId);
        }

        CharacterDAO dao = new CharacterDAO();

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            // In combat - apply damage to combatant
            com.example.tassmud.model.GameCharacter targetChar = targetCombatant.getAsCharacter();
            int oldHp = targetChar.getHpCur();
            targetChar.setHpCur(oldHp - total);
            int newHp = targetChar.getHpCur();

            // Persist HP for players
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                dao.saveCharacterStateByName(targetChar.getName(), newHp,
                        targetChar.getMpCur(), targetChar.getMvCur(), targetChar.getCurrentRoom());
            }

            // Notify target (if player)
            if (targetCombatant.isPlayer()) {
                ClientHandler targetSession = ClientHandler.charIdToSession.get(targetCombatant.getCharacterId());
                if (targetSession != null) {
                    targetSession.sendRaw("The " + damageType + " burns you for " + total + " damage! [" + newHp + " HP]");
                }
            }

            // Notify caster (if player)
            if (casterId != null && casterId > 0) {
                ClientHandler casterSession = ClientHandler.charIdToSession.get(casterId);
                if (casterSession != null) {
                    casterSession.sendRaw("Your " + damageType + " burns " + targetName + " for " + total + " damage!");
                }
            }

            logger.debug("[DotEffect] {} ticks {} {} damage on {} ({}->{}hp)",
                    def.getName(), total, damageType, targetName, oldHp, newHp);

            // Check for death - combat system will handle it via HP check
            if (newHp <= 0) {
                logger.info("[DotEffect] {} killed {} with {} DOT", casterName, targetName, def.getName());
            }
        } else if (!isMobTarget && targetId != null && targetId > 0) {
            // Not in combat - player target, apply directly to character record
            CharacterDAO.CharacterRecord rec = dao.findById(targetId);
            if (rec == null) return;

            int newHp = Math.max(0, rec.hpCur - total);
            dao.saveCharacterStateByName(rec.name, newHp, rec.mpCur, rec.mvCur, rec.currentRoom);

            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("The " + damageType + " burns you for " + total + " damage! [" + newHp + " HP]");
            }

            logger.debug("[DotEffect] {} ticks {} {} damage on {} (out of combat)",
                    def.getName(), total, damageType, targetName);
        }
        // For mobs not in combat, the DOT just fizzles (they're probably dead or despawned)
    }

    /**
     * Resolve a target/caster ID to a name.
     * Positive IDs are player character IDs, negative IDs are mob instance IDs.
     */
    private String resolveName(Integer id) {
        if (id == null) return "unknown";
        
        if (id > 0) {
            // Player character
            CharacterDAO dao = new CharacterDAO();
            CharacterDAO.CharacterRecord rec = dao.findById(id);
            return rec != null ? rec.name : "unknown";
        } else {
            // Mob (negative instance ID)
            long mobInstanceId = -id;
            MobileDAO mobDao = new MobileDAO();
            Mobile mob = mobDao.getInstanceById(mobInstanceId);
            return mob != null ? mob.getName() : "unknown";
        }
    }
}
