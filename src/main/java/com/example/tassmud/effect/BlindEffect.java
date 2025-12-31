package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for blindness debuff.
 * 
 * Blindness prevents the target from:
 * - Seeing room details, objects, mobs, or exits
 * - Performing targeted commands/skills/spells (anything requiring a target search)
 * 
 * Blindness does NOT prevent:
 * - Ongoing combat (automatic attacks continue)
 * - Non-targeted actions
 * 
 * Combat penalty:
 * - All combat actions (including basic attacks) have a 50% miss chance
 * 
 * Duration scales with proficiency:
 * - Minimum duration at low proficiency (min_duration param, default 5 seconds)
 * - Maximum duration at 100% proficiency (duration param, default 30 seconds)
 */
public class BlindEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(BlindEffect.class);
    
    /** Default minimum duration at low proficiency (seconds) */
    private static final double DEFAULT_MIN_DURATION = 5.0;
    
    /** Default maximum duration at full proficiency (seconds) */
    private static final double DEFAULT_MAX_DURATION = 30.0;
    
    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;
        
        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);
        
        long now = System.currentTimeMillis();
        
        // Calculate duration based on proficiency
        // Duration scales linearly from min_duration to max duration
        double maxDuration = def.getDurationSeconds() > 0 ? def.getDurationSeconds() : DEFAULT_MAX_DURATION;
        double minDuration = DEFAULT_MIN_DURATION;
        try {
            String minDurStr = p.get("min_duration");
            if (minDurStr != null) {
                minDuration = Double.parseDouble(minDurStr);
            }
        } catch (Exception ignored) {}
        
        // Get proficiency (1-100)
        int prof = 1;
        try {
            String profStr = p.get("proficiency");
            if (profStr != null) {
                prof = Integer.parseInt(profStr);
            }
        } catch (Exception ignored) {}
        prof = Math.max(1, Math.min(100, prof));
        
        // Scale duration: min + (max - min) * (prof / 100)
        double effectiveDuration = minDuration + (maxDuration - minDuration) * (prof / 100.0);
        long durationMs = (long) (effectiveDuration * 1000);
        long expiresAt = now + durationMs;
        
        UUID instanceId = UUID.randomUUID();
        EffectInstance inst = new EffectInstance(instanceId, def.getId(), casterId, targetId, p, now, expiresAt, def.getPriority());
        
        // Notify target they've been blinded
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("Your vision goes dark! You can't see anything!");
        }
        
        // Notify caster
        if (casterId != null && !casterId.equals(targetId)) {
            ClientHandler casterSession = ClientHandler.charIdToSession.get(casterId);
            if (casterSession != null) {
                String targetName = resolveName(targetId);
                casterSession.sendRaw("You blind " + targetName + "!");
            }
        }
        
        logger.debug("[BlindEffect] Applied blindness to target {} for {}s (prof={}%)", 
                targetId, effectiveDuration, prof);
        
        return inst;
    }
    
    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // Blindness has no tick behavior - it's a passive debuff
    }
    
    @Override
    public void expire(EffectInstance instance) {
        Integer targetId = instance.getTargetId();
        
        // Notify target the blindness has worn off
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("Your vision clears.");
        }
        
        logger.debug("[BlindEffect] Blindness expired on target {}", targetId);
    }
    
    /**
     * Resolve a character ID to a display name.
     */
    private String resolveName(Integer charId) {
        if (charId == null) return "someone";
        
        // Check for mob (negative ID convention)
        if (charId < 0) {
            int mobInstanceId = -charId;
            com.example.tassmud.persistence.MobileDAO mobDao = new com.example.tassmud.persistence.MobileDAO();
            com.example.tassmud.model.Mobile mob = mobDao.getInstanceById(mobInstanceId);
            if (mob != null) {
                return mob.getName();
            }
            return "someone";
        }
        
        // Player character
        com.example.tassmud.persistence.CharacterDAO dao = new com.example.tassmud.persistence.CharacterDAO();
        com.example.tassmud.persistence.CharacterDAO.CharacterRecord rec = dao.findById(charId);
        return rec != null ? rec.name : "someone";
    }
}
