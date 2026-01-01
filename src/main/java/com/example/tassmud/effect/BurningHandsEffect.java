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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Burning Hands effect - a fire DOT that can spread to other enemies in combat.
 * 
 * - 2nd level single target fire DOT spell
 * - Scales up to 5d4 damage per tick
 * - 15 second duration
 * - Every tick has a 25% chance of spreading to another enemy in combat
 * - Refreshes duration if cast again (same target, same caster)
 * - Can "dance" between enemies - spreading back to previously affected targets
 *   if the original effect has worn off
 * 
 * Target ID convention:
 *   - Positive ID: player character ID
 *   - Negative ID: -(mobile instanceId) for mob targets
 */
public class BurningHandsEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(BurningHandsEffect.class);
    private static final int DEFAULT_TICK_INTERVAL_MS = 3000; // 3 seconds
    private static final double SPREAD_CHANCE = 0.25; // 25% chance to spread per tick

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

        // Handle REFRESH stack policy - remove existing effect from same caster on same target
        if (def.getStackPolicy() == EffectDefinition.StackPolicy.REFRESH ||
            def.getStackPolicy() == EffectDefinition.StackPolicy.REPLACE_HIGHER_PRIORITY) {
            for (EffectInstance existing : EffectRegistry.getActiveForTarget(targetId)) {
                if (existing.getDefId().equals(def.getId()) && 
                    casterId != null && casterId.equals(existing.getCasterId())) {
                    EffectRegistry.removeInstance(existing.getId());
                    logger.debug("[BurningHandsEffect] Refreshed existing {} on target {}", def.getName(), targetId);
                    break;
                }
            }
        }

        // Compute duration
        long durationMs = (long) (def.getDurationSeconds() * 1000);
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DURATION)) {
            durationMs = (long) (durationMs * (0.5 + (proficiency / 100.0) * 0.5));
        }

        long nowMs = System.currentTimeMillis();
        long expiresAtMs = nowMs + durationMs;

        // Store proficiency and last tick time
        p.put("proficiency", String.valueOf(proficiency));
        p.put("last_tick_ms", String.valueOf(nowMs));

        UUID id = UUID.randomUUID();
        EffectInstance inst = new EffectInstance(id, def.getId(), casterId, targetId, p, nowMs, expiresAtMs, def.getPriority());

        // Deal initial tick damage immediately
        dealTickDamage(inst, def, nowMs);

        // Notify target
        String casterName = resolveName(casterId);
        
        if (targetId > 0) {
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("Flames from " + casterName + "'s burning hands engulf you!");
            }
        }

        // Notify caster
        if (casterId != null && casterId > 0) {
            ClientHandler casterSession = ClientHandler.charIdToSession.get(casterId);
            if (casterSession != null) {
                String targetName = resolveName(targetId);
                casterSession.sendRaw("Flames shoot from your fingertips, engulfing " + targetName + "!");
            }
        }

        logger.debug("[BurningHandsEffect] Applied {} to {} for {}ms", def.getName(), resolveName(targetId), durationMs);
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
            return;
        }

        // Update last tick time
        p.put("last_tick_ms", String.valueOf(nowMs));

        // Deal damage
        dealTickDamage(instance, def, nowMs);

        // Attempt to spread to another enemy (25% chance)
        attemptSpread(instance, def);
    }

    @Override
    public void expire(EffectInstance instance) {
        EffectDefinition def = EffectRegistry.getDefinition(instance.getDefId());
        String effectName = def != null ? def.getName() : "burning flames";
        Integer targetId = instance.getTargetId();

        if (targetId != null && targetId > 0) {
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("The burning flames fade away.");
            }
        }

        logger.debug("[BurningHandsEffect] {} expired on target {}", effectName, targetId);
    }

    /**
     * Attempt to spread the burning effect to another enemy in combat.
     * 25% chance per tick, only spreads if there are valid targets.
     */
    private void attemptSpread(EffectInstance instance, EffectDefinition def) {
        // Roll for spread
        if (Math.random() > SPREAD_CHANCE) {
            return; // No spread this tick
        }

        Integer targetId = instance.getTargetId();
        Integer casterId = instance.getCasterId();
        
        // Find the combat the target is in
        CombatManager cm = CombatManager.getInstance();
        Combat combat = null;
        Combatant targetCombatant = null;

        boolean isMobTarget = targetId != null && targetId < 0;
        long mobInstanceId = isMobTarget ? -targetId : 0;

        if (isMobTarget) {
            for (Combat c : cm.getAllActiveCombats()) {
                targetCombatant = c.findByMobileInstanceId(mobInstanceId);
                if (targetCombatant != null) {
                    combat = c;
                    break;
                }
            }
        } else if (targetId != null && targetId > 0) {
            for (Combat c : cm.getAllActiveCombats()) {
                targetCombatant = c.findByCharacterId(targetId);
                if (targetCombatant != null) {
                    combat = c;
                    break;
                }
            }
        }

        if (combat == null || targetCombatant == null) {
            logger.debug("[BurningHandsEffect] Cannot spread - target not in combat");
            return;
        }

        // Find other enemies (same alliance as original target, excluding original target)
        // The effect spreads to enemies of the caster, which are allies of the target
        int targetAlliance = targetCombatant.getAlliance();
        List<Combatant> potentialTargets = combat.getCombatants().stream()
                .filter(Combatant::isActive)
                .filter(Combatant::isAlive)
                .filter(c -> c.getAlliance() == targetAlliance) // Same alliance as current target
                .filter(c -> !isSameTarget(c, targetId)) // Not the current target
                .filter(c -> !hasActiveBurningHands(c, def.getId())) // Doesn't already have burning hands
                .collect(Collectors.toList());

        if (potentialTargets.isEmpty()) {
            logger.debug("[BurningHandsEffect] No valid spread targets available");
            return;
        }

        // Pick a random target
        Combatant newTarget = potentialTargets.get((int) (Math.random() * potentialTargets.size()));
        Integer newTargetId = getTargetIdForCombatant(newTarget);

        if (newTargetId == null) {
            return;
        }

        // Copy params from original instance for the spread
        Map<String, String> spreadParams = new HashMap<>(instance.getParams());
        // Keep the same caster and proficiency but start fresh tick timer
        spreadParams.put("last_tick_ms", String.valueOf(System.currentTimeMillis()));

        // Apply the effect to the new target
        EffectInstance spreadInstance = EffectRegistry.apply(def.getId(), casterId, newTargetId, spreadParams);
        
        if (spreadInstance != null) {
            String originalTargetName = resolveName(targetId);
            String newTargetName = resolveName(newTargetId);
            
            logger.info("[BurningHandsEffect] Flames spread from {} to {}!", originalTargetName, newTargetName);

            // Notify everyone in combat about the spread
            if (casterId != null && casterId > 0) {
                ClientHandler casterSession = ClientHandler.charIdToSession.get(casterId);
                if (casterSession != null) {
                    casterSession.sendRaw("The flames leap from " + originalTargetName + " to " + newTargetName + "!");
                }
            }

            // Notify original target if player
            if (targetId > 0) {
                ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
                if (targetSession != null) {
                    targetSession.sendRaw("The flames leap from you to " + newTargetName + "!");
                }
            }

            // Notify new target if player
            if (newTargetId > 0) {
                ClientHandler newTargetSession = ClientHandler.charIdToSession.get(newTargetId);
                if (newTargetSession != null) {
                    newTargetSession.sendRaw("Flames leap to you from " + originalTargetName + "!");
                }
            }
        }
    }

    /**
     * Check if a combatant matches the given target ID.
     */
    private boolean isSameTarget(Combatant c, Integer targetId) {
        if (targetId == null) return false;
        if (targetId > 0) {
            // Player target
            return targetId.equals(c.getCharacterId());
        } else {
            // Mob target
            long mobInstanceId = -targetId;
            return c.getMobile() != null && c.getMobile().getInstanceId() == mobInstanceId;
        }
    }

    /**
     * Get the target ID for a combatant (positive for players, negative for mobs).
     */
    private Integer getTargetIdForCombatant(Combatant c) {
        if (c.isPlayer() && c.getCharacterId() != null) {
            return c.getCharacterId();
        } else if (c.isMobile() && c.getMobile() != null) {
            return -(int) c.getMobile().getInstanceId();
        }
        return null;
    }

    /**
     * Check if a combatant already has this burning hands effect active.
     */
    private boolean hasActiveBurningHands(Combatant c, String effectDefId) {
        Integer targetId = getTargetIdForCombatant(c);
        if (targetId == null) return false;
        
        return EffectRegistry.hasEffect(targetId, effectDefId);
    }

    private void dealTickDamage(EffectInstance instance, EffectDefinition def, long nowMs) {
        Integer targetId = instance.getTargetId();
        Integer casterId = instance.getCasterId();
        Map<String, String> p = instance.getParams();

        // Parse dice spec (e.g., "5d4")
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
        int baseN, dieM;
        try {
            baseN = Integer.parseInt(nStr);
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

        String damageType = p != null ? p.getOrDefault("damage_type", "fire") : "fire";
        String casterName = resolveName(casterId);
        String targetName = resolveName(targetId);

        boolean isMobTarget = targetId != null && targetId < 0;
        long mobInstanceId = isMobTarget ? -targetId : 0;

        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = null;

        if (isMobTarget) {
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
                    targetSession.sendRaw("The flames burn you for " + total + " damage! [" + newHp + " HP]");
                }
            }

            // Notify caster (if player)
            if (casterId != null && casterId > 0) {
                ClientHandler casterSession = ClientHandler.charIdToSession.get(casterId);
                if (casterSession != null) {
                    casterSession.sendRaw("Your flames burn " + targetName + " for " + total + " damage!");
                }
            }

            logger.debug("[BurningHandsEffect] {} ticks {} {} damage on {} ({}->{}hp)",
                    def.getName(), total, damageType, targetName, oldHp, newHp);

            if (newHp <= 0) {
                logger.info("[BurningHandsEffect] {} killed {} with {} DOT", casterName, targetName, def.getName());
            }
        } else if (!isMobTarget && targetId != null && targetId > 0) {
            // Not in combat - player target, apply directly
            CharacterDAO.CharacterRecord rec = dao.findById(targetId);
            if (rec == null) return;

            int newHp = Math.max(0, rec.hpCur - total);
            dao.saveCharacterStateByName(rec.name, newHp, rec.mpCur, rec.mvCur, rec.currentRoom);

            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("The flames burn you for " + total + " damage! [" + newHp + " HP]");
            }

            logger.debug("[BurningHandsEffect] {} ticks {} {} damage on {} (out of combat)",
                    def.getName(), total, damageType, targetName);
        }
    }

    private String resolveName(Integer id) {
        if (id == null) return "unknown";
        
        if (id > 0) {
            CharacterDAO dao = new CharacterDAO();
            CharacterDAO.CharacterRecord rec = dao.findById(id);
            return rec != null ? rec.name : "unknown";
        } else {
            long mobInstanceId = -id;
            MobileDAO mobDao = new MobileDAO();
            Mobile mob = mobDao.getInstanceById(mobInstanceId);
            return mob != null ? mob.getName() : "unknown";
        }
    }
}
