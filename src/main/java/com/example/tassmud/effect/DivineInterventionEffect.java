package com.example.tassmud.effect;

import com.example.tassmud.combat.Combatant;
import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Divine Intervention effect — places a divine ward on the target.
 * When the target would die, the ward intercepts the killing blow,
 * removes itself, and restores the target to full health.
 *
 * Only one target may be warded per caster at a time. Casting on a second
 * target automatically removes the ward from the first.
 */
public class DivineInterventionEffect implements EffectHandler {

    public static final String EFFECT_DEF_ID = "1020";

    private static final Logger logger = LoggerFactory.getLogger(DivineInterventionEffect.class);

    /** Maps casterId → the active EffectInstance (for one-target-per-caster enforcement). */
    private static final ConcurrentHashMap<Integer, EffectInstance> casterToInstance = new ConcurrentHashMap<>();

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        // If this caster already has an active ward on another target, remove it first
        if (casterId != null) {
            EffectInstance existing = casterToInstance.remove(casterId);
            if (existing != null) {
                Integer oldTargetId = existing.getTargetId();
                EffectRegistry.removeInstance(existing.getId());
                if (oldTargetId != null) {
                    // Resolve caster name for the notification message
                    String casterName = "the cleric";
                    try {
                        com.example.tassmud.persistence.CharacterDAO.CharacterRecord casterRec =
                            com.example.tassmud.persistence.DaoProvider.characters().findById(casterId);
                        if (casterRec != null && casterRec.name != null) casterName = casterRec.name;
                    } catch (Exception ignored) {}
                    ClientHandler.sendToCharacter(oldTargetId,
                        "\u001B[33mThe hand of the Angel withdraws from you as " + casterName +
                        " bestows their blessing upon another.\u001B[0m");
                }
                logger.debug("[divine intervention] Displaced old ward (caster={}, oldTarget={})",
                    casterId, existing.getTargetId());
            }
        }

        // Compute duration
        long now = System.currentTimeMillis();
        long expiresAt = def.getDurationSeconds() > 0
            ? now + (long)(def.getDurationSeconds() * 1000.0)
            : 0;

        UUID instanceId = UUID.randomUUID();

        Map<String, String> params = new HashMap<>();
        if (def.getParams() != null) params.putAll(def.getParams());
        if (extraParams != null) params.putAll(extraParams);
        if (casterId != null) params.put("casterId", casterId.toString());

        EffectInstance inst = new EffectInstance(instanceId, def.getId(), casterId, targetId, params, now, expiresAt, def.getPriority());

        // Track this instance for the caster (stored before registry adds it to activeInstances)
        if (casterId != null) {
            casterToInstance.put(casterId, inst);
        }

        logger.debug("[divine intervention] Applied ward (caster={}, target={}, expires={})",
            casterId, targetId, expiresAt);
        return inst;
    }

    @Override
    public void expire(EffectInstance instance) {
        // Clean up caster tracking
        Integer casterId = instance.getCasterId();
        if (casterId != null) {
            casterToInstance.remove(casterId, instance);
        }

        // Notify target the ward expired naturally
        Integer targetId = instance.getTargetId();
        if (targetId != null) {
            ClientHandler.sendToCharacter(targetId,
                "\u001B[33mThe divine ward protecting you has faded.\u001B[0m");
        }

        logger.debug("[divine intervention] Effect expired (caster={}, target={})", casterId, targetId);
    }

    /**
     * Called by DeathHandler when a combatant would die.
     * If the victim has an active Divine Intervention ward, intercepts the death:
     * removes the ward, restores the victim to full HP, broadcasts the divine save,
     * and returns true (caller must NOT continue with death processing).
     *
     * @param victim  The combatant who would die
     * @param roomId  The room where combat is occurring (for broadcast)
     * @return true if death was intercepted; false if no ward was active
     */
    public static boolean checkAndIntercept(Combatant victim, int roomId) {
        Integer targetId = victim.getCharacterId();
        if (targetId == null) return false;

        List<EffectInstance> active = EffectRegistry.getActiveForTarget(targetId);
        EffectInstance di = null;
        for (EffectInstance ei : active) {
            if (EFFECT_DEF_ID.equals(ei.getDefId())) {
                di = ei;
                break;
            }
        }
        if (di == null) return false;

        // Remove from registry
        EffectRegistry.removeInstance(di.getId());

        // Remove from caster tracking
        Integer casterId = di.getCasterId();
        if (casterId != null) {
            casterToInstance.remove(casterId, di);
        }

        // Restore victim to full HP
        int hpMax = victim.getHpMax();
        int hpCurrent = victim.getHpCurrent();
        if (hpCurrent < hpMax) {
            victim.heal(hpMax - hpCurrent);
        }

        // Announce to the room
        String victimName = victim.getName();
        ClientHandler.broadcastRoomMessage(roomId,
            "\u001B[33mA divine hand reaches from the heavens, pulling " + victimName +
            " back from death's door! The Angel's ward is consumed.\u001B[0m");

        // Personal message to the saved player
        ClientHandler.sendToCharacter(targetId,
            "\u001B[93mDivine Intervention! An Angel saves you from death and restores your health!\u001B[0m");

        logger.info("[divine intervention] Death intercepted for {} (caster={})", victimName, casterId);
        return true;
    }
}
