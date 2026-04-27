package com.example.tassmud.effect;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.AllyManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Bestial Wrath effect — ranger/companion battle frenzy.
 *
 * While active:
 *  - The ranger's tamed animal companion cannot miss and all its strikes are critical hits.
 *  - The ranger automatically fires a rapid shot every 3 seconds (no cooldown, no proficiency check).
 *  - All multishot proficiency checks in RangerSkillHandler treat the ranger's proficiency as 100.
 */
public class BestialWrathEffect implements EffectHandler {

    // ── Static state shared across all active instances ─────────────────────
    private static final Set<Integer> activeRangers = ConcurrentHashMap.newKeySet();
    private static final Set<Long>    frenziedCompanions = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer, ScheduledFuture<?>> autoShotTasks =
            new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bw-autoshot");
                t.setDaemon(true);
                return t;
            });

    // ── Public query methods (used by RangerSkillHandler & BasicAttackCommand) ─
    public static boolean isActive(int charId) {
        return activeRangers.contains(charId);
    }

    public static boolean isFrenziedCompanion(long mobInstanceId) {
        return frenziedCompanions.contains(mobInstanceId);
    }

    // ── EffectHandler ─────────────────────────────────────────────────────────
    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (targetId == null) return null;

        // Activate frenzy on ranger
        activeRangers.add(targetId);

        // Enrage the tamed animal companion (if one exists)
        var binding = AllyManager.getInstance().getCompanionBinding(targetId);
        if (binding != null) {
            long mobId = binding.getMobInstanceId();
            if (mobId > 0) frenziedCompanions.add(mobId);
        }

        // Schedule auto rapid-shots every 3 seconds
        final int rangerCharId = targetId;
        ScheduledFuture<?> task = SCHEDULER.scheduleAtFixedRate(
                () -> doAutoShot(rangerCharId), 3000L, 3000L, TimeUnit.MILLISECONDS);
        autoShotTasks.put(rangerCharId, task);

        // Notify ranger
        ClientHandler.sendToCharacter(targetId,
                "\u001B[1;31mBestial fury surges through you and your companion! You enter a savage frenzy!\u001B[0m");

        long now      = System.currentTimeMillis();
        long expiresAt = now + (long)(def.getDurationSeconds() * 1000);
        return new EffectInstance(UUID.randomUUID(), def.getId(), casterId, targetId,
                extraParams, now, expiresAt, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // nothing to do each tick
    }

    @Override
    public void expire(EffectInstance instance) {
        Integer charId = instance.getTargetId();
        if (charId == null) return;

        activeRangers.remove(charId);

        // De-enrage companion
        var binding = AllyManager.getInstance().getCompanionBinding(charId);
        if (binding != null) frenziedCompanions.remove(binding.getMobInstanceId());

        // Cancel auto-shot task
        ScheduledFuture<?> task = autoShotTasks.remove(charId);
        if (task != null) task.cancel(false);

        ClientHandler.sendToCharacter(charId,
                "The bestial frenzy fades from you and your companion.");
    }

    // ── Auto-shot logic (fires every 3 s while BW is active) ─────────────────
    private static void doAutoShot(int charId) {
        try {
            // Bail out if BW is no longer active (race with expire)
            if (!activeRangers.contains(charId)) return;

            CombatManager combatMgr = CombatManager.getInstance();
            Combat activeCombat = combatMgr.getCombatForCharacter(charId);
            if (activeCombat == null) return;

            Combatant userCombatant = activeCombat.findByCharacterId(charId);
            if (userCombatant == null) return;

            // Pick first living enemy
            Combatant targetCombatant = null;
            for (Combatant c : activeCombat.getCombatants()) {
                if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                    targetCombatant = c;
                    break;
                }
            }
            if (targetCombatant == null) return;

            // Verify ranged weapon is equipped
            Long mainHandId = DaoProvider.equipment().getCharacterEquipment(charId,
                    EquipmentSlot.MAIN_HAND.getId());
            if (mainHandId == null) return;
            ItemDAO itemDAO = new ItemDAO();
            ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
            ItemTemplate weaponTmpl = (weaponInst != null) ? itemDAO.getTemplateById(weaponInst.templateId) : null;
            WeaponFamily weaponFamily = (weaponTmpl != null) ? weaponTmpl.getWeaponFamily() : null;
            if (weaponFamily == null || !weaponFamily.isRanged()) return;

            // Compute attack stats
            var rec = DaoProvider.characters().getCharacterById(charId);
            if (rec == null) return;
            int dexMod     = (rec.baseStats.dex() - 10) / 2;
            int charLevel  = (rec.currentClassId != null)
                    ? DaoProvider.classes().getCharacterClassLevel(charId, rec.currentClassId) : 1;
            int attackBonus = dexMod + charLevel / 4;
            int targetArmor = (targetCombatant.getAsCharacter() != null)
                    ? targetCombatant.getAsCharacter().getArmor() : 10;

            // Roll
            String shooterName = rec.name;
            String targetName  = targetCombatant.getName();
            int roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 21);
            boolean hit = (roll != 1) && (roll == 20 || (roll + attackBonus) >= targetArmor);

            if (hit) {
                int baseDie = weaponInst.getEffectiveBaseDie(weaponTmpl);
                int multiplier = weaponInst.getEffectiveMultiplier(weaponTmpl);
                if (baseDie <= 0) baseDie = 4;
                if (multiplier <= 0) multiplier = 1;
                int damage = 0;
                for (int i = 0; i < multiplier; i++) {
                    damage += java.util.concurrent.ThreadLocalRandom.current().nextInt(1, baseDie + 1);
                }
                damage = Math.max(1, damage + dexMod);

                targetCombatant.damage(damage);
                activeCombat.addAttackAggro(charId, damage);

                ClientHandler.sendToCharacter(charId,
                        "\u001B[33mFrenzy Shot!\u001B[0m You loose an arrow at " + targetName
                        + " for \u001B[1;31m" + damage + "\u001B[0m damage!");

                if (targetCombatant.isPlayer()) {
                    Integer targetCharId = targetCombatant.getCharacterId();
                    ClientHandler targetSession = ClientHandler.charIdToSession.get(targetCharId);
                    if (targetSession != null)
                        targetSession.out.println(shooterName + "'s frenzy shot hits you for " + damage + " damage!");
                }
                ClientHandler.roomAnnounceFromActor(rec.currentRoom,
                        shooterName + " fires a frenzy shot at " + targetName + "!", charId);

                if (!targetCombatant.isAlive()) {
                    ClientHandler.sendToCharacter(charId, targetName + " has been slain!");
                }
            } else {
                ClientHandler.sendToCharacter(charId,
                        "Your frenzy shot at " + targetName + " misses!");
                ClientHandler.roomAnnounceFromActor(rec.currentRoom,
                        shooterName + " fires a frenzy shot at " + targetName + " but misses!", charId);
            }
        } catch (Exception e) {
            // Swallow exceptions so the scheduler doesn't die
        }
    }
}
