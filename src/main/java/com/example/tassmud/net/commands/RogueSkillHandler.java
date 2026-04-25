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
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.RegenerationService;

/**
 * Delegate handler for rogue skill commands extracted from CombatCommandHandler.
 * Not a standalone CommandHandler — methods are called by CombatCommandHandler.
 */
class RogueSkillHandler {

    private static final Logger logger = LoggerFactory.getLogger(RogueSkillHandler.class);

    boolean handleVisibleCommand(CommandContext ctx) {
        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = dao.findByName(name);

        // VISIBLE/UNHIDE - Drop invisibility
        if (rec == null) {
            out.println("You must be logged in to become visible.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        
        // Check if currently invisible
        if (!com.example.tassmud.effect.EffectRegistry.isInvisible(charId)) {
            out.println("You are already visible.");
            return true;
        }
        
        // Remove invisibility effect
        com.example.tassmud.effect.EffectRegistry.removeInvisibility(charId);
        
        out.println("You become visible again.");
        // Announce to room
        ClientHandler.roomAnnounce(ctx.currentRoomId, name + " fades into view.", ctx.characterId, true);
        return true;
    }

    boolean handleHideCommand(CommandContext ctx) {
        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = dao.findByName(name);

        // HIDE - Skill-based invisibility (requires hide skill, has cooldown)
        if (rec == null) {
            out.println("You must be logged in to hide.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        
        // Look up the hide skill (id=305)
        Skill hideSkill = DaoProvider.skills().getSkillById(305);
        if (hideSkill == null) {
            out.println("Hide skill not found in database.");
            return true;
        }
        
        // Check if character knows the hide skill
        CharacterSkill charHide = DaoProvider.skills().getCharacterSkill(charId, 305);
        if (charHide == null) {
            out.println("You don't know how to hide.");
            return true;
        }
        
        // Check cooldown using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult hideCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, hideSkill);
        if (hideCheck.isFailure()) {
            out.println(hideCheck.getFailureMessage());
            return true;
        }
        
        // Check if already invisible
        if (com.example.tassmud.effect.EffectRegistry.isInvisible(charId)) {
            out.println("You are already invisible.");
            return true;
        }
        
        // Perform proficiency roll to determine success
        // Success chance = proficiency%
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        int proficiency = charHide.getProficiency();
        boolean hideSucceeded = roll <= proficiency;
        
        if (hideSucceeded) {
            // Apply invisibility effect (id 110)
            com.example.tassmud.effect.EffectInstance inst = 
                com.example.tassmud.effect.EffectRegistry.apply("110", charId, charId, null);
            
            if (inst != null) {
                out.println("You fade from view, becoming invisible.");
                // Announce to room only if others can see invisible
                ClientHandler.roomAnnounceFromActor(ctx.currentRoomId, name + " fades from view.", ctx.characterId);
            } else {
                out.println("You try to hide but fail.");
            }
        } else {
            out.println("You attempt to hide but fail to conceal yourself.");
        }
        
        // Record skill use (applies cooldown and checks proficiency growth)
        com.example.tassmud.util.SkillExecution.Result hideResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, hideSkill, charHide, dao, hideSucceeded);
        
        // Debug channel output for proficiency check
        ctx.handler.sendDebug("Hide proficiency check:");
        ctx.handler.sendDebug("  Skill progression: " + hideSkill.getProgression());
        ctx.handler.sendDebug("  Current proficiency: " + proficiency + "%");
        ctx.handler.sendDebug("  Roll: " + roll + " (needed <= " + proficiency + ")");
        ctx.handler.sendDebug("  Skill succeeded: " + hideSucceeded);
        ctx.handler.sendDebug("  Proficiency improved: " + hideResult.didProficiencyImprove());
        
        if (hideResult.didProficiencyImprove()) {
            out.println(hideResult.getProficiencyMessage());
        }
        return true;
    }

    boolean handleSneakCommand(CommandContext ctx) {
        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = dao.findByName(name);

        if (rec == null) {
            out.println("You must be logged in to sneak.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        
        // Check if character knows the sneak skill (id=300)
        CharacterSkill charSneak = DaoProvider.skills().getCharacterSkill(charId, 300);
        if (charSneak == null) {
            out.println("You don't know how to sneak.");
            return true;
        }
        
        // Toggle sneak mode using character flag
        String currentSneak = dao.getCharacterFlag(charId, "is_sneaking");
        boolean isSneaking = "true".equalsIgnoreCase(currentSneak);
        
        if (isSneaking) {
            // Turn off sneak mode
            dao.setCharacterFlag(charId, "is_sneaking", "false");
            out.println("You stop sneaking and move normally.");
        } else {
            // Turn on sneak mode
            dao.setCharacterFlag(charId, "is_sneaking", "true");
            out.println("You begin to move silently, sneaking through the shadows.");
        }
        return true;
    }

    boolean handleBackstabCommand(CommandContext ctx) {
        String name = ctx.playerName;
        Integer characterId = ctx.characterId;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);
        
        // BACKSTAB - Initiate combat with a devastating sneak attack
        // Damage is doubled on hit, quadrupled on critical
        // Cooldown scales from 15s (0% prof) to 3s (100% prof)
        
        if (rec == null) {
            out.println("You must be logged in to backstab.");
            return true;
        }
        
        if (characterId == null) {
            characterId = dao.getCharacterIdByName(name);
        }
        
        // Check if character knows backstab (id=301)
        Skill backstabSkill = DaoProvider.skills().getSkillById(301);
        if (backstabSkill == null) {
            out.println("Backstab skill not found in database.");
            return true;
        }
        
        CharacterSkill charBackstab = DaoProvider.skills().getCharacterSkill(characterId, 301);
        if (charBackstab == null) {
            out.println("You don't know how to backstab.");
            return true;
        }
        
        // Check cooldown using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult bsCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, characterId, backstabSkill);
        if (bsCheck.isFailure()) {
            out.println(bsCheck.getFailureMessage());
            return true;
        }
        
        // Must NOT be in combat - backstab initiates combat
        CombatManager cm = CombatManager.getInstance();
        if (cm.isInCombat(characterId)) {
            out.println("You can only backstab targets that are unaware of you.");
            return true;
        }
        
        // Check stance allows initiating combat
        Stance s = RegenerationService.getInstance().getPlayerStance(characterId);
        if (!s.canInitiateCombat()) {
            out.println("You must be standing to backstab.");
            return true;
        }
        
        // Check blindness
        if (com.example.tassmud.effect.EffectRegistry.isBlind(characterId)) {
            out.println("You can't see to target anyone!");
            return true;
        }
        
        // Get target argument
        String targetArg = ctx.getArgs();
        if (targetArg == null || targetArg.trim().isEmpty()) {
            out.println("Usage: backstab <target>");
            return true;
        }
        
        Integer roomId = rec.currentRoom;
        if (roomId == null) {
            out.println("You are nowhere to attack from.");
            return true;
        }
        
        // Check SAFE room flag
        if (DaoProvider.rooms().isRoomSafe(roomId)) {
            out.println("You cannot fight here. This is a safe area.");
            return true;
        }
        
        // Find the target mob
        Mobile matched = com.example.tassmud.util.MobileMatchingService.findInRoom(roomId, targetArg);
        if (matched == null) {
            java.util.List<Mobile> mobs = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(roomId);
            if (mobs == null || mobs.isEmpty()) {
                out.println("There are no creatures here to backstab.");
            } else {
                out.println("No such target here: " + targetArg);
            }
            return true;
        }
        MobileDAO mobDao = DaoProvider.mobiles();
        
        // Can't backstab a mob already in combat
        if (cm.isInCombat(matched)) {
            out.println(matched.getName() + " is already in combat and aware of their surroundings.");
            return true;
        }
        
        // Execute the backstab attack before initiating combat
        // Build attacker character
        GameCharacter attacker = ClientHandler.buildCharacterForCombat(rec, characterId);
        if (attacker == null) {
            out.println("Failed to prepare you for combat.");
            return true;
        }
        
        int proficiency = charBackstab.getProficiency();
        
        // Calculate hit chance using proficiency-modified opposed check
        CharacterClassDAO classDao = DaoProvider.classes();
        int playerLevel = rec.currentClassId != null 
            ? classDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
        int mobLevel = Math.max(1, matched.getLevel());
        
        // Roll attack (d20 + level bonus + DEX mod for backstab)
        int dexMod = (attacker.getDex() - 10) / 2;
        int attackRoll = ThreadLocalRandom.current().nextInt(1, 21);
        int totalAttack = attackRoll + playerLevel + dexMod;
        
        // Target's AC (we'll use the mob's level-based defense)
        int targetDefense = 10 + mobLevel;
        
        // Check for critical (nat 20)
        boolean isCrit = (attackRoll == 20);
        
        // Check for miss (nat 1 always misses, or roll < defense unless crit)
        boolean isHit = (attackRoll != 1) && (isCrit || totalAttack >= targetDefense);
        
        // Calculate cooldown based on proficiency: 15s at 0%, 3s at 100%
        // Formula: 15 - (proficiency * 0.12) = 15s to 3s
        int cooldownSeconds = 15 - (int)(proficiency * 0.12);
        cooldownSeconds = Math.max(3, cooldownSeconds);
        
        // Apply cooldown
        com.example.tassmud.util.CooldownManager.getInstance().setPlayerCooldown(
            name, com.example.tassmud.model.CooldownType.SKILL, 301, cooldownSeconds);
        
        // Try to improve proficiency
        DaoProvider.skills().tryImproveSkill(characterId, 301, backstabSkill);
        
        if (!isHit) {
            // Miss - announce and then initiate normal combat
            out.println("You attempt to backstab " + matched.getName() + " but miss!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " attempts to backstab " + matched.getName() + " but misses!", characterId);
            
            // Initiate combat (mob is now aware)
            cm.initiateCombat(attacker, characterId, matched, roomId);
            return true;
        }
        
        // Hit! Calculate damage
        // Use a simple damage formula based on player level and DEX
        int baseDamage = ThreadLocalRandom.current().nextInt(1, (4 + playerLevel) + 1) + dexMod;
        if (baseDamage < 1) baseDamage = 1;
        
        // Apply backstab multiplier: 2x on hit, 4x on crit
        int damageMultiplier = isCrit ? 4 : 2;
        int totalDamage = baseDamage * damageMultiplier;
        
        // Apply the damage to the mob
        int oldHp = matched.getHpCur();
        matched.setHpCur(oldHp - totalDamage);
        mobDao.updateInstance(matched);
        
        // Announce the backstab
        if (isCrit) {
            out.println("{R*** CRITICAL BACKSTAB! ***{x");
            out.println("You drive your blade deep into " + matched.getName() + "'s back for " + totalDamage + " damage!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " drives their blade deep into " + matched.getName() + "'s back!", characterId);
        } else {
            out.println("You backstab " + matched.getName() + " for " + totalDamage + " damage!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " backstabs " + matched.getName() + "!", characterId);
        }
        
        // Debug output
        ctx.handler.sendDebug("Backstab attack:");
        ctx.handler.sendDebug("  Roll: " + attackRoll + " + " + playerLevel + " (level) + " + dexMod + " (DEX) = " + totalAttack);
        ctx.handler.sendDebug("  vs Defense: " + targetDefense);
        ctx.handler.sendDebug("  Critical: " + isCrit);
        ctx.handler.sendDebug("  Base damage: " + baseDamage + " x" + damageMultiplier + " = " + totalDamage);
        ctx.handler.sendDebug("  Cooldown: " + cooldownSeconds + "s (proficiency: " + proficiency + "%)");
        
        // Check if mob died from backstab
        if (matched.getHpCur() <= 0) {
            matched.setHpCur(0);
            
            out.println(matched.getName() + " crumples to the ground, dead!");
            ClientHandler.roomAnnounce(roomId, matched.getName() + " crumples to the ground, dead!");
            
            // Calculate gold for corpse
            java.util.Random rand = new java.util.Random();
            mobLevel = Math.max(1, matched.getLevel());
            int baseGold = mobLevel * 2;
            int bonusGold = (mobLevel * mobLevel) / 5;
            int variance = rand.nextInt(mobLevel + 1);
            long goldAmount = baseGold + bonusGold + variance;
            
            // Create corpse with gold
            com.example.tassmud.persistence.ItemDAO itemDao = DaoProvider.items();
            long corpseId = itemDao.createCorpse(roomId, matched.getName(), goldAmount);
            
            if (corpseId > 0) {
                // Generate loot in the corpse
                java.util.List<com.example.tassmud.util.LootGenerator.GeneratedItem> loot = 
                    com.example.tassmud.util.LootGenerator.generateLoot(mobLevel, corpseId, itemDao);
                for (com.example.tassmud.util.LootGenerator.GeneratedItem item : loot) {
                    if (item.customName != null) {
                        ClientHandler.roomAnnounce(roomId, "  * " + item.customName);
                    }
                }
            }
            
            // Award XP using centralized service
            com.example.tassmud.util.ExperienceService.awardCombatXp(
                    characterId, mobLevel, msg -> out.println(msg));
            
            // Mark mob dead and remove from world
            matched.die();
            com.example.tassmud.util.MobileRegistry.getInstance().unregister(matched.getInstanceId());
            mobDao.deleteInstance(matched.getInstanceId());
            
            return true;
        }
        
        // Mob survived - initiate combat
        cm.initiateCombat(attacker, characterId, matched, roomId);
        return true;
    }

    boolean handleCircleCommand(CommandContext ctx) {
        String name = ctx.playerName;
        Integer characterId = ctx.characterId;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);
        
        // CIRCLE - In-combat version of backstab
        // Damage is doubled on hit, quadrupled on critical
        // Cooldown scales from 30s (0% prof) to 6s (100% prof)
        
        if (rec == null) {
            out.println("You must be logged in to circle.");
            return true;
        }
        
        if (characterId == null) {
            characterId = dao.getCharacterIdByName(name);
        }
        
        // Check if character knows circle (id=307)
        Skill circleSkill = DaoProvider.skills().getSkillById(307);
        if (circleSkill == null) {
            out.println("Circle skill not found in database.");
            return true;
        }
        
        CharacterSkill charCircle = DaoProvider.skills().getCharacterSkill(characterId, 307);
        if (charCircle == null) {
            out.println("You don't know how to circle.");
            return true;
        }
        
        // Check cooldown using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult circleCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, characterId, circleSkill);
        if (circleCheck.isFailure()) {
            out.println(circleCheck.getFailureMessage());
            return true;
        }
        
        // Must BE in combat - circle is an in-combat skill
        CombatManager cm = CombatManager.getInstance();
        Combat combat = cm.getCombatForCharacter(characterId);
        if (combat == null) {
            out.println("You must be in combat to use circle. Use 'backstab' to initiate combat.");
            return true;
        }
        
        // Get the player's combatant
        Combatant userCombatant = combat.findByCharacterId(characterId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }
        
        // Check blindness
        if (com.example.tassmud.effect.EffectRegistry.isBlind(characterId)) {
            out.println("You can't see to circle anyone!");
            return true;
        }
        
        // Get target - either from args or current combat target
        String targetArg = ctx.getArgs();
        Combatant targetCombatant = null;
        
        if (targetArg != null && !targetArg.trim().isEmpty()) {
            // Find target by name in combat
            String search = targetArg.trim().toLowerCase();
            for (Combatant c : combat.getCombatants()) {
                if (c.isHostileTo(userCombatant) && c.isAlive()) {
                    String cName = c.getName();
                    if (cName != null && cName.toLowerCase().startsWith(search)) {
                        targetCombatant = c;
                        break;
                    }
                }
            }
            if (targetCombatant == null) {
                out.println("No such enemy in combat: " + targetArg);
                return true;
            }
        } else {
            // Use first available hostile target
            for (Combatant c : combat.getCombatants()) {
                if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                    targetCombatant = c;
                    break;
                }
            }
            if (targetCombatant == null) {
                out.println("You have no valid target. Specify a target: circle <target>");
                return true;
            }
        }
        
        int proficiency = charCircle.getProficiency();
        
        // Build attacker character
        GameCharacter attacker = ClientHandler.buildCharacterForCombat(rec, characterId);
        if (attacker == null) {
            out.println("Failed to prepare attack.");
            return true;
        }
        
        // Calculate hit chance
        CharacterClassDAO classDao = DaoProvider.classes();
        int playerLevel = rec.currentClassId != null 
            ? classDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? classDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use their level or estimate from HP
            Mobile mob = targetCombatant.getMobile();
            targetLevel = mob != null ? Math.max(1, mob.getLevel()) : Math.max(1, targetCombatant.getHpMax() / 10);
        }
        
        // Roll attack (d20 + level bonus + DEX mod)
        int dexMod = (attacker.getDex() - 10) / 2;
        int attackRoll = ThreadLocalRandom.current().nextInt(1, 21);
        int totalAttack = attackRoll + playerLevel + dexMod;
        
        // Target's defense
        int targetDefense = targetCombatant.getArmor();
        
        // Check for critical (nat 20)
        boolean isCrit = (attackRoll == 20);
        
        // Check for miss (nat 1 always misses, or roll < defense unless crit)
        boolean isHit = (attackRoll != 1) && (isCrit || totalAttack >= targetDefense);
        
        // Calculate cooldown based on proficiency: 30s at 0%, 6s at 100%
        // Formula: 30 - (proficiency * 0.24) = 30s to 6s
        int cooldownSeconds = 30 - (int)(proficiency * 0.24);
        cooldownSeconds = Math.max(6, cooldownSeconds);
        
        // Apply cooldown
        com.example.tassmud.util.CooldownManager.getInstance().setPlayerCooldown(
            name, com.example.tassmud.model.CooldownType.SKILL, 307, cooldownSeconds);
        
        // Try to improve proficiency
        DaoProvider.skills().tryImproveSkill(characterId, 307, circleSkill);
        
        String targetName = targetCombatant.getName();
        Integer roomId = rec.currentRoom;
        
        if (!isHit) {
            // Miss
            out.println("You dart around " + targetName + " but fail to find an opening!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " circles around " + targetName + " but misses!", characterId);
            return true;
        }
        
        // Hit! Calculate damage
        int baseDamage = ThreadLocalRandom.current().nextInt(1, (4 + playerLevel) + 1) + dexMod;
        if (baseDamage < 1) baseDamage = 1;
        
        // Apply circle multiplier: 2x on hit, 4x on crit
        int damageMultiplier = isCrit ? 4 : 2;
        int totalDamage = baseDamage * damageMultiplier;
        
        // Apply the damage
        targetCombatant.damage(totalDamage);
        
        // Announce the circle attack
        if (isCrit) {
            out.println("{R*** CRITICAL CIRCLE! ***{x");
            out.println("You dart behind " + targetName + " and drive your blade deep for " + totalDamage + " damage!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " darts behind " + targetName + " and delivers a devastating strike!", characterId);
        } else {
            out.println("You circle behind " + targetName + " and strike for " + totalDamage + " damage!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " circles behind " + targetName + " and strikes!", characterId);
        }
        
        // Debug output
        ctx.handler.sendDebug("Circle attack:");
        ctx.handler.sendDebug("  Roll: " + attackRoll + " + " + playerLevel + " (level) + " + dexMod + " (DEX) = " + totalAttack);
        ctx.handler.sendDebug("  vs Defense: " + targetDefense);
        ctx.handler.sendDebug("  Critical: " + isCrit);
        ctx.handler.sendDebug("  Base damage: " + baseDamage + " x" + damageMultiplier + " = " + totalDamage);
        ctx.handler.sendDebug("  Cooldown: " + cooldownSeconds + "s (proficiency: " + proficiency + "%)");
        
        // Check if target died
        if (!targetCombatant.isAlive()) {
            out.println(targetName + " collapses from your devastating strike!");
            // Combat system will handle death on next tick
        }
        
        return true;
    }

    boolean handleAssassinateCommand(CommandContext ctx) {
        String name = ctx.playerName;
        Integer characterId = ctx.characterId;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);
        
        // ASSASSINATE - Ultimate backstab: 4x damage on hit, INSTANT KILL on crit
        // Fixed 60s cooldown, out-of-combat only
        
        if (rec == null) {
            out.println("You must be logged in to assassinate.");
            return true;
        }
        
        if (characterId == null) {
            characterId = dao.getCharacterIdByName(name);
        }
        
        // Check if character knows assassinate (id=308)
        Skill assassinateSkill = DaoProvider.skills().getSkillById(308);
        if (assassinateSkill == null) {
            out.println("Assassinate skill not found in database.");
            return true;
        }
        
        CharacterSkill charAssassinate = DaoProvider.skills().getCharacterSkill(characterId, 308);
        if (charAssassinate == null) {
            out.println("You don't know how to assassinate.");
            return true;
        }
        
        // Check cooldown using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult assassinateCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, characterId, assassinateSkill);
        if (assassinateCheck.isFailure()) {
            out.println(assassinateCheck.getFailureMessage());
            return true;
        }
        
        // Must NOT be in combat - assassinate is an initiation skill
        CombatManager cm = CombatManager.getInstance();
        Combat combat = cm.getCombatForCharacter(characterId);
        if (combat != null) {
            out.println("You cannot assassinate while in combat. Use 'circle' instead.");
            return true;
        }
        
        // Check blindness
        if (com.example.tassmud.effect.EffectRegistry.isBlind(characterId)) {
            out.println("You can't see well enough to find a vital spot!");
            return true;
        }
        
        // Need a target
        String targetArg = ctx.getArgs();
        if (targetArg == null || targetArg.trim().isEmpty()) {
            out.println("Assassinate whom?");
            return true;
        }
        
        Integer roomId = rec.currentRoom;
        if (roomId == null) {
            out.println("You are nowhere to attack from.");
            return true;
        }
        
        // Check SAFE room flag
        if (DaoProvider.rooms().isRoomSafe(roomId)) {
            out.println("You cannot fight here. This is a safe area.");
            return true;
        }
        
        // Find the target mob
        Mobile matched = com.example.tassmud.util.MobileMatchingService.findInRoom(roomId, targetArg);
        if (matched == null) {
            java.util.List<Mobile> mobs = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(roomId);
            if (mobs == null || mobs.isEmpty()) {
                out.println("There are no creatures here to assassinate.");
            } else {
                out.println("No such target here: " + targetArg);
            }
            return true;
        }
        MobileDAO mobDao = DaoProvider.mobiles();
        
        // Can't assassinate a mob already in combat
        if (cm.isInCombat(matched)) {
            out.println(matched.getName() + " is already in combat and aware of their surroundings.");
            return true;
        }
        
        // Execute the assassination attempt
        GameCharacter attacker = ClientHandler.buildCharacterForCombat(rec, characterId);
        if (attacker == null) {
            out.println("Failed to prepare you for combat.");
            return true;
        }
        
        // Calculate hit chance using proficiency-modified opposed check
        CharacterClassDAO classDao = DaoProvider.classes();
        int playerLevel = rec.currentClassId != null 
            ? classDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
        int mobLevel = Math.max(1, matched.getLevel());
        
        // Roll attack (d20 + level bonus + DEX mod)
        int dexMod = (attacker.getDex() - 10) / 2;
        int attackRoll = ThreadLocalRandom.current().nextInt(1, 21);
        int totalAttack = attackRoll + playerLevel + dexMod;
        
        // Target's AC (level-based defense)
        int targetDefense = 10 + mobLevel;
        
        // Check for critical (nat 20) - THIS IS THE INSTANT KILL
        boolean isCrit = (attackRoll == 20);
        
        // Check for miss (nat 1 always misses, or roll < defense unless crit)
        boolean isHit = (attackRoll != 1) && (isCrit || totalAttack >= targetDefense);
        
        // Fixed 60s cooldown
        int cooldownSeconds = 60;
        
        // Apply cooldown
        com.example.tassmud.util.CooldownManager.getInstance().setPlayerCooldown(
            name, com.example.tassmud.model.CooldownType.SKILL, 308, cooldownSeconds);
        
        // Try to improve proficiency
        DaoProvider.skills().tryImproveSkill(characterId, 308, assassinateSkill);
        
        if (!isHit) {
            // Miss - announce and then initiate normal combat
            out.println("You move in for the kill but " + matched.getName() + " senses your presence!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " attempts to assassinate " + matched.getName() + " but is detected!", characterId);
            
            // Initiate combat (target is now aware)
            cm.initiateCombat(attacker, characterId, matched, roomId);
            return true;
        }
        
        // Debug output
        ctx.handler.sendDebug("Assassinate attack:");
        ctx.handler.sendDebug("  Roll: " + attackRoll + " + " + playerLevel + " (level) + " + dexMod + " (DEX) = " + totalAttack);
        ctx.handler.sendDebug("  vs Defense: " + targetDefense);
        ctx.handler.sendDebug("  Critical: " + isCrit);
        ctx.handler.sendDebug("  Cooldown: " + cooldownSeconds + "s (fixed)");
        
        // CRITICAL HIT = INSTANT DEATH
        if (isCrit) {
            out.println("{R*** ASSASSINATION! ***{x");
            out.println("Your blade finds the perfect spot. " + matched.getName() + " dies instantly!");
            ClientHandler.roomAnnounceFromActor(roomId, 
                name + " executes " + matched.getName() + " with a perfect killing blow!", characterId);
            ClientHandler.roomAnnounce(roomId, matched.getName() + " crumples to the ground, dead!");
            
            // Handle death directly (no combat initiated)
            handleAssassinationKill(matched, mobDao, roomId, characterId, out);
            return true;
        }
        
        // Regular hit - 4x damage
        int baseDamage = ThreadLocalRandom.current().nextInt(1, (4 + playerLevel) + 1) + dexMod;
        if (baseDamage < 1) baseDamage = 1;
        int totalDamage = baseDamage * 4; // Always 4x on hit
        
        // Apply the damage to the mob
        int oldHp = matched.getHpCur();
        matched.setHpCur(oldHp - totalDamage);
        mobDao.updateInstance(matched);
        
        out.println("You strike " + matched.getName() + " from the shadows for " + totalDamage + " damage!");
        ClientHandler.roomAnnounceFromActor(roomId, 
            name + " strikes " + matched.getName() + " from the shadows!", characterId);
        
        ctx.handler.sendDebug("  Base damage: " + baseDamage + " x4 = " + totalDamage);
        
        // Check if mob died from the damage
        if (matched.getHpCur() <= 0) {
            matched.setHpCur(0);
            out.println(matched.getName() + " crumples to the ground, dead!");
            ClientHandler.roomAnnounce(roomId, matched.getName() + " crumples to the ground, dead!");
            
            handleAssassinationKill(matched, mobDao, roomId, characterId, out);
            return true;
        }
        
        // Mob survived - initiate combat
        cm.initiateCombat(attacker, characterId, matched, roomId);
        return true;
    }
    
    /**
     * Handle the death of a mob from assassination (out-of-combat kill).
     * Creates corpse, generates loot, awards XP.
     */
    void handleAssassinationKill(Mobile mob, MobileDAO mobDao, int roomId, 
            int characterId, PrintWriter out) {
        int mobLevel = Math.max(1, mob.getLevel());
        
        // Calculate gold for corpse
        java.util.Random rand = new java.util.Random();
        int baseGold = mobLevel * 2;
        int bonusGold = (mobLevel * mobLevel) / 5;
        int variance = rand.nextInt(mobLevel + 1);
        long goldAmount = baseGold + bonusGold + variance;
        
        // Create corpse with gold
        com.example.tassmud.persistence.ItemDAO itemDao = DaoProvider.items();
        long corpseId = itemDao.createCorpse(roomId, mob.getName(), goldAmount);
        
        if (corpseId > 0) {
            // Generate loot in the corpse
            java.util.List<com.example.tassmud.util.LootGenerator.GeneratedItem> loot = 
                com.example.tassmud.util.LootGenerator.generateLoot(mobLevel, corpseId, itemDao);
            for (com.example.tassmud.util.LootGenerator.GeneratedItem item : loot) {
                if (item.customName != null) {
                    ClientHandler.roomAnnounce(roomId, "  * " + item.customName);
                }
            }
        }
        
        // Award XP using centralized service
        com.example.tassmud.util.ExperienceService.awardCombatXp(
                characterId, mobLevel, msg -> out.println(msg));
        
        // Mark mob dead and remove from world
        mob.die();
        com.example.tassmud.util.MobileRegistry.getInstance().unregister(mob.getInstanceId());
        mobDao.deleteInstance(mob.getInstanceId());
    }

    boolean handleShadowCommand(CommandContext ctx) {
        String name = ctx.playerName;
        Integer characterId = ctx.characterId;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);
        
        // SHADOW STEP - Personal teleport to a marked location
        // "shadow set" - Mark current room as shadow destination
        // "shadow step" or "ss" - Teleport to marked room
        // Cooldown scales from 60s (0% prof) to 15s (100% prof)
        
        if (rec == null) {
            out.println("You must be logged in to use shadow step.");
            return true;
        }
        
        if (characterId == null) {
            characterId = dao.getCharacterIdByName(name);
        }
        
        // Check if character knows shadow step (id=309)
        Skill shadowSkill = DaoProvider.skills().getSkillById(309);
        if (shadowSkill == null) {
            out.println("Shadow Step skill not found in database.");
            return true;
        }
        
        CharacterSkill charShadow = DaoProvider.skills().getCharacterSkill(characterId, 309);
        if (charShadow == null) {
            out.println("You don't know how to step through shadows.");
            return true;
        }
        
        // Parse subcommand
        String args = ctx.getArgs();
        String cmdName = ctx.getCommandName();
        
        // "ss" alone means "shadow step" (teleport)
        if ("ss".equals(cmdName) && (args == null || args.trim().isEmpty())) {
            return executeShadowStep(ctx, rec, characterId, dao, out, shadowSkill, charShadow);
        }
        
        // Parse "shadow <subcommand>"
        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: shadow set - Mark your current location");
            out.println("       shadow step (or ss) - Teleport to marked location");
            return true;
        }
        
        String subCmd = args.trim().toLowerCase().split("\\s+")[0];
        
        if ("set".equals(subCmd)) {
            return executeShadowSet(ctx, rec, characterId, dao, out);
        } else if ("step".equals(subCmd)) {
            return executeShadowStep(ctx, rec, characterId, dao, out, shadowSkill, charShadow);
        } else {
            out.println("Unknown shadow command: " + subCmd);
            out.println("Usage: shadow set - Mark your current location");
            out.println("       shadow step (or ss) - Teleport to marked location");
            return true;
        }
    }
    
    boolean executeShadowSet(CommandContext ctx, CharacterRecord rec, Integer characterId, 
            CharacterDAO dao, PrintWriter out) {
        Integer roomId = rec.currentRoom;
        if (roomId == null) {
            out.println("You are nowhere to mark.");
            return true;
        }
        
        // Can't set shadow point in a PRISON room
        if (DaoProvider.rooms().hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.PRISON)) {
            out.println("The oppressive magic of this prison prevents you from marking it.");
            return true;
        }
        
        // Store the shadow point as a character flag
        dao.setCharacterFlag(characterId, "shadow_step_room", String.valueOf(roomId));
        
        // Get room name for feedback
        Room room = DaoProvider.rooms().getRoomById(roomId);
        String roomName = (room != null) ? room.getName() : "this location";
        
        out.println("You attune yourself to the shadows here.");
        out.println("Shadow point set to: " + roomName);
        ClientHandler.roomAnnounceFromActor(roomId, 
            ctx.playerName + " concentrates, attuning to the shadows.", characterId);
        
        return true;
    }
    
    boolean executeShadowStep(CommandContext ctx, CharacterRecord rec, Integer characterId,
            CharacterDAO dao, PrintWriter out, Skill shadowSkill, CharacterSkill charShadow) {
        Integer currentRoomId = rec.currentRoom;
        if (currentRoomId == null) {
            out.println("You are nowhere to step from.");
            return true;
        }
        
        // Can't shadow step from a PRISON room
        if (DaoProvider.rooms().hasRoomFlag(currentRoomId, com.example.tassmud.model.RoomFlag.PRISON)) {
            out.println("The oppressive magic of this prison prevents you from stepping through shadows.");
            return true;
        }
        
        // Can't use while in combat
        CombatManager cm = CombatManager.getInstance();
        if (cm.getCombatForCharacter(characterId) != null) {
            out.println("You cannot step through shadows while in combat!");
            return true;
        }
        
        // Check cooldown
        com.example.tassmud.util.AbilityCheck.CheckResult shadowCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(ctx.playerName, characterId, shadowSkill);
        if (shadowCheck.isFailure()) {
            out.println(shadowCheck.getFailureMessage());
            return true;
        }
        
        // Get the shadow point
        String shadowRoomStr = dao.getCharacterFlag(characterId, "shadow_step_room");
        if (shadowRoomStr == null || shadowRoomStr.isEmpty()) {
            out.println("You have not set a shadow point. Use 'shadow set' first.");
            return true;
        }
        
        int targetRoomId;
        try {
            targetRoomId = Integer.parseInt(shadowRoomStr);
        } catch (NumberFormatException e) {
            out.println("Your shadow point has become corrupted. Use 'shadow set' to mark a new location.");
            dao.setCharacterFlag(characterId, "shadow_step_room", null);
            return true;
        }
        
        // Check if target room still exists
        Room targetRoom = DaoProvider.rooms().getRoomById(targetRoomId);
        if (targetRoom == null) {
            out.println("Your shadow point no longer exists. Use 'shadow set' to mark a new location.");
            dao.setCharacterFlag(characterId, "shadow_step_room", null);
            return true;
        }
        String targetRoomName = targetRoom.getName();
        
        // Can't step to the room you're already in
        if (targetRoomId == currentRoomId) {
            out.println("You are already at your shadow point.");
            return true;
        }
        
        // Calculate cooldown based on proficiency: 60s at 0%, 15s at 100%
        // Formula: 60 - (proficiency * 0.45) = 60s to 15s
        int proficiency = charShadow.getProficiency();
        int cooldownSeconds = 60 - (int)(proficiency * 0.45);
        cooldownSeconds = Math.max(15, cooldownSeconds);
        
        // Apply cooldown
        com.example.tassmud.util.CooldownManager.getInstance().setPlayerCooldown(
            ctx.playerName, com.example.tassmud.model.CooldownType.SKILL, 309, cooldownSeconds);
        
        // Try to improve proficiency
        DaoProvider.skills().tryImproveSkill(characterId, 309, shadowSkill);
        
        // Execute the shadow step
        Room currentRoom = DaoProvider.rooms().getRoomById(currentRoomId);
        String currentRoomName = (currentRoom != null) ? currentRoom.getName() : "unknown";
        
        // Announce departure
        out.println("You step into the shadows...");
        ClientHandler.roomAnnounceFromActor(currentRoomId, 
            ctx.playerName + " steps into the shadows and vanishes!", characterId);
        
        // Move the character
        dao.updateCharacterRoom(ctx.playerName, targetRoomId);
        
        // Update client handler's current room
        ctx.handler.currentRoomId = targetRoomId;
        
        // Announce arrival
        out.println("...and emerge from the darkness.");
        ClientHandler.roomAnnounceFromActor(targetRoomId, 
            ctx.playerName + " emerges from the shadows!", characterId);
        
        // Show the room
        MovementCommandHandler.showRoom(targetRoom, targetRoomId, ctx);
        
        // Debug output
        ctx.handler.sendDebug("Shadow Step:");
        ctx.handler.sendDebug("  From: " + currentRoomName + " (room " + currentRoomId + ")");
        ctx.handler.sendDebug("  To: " + targetRoomName + " (room " + targetRoomId + ")");
        ctx.handler.sendDebug("  Cooldown: " + cooldownSeconds + "s (proficiency: " + proficiency + "%)");
        
        return true;
    }

    boolean handlePickCommand(CommandContext ctx) {
        final int LOCKPICK_TEMPLATE_ID = 5;
        final int LOCKPICKING_SKILL_ID = 50;

        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterDAO.CharacterRecord rec = dao.findByName(name);
        if (rec == null) {
            out.println("You must be logged in to pick locks.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        // Check lockpicking skill
        Skill lockSkill = DaoProvider.skills().getSkillById(LOCKPICKING_SKILL_ID);
        if (lockSkill == null) {
            out.println("Lockpicking skill not found in database.");
            return true;
        }
        CharacterSkill charLock = DaoProvider.skills().getCharacterSkill(charId, LOCKPICKING_SKILL_ID);
        if (charLock == null) {
            out.println("You don't know how to pick locks.");
            return true;
        }

        // Parse direction argument
        String targetArg = ctx.getArgs();
        if (targetArg == null || targetArg.trim().isEmpty()) {
            out.println("Pick which direction?");
            return true;
        }
        String target = targetArg.trim().toLowerCase();
        Integer curRoomId = rec.currentRoom;
        if (curRoomId == null) {
            out.println("You are nowhere.");
            return true;
        }

        String dirToken = null;
        switch (target) {
            case "n": case "north": dirToken = "north"; break;
            case "e": case "east":  dirToken = "east";  break;
            case "s": case "south": dirToken = "south"; break;
            case "w": case "west":  dirToken = "west";  break;
            case "u": case "up":    dirToken = "up";    break;
            case "d": case "down":  dirToken = "down";  break;
        }

        com.example.tassmud.model.Door door = null;
        if (dirToken != null) {
            door = DaoProvider.rooms().getDoor(curRoomId, dirToken);
        } else {
            java.util.List<com.example.tassmud.model.Door> doors = DaoProvider.rooms().getDoorsForRoom(curRoomId);
            for (com.example.tassmud.model.Door d : doors) {
                if (d.description != null && d.description.toLowerCase().contains(target)) {
                    door = d; break;
                }
                if (d.direction != null && d.direction.startsWith(target)) {
                    door = d; break;
                }
            }
        }

        if (door == null) {
            out.println("You don't see a door like that here.");
            return true;
        }
        if (door.hidden) {
            out.println("You don't see a way that way.");
            return true;
        }
        if (!door.isLocked()) {
            out.println("That door isn't locked.");
            return true;
        }

        // Find a lockpick in inventory
        java.util.List<com.example.tassmud.persistence.ItemDAO.RoomItem> inv =
            DaoProvider.items().getItemsByCharacter(charId);
        com.example.tassmud.persistence.ItemDAO.RoomItem lockpick = null;
        for (com.example.tassmud.persistence.ItemDAO.RoomItem ri : inv) {
            if (ri.instance.templateId == LOCKPICK_TEMPLATE_ID) {
                lockpick = ri;
                break;
            }
        }
        if (lockpick == null) {
            out.println("You need a lockpick to pick a lock.");
            return true;
        }

        // Always consume the lockpick, regardless of outcome
        DaoProvider.items().deleteInstance(lockpick.instance.instanceId);

        // Roll: success if roll <= (10 + proficiency/2), max 60%
        int proficiency = charLock.getProficiency();
        int successChance = Math.min(10 + proficiency / 2, 60);
        int roll = ThreadLocalRandom.current().nextInt(100) + 1;
        boolean succeeded = roll <= successChance;

        if (succeeded) {
            DaoProvider.rooms().upsertDoor(curRoomId, door.direction, door.toRoomId, "CLOSED", false,
                door.hidden, door.blocked, door.keyItemId, door.description);
            out.println("With a soft click, the " + door.direction + " door unlocks.");
            ClientHandler.roomAnnounceFromActor(curRoomId,
                name + " picks the lock on the " + door.direction + " door.", charId);
        } else {
            out.println("Your lockpick snaps without effect.");
        }

        // Record skill use for proficiency growth
        com.example.tassmud.util.SkillExecution.Result result =
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, lockSkill, charLock, dao, succeeded);

        ctx.handler.sendDebug("Pick Lock: roll=" + roll + " need<=" + successChance
            + " proficiency=" + proficiency + "% succeeded=" + succeeded);
        ctx.handler.sendDebug("  Proficiency improved: " + result.didProficiencyImprove());

        if (result.didProficiencyImprove()) {
            out.println(result.getProficiencyMessage());
        }
        return true;
    }

    boolean handlePoisonWeaponCommand(CommandContext ctx) {
        final int POISON_WEAPON_SKILL_ID = 310;
        final int POISON_VIAL_TEMPLATE_ID = 6;
        final int PLAGUE_VIAL_TEMPLATE_ID = 7;
        final String POISON_WEAPON_EFFECT_ID = "1031";
        final String PLAGUE_WEAPON_EFFECT_ID = "1032";

        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterDAO.CharacterRecord rec = dao.findByName(name);
        if (rec == null) {
            out.println("You must be logged in to use poison weapon.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        Skill skill = DaoProvider.skills().getSkillById(POISON_WEAPON_SKILL_ID);
        if (skill == null) {
            out.println("Poison Weapon skill not found in database.");
            return true;
        }
        CharacterSkill charSkill = DaoProvider.skills().getCharacterSkill(charId, POISON_WEAPON_SKILL_ID);
        if (charSkill == null) {
            out.println("You don't know how to apply contact poison to a weapon.");
            return true;
        }

        // Check cooldown
        com.example.tassmud.util.AbilityCheck.CheckResult check =
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, skill);
        if (check.isFailure()) {
            out.println(check.getFailureMessage());
            return true;
        }

        // Find a coating vial in inventory — plague preferred over poison
        java.util.List<com.example.tassmud.persistence.ItemDAO.RoomItem> inv =
            DaoProvider.items().getItemsByCharacter(charId);
        com.example.tassmud.persistence.ItemDAO.RoomItem vial = null;
        boolean isPlague = false;
        for (com.example.tassmud.persistence.ItemDAO.RoomItem ri : inv) {
            if (ri.instance.templateId == PLAGUE_VIAL_TEMPLATE_ID) {
                vial = ri;
                isPlague = true;
                break;
            }
            if (ri.instance.templateId == POISON_VIAL_TEMPLATE_ID && vial == null) {
                vial = ri;
            }
        }
        if (vial == null) {
            out.println("You need a vial of poison or plague to coat your weapon.");
            return true;
        }

        // Consume the vial
        DaoProvider.items().deleteInstance(vial.instance.instanceId);

        // Apply the appropriate WEAPON_COATING effect to self
        int proficiency = charSkill.getProficiency();
        String coatingEffectId = isPlague ? PLAGUE_WEAPON_EFFECT_ID : POISON_WEAPON_EFFECT_ID;
        com.example.tassmud.effect.EffectRegistry.apply(
            coatingEffectId, charId, charId,
            java.util.Map.of("proficiency", String.valueOf(proficiency)));

        if (isPlague) {
            out.println("You carefully coat your weapon with virulent plague contagion.");
            out.println("Your next critical hit will infect the target with plague.");
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId,
                name + " coats their weapon with a foul, reeking substance.", charId);
        } else {
            out.println("You carefully coat your weapon with contact poison.");
            out.println("Your next critical hit will inject poison into the target.");
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId,
                name + " carefully coats their weapon with a dark liquid.", charId);
        }

        // Record skill use for proficiency growth
        com.example.tassmud.util.SkillExecution.Result result =
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, skill, charSkill, dao, true);
        if (result.didProficiencyImprove()) {
            out.println(result.getProficiencyMessage());
        }
        return true;
    }
}
