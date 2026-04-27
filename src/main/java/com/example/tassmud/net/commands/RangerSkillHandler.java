package com.example.tassmud.net.commands;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CooldownType;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobType;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.AllyManager;
import com.example.tassmud.util.CooldownManager;
import com.example.tassmud.util.MobileMatchingService;
import com.example.tassmud.util.MobileRegistry;
import com.example.tassmud.util.ProficiencyCheck;
import com.example.tassmud.effect.BestialWrathEffect;

import java.io.PrintWriter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles Ranger-specific skills: tame and release.
 * Methods are package-private so {@link CombatCommandHandler} can call them directly.
 */
class RangerSkillHandler {

    private static final int TAME_SKILL_ID = 411;

    boolean handleTameCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        Integer charId = ctx.characterId;

        if (charId == null) return true;
        if (ctx.inCombat) {
            out.println("You cannot tame a creature while in combat!");
            return true;
        }

        String args = ctx.getArgs().trim();
        if (args.isEmpty()) {
            out.println("Tame what? (Usage: tame <target>)");
            return true;
        }

        CharacterSkill tameSkill = DaoProvider.skills().getCharacterSkill(charId, TAME_SKILL_ID);
        if (tameSkill == null) {
            out.println("You don't know how to tame creatures.");
            return true;
        }

        if (AllyManager.getInstance().hasCompanion(charId)) {
            out.println("You already have a companion. Use 'release' to free them first.");
            return true;
        }

        Mobile target = MobileMatchingService.findInRoom(ctx.currentRoomId, args);
        if (target == null || target.isDead()) {
            out.println("You don't see that here.");
            return true;
        }

        MobType mobType = target.getMobType();
        if (!mobType.isWildlife()) {
            out.println(target.getDisplayName() + " cannot be tamed.");
            return true;
        }

        int proficiency = tameSkill.getProficiency();
        if (mobType == MobType.DRAGON && proficiency < 100) {
            out.println("You need complete mastery of taming before you can tame a " + target.getMobTypeName() + ".");
            return true;
        }
        if (mobType == MobType.MAGICAL_BEAST && proficiency < 50) {
            out.println("You need greater skill in taming before you can tame a " + target.getMobTypeName() + ".");
            return true;
        }

        CharacterRecord rec = ctx.character;
        int rangerLevel = (rec != null && rec.currentClassId != null)
                ? DaoProvider.classes().getCharacterClassLevel(charId, rec.currentClassId) : 1;
        if (target.getLevel() > rangerLevel) {
            out.println(target.getDisplayName() + " is too powerful for you to tame at your level.");
            return true;
        }

        if (AllyManager.getInstance().isAlly(target.getInstanceId())) {
            out.println("That creature is already bound to someone.");
            return true;
        }

        // Refuse to tame a mob that is currently in combat
        Combat mobCombat = CombatManager.getInstance().getCombatInRoom(ctx.currentRoomId);
        if (mobCombat != null && mobCombat.containsMobile(target.getInstanceId())) {
            out.println("You cannot tame a creature that is in combat!");
            return true;
        }

        // Success — bind the tamed companion
        AllyBinding binding = AllyBinding.tamedCompanion(target.getInstanceId(), charId, target.getTemplateId());
        AllyManager.getInstance().bindAlly(binding);

        // Try to improve tame proficiency
        Skill skillDef = DaoProvider.skills().getSkillById(TAME_SKILL_ID);
        if (skillDef != null) {
            DaoProvider.skills().tryImproveSkill(charId, TAME_SKILL_ID, skillDef);
        }

        String mobName = target.getName();
        out.println("You extend your hand toward " + mobName + ", meeting its gaze steadily.");
        out.println("After a long moment, it relaxes and accepts you.");
        ClientHandler.broadcastRoomMessage(ctx.currentRoomId,
                ctx.playerName + " calms " + mobName + " with a practiced touch.", charId);
        out.println("What will you name your new companion?");

        // Capture final references for the lambda
        final AllyBinding finalBinding = binding;
        final Mobile finalTarget = target;
        final int finalCharId = charId;
        ctx.handler.pendingInputCallback = inputLine -> {
            String companionName = inputLine.trim();
            if (companionName.isEmpty()) companionName = mobName;
            if (companionName.length() > 30) companionName = companionName.substring(0, 30);
            finalBinding.setCompanionName(companionName);
            finalTarget.setOverrideName(companionName);
            out.println("Your companion accepts the name '" + companionName + "'.");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId,
                    ctx.playerName + "'s companion is now known as " + companionName + ".", finalCharId);
        };

        return true;
    }

    boolean handleReleaseCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        Integer charId = ctx.characterId;

        if (charId == null) return true;

        AllyBinding companion = AllyManager.getInstance().getCompanionBinding(charId);
        if (companion == null) {
            out.println("You have no companion to release.");
            return true;
        }

        long mobId = companion.getMobInstanceId();
        Mobile mob = MobileRegistry.getInstance().getById(mobId);
        String displayName = (mob != null) ? mob.getDisplayName() : "your companion";
        Integer roomId = (mob != null) ? mob.getCurrentRoom() : ctx.currentRoomId;

        AllyManager.getInstance().releaseAlly(charId, mobId);

        if (mob != null) {
            mob.setOverrideName(null);
            MobileRegistry.getInstance().unregister(mobId);
            DaoProvider.mobiles().deleteInstance(mobId);
            if (roomId != null) {
                ClientHandler.broadcastRoomMessage(roomId,
                        displayName + " turns and wanders off into the distance.", charId);
            }
        }

        out.println("You release " + displayName + ". They wander off into the wild.");
        return true;
    }

    boolean handleRapidShotCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;

        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        if (charId == null) {
            out.println("You must be logged in to use Rapid Shot.");
            return true;
        }

        final int RAPID_SHOT_SKILL_ID = 412;

        Skill rapidShotSkill = DaoProvider.skills().getSkillById(RAPID_SHOT_SKILL_ID);
        if (rapidShotSkill == null) {
            out.println("Rapid Shot skill not found in database.");
            return true;
        }

        CharacterSkill charRapidShot = DaoProvider.skills().getCharacterSkill(charId, RAPID_SHOT_SKILL_ID);
        if (charRapidShot == null) {
            out.println("You don't know how to use Rapid Shot.");
            return true;
        }

        // Proficiency-scaled cooldown: 20s at 1-9%, 10s at 100%
        int proficiency = charRapidShot.getProficiency();
        int cooldownSeconds = Math.max(10, 20 - (proficiency / 10));

        CooldownManager cooldownMgr = CooldownManager.getInstance();
        double remainingCooldown = cooldownMgr.getPlayerCooldownRemaining(name, CooldownType.SKILL, RAPID_SHOT_SKILL_ID);
        if (remainingCooldown > 0) {
            out.println("Rapid Shot is on cooldown for " + "%.1f".formatted(remainingCooldown) + " more seconds.");
            return true;
        }

        // Must be in combat
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use Rapid Shot.");
            return true;
        }

        // Check ranged weapon in main hand
        ItemDAO itemDAO = new ItemDAO();
        Long mainHandId = DaoProvider.equipment().getCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandId == null) {
            out.println("You need a ranged weapon equipped to use Rapid Shot.");
            return true;
        }
        ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
        ItemTemplate weaponTmpl = (weaponInst != null) ? itemDAO.getTemplateById(weaponInst.templateId) : null;
        WeaponFamily weaponFamily = (weaponTmpl != null) ? weaponTmpl.getWeaponFamily() : null;
        if (weaponFamily == null || !weaponFamily.isRanged()) {
            out.println("You need a ranged weapon equipped to use Rapid Shot.");
            return true;
        }

        // Find combatants
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }
        if (targetCombatant == null) {
            out.println("You have no target to fire at.");
            return true;
        }

        // Compute attack stats
        CharacterRecord rec = dao.getCharacterById(charId);
        int dexMod = (rec != null) ? (rec.baseStats.dex() - 10) / 2 : 0;
        int charLevel = (rec != null && rec.currentClassId != null)
            ? DaoProvider.classes().getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int attackBonus = dexMod + charLevel / 4;
        int targetArmor = (targetCombatant.getAsCharacter() != null)
            ? targetCombatant.getAsCharacter().getArmor() : 10;

        // Fire the primary Rapid Shot
        boolean firstHit = fireRangedShot(name, charId, "rapid shot", "Rapid Shot",
            targetCombatant, weaponInst, weaponTmpl, attackBonus, dexMod, targetArmor,
            activeCombat, ctx.currentRoomId, out);

        // Multishot passive (413): 25% + prof/2 chance for a second shot
        final int MULTISHOT_ID = 413;
        final int IMPROVED_MULTISHOT_ID = 414;
        final int GREATER_MULTISHOT_ID = 415;

        CharacterSkill charMultishot = DaoProvider.skills().getCharacterSkill(charId, MULTISHOT_ID);
        if (charMultishot != null && targetCombatant.isAlive()) {
            int multishotEffProf = BestialWrathEffect.isActive(charId) ? 100 : charMultishot.getProficiency();
            int multishotChance = 25 + multishotEffProf / 2;
            if (ThreadLocalRandom.current().nextInt(100) < multishotChance) {
                Skill multishotSkill = DaoProvider.skills().getSkillById(MULTISHOT_ID);
                boolean msHit = fireRangedShot(name, charId, "multishot", "Multishot",
                    targetCombatant, weaponInst, weaponTmpl, attackBonus, dexMod, targetArmor,
                    activeCombat, ctx.currentRoomId, out);
                if (multishotSkill != null) {
                    ProficiencyCheck.Result msProf =
                        ProficiencyCheck.checkProficiencyGrowth(charId, multishotSkill, charMultishot, msHit, dao);
                    if (msProf.hasImproved()) out.println(msProf.getImprovementMessage());
                }

                // Improved Multishot passive (414): proficiency/2 chance for a third shot
                CharacterSkill charImproved = DaoProvider.skills().getCharacterSkill(charId, IMPROVED_MULTISHOT_ID);
                if (charImproved != null && targetCombatant.isAlive()) {
                    int improvedChance = (BestialWrathEffect.isActive(charId) ? 100 : charImproved.getProficiency()) / 2;
                    if (improvedChance > 0 && ThreadLocalRandom.current().nextInt(100) < improvedChance) {
                        Skill improvedSkill = DaoProvider.skills().getSkillById(IMPROVED_MULTISHOT_ID);
                        boolean imHit = fireRangedShot(name, charId, "improved multishot", "Improved Multishot",
                            targetCombatant, weaponInst, weaponTmpl, attackBonus, dexMod, targetArmor,
                            activeCombat, ctx.currentRoomId, out);
                        if (improvedSkill != null) {
                            ProficiencyCheck.Result imProf =
                                ProficiencyCheck.checkProficiencyGrowth(charId, improvedSkill, charImproved, imHit, dao);
                            if (imProf.hasImproved()) out.println(imProf.getImprovementMessage());
                        }

                        // Greater Multishot passive (415): proficiency/3 chance for a fourth shot
                        CharacterSkill charGreater = DaoProvider.skills().getCharacterSkill(charId, GREATER_MULTISHOT_ID);
                        if (charGreater != null && targetCombatant.isAlive()) {
                            int greaterChance = (BestialWrathEffect.isActive(charId) ? 100 : charGreater.getProficiency()) / 3;
                            if (greaterChance > 0 && ThreadLocalRandom.current().nextInt(100) < greaterChance) {
                                Skill greaterSkill = DaoProvider.skills().getSkillById(GREATER_MULTISHOT_ID);
                                boolean gmHit = fireRangedShot(name, charId, "greater multishot", "Greater Multishot",
                                    targetCombatant, weaponInst, weaponTmpl, attackBonus, dexMod, targetArmor,
                                    activeCombat, ctx.currentRoomId, out);
                                if (greaterSkill != null) {
                                    ProficiencyCheck.Result gmProf =
                                        ProficiencyCheck.checkProficiencyGrowth(charId, greaterSkill, charGreater, gmHit, dao);
                                    if (gmProf.hasImproved()) out.println(gmProf.getImprovementMessage());
                                }
                            }
                        }
                    }
                }
            }
        }

        // Apply proficiency-scaled cooldown
        cooldownMgr.setPlayerCooldown(name, CooldownType.SKILL, RAPID_SHOT_SKILL_ID, cooldownSeconds);

        // Try to improve Rapid Shot proficiency
        ProficiencyCheck.Result profResult =
            ProficiencyCheck.checkProficiencyGrowth(charId, rapidShotSkill, charRapidShot, firstHit, dao);
        if (profResult.hasImproved()) {
            out.println(profResult.getImprovementMessage());
        }

        ctx.handler.sendDebug("Rapid Shot: attackBonus=" + attackBonus
            + " targetArmor=" + targetArmor + " cooldown=" + cooldownSeconds + "s");

        return true;
    }

    private boolean fireRangedShot(
            String shooterName, int charId, String shotLabel, String shotLabelCapped,
            Combatant targetCombatant,
            ItemInstance weaponInst, ItemTemplate weaponTmpl,
            int attackBonus, int dexMod, int targetArmor,
            Combat activeCombat, int currentRoomId, PrintWriter out) {

        String targetName = targetCombatant.getName();
        int roll = ThreadLocalRandom.current().nextInt(1, 21);
        boolean hit = (roll != 1) && (roll == 20 || (roll + attackBonus) >= targetArmor);

        if (hit) {
            int baseDie = weaponInst.getEffectiveBaseDie(weaponTmpl);
            int multiplier = weaponInst.getEffectiveMultiplier(weaponTmpl);
            if (baseDie <= 0) baseDie = 4;
            if (multiplier <= 0) multiplier = 1;
            int damage = 0;
            for (int i = 0; i < multiplier; i++) {
                damage += ThreadLocalRandom.current().nextInt(1, baseDie + 1);
            }
            damage = Math.max(1, damage + dexMod);

            targetCombatant.damage(damage);
            activeCombat.addAttackAggro(charId, damage);

            out.println("\u001B[33m" + shotLabelCapped + "!\u001B[0m You loose an arrow at " + targetName
                + " for \u001B[1;31m" + damage + "\u001B[0m damage!");

            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(shooterName + "'s " + shotLabel + " hits you for " + damage + " damage!");
                }
            }
            ClientHandler.roomAnnounceFromActor(currentRoomId,
                shooterName + " fires a " + shotLabel + " at " + targetName + "!", charId);

            if (!targetCombatant.isAlive()) {
                out.println(targetName + " has been slain!");
            }
        } else {
            out.println("Your " + shotLabel + " at " + targetName + " misses!");

            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(shooterName + " fires a " + shotLabel + " at you but misses.");
                }
            }
            ClientHandler.roomAnnounceFromActor(currentRoomId,
                shooterName + " fires a " + shotLabel + " at " + targetName + " but misses!", charId);
        }

        return hit;
    }
}

