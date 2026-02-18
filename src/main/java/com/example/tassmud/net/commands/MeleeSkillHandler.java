package com.example.tassmud.net.commands;

import com.example.tassmud.persistence.DaoProvider;
import java.io.PrintWriter;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Skill;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;

/**
 * Delegate handler for melee combat skill commands, extracted from CombatCommandHandler.
 * Methods are package-private so CombatCommandHandler can call them directly.
 */
class MeleeSkillHandler {

    private static final Logger logger = LoggerFactory.getLogger(MeleeSkillHandler.class);

    boolean handleTauntCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // TAUNT - combat skill that increases aggro to max + 1000
        if (rec == null) {
            out.println("You must be logged in to taunt.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the taunt skill (id=21)
        Skill tauntSkill = DaoProvider.skills().getSkillById(21);
        if (tauntSkill == null) {
            out.println("Taunt skill not found in database.");
            return true;
        }

        // Check if character knows the taunt skill
        CharacterSkill charTaunt = DaoProvider.skills().getCharacterSkill(charId, 21);
        if (charTaunt == null) {
            out.println("You don't know how to taunt.");
            return true;
        }

        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult tauntCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, tauntSkill);
        if (tauntCheck.isFailure()) {
            out.println(tauntCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your taunt falters.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to taunt but the words come out garbled!");
            return true;
        }

        // Get the active combat and target
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use taunt.");
            return true;
        }

        // Get the user's combatant and verify there are enemies
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Find any opponent (to verify there are enemies to taunt)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no enemies to taunt.");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO tauntClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? tauntClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? tauntClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use a level based on their HP
            targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charTaunt.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        boolean tauntSucceeded = roll <= successChance;

        if (tauntSucceeded) {
            // Success! Set aggro to max aggro + 1000
            long currentMaxAggro = activeCombat.getMaxAggro();
            long myCurrentAggro = activeCombat.getAggro(charId);
            long newAggro;
            
            if (myCurrentAggro >= currentMaxAggro) {
                // Already have highest aggro, just add 1000
                newAggro = myCurrentAggro + 1000;
                out.println("\u001B[33mYou let out a battle cry! Your aggro increases by 1000.\u001B[0m");
            } else {
                // Set to max + 1000
                newAggro = currentMaxAggro + 1000;
                out.println("\u001B[33mYou let out a battle cry! All enemies turn their attention to you!\u001B[0m");
            }
            
            activeCombat.setAggro(charId, newAggro);

            // Notify group members if any
            com.example.tassmud.util.GroupManager groupMgr = com.example.tassmud.util.GroupManager.getInstance();
            java.util.Optional<com.example.tassmud.model.Group> groupOpt = groupMgr.getGroupForCharacter(charId);
            if (groupOpt.isPresent()) {
                com.example.tassmud.model.Group group = groupOpt.get();
                for (Integer memberId : group.getMemberIds()) {
                    if (!memberId.equals(charId)) {
                        ClientHandler memberHandler = ClientHandler.charIdToSession.get(memberId);
                        if (memberHandler != null) {
                            memberHandler.out.println("\u001B[33m" + name + " taunts the enemies!\u001B[0m");
                        }
                    }
                }
            }
            
            // Room announcement
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId, 
                name + " lets out a battle cry, drawing the attention of all enemies!", charId);
        } else {
            // Miss
            out.println("Your taunt fails to get anyone's attention.");
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId, 
                name + " tries to taunt but no one pays attention.", charId);
        }

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result skillResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, tauntSkill, charTaunt, dao, tauntSucceeded);

        // Debug channel output for proficiency check
        ctx.handler.sendDebug("Taunt proficiency check:");
        ctx.handler.sendDebug("  Skill progression: " + tauntSkill.getProgression());
        ctx.handler.sendDebug("  Current proficiency: " + charTaunt.getProficiency() + "%");
        ctx.handler.sendDebug("  Gain chance at this level: " + tauntSkill.getProgression().getGainChance(charTaunt.getProficiency()) + "%");
        ctx.handler.sendDebug("  Skill succeeded: " + tauntSucceeded);
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency() + 
                        " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleFeignCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // FEIGN - combat skill that cuts aggro in half
        if (rec == null) {
            out.println("You must be logged in to feign.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the feign skill (id=22)
        Skill feignSkill = DaoProvider.skills().getSkillById(22);
        if (feignSkill == null) {
            out.println("Feign skill not found in database.");
            return true;
        }

        // Check if character knows the feign skill
        CharacterSkill charFeign = DaoProvider.skills().getCharacterSkill(charId, 22);
        if (charFeign == null) {
            out.println("You don't know how to feign.");
            return true;
        }

        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult feignCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, feignSkill);
        if (feignCheck.isFailure()) {
            out.println(feignCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your feign is unconvincing.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to feign weakness but the act is unconvincing!");
            return true;
        }

        // Get the active combat and target
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use feign.");
            return true;
        }

        // Get the user's combatant and verify there are enemies
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Find any opponent (to verify there are enemies)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no enemies to feign weakness against.");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO feignClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? feignClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? feignClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use a level based on their HP
            targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charFeign.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        boolean feignSucceeded = roll <= successChance;

        if (feignSucceeded) {
            // Success! Cut aggro in half
            long currentAggro = activeCombat.getAggro(charId);
            long newAggro = currentAggro / 2;
            
            activeCombat.setAggro(charId, newAggro);
            
            out.println("\u001B[36mYou feign weakness! Enemies lose interest in you.\u001B[0m");
            
            // Room announcement
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId, 
                name + " staggers and appears weakened, drawing less attention.", charId);
        } else {
            // Miss
            out.println("Your attempt to feign weakness is unconvincing.");
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId, 
                name + " pretends to be weakened but no one is fooled.", charId);
        }

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result skillResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, feignSkill, charFeign, dao, feignSucceeded);

        // Debug channel output for proficiency check
        ctx.handler.sendDebug("Feign proficiency check:");
        ctx.handler.sendDebug("  Skill progression: " + feignSkill.getProgression());
        ctx.handler.sendDebug("  Current proficiency: " + charFeign.getProficiency() + "%");
        ctx.handler.sendDebug("  Gain chance at this level: " + feignSkill.getProgression().getGainChance(charFeign.getProficiency()) + "%");
        ctx.handler.sendDebug("  Skill succeeded: " + feignSucceeded);
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency() + 
                        " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleInfuseCommand(CommandContext ctx) {
        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = dao.findByName(name);

        // INFUSE - Infuse your staff with arcane energy
        if (rec == null) {
            out.println("You must be logged in to use infuse.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        
        // Look up the lesser arcane infusion skill (id=100)
        final int ARCANE_INFUSION_SKILL_ID = 100;
        Skill infuseSkill = DaoProvider.skills().getSkillById(ARCANE_INFUSION_SKILL_ID);
        if (infuseSkill == null) {
            out.println("Arcane Infusion skill not found in database.");
            return true;
        }
        
        // Check if character knows the arcane infusion skill
        CharacterSkill charInfuse = DaoProvider.skills().getCharacterSkill(charId, ARCANE_INFUSION_SKILL_ID);
        if (charInfuse == null) {
            out.println("You don't know how to infuse weapons with arcane energy.");
            return true;
        }
        
        // Check cooldown using unified check (no combat requirement for infusion)
        com.example.tassmud.util.AbilityCheck.CheckResult infuseCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, infuseSkill);
        if (infuseCheck.isFailure()) {
            out.println(infuseCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your concentration! The arcane energy dissipates.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to channel arcane energy but dark curse magic interferes!");
            return true;
        }
        
        // Check if player has a staff equipped
        ItemDAO itemDao = DaoProvider.items();
        Long mainHandInstanceId = DaoProvider.equipment().getCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            out.println("You need to have a weapon equipped to infuse it.");
            return true;
        }
        
        ItemInstance weaponInstance = itemDao.getInstance(mainHandInstanceId);
        if (weaponInstance == null) {
            out.println("You need to have a weapon equipped to infuse it.");
            return true;
        }
        
        ItemTemplate weaponTemplate = itemDao.getTemplateById(weaponInstance.templateId);
        if (weaponTemplate == null) {
            out.println("You need to have a weapon equipped to infuse it.");
            return true;
        }
        
        // Check if the weapon is a staff
        com.example.tassmud.model.WeaponFamily weaponFamily = weaponTemplate.getWeaponFamily();
        if (weaponFamily != com.example.tassmud.model.WeaponFamily.STAVES) {
            out.println("You can only infuse staves with arcane energy.");
            return true;
        }
        
        // Apply the skill's effects to self
        int proficiency = charInfuse.getProficiency();
        com.example.tassmud.util.SkillExecution.EffectResult effectResult = 
            com.example.tassmud.util.SkillExecution.applySkillEffectsToSelf(infuseSkill, charId, proficiency);
        
        if (effectResult.hasAppliedEffects()) {
            String weaponName = weaponTemplate.name != null ? weaponTemplate.name : "staff";
            out.println("You channel arcane energy into your " + weaponName + "! It crackles with magical power.");
            out.println("Your attacks now fire arcane bolts, targeting reflex and using Intelligence for damage.");
            // Broadcast to room (excluding self)
            for (ClientHandler ch : ClientHandler.charIdToSession.values()) {
                if (ch != ctx.handler && ch.currentRoomId != null && ch.currentRoomId.equals(ctx.currentRoomId)) {
                    ch.out.println(name + "'s " + weaponName + " begins to glow with arcane energy!");
                }
            }
        } else {
            out.println("You attempt to channel arcane energy, but nothing happens.");
        }
        
        // Apply cooldown and check proficiency growth
        boolean infuseSucceeded = effectResult.hasAppliedEffects();
        com.example.tassmud.util.SkillExecution.Result infuseResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, infuseSkill, charInfuse, dao, infuseSucceeded);
        
        if (infuseResult.didProficiencyImprove()) {
            out.println(infuseResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleHeroicStrikeCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // HEROIC STRIKE - devastating attack: guaranteed crit + max damage
        // Cooldown: 20s base, -5s per miss, guaranteed hit after 4 misses
        if (rec == null) {
            out.println("You must be logged in to use Heroic Strike.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        
        // Look up the heroic strike skill (id=18)
        final int HEROIC_STRIKE_SKILL_ID = 18;
        Skill heroicSkill = DaoProvider.skills().getSkillById(HEROIC_STRIKE_SKILL_ID);
        if (heroicSkill == null) {
            out.println("Heroic Strike skill not found in database.");
            return true;
        }
        
        // Check if character knows the heroic strike skill
        CharacterSkill charHeroic = DaoProvider.skills().getCharacterSkill(charId, HEROIC_STRIKE_SKILL_ID);
        if (charHeroic == null) {
            out.println("You don't know how to use Heroic Strike.");
            return true;
        }
        
        // Get the miss bonus (tracks accumulated misses - each miss adds 5, max 20)
        String missBonusStr = dao.getCharacterFlag(charId, "heroic_strike_miss_bonus");
        int missBonus = 0;
        try {
            if (missBonusStr != null) {
                missBonus = Integer.parseInt(missBonusStr);
            }
        } catch (NumberFormatException e) {
            missBonus = 0;
        }
        
        // Calculate effective cooldown: base 20 - missBonus
        // At 0 misses: 20s, at 1 miss: 15s, at 2: 10s, at 3: 5s, at 4+: 0s (guaranteed hit)
        int baseCooldown = (int)heroicSkill.getCooldown();
        int effectiveCooldown = Math.max(0, baseCooldown - missBonus);
        
        // Check cooldown manually (can't use unified check due to dynamic cooldown)
        com.example.tassmud.util.CooldownManager cooldownMgr = com.example.tassmud.util.CooldownManager.getInstance();
        double remainingCooldown = cooldownMgr.getPlayerCooldownRemaining(name, 
            com.example.tassmud.model.CooldownType.SKILL, HEROIC_STRIKE_SKILL_ID);
        
        if (remainingCooldown > 0) {
            out.println("Heroic Strike is on cooldown for " + "%.1f".formatted(remainingCooldown) + " more seconds.");
            return true;
        }
        
        // Check if in combat (COMBAT trait)
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use Heroic Strike.");
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your heroic strike falters.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts a heroic strike but dark curse energy interferes!");
            // Apply cooldown even on curse failure
            cooldownMgr.setPlayerCooldown(name, com.example.tassmud.model.CooldownType.SKILL, 
                HEROIC_STRIKE_SKILL_ID, effectiveCooldown);
            return true;
        }
        
        // Get the user's combatant and find the opponent
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Find opponent (first combatant on different alliance)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no opponent to strike.");
            return true;
        }

        String targetName = targetCombatant.getName();
        
        // Determine if this is a guaranteed hit (4+ misses accumulated = 20 bonus)
        boolean guaranteedHit = missBonus >= 20;
        boolean heroicSucceeded;
        
        if (guaranteedHit) {
            // After 4 misses, the attack is guaranteed
            heroicSucceeded = true;
            out.println("\u001B[33mYour determination reaches its peak!\u001B[0m");
        } else {
            // Perform opposed check with proficiency (like kick)
            CharacterClassDAO heroicClassDao = DaoProvider.classes();
            int userLevel = rec.currentClassId != null ? heroicClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
            int targetLevel;
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                CharacterRecord targetRec = dao.getCharacterById(targetCharId);
                targetLevel = targetRec != null && targetRec.currentClassId != null 
                    ? heroicClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
            } else {
                // For mobiles, use a level based on their HP
                targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
            }

            int roll = ThreadLocalRandom.current().nextInt(1, 101);
            int proficiency = charHeroic.getProficiency();
            int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);
            heroicSucceeded = roll <= successChance;
            
            ctx.handler.sendDebug("Heroic Strike check: roll=" + roll + " vs " + successChance + "% (miss bonus=" + missBonus + ")");
        }

        if (heroicSucceeded) {
            // SUCCESS! Calculate max damage as a guaranteed critical
            int maxDamage = calculateMaxWeaponDamage(charId, dao, userCombatant);
            int critDamage = maxDamage * 2; // Critical hit doubles damage
            
            // Apply damage to target
            targetCombatant.damage(critDamage);
            
            // Add aggro (10 + damage)
            activeCombat.addAttackAggro(charId, critDamage);
            
            // Messages
            out.println("\u001B[1;33m*** HEROIC STRIKE! ***\u001B[0m Your devastating blow deals \u001B[1;31m" 
                + critDamage + "\u001B[0m critical damage to " + targetName + "!");
            
            // Notify target if player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println("\u001B[1;31m" + name + "'s HEROIC STRIKE deals " + critDamage + " critical damage to you!\u001B[0m");
                }
            }
            
            // Room announcement
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId, 
                name + " lands a devastating HEROIC STRIKE on " + targetName + "!", charId);
            
            // Reset miss bonus on success
            dao.setCharacterFlag(charId, "heroic_strike_miss_bonus", "0");
            
            // Apply full cooldown (20s)
            cooldownMgr.setPlayerCooldown(name, com.example.tassmud.model.CooldownType.SKILL, 
                HEROIC_STRIKE_SKILL_ID, baseCooldown);
            
            // Check if target died
            if (!targetCombatant.isAlive()) {
                out.println(targetName + " has been slain by your heroic strike!");
            }
        } else {
            // MISS - increase miss bonus by 5 (reduces next cooldown)
            int newMissBonus = Math.min(20, missBonus + 5);
            dao.setCharacterFlag(charId, "heroic_strike_miss_bonus", String.valueOf(newMissBonus));
            
            int newCooldown = Math.max(0, baseCooldown - newMissBonus);
            
            out.println("Your heroic strike misses " + targetName + ".");
            out.println("\u001B[33mYour determination grows... (next cooldown: " + newCooldown + "s)\u001B[0m");
            
            // Notify target if player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " attempts a heroic strike but misses you.");
                }
            }
            
            // Apply reduced cooldown
            cooldownMgr.setPlayerCooldown(name, com.example.tassmud.model.CooldownType.SKILL, 
                HEROIC_STRIKE_SKILL_ID, effectiveCooldown);
        }

        // Record skill use for proficiency growth (but don't apply cooldown again)
        com.example.tassmud.util.ProficiencyCheck.Result profResult = 
            com.example.tassmud.util.ProficiencyCheck.checkProficiencyGrowth(
                charId, heroicSkill, charHeroic, heroicSucceeded, dao);
        if (profResult.hasImproved()) {
            out.println(profResult.getImprovementMessage());
        }
        
        return true;
    }
    
    /**
     * Calculate max weapon damage for heroic strike.
     * Returns the maximum possible roll (all dice at max value).
     */
    int calculateMaxWeaponDamage(Integer charId, CharacterDAO dao, Combatant attacker) {
        // Get equipped weapon
        com.example.tassmud.persistence.ItemDAO itemDAO = new com.example.tassmud.persistence.ItemDAO();
        Long mainHandId = DaoProvider.equipment().getCharacterEquipment(charId, com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
        
        if (mainHandId != null) {
            com.example.tassmud.model.ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
            if (weaponInst != null) {
                com.example.tassmud.model.ItemTemplate weaponTmpl = itemDAO.getTemplateById(weaponInst.templateId);
                int baseDie = weaponInst.getEffectiveBaseDie(weaponTmpl);
                int multiplier = weaponInst.getEffectiveMultiplier(weaponTmpl);
                if (baseDie > 0) {
                    multiplier = multiplier > 0 ? multiplier : 1;
                    // Max damage = multiplier * baseDie (all dice at max)
                    int maxRoll = multiplier * baseDie;
                    
                    // Add stat bonus (STR mod)
                    CharacterRecord rec = dao.getCharacterById(charId);
                    if (rec != null) {
                        int strMod = (rec.baseStats.str() - 10) / 2;
                        // Two-handed gets 1.5x STR bonus
                        if (isTwoHandedWeapon(charId, dao, itemDAO, mainHandId)) {
                            strMod = (int) Math.floor(strMod * 1.5);
                        }
                        maxRoll += strMod;
                    }
                    return Math.max(1, maxRoll);
                }
            }
        }
        
        // Unarmed: 1d4 max = 4
        CharacterRecord rec = dao.getCharacterById(charId);
        int strMod = rec != null ? (rec.baseStats.str() - 10) / 2 : 0;
        return Math.max(1, 4 + strMod);
    }
    
    /**
     * Check if equipped weapon is two-handed.
     */
    boolean isTwoHandedWeapon(Integer charId, CharacterDAO dao, 
            com.example.tassmud.persistence.ItemDAO itemDAO, Long mainHandId) {
        if (mainHandId == null) return false;
        
        // Check if off-hand is empty or has the same item (two-handed weapon)
        Long offHandId = DaoProvider.equipment().getCharacterEquipment(charId, com.example.tassmud.model.EquipmentSlot.OFF_HAND.getId());
        if (offHandId == null) {
            // Check if weapon requires two hands
            com.example.tassmud.model.ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
            if (weaponInst != null) {
                com.example.tassmud.model.ItemTemplate weaponTmpl = itemDAO.getTemplateById(weaponInst.templateId);
                if (weaponTmpl != null) {
                    // Check if weapon family is typically two-handed
                    com.example.tassmud.model.WeaponFamily family = weaponTmpl.getWeaponFamily();
                    if (family != null) {
                        return family == com.example.tassmud.model.WeaponFamily.STAVES ||
                               family == com.example.tassmud.model.WeaponFamily.GLAIVES ||
                               family == com.example.tassmud.model.WeaponFamily.BOWS ||
                               family == com.example.tassmud.model.WeaponFamily.CROSSBOWS;
                    }
                }
            }
        }
        return mainHandId.equals(offHandId);
    }

    boolean handleBashCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // BASH - combat skill that stuns and slows opponent (requires shield)
        if (rec == null) {
            out.println("You must be logged in to use bash.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the bash skill (id=11)
        Skill bashSkill = DaoProvider.skills().getSkillById(11);
        if (bashSkill == null) {
            out.println("Bash skill not found in database.");
            return true;
        }

        // Check if character knows the bash skill
        CharacterSkill charBash = DaoProvider.skills().getCharacterSkill(charId, 11);
        if (charBash == null) {
            out.println("You don't know how to bash.");
            return true;
        }

        // Check cooldown, combat traits, and SHIELD requirement using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult bashCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, bashSkill);
        if (bashCheck.isFailure()) {
            out.println(bashCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your bash goes wide.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to bash but staggers, cursed energy disrupting their balance!");
            return true;
        }

        // Get the active combat and target
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use bash.");
            return true;
        }

        // Get the user's combatant and find the opponent
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Bash sacrifices the user's next attack
        userCombatant.addStatusFlag(Combatant.StatusFlag.INTERRUPTED);

        // Find opponent (first combatant on different alliance)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no opponent to bash.");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO bashClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? bashClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? bashClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use a level based on their HP
            targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charBash.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        String targetName = targetCombatant.getName();
        boolean bashSucceeded = roll <= successChance;

        if (bashSucceeded) {
            // Success! Apply STUNNED and SLOWED for 1d6 rounds
            int stunDuration = ThreadLocalRandom.current().nextInt(1, 7);
            targetCombatant.addStatusFlag(Combatant.StatusFlag.STUNNED);
            targetCombatant.addStatusFlag(Combatant.StatusFlag.SLOWED);

            out.println("You slam your shield into " + targetName + ", stunning them for " + stunDuration + " rounds!");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " bashes you with their shield! You are stunned and slowed!");
                }
            }

            // Debug output
            ctx.handler.sendDebug("Bash success! Applied STUNNED and SLOWED for " + stunDuration + " rounds.");
            ctx.handler.sendDebug("  (Note: Current implementation removes status on first trigger. Multi-round tracking TODO.)");
        } else {
            // Miss
            out.println("Your shield bash misses " + targetName + ".");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " tries to bash you with their shield but misses.");
                }
            }
        }

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result bashResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, bashSkill, charBash, dao, bashSucceeded);

        if (bashResult.didProficiencyImprove()) {
            out.println(bashResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleKickCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // KICK - combat skill that interrupts opponent's next attack
        if (rec == null) {
            out.println("You must be logged in to use kick.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the kick skill (id=10)
        Skill kickSkill = DaoProvider.skills().getSkillById(10);
        if (kickSkill == null) {
            out.println("Kick skill not found in database.");
            return true;
        }

        // Check if character knows the kick skill
        CharacterSkill charKick = DaoProvider.skills().getCharacterSkill(charId, 10);
        if (charKick == null) {
            out.println("You don't know how to kick.");
            return true;
        }

        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult kickCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, kickSkill);
        if (kickCheck.isFailure()) {
            out.println(kickCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your kick misses wildly.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to kick but stumbles, cursed energy disrupting their movement!");
            return true;
        }

        // Get the active combat and target
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use kick.");
            return true;
        }

        // Get the user's combatant and find the opponent
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Find opponent (first combatant on different alliance)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no opponent to kick.");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO kickClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? kickClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? kickClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use their actual level
            Mobile targetMob = targetCombatant.getMobile();
            targetLevel = targetMob != null ? targetMob.getLevel() : 1;
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charKick.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        String targetName = targetCombatant.getName();
        boolean kickSucceeded = roll <= successChance;

        if (kickSucceeded) {
            // Success! Interrupt the opponent's next attack
            targetCombatant.addStatusFlag(Combatant.StatusFlag.INTERRUPTED);
            out.println("Your kick connects! " + targetName + "'s next attack is interrupted.");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " kicks you, interrupting your next attack!");
                }
            }
        } else {
            // Miss
            out.println("Your kick misses " + targetName + ".");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " tries to kick you but misses.");
                }
            }
        }

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result skillResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, kickSkill, charKick, dao, kickSucceeded);

        // Debug channel output for proficiency check (only shown if debug enabled)
        ctx.handler.sendDebug("Kick proficiency check:");
        ctx.handler.sendDebug("  Skill progression: " + kickSkill.getProgression());
        ctx.handler.sendDebug("  Current proficiency: " + charKick.getProficiency() + "%");
        ctx.handler.sendDebug("  Gain chance at this level: " + kickSkill.getProgression().getGainChance(charKick.getProficiency()) + "%");
        ctx.handler.sendDebug("  Skill succeeded: " + kickSucceeded);
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency() + 
                        " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleDisarmCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // DISARM - combat skill that causes enemy to drop their weapon
        if (rec == null) {
            out.println("You must be logged in to use disarm.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the disarm skill (id=12)
        Skill disarmSkill = DaoProvider.skills().getSkillById(12);
        if (disarmSkill == null) {
            out.println("Disarm skill not found in database.");
            return true;
        }

        // Check if character knows the disarm skill
        CharacterSkill charDisarm = DaoProvider.skills().getCharacterSkill(charId, 12);
        if (charDisarm == null) {
            out.println("You don't know how to disarm.");
            return true;
        }

        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult disarmCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, disarmSkill);
        if (disarmCheck.isFailure()) {
            out.println(disarmCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your disarm attempt fails miserably.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to disarm but fumbles, cursed energy disrupting their movement!");
            return true;
        }

        // Get the active combat and target
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use disarm.");
            return true;
        }

        // Get the user's combatant and find the opponent
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Find opponent (first combatant on different alliance)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no opponent to disarm.");
            return true;
        }

        String targetName = targetCombatant.getName();
        
        // Check if target is wielding a weapon
        ItemDAO itemDao = DaoProvider.items();
        Long targetWeaponInstanceId = null;
        ItemInstance targetWeaponInstance = null;
        ItemTemplate targetWeaponTemplate = null;
        
        if (targetCombatant.isPlayer()) {
            // Player target - check their main hand equipment
            Integer targetCharId = targetCombatant.getCharacterId();
            targetWeaponInstanceId = DaoProvider.equipment().getCharacterEquipment(targetCharId, EquipmentSlot.MAIN_HAND.getId());
            if (targetWeaponInstanceId != null) {
                targetWeaponInstance = itemDao.getInstance(targetWeaponInstanceId);
                if (targetWeaponInstance != null) {
                    targetWeaponTemplate = itemDao.getTemplateById(targetWeaponInstance.templateId);
                }
            }
        } else {
            // Mobile target - check their equipped items from mobile_instance_item
            Mobile targetMobile = targetCombatant.getMobile();
            if (targetMobile != null) {
                MobileDAO mobileDao = DaoProvider.mobiles();
                java.util.List<MobileDAO.MobileItemMarker> markers = mobileDao.getMobileItemMarkers(targetMobile.getInstanceId());
                for (MobileDAO.MobileItemMarker marker : markers) {
                    if ("equip".equalsIgnoreCase(marker.kind) || "main_hand".equalsIgnoreCase(marker.kind)) {
                        ItemInstance inst = itemDao.getInstance(marker.itemInstanceId);
                        if (inst != null) {
                            ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                            if (tmpl != null && tmpl.isWeapon()) {
                                targetWeaponInstanceId = marker.itemInstanceId;
                                targetWeaponInstance = inst;
                                targetWeaponTemplate = tmpl;
                                break;
                            }
                        }
                    }
                }
            }
        }

        // If target isn't wielding a weapon, report that and exit
        if (targetWeaponInstance == null || targetWeaponTemplate == null || !targetWeaponTemplate.isWeapon()) {
            out.println(targetName + " isn't wielding a weapon.");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO disarmClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? disarmClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? disarmClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use their level
            Mobile targetMobile = targetCombatant.getMobile();
            targetLevel = targetMobile != null ? targetMobile.getLevel() : 1;
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charDisarm.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        boolean disarmSucceeded = roll <= successChance;
        String weaponName = targetWeaponTemplate.name != null ? targetWeaponTemplate.name : "a weapon";

        if (disarmSucceeded) {
            // Success! Cause the opponent to drop their weapon to the floor
            Integer roomId = ctx.currentRoomId;
            
            if (targetCombatant.isPlayer()) {
                // Remove from player's equipment and move to room
                Integer targetCharId = targetCombatant.getCharacterId();
                DaoProvider.equipment().setCharacterEquipment(targetCharId, EquipmentSlot.MAIN_HAND.getId(), null);
                // Also clear off-hand if it's the same item (two-handed weapon)
                Long offHandId = DaoProvider.equipment().getCharacterEquipment(targetCharId, EquipmentSlot.OFF_HAND.getId());
                if (offHandId != null && offHandId.equals(targetWeaponInstanceId)) {
                    DaoProvider.equipment().setCharacterEquipment(targetCharId, EquipmentSlot.OFF_HAND.getId(), null);
                }
            }
            // Move the weapon to the room floor
            itemDao.moveInstanceToRoom(targetWeaponInstanceId, roomId);
            
            // Add DISARMED status flag to target
            targetCombatant.addStatusFlag(Combatant.StatusFlag.DISARMED);
            
            out.println("Your disarm succeeds! " + targetName + " drops " + weaponName + " to the ground!");
            ClientHandler.broadcastRoomMessage(roomId, 
                name + " disarms " + targetName + ", sending " + weaponName + " clattering to the floor!");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " disarms you! " + weaponName + " falls to the ground!");
                }
            }
        } else {
            // Miss
            out.println("Your disarm attempt fails against " + targetName + ".");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " tries to disarm you but fails.");
                }
            }
        }

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result skillResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, disarmSkill, charDisarm, dao, disarmSucceeded);

        // Debug channel output for proficiency check (only shown if debug enabled)
        ctx.handler.sendDebug("Disarm proficiency check:");
        ctx.handler.sendDebug("  Skill progression: " + disarmSkill.getProgression());
        ctx.handler.sendDebug("  Current proficiency: " + charDisarm.getProficiency() + "%");
        ctx.handler.sendDebug("  Gain chance at this level: " + disarmSkill.getProgression().getGainChance(charDisarm.getProficiency()) + "%");
        ctx.handler.sendDebug("  Skill succeeded: " + disarmSucceeded);
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency() + 
                        " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleTripCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // TRIP - combat skill that knocks opponent prone
        if (rec == null) {
            out.println("You must be logged in to use trip.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the trip skill (id=20)
        Skill tripSkill = DaoProvider.skills().getSkillById(20);
        if (tripSkill == null) {
            out.println("Trip skill not found in database.");
            return true;
        }

        // Check if character knows the trip skill
        CharacterSkill charTrip = DaoProvider.skills().getCharacterSkill(charId, 20);
        if (charTrip == null) {
            out.println("You don't know how to trip.");
            return true;
        }

        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult tripCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, tripSkill);
        if (tripCheck.isFailure()) {
            out.println(tripCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your trip attempt misses wildly.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " attempts to trip but stumbles, cursed energy disrupting their movement!");
            return true;
        }

        // Get the active combat and target
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use trip.");
            return true;
        }

        // Get the user's combatant and find the opponent
        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Find opponent (first combatant on different alliance)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no opponent to trip.");
            return true;
        }

        String targetName = targetCombatant.getName();
        
        // Check if target is flying - flying targets are immune to trip
        Integer targetCharId = targetCombatant.getCharacterId();
        if (targetCharId != null && com.example.tassmud.effect.FlyingEffect.isFlying(targetCharId)) {
            out.println("You cannot trip " + targetName + " - they are flying!");
            return true;
        }
        
        // Check if target is already prone
        if (targetCombatant.isProne()) {
            out.println(targetName + " is already prone!");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO tripClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? tripClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? tripClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use their actual level
            Mobile targetMob = targetCombatant.getMobile();
            targetLevel = targetMob != null ? targetMob.getLevel() : 1;
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charTrip.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        boolean tripSucceeded = roll <= successChance;

        if (tripSucceeded) {
            // Success! Knock the opponent prone
            targetCombatant.setProne();
            out.println("Your trip connects! " + targetName + " crashes to the ground, prone!");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " trips you, sending you sprawling to the ground!");
                }
            }
        } else {
            // Miss
            out.println("Your trip misses " + targetName + ".");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " tries to trip you but misses.");
                }
            }
        }

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result skillResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, tripSkill, charTrip, dao, tripSucceeded);

        // Debug channel output for proficiency check (only shown if debug enabled)
        ctx.handler.sendDebug("Trip proficiency check:");
        ctx.handler.sendDebug("  Skill progression: " + tripSkill.getProgression());
        ctx.handler.sendDebug("  Current proficiency: " + charTrip.getProficiency() + "%");
        ctx.handler.sendDebug("  Gain chance at this level: " + tripSkill.getProgression().getGainChance(charTrip.getProficiency()) + "%");
        ctx.handler.sendDebug("  Skill succeeded: " + tripSucceeded);
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency() + 
                        " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }

    // ── Flurry of Blows (Monk ki skill) ────────────────────────────

    private static final int FLURRY_SKILL_ID = 701;

    boolean handleFlurryCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        if (rec == null) {
            out.println("You must be logged in to use flurry.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the flurry skill
        Skill flurrySkill = DaoProvider.skills().getSkillById(FLURRY_SKILL_ID);
        if (flurrySkill == null) {
            out.println("Flurry of Blows skill not found in database.");
            return true;
        }

        CharacterSkill charFlurry = DaoProvider.skills().getCharacterSkill(charId, FLURRY_SKILL_ID);
        if (charFlurry == null) {
            out.println("You don't know how to use Flurry of Blows.");
            return true;
        }

        // Check cooldown and combat traits
        com.example.tassmud.util.AbilityCheck.CheckResult flurryCheck =
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, flurrySkill);
        if (flurryCheck.isFailure()) {
            out.println(flurryCheck.getFailureMessage());
            return true;
        }

        // Check for curse effect
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your ki dissipates.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId,
                rec.name + " attempts to channel ki but dark energy disrupts their focus!");
            return true;
        }

        // Must be in combat
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use Flurry of Blows.");
            return true;
        }

        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Check & spend ki
        com.example.tassmud.model.GameCharacter gc = userCombatant.getCharacter();
        if (gc == null || gc.getKiCur() < 1) {
            out.println("You do not have enough ki. (Need 1, have " + (gc != null ? gc.getKiCur() : 0) + ")");
            return true;
        }
        gc.spendKi(1);
        DaoProvider.characters().saveKiByName(name, gc.getKiMax(), gc.getKiCur());

        // Apply the flurry effect (handles messaging internally)
        int proficiency = charFlurry.getProficiency();
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("proficiency", String.valueOf(proficiency));
        com.example.tassmud.effect.EffectRegistry.apply(
            com.example.tassmud.effect.FlurryEffect.EFFECT_FLURRY, charId, charId, params);

        // Send ki update to player
        out.println("\u001B[1;33mKi: " + gc.getKiCur() + "/" + gc.getKiMax() + "\u001B[0m");

        // Proficiency improvement
        com.example.tassmud.util.SkillExecution.Result skillResult =
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, flurrySkill, charFlurry, dao, true);

        ctx.handler.sendDebug("Flurry proficiency check:");
        ctx.handler.sendDebug("  Current proficiency: " + charFlurry.getProficiency() + "%");
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency()
                + " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }

    // ── Stunning Fist (Monk ki skill) ────────────────────────────────

    private static final int STUNNING_FIST_SKILL_ID = 702;

    boolean handleStunningFistCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        if (rec == null) {
            out.println("You must be logged in to use stunning fist.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Look up the stunning fist skill (id=702)
        Skill stunSkill = DaoProvider.skills().getSkillById(STUNNING_FIST_SKILL_ID);
        if (stunSkill == null) {
            out.println("Stunning Fist skill not found in database.");
            return true;
        }

        // Check if character knows the skill
        CharacterSkill charStun = DaoProvider.skills().getCharacterSkill(charId, STUNNING_FIST_SKILL_ID);
        if (charStun == null) {
            out.println("You don't know how to use Stunning Fist.");
            return true;
        }

        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult stunCheck =
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, stunSkill);
        if (stunCheck.isFailure()) {
            out.println(stunCheck.getFailureMessage());
            return true;
        }

        // Check for curse effect - may cause skill to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your focus! Your stunning fist goes wide.\u001B[0m");
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId,
                rec.name + " attempts a pressure point strike but dark energy disrupts their focus!");
            return true;
        }

        // Must be in combat
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use Stunning Fist.");
            return true;
        }

        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Check & spend ALL ki (minimum 1)
        com.example.tassmud.model.GameCharacter gc = userCombatant.getCharacter();
        if (gc == null || gc.getKiCur() < 1) {
            out.println("You do not have enough ki. (Need at least 1, have " + (gc != null ? gc.getKiCur() : 0) + ")");
            return true;
        }
        int kiSpent = gc.getKiCur();
        gc.spendKi(kiSpent);
        DaoProvider.characters().saveKiByName(name, gc.getKiMax(), gc.getKiCur());

        // Find opponent (first combatant on different alliance)
        Combatant targetCombatant = null;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                targetCombatant = c;
                break;
            }
        }

        if (targetCombatant == null) {
            out.println("You have no opponent to strike.");
            return true;
        }

        // Get levels for opposed check
        CharacterClassDAO stunClassDao = DaoProvider.classes();
        int userLevel = rec.currentClassId != null ? stunClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null
                ? stunClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            Mobile targetMob = targetCombatant.getMobile();
            targetLevel = targetMob != null ? targetMob.getLevel() : 1;
        }

        // Perform opposed check with proficiency (same formula as kick)
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charStun.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        String targetName = targetCombatant.getName();
        boolean stunSucceeded = roll <= successChance;

        if (stunSucceeded) {
            // Stun duration: 1d(kiSpent) rounds
            int stunDuration = ThreadLocalRandom.current().nextInt(1, kiSpent + 1);
            targetCombatant.applyStun(stunDuration);

            out.println("\u001B[1;33mYou channel " + kiSpent + " ki into a devastating pressure point strike!\u001B[0m");
            out.println("\u001B[1;31m" + targetName + " is stunned for " + stunDuration + " round" + (stunDuration != 1 ? "s" : "") + "!\u001B[0m");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println("\u001B[1;31m" + name + " strikes a pressure point! You are stunned!\u001B[0m");
                }
            }

            // Room announcement
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId,
                name + " channels ki into a devastating pressure point strike, stunning " + targetName + "!");
        } else {
            // Miss
            out.println("\u001B[1;33mYou channel " + kiSpent + " ki into a pressure point strike, but miss!\u001B[0m");

            // Notify the opponent if they're a player
            if (targetCombatant.isPlayer()) {
                Integer targetCharId = targetCombatant.getCharacterId();
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println(name + " attempts a pressure point strike but misses.");
                }
            }
        }

        // Send ki update to player
        out.println("\u001B[1;33mKi: " + gc.getKiCur() + "/" + gc.getKiMax() + "\u001B[0m");

        // Use unified skill execution to apply cooldown and check proficiency growth
        com.example.tassmud.util.SkillExecution.Result skillResult =
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, stunSkill, charStun, dao, stunSucceeded);

        // Debug channel output
        ctx.handler.sendDebug("Stunning Fist check:");
        ctx.handler.sendDebug("  Ki spent: " + kiSpent);
        ctx.handler.sendDebug("  Roll: " + roll + " vs " + successChance + " (user L" + userLevel + " vs target L" + targetLevel + ", prof " + proficiency + "%)");
        ctx.handler.sendDebug("  Result: " + (stunSucceeded ? "HIT" : "MISS"));
        ctx.handler.sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
        if (skillResult.getProficiencyResult() != null) {
            ctx.handler.sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency()
                + " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
        }

        if (skillResult.didProficiencyImprove()) {
            out.println(skillResult.getProficiencyMessage());
        }
        return true;
    }
}
