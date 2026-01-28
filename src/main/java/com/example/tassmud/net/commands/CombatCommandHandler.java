package com.example.tassmud.net.commands;

import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.ProficiencyCheck;
import com.example.tassmud.util.ProficiencyCheck.Result;
import com.example.tassmud.util.RegenerationService;

/**
 * Handles combat-related commands by delegating to ClientHandler.
 * Commands handled here include: kill, k, attack, fight, combat, flee, cast, kick, bash, heroic strike, infuse, hide, visible, unhide
 */
public class CombatCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(CombatCommandHandler.class);

    private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.COMBAT).stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toUnmodifiableSet());
    
    @Override
    public boolean supports(String commandName) {
        return SUPPORTED_COMMANDS.contains(commandName);
    }
    
    @Override
    public boolean handle(CommandContext ctx) {
        String cmdName = ctx.getCommandName();
        
        switch (cmdName) {
            case "kill":
            case "k":
            case "attack":
            case "fight":
                return handleKillCommand(ctx);
            case "combat":
                return handleCombatCommand(ctx);
            case "flee":
                return handleFleeCommand(ctx);
            case "cast":
                return handleCastCommand(ctx);
            case "kick":
                return handleKickCommand(ctx);
            case "disarm":
                return handleDisarmCommand(ctx);
            case "trip":
                return handleTripCommand(ctx);
            case "bash":
                return handleBashCommand(ctx);
            case "heroic strike":
            case "heroic":
            case "heroicstrike":
                return handleHeroicStrikeCommand(ctx);
            case "taunt":
                return handleTauntCommand(ctx);
            case "feign":
                return handleFeignCommand(ctx);
            case "infuse":
                return handleInfuseCommand(ctx);
            case "hide":
                return handleHideCommand(ctx);
            case "visible":
            case "unhide":
                return handleVisibleCommand(ctx);
            case "sneak":
                return handleSneakCommand(ctx);
            case "backstab":
            case "bs":
                return handleBackstabCommand(ctx);
            case "circle":
                return handleCircleCommand(ctx);
            case "assassinate":
                return handleAssassinateCommand(ctx);
            case "shadow":
            case "ss":
                return handleShadowCommand(ctx);
            default:
                return false;
        }
    }

    private boolean handleCombatCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;

        // COMBAT - show current combat status
        Integer charId = ctx.characterId;
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        CombatManager combatMgr = CombatManager.getInstance();
        if (!combatMgr.isInCombat(charId)) {
            out.println("You are not in combat.");
            return true;
        }
        Combat combat = combatMgr.getCombatForCharacter(charId);
        if (combat == null) {
            out.println("You are not in combat.");
            return true;
        }

        out.println("=== Combat Status ===");
        out.println("Round: " + combat.getCurrentRound());
        out.println("State: " + combat.getState().getDisplayName());
        out.println();
        out.println("Combatants:");
        for (Combatant c : combat.getActiveCombatants()) {
            String indicator = c.isPlayer() ? "[Player] " : "[Mob] ";
            int hpPct = c.getHpMax() > 0 ? (c.getHpCurrent() * 100) / c.getHpMax() : 0;
            String hpBar = ClientHandler.getHpBar(hpPct);
            out.println("  " + indicator + c.getName() + " " + hpBar + " (" + c.getHpCurrent() + "/" + c.getHpMax() + " HP)");
        }
        out.println();
        out.println("Recent combat log:");
        String log = combat.getRecentLog(5);
        if (!log.isEmpty()) {
            for (String logLine : log.split("\n")) {
                out.println("  " + logLine);
            }
        } else {
            out.println("  (no events yet)");
        }
        return true;
    }

    private boolean handleVisibleCommand(CommandContext ctx) {
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

    private boolean handleHideCommand(CommandContext ctx) {
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
        Skill hideSkill = dao.getSkillById(305);
        if (hideSkill == null) {
            out.println("Hide skill not found in database.");
            return true;
        }
        
        // Check if character knows the hide skill
        CharacterSkill charHide = dao.getCharacterSkill(charId, 305);
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
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
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

    private boolean handleSneakCommand(CommandContext ctx) {
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
        CharacterSkill charSneak = dao.getCharacterSkill(charId, 300);
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

    private boolean handleBackstabCommand(CommandContext ctx) {
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
        Skill backstabSkill = dao.getSkillById(301);
        if (backstabSkill == null) {
            out.println("Backstab skill not found in database.");
            return true;
        }
        
        CharacterSkill charBackstab = dao.getCharacterSkill(characterId, 301);
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
        if (dao.isRoomSafe(roomId)) {
            out.println("You cannot fight here. This is a safe area.");
            return true;
        }
        
        // Find the target mob
        MobileDAO mobDao = new MobileDAO();
        java.util.List<Mobile> mobs = mobDao.getMobilesInRoom(roomId);
        if (mobs == null || mobs.isEmpty()) {
            out.println("There are no creatures here to backstab.");
            return true;
        }
        
        String search = targetArg.trim().toLowerCase();
        Mobile matched = null;
        for (Mobile m : mobs) {
            if (m.isDead()) continue;
            if (m.getName() != null && m.getName().toLowerCase().startsWith(search)) { matched = m; break; }
            if (m.getShortDesc() != null && m.getShortDesc().toLowerCase().startsWith(search)) { matched = m; break; }
        }
        if (matched == null) {
            // Try template keyword match
            for (Mobile m : mobs) {
                if (m.isDead()) continue;
                MobileTemplate mt = mobDao.getTemplateById(m.getTemplateId());
                if (mt != null && mt.matchesKeyword(search)) { matched = m; break; }
            }
        }
        
        if (matched == null) {
            out.println("No such target here: " + targetArg);
            return true;
        }
        
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
        CharacterClassDAO classDao = new CharacterClassDAO();
        int playerLevel = rec.currentClassId != null 
            ? classDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
        int mobLevel = Math.max(1, matched.getLevel());
        
        // Roll attack (d20 + level bonus + DEX mod for backstab)
        int dexMod = (attacker.getDex() - 10) / 2;
        int attackRoll = (int)(ThreadLocalRandom.current().nextDouble() * 20) + 1;
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
        dao.tryImproveSkill(characterId, 301, backstabSkill);
        
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
        int baseDamage = (int)(ThreadLocalRandom.current().nextDouble() * (4 + playerLevel)) + 1 + dexMod;
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
            com.example.tassmud.persistence.ItemDAO itemDao = new com.example.tassmud.persistence.ItemDAO();
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
            
            // Award XP using class system
            CharacterClassDAO classDAO = new CharacterClassDAO();
            Integer classId = classDAO.getCharacterCurrentClassId(characterId);
            if (classId != null) {
                int charLevel = classDAO.getCharacterClassLevel(characterId, classId);
                int effectiveLevel = charLevel + (charLevel / 10);
                int levelDiff = Math.max(-10, Math.min(10, effectiveLevel - mobLevel));
                double xpDouble = 100.0 / Math.pow(2, levelDiff);
                int xpAwarded = Math.max(1, (int) Math.round(xpDouble));
                
                boolean leveledUp = classDAO.addXpToCurrentClass(characterId, xpAwarded);
                out.println("You gain " + xpAwarded + " experience.");
                
                if (leveledUp) {
                    int newLevel = classDAO.getCharacterClassLevel(characterId, classId);
                    out.println("You have reached level " + newLevel + "!");
                    final int charId = characterId;
                    classDAO.processLevelUp(characterId, newLevel, msg -> out.println(msg));
                }
            }
            
            // Mark mob dead and remove from world
            matched.die();
            mobDao.deleteInstance(matched.getInstanceId());
            
            return true;
        }
        
        // Mob survived - initiate combat
        cm.initiateCombat(attacker, characterId, matched, roomId);
        return true;
    }

    private boolean handleCircleCommand(CommandContext ctx) {
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
        Skill circleSkill = dao.getSkillById(307);
        if (circleSkill == null) {
            out.println("Circle skill not found in database.");
            return true;
        }
        
        CharacterSkill charCircle = dao.getCharacterSkill(characterId, 307);
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
        CharacterClassDAO classDao = new CharacterClassDAO();
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
        int attackRoll = (int)(ThreadLocalRandom.current().nextDouble() * 20) + 1;
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
        dao.tryImproveSkill(characterId, 307, circleSkill);
        
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
        int baseDamage = (int)(ThreadLocalRandom.current().nextDouble() * (4 + playerLevel)) + 1 + dexMod;
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

    private boolean handleAssassinateCommand(CommandContext ctx) {
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
        Skill assassinateSkill = dao.getSkillById(308);
        if (assassinateSkill == null) {
            out.println("Assassinate skill not found in database.");
            return true;
        }
        
        CharacterSkill charAssassinate = dao.getCharacterSkill(characterId, 308);
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
        if (dao.isRoomSafe(roomId)) {
            out.println("You cannot fight here. This is a safe area.");
            return true;
        }
        
        // Find the target mob
        MobileDAO mobDao = new MobileDAO();
        java.util.List<Mobile> mobs = mobDao.getMobilesInRoom(roomId);
        if (mobs == null || mobs.isEmpty()) {
            out.println("There are no creatures here to assassinate.");
            return true;
        }
        
        String search = targetArg.trim().toLowerCase();
        Mobile matched = null;
        for (Mobile m : mobs) {
            if (m.isDead()) continue;
            if (m.getName() != null && m.getName().toLowerCase().startsWith(search)) { matched = m; break; }
            if (m.getShortDesc() != null && m.getShortDesc().toLowerCase().startsWith(search)) { matched = m; break; }
        }
        if (matched == null) {
            // Try template keyword match
            for (Mobile m : mobs) {
                if (m.isDead()) continue;
                MobileTemplate mt = mobDao.getTemplateById(m.getTemplateId());
                if (mt != null && mt.matchesKeyword(search)) { matched = m; break; }
            }
        }
        
        if (matched == null) {
            out.println("No such target here: " + targetArg);
            return true;
        }
        
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
        
        int proficiency = charAssassinate.getProficiency();
        
        // Calculate hit chance using proficiency-modified opposed check
        CharacterClassDAO classDao = new CharacterClassDAO();
        int playerLevel = rec.currentClassId != null 
            ? classDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
        int mobLevel = Math.max(1, matched.getLevel());
        
        // Roll attack (d20 + level bonus + DEX mod)
        int dexMod = (attacker.getDex() - 10) / 2;
        int attackRoll = (int)(ThreadLocalRandom.current().nextDouble() * 20) + 1;
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
        dao.tryImproveSkill(characterId, 308, assassinateSkill);
        
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
            handleAssassinationKill(matched, mobDao, roomId, characterId, classDao, out);
            return true;
        }
        
        // Regular hit - 4x damage
        int baseDamage = (int)(ThreadLocalRandom.current().nextDouble() * (4 + playerLevel)) + 1 + dexMod;
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
            
            handleAssassinationKill(matched, mobDao, roomId, characterId, classDao, out);
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
    private void handleAssassinationKill(Mobile mob, MobileDAO mobDao, int roomId, 
            int characterId, CharacterClassDAO classDao, PrintWriter out) {
        int mobLevel = Math.max(1, mob.getLevel());
        
        // Calculate gold for corpse
        java.util.Random rand = new java.util.Random();
        int baseGold = mobLevel * 2;
        int bonusGold = (mobLevel * mobLevel) / 5;
        int variance = rand.nextInt(mobLevel + 1);
        long goldAmount = baseGold + bonusGold + variance;
        
        // Create corpse with gold
        com.example.tassmud.persistence.ItemDAO itemDao = new com.example.tassmud.persistence.ItemDAO();
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
        
        // Award XP using class system
        Integer classId = classDao.getCharacterCurrentClassId(characterId);
        if (classId != null) {
            int charLevel = classDao.getCharacterClassLevel(characterId, classId);
            int effectiveLevel = charLevel + (charLevel / 10);
            int levelDiff = Math.max(-10, Math.min(10, effectiveLevel - mobLevel));
            double xpDouble = 100.0 / Math.pow(2, levelDiff);
            int xpAwarded = Math.max(1, (int) Math.round(xpDouble));
            
            boolean leveledUp = classDao.addXpToCurrentClass(characterId, xpAwarded);
            out.println("You gain " + xpAwarded + " experience.");
            
            if (leveledUp) {
                int newLevel = classDao.getCharacterClassLevel(characterId, classId);
                out.println("You have reached level " + newLevel + "!");
                classDao.processLevelUp(characterId, newLevel, msg -> out.println(msg));
            }
        }
        
        // Mark mob dead and remove from world
        mob.die();
        mobDao.deleteInstance(mob.getInstanceId());
    }

    private boolean handleShadowCommand(CommandContext ctx) {
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
        Skill shadowSkill = dao.getSkillById(309);
        if (shadowSkill == null) {
            out.println("Shadow Step skill not found in database.");
            return true;
        }
        
        CharacterSkill charShadow = dao.getCharacterSkill(characterId, 309);
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
    
    private boolean executeShadowSet(CommandContext ctx, CharacterRecord rec, Integer characterId, 
            CharacterDAO dao, PrintWriter out) {
        Integer roomId = rec.currentRoom;
        if (roomId == null) {
            out.println("You are nowhere to mark.");
            return true;
        }
        
        // Can't set shadow point in a PRISON room
        if (dao.hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.PRISON)) {
            out.println("The oppressive magic of this prison prevents you from marking it.");
            return true;
        }
        
        // Store the shadow point as a character flag
        dao.setCharacterFlag(characterId, "shadow_step_room", String.valueOf(roomId));
        
        // Get room name for feedback
        Room room = dao.getRoomById(roomId);
        String roomName = (room != null) ? room.getName() : "this location";
        
        out.println("You attune yourself to the shadows here.");
        out.println("Shadow point set to: " + roomName);
        ClientHandler.roomAnnounceFromActor(roomId, 
            ctx.playerName + " concentrates, attuning to the shadows.", characterId);
        
        return true;
    }
    
    private boolean executeShadowStep(CommandContext ctx, CharacterRecord rec, Integer characterId,
            CharacterDAO dao, PrintWriter out, Skill shadowSkill, CharacterSkill charShadow) {
        Integer currentRoomId = rec.currentRoom;
        if (currentRoomId == null) {
            out.println("You are nowhere to step from.");
            return true;
        }
        
        // Can't shadow step from a PRISON room
        if (dao.hasRoomFlag(currentRoomId, com.example.tassmud.model.RoomFlag.PRISON)) {
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
        Room targetRoom = dao.getRoomById(targetRoomId);
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
        dao.tryImproveSkill(characterId, 309, shadowSkill);
        
        // Execute the shadow step
        Room currentRoom = dao.getRoomById(currentRoomId);
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

    private boolean handleTauntCommand(CommandContext ctx) {
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
        Skill tauntSkill = dao.getSkillById(21);
        if (tauntSkill == null) {
            out.println("Taunt skill not found in database.");
            return true;
        }

        // Check if character knows the taunt skill
        CharacterSkill charTaunt = dao.getCharacterSkill(charId, 21);
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
        CharacterClassDAO tauntClassDao = new CharacterClassDAO();
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
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
        int proficiency = charTaunt.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        String targetName = targetCombatant.getName();
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

    private boolean handleFeignCommand(CommandContext ctx) {
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
        Skill feignSkill = dao.getSkillById(22);
        if (feignSkill == null) {
            out.println("Feign skill not found in database.");
            return true;
        }

        // Check if character knows the feign skill
        CharacterSkill charFeign = dao.getCharacterSkill(charId, 22);
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
        CharacterClassDAO feignClassDao = new CharacterClassDAO();
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
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
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

    private boolean handleInfuseCommand(CommandContext ctx) {
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
        Skill infuseSkill = dao.getSkillById(ARCANE_INFUSION_SKILL_ID);
        if (infuseSkill == null) {
            out.println("Arcane Infusion skill not found in database.");
            return true;
        }
        
        // Check if character knows the arcane infusion skill
        CharacterSkill charInfuse = dao.getCharacterSkill(charId, ARCANE_INFUSION_SKILL_ID);
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
        ItemDAO itemDao = new ItemDAO();
        Long mainHandInstanceId = dao.getCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.getId());
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

    private boolean handleHeroicStrikeCommand(CommandContext ctx) {
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
        Skill heroicSkill = dao.getSkillById(HEROIC_STRIKE_SKILL_ID);
        if (heroicSkill == null) {
            out.println("Heroic Strike skill not found in database.");
            return true;
        }
        
        // Check if character knows the heroic strike skill
        CharacterSkill charHeroic = dao.getCharacterSkill(charId, HEROIC_STRIKE_SKILL_ID);
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
            CharacterClassDAO heroicClassDao = new CharacterClassDAO();
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

            int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
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
    private int calculateMaxWeaponDamage(Integer charId, CharacterDAO dao, Combatant attacker) {
        // Get equipped weapon
        com.example.tassmud.persistence.ItemDAO itemDAO = new com.example.tassmud.persistence.ItemDAO();
        Long mainHandId = dao.getCharacterEquipment(charId, com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
        
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
                        int strMod = (rec.str - 10) / 2;
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
        int strMod = rec != null ? (rec.str - 10) / 2 : 0;
        return Math.max(1, 4 + strMod);
    }
    
    /**
     * Check if equipped weapon is two-handed.
     */
    private boolean isTwoHandedWeapon(Integer charId, CharacterDAO dao, 
            com.example.tassmud.persistence.ItemDAO itemDAO, Long mainHandId) {
        if (mainHandId == null) return false;
        
        // Check if off-hand is empty or has the same item (two-handed weapon)
        Long offHandId = dao.getCharacterEquipment(charId, com.example.tassmud.model.EquipmentSlot.OFF_HAND.getId());
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

    private boolean handleBashCommand(CommandContext ctx) {
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
        Skill bashSkill = dao.getSkillById(11);
        if (bashSkill == null) {
            out.println("Bash skill not found in database.");
            return true;
        }

        // Check if character knows the bash skill
        CharacterSkill charBash = dao.getCharacterSkill(charId, 11);
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
        CharacterClassDAO bashClassDao = new CharacterClassDAO();
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
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
        int proficiency = charBash.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        String targetName = targetCombatant.getName();
        boolean bashSucceeded = roll <= successChance;

        if (bashSucceeded) {
            // Success! Apply STUNNED and SLOWED for 1d6 rounds
            int stunDuration = (int)(ThreadLocalRandom.current().nextDouble() * 6) + 1;
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

    private boolean handleKickCommand(CommandContext ctx) {
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
        Skill kickSkill = dao.getSkillById(10);
        if (kickSkill == null) {
            out.println("Kick skill not found in database.");
            return true;
        }

        // Check if character knows the kick skill
        CharacterSkill charKick = dao.getCharacterSkill(charId, 10);
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
        CharacterClassDAO kickClassDao = new CharacterClassDAO();
        int userLevel = rec.currentClassId != null ? kickClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            Integer targetCharId = targetCombatant.getCharacterId();
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? kickClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use a level based on their HP (TODO: add proper level to Mobile)
            targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
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

    private boolean handleDisarmCommand(CommandContext ctx) {
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
        Skill disarmSkill = dao.getSkillById(12);
        if (disarmSkill == null) {
            out.println("Disarm skill not found in database.");
            return true;
        }

        // Check if character knows the disarm skill
        CharacterSkill charDisarm = dao.getCharacterSkill(charId, 12);
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
        ItemDAO itemDao = new ItemDAO();
        Long targetWeaponInstanceId = null;
        ItemInstance targetWeaponInstance = null;
        ItemTemplate targetWeaponTemplate = null;
        
        if (targetCombatant.isPlayer()) {
            // Player target - check their main hand equipment
            Integer targetCharId = targetCombatant.getCharacterId();
            targetWeaponInstanceId = dao.getCharacterEquipment(targetCharId, EquipmentSlot.MAIN_HAND.getId());
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
                MobileDAO mobileDao = new MobileDAO();
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
        CharacterClassDAO disarmClassDao = new CharacterClassDAO();
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
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
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
                dao.setCharacterEquipment(targetCharId, EquipmentSlot.MAIN_HAND.getId(), null);
                // Also clear off-hand if it's the same item (two-handed weapon)
                Long offHandId = dao.getCharacterEquipment(targetCharId, EquipmentSlot.OFF_HAND.getId());
                if (offHandId != null && offHandId.equals(targetWeaponInstanceId)) {
                    dao.setCharacterEquipment(targetCharId, EquipmentSlot.OFF_HAND.getId(), null);
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

    private boolean handleTripCommand(CommandContext ctx) {
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
        Skill tripSkill = dao.getSkillById(20);
        if (tripSkill == null) {
            out.println("Trip skill not found in database.");
            return true;
        }

        // Check if character knows the trip skill
        CharacterSkill charTrip = dao.getCharacterSkill(charId, 20);
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
        CharacterClassDAO tripClassDao = new CharacterClassDAO();
        int userLevel = rec.currentClassId != null ? tripClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
        int targetLevel;
        if (targetCombatant.isPlayer()) {
            CharacterRecord targetRec = dao.getCharacterById(targetCharId);
            targetLevel = targetRec != null && targetRec.currentClassId != null 
                ? tripClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
        } else {
            // For mobiles, use a level based on their HP (TODO: add proper level to Mobile)
            targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
        }

        // Perform opposed check with proficiency (1d100 vs success chance)
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
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

    private boolean handleCastCommand(CommandContext ctx) {
        String name = ctx.playerName;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);
        
        // CAST <spell_name> [target]
        // Spells with EXPLICIT_MOB_TARGET or ITEM targets require a target name
        if (rec == null) {
            out.println("You must be logged in to cast spells.");
            return true;
        }
        String castArgs = ctx.getArgs();
        if (castArgs == null || castArgs.trim().isEmpty()) {
            out.println("Usage: cast <spell_name> [target]");
            out.println("Example: cast magic missile");
            return true;
        }
        
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get all spells the character knows
        java.util.List<CharacterSpell> knownSpells = dao.getAllCharacterSpells(charId);
        if (knownSpells.isEmpty()) {
            out.println("You don't know any spells.");
            return true;
        }
        
        // Parse: spell name and optional target
        // We need to find the spell first, then remaining args are target
        String argsLower = castArgs.toLowerCase().trim();
        
        // Build list of spell definitions for spells we know
        java.util.List<Spell> knownSpellDefs = new java.util.ArrayList<>();
        for (CharacterSpell cs : knownSpells) {
            Spell spellDef = dao.getSpellById(cs.getSpellId());
            if (spellDef != null) {
                knownSpellDefs.add(spellDef);
            }
        }
        
        if (knownSpellDefs.isEmpty()) {
            out.println("You don't know any spells.");
            return true;
        }
        
        // Smart spell matching - try to find the best match
        // We need to figure out which part is spell name vs target
        Spell matchedSpell = null;
        String targetArg = null;
        
        // Strategy: try progressively shorter prefixes of args as spell name
        String[] words = castArgs.trim().split("\\s+");
        
        // Try matching from longest to shortest prefix
        for (int wordCount = words.length; wordCount >= 1 && matchedSpell == null; wordCount--) {
            StringBuilder spellNameBuilder = new StringBuilder();
            for (int i = 0; i < wordCount; i++) {
                if (i > 0) spellNameBuilder.append(" ");
                spellNameBuilder.append(words[i]);
            }
            String spellSearch = spellNameBuilder.toString().toLowerCase();
            
            // Priority 1: Exact name match
            for (Spell sp : knownSpellDefs) {
                if (sp.getName().equalsIgnoreCase(spellSearch)) {
                    matchedSpell = sp;
                    // Remaining words are target
                    if (wordCount < words.length) {
                        StringBuilder targetBuilder = new StringBuilder();
                        for (int i = wordCount; i < words.length; i++) {
                            if (i > wordCount) targetBuilder.append(" ");
                            targetBuilder.append(words[i]);
                        }
                        targetArg = targetBuilder.toString();
                    }
                    break;
                }
            }
            
            // Priority 2: Name starts with search (prefix match)
            if (matchedSpell == null) {
                for (Spell sp : knownSpellDefs) {
                    if (sp.getName().toLowerCase().startsWith(spellSearch)) {
                        matchedSpell = sp;
                        if (wordCount < words.length) {
                            StringBuilder targetBuilder = new StringBuilder();
                            for (int i = wordCount; i < words.length; i++) {
                                if (i > wordCount) targetBuilder.append(" ");
                                targetBuilder.append(words[i]);
                            }
                            targetArg = targetBuilder.toString();
                        }
                        break;
                    }
                }
            }
            
            // Priority 3: Any word in name starts with search
            if (matchedSpell == null) {
                for (Spell sp : knownSpellDefs) {
                    String[] spellWords = sp.getName().toLowerCase().split("\\s+");
                    for (String sw : spellWords) {
                        if (sw.startsWith(spellSearch) || sw.equals(spellSearch)) {
                            matchedSpell = sp;
                            if (wordCount < words.length) {
                                StringBuilder targetBuilder = new StringBuilder();
                                for (int i = wordCount; i < words.length; i++) {
                                    if (i > wordCount) targetBuilder.append(" ");
                                    targetBuilder.append(words[i]);
                                }
                                targetArg = targetBuilder.toString();
                            }
                            break;
                        }
                    }
                    if (matchedSpell != null) break;
                }
            }
            
            // Priority 4: Name contains search substring
            if (matchedSpell == null) {
                for (Spell sp : knownSpellDefs) {
                    if (sp.getName().toLowerCase().contains(spellSearch)) {
                        matchedSpell = sp;
                        if (wordCount < words.length) {
                            StringBuilder targetBuilder = new StringBuilder();
                            for (int i = wordCount; i < words.length; i++) {
                                if (i > wordCount) targetBuilder.append(" ");
                                targetBuilder.append(words[i]);
                            }
                            targetArg = targetBuilder.toString();
                        }
                        break;
                    }
                }
            }
        }
        
        if (matchedSpell == null) {
            out.println("You don't know a spell matching '" + castArgs + "'.");
            out.println("Type 'spells' to see your known spells.");
            return true;
        }
        
        // Check cooldown and combat traits before allowing cast
        com.example.tassmud.util.AbilityCheck.CheckResult spellCheck = 
            com.example.tassmud.util.AbilityCheck.canPlayerCastSpell(name, charId, matchedSpell);
        if (spellCheck.isFailure()) {
            out.println(spellCheck.getFailureMessage());
            return true;
        }
        
        // Check for curse effect - may cause spell to fail
        if (com.example.tassmud.effect.CursedEffect.checkCurseFails(charId)) {
            out.println("\u001B[35mThe curse disrupts your concentration! Your spell fizzles.\u001B[0m");
            // Notify the room
            ClientHandler.broadcastRoomMessage(ctx.currentRoomId, 
                rec.name + " begins to cast " + matchedSpell.getName() + " but the spell fizzles!");
            return true;
        }
        
        // Calculate MP cost: spell level (default), or spell-specific cost if defined
        int spellLevel = matchedSpell.getLevel();
        int mpCost = matchedSpell.getMpCost() > 0 ? matchedSpell.getMpCost() : spellLevel;
        
        // Check if player has enough MP (checked before casting, deducted only on success)
        if (rec.mpCur < mpCost) {
            out.println("You don't have enough mana to cast " + matchedSpell.getName() + ". (Need " + mpCost + " MP, have " + rec.mpCur + ")");
            return true;
        }
        
        // Check if spell requires a target
        Spell.SpellTarget targetType = matchedSpell.getTarget();
        boolean needsTarget = (targetType == Spell.SpellTarget.EXPLICIT_MOB_TARGET || 
                                targetType == Spell.SpellTarget.ITEM);
        
        if (needsTarget && (targetArg == null || targetArg.trim().isEmpty())) {
            String targetDesc = targetType == Spell.SpellTarget.ITEM ? "an item" : "a target";
            out.println(matchedSpell.getName() + " requires " + targetDesc + ".");
            out.println("Usage: cast " + matchedSpell.getName().toLowerCase() + " <target>");
            return true;
        }
        
        // Build the cast message based on target type (MP cost shown after successful cast)
        StringBuilder castMsg = new StringBuilder();
        castMsg.append("You begin casting ").append(matchedSpell.getName());
        
        switch (targetType) {
            case SELF:
                castMsg.append(" on yourself.");
                break;
            case CURRENT_ENEMY:
                castMsg.append(" at your current enemy.");
                break;
            case EXPLICIT_MOB_TARGET:
                castMsg.append(" at ").append(targetArg).append(".");
                break;
            case ITEM:
                castMsg.append(" on ").append(targetArg).append(".");
                break;
            case ALL_ENEMIES:
                castMsg.append(", targeting all enemies!");
                break;
            case ALL_ALLIES:
                castMsg.append(", targeting all allies.");
                break;
            case EVERYONE:
                castMsg.append(", targeting everyone in the room!");
                break;
        }
        
        out.println(castMsg.toString());

        // Dispatch spell effects via the EffectRegistry
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        Spell.SpellTarget ttype = matchedSpell.getTarget();
        if (ttype == Spell.SpellTarget.ALL_ALLIES) {
            for (ClientHandler s : ClientHandler.sessions) {
                if (s == ctx.handler) continue;
                Integer otherCharId = ClientHandler.getCharacterIdByName(s.playerName);
                if (otherCharId == null) continue;
                Integer otherRoom = s.currentRoomId;
                if (otherRoom != null && otherRoom.equals(ctx.handler.currentRoomId)) {
                    targets.add(otherCharId);
                }
            }
            targets.add(charId);
        } else if (ttype == Spell.SpellTarget.SELF) {
            targets.add(charId);
        } else if (ttype == Spell.SpellTarget.CURRENT_ENEMY) {
            // Get current combat target - pick first valid enemy
            Combat activeCombat = CombatManager.getInstance().getCombatForCharacter(charId);
            if (activeCombat != null) {
                Combatant casterCombatant = activeCombat.findByCharacterId(charId);
                if (casterCombatant != null) {
                    java.util.List<Combatant> enemies = activeCombat.getValidTargets(casterCombatant);
                    if (!enemies.isEmpty()) {
                        Combatant target = enemies.getFirst();
                        // For players, use characterId; for mobs, use negative instanceId as a convention
                        if (target.isPlayer()) {
                            targets.add(target.getCharacterId());
                        } else if (target.getMobile() != null) {
                            // Use negative instance ID as target ID for mobs (convention for effect system)
                            targets.add(-(int)target.getMobile().getInstanceId());
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                out.println("You don't have a current combat target.");
                return true;
            }
        } else if (ttype == Spell.SpellTarget.EXPLICIT_MOB_TARGET) {
            // Blindness check - can't target explicitly if blind (but current enemy fallback still works)
            if (targetArg != null && !targetArg.trim().isEmpty() && 
                com.example.tassmud.effect.EffectRegistry.isBlind(charId)) {
                out.println("You can't see to target " + targetArg + "!");
                return true;
            }
            
            // Try to resolve target - first check for explicit arg, then fall back to current enemy
            Integer targetId = null;
            if (targetArg != null && !targetArg.trim().isEmpty()) {
                // First try player name
                targetId = dao.getCharacterIdByName(targetArg);
                // If not a player, try mob in room
                if (targetId == null) {
                    MobileDAO mobDao = new MobileDAO();
                    java.util.List<Mobile> mobs = mobDao.getMobilesInRoom(ctx.currentRoomId);
                    String search = targetArg.trim().toLowerCase();
                    for (Mobile m : mobs) {
                        if (m.isDead()) continue;
                        if (m.getName() != null && m.getName().toLowerCase().startsWith(search)) {
                            // Use negative instance ID as target ID for mobs
                            targetId = -(int)m.getInstanceId();
                            break;
                        }
                        if (m.getShortDesc() != null && m.getShortDesc().toLowerCase().startsWith(search)) {
                            targetId = -(int)m.getInstanceId();
                            break;
                        }
                        MobileTemplate mt = mobDao.getTemplateById(m.getTemplateId());
                        if (mt != null && mt.matchesKeyword(search)) {
                            targetId = -(int)m.getInstanceId();
                            break;
                        }
                    }
                }
            } else {
                // No explicit target - fall back to current combat enemy
                Combat activeCombat = CombatManager.getInstance().getCombatForCharacter(charId);
                if (activeCombat != null) {
                    Combatant casterCombatant = activeCombat.findByCharacterId(charId);
                    if (casterCombatant != null) {
                        java.util.List<Combatant> enemies = activeCombat.getValidTargets(casterCombatant);
                        if (!enemies.isEmpty()) {
                            Combatant target = enemies.getFirst();
                            if (target.isPlayer()) {
                                targetId = target.getCharacterId();
                            } else if (target.getMobile() != null) {
                                targetId = -(int)target.getMobile().getInstanceId();
                            }
                        }
                    }
                }
            }
            if (targetId != null) {
                targets.add(targetId);
            }
        } else {
            // Generic fallback for other target types
            if (targetArg != null && !targetArg.trim().isEmpty()) {
                Integer targetId = dao.getCharacterIdByName(targetArg);
                if (targetId != null) targets.add(targetId);
            }
        }

        if (targets.isEmpty()) {
            out.println("No valid targets found for that spell.");
        } else {
            // Determine caster proficiency for this spell and send as extraParams
            com.example.tassmud.model.CharacterSpell charSpell = dao.getCharacterSpell(charId, matchedSpell.getId());
            java.util.Map<String,String> extraParams = new java.util.HashMap<>();
            int proficiency = 1;
            if (charSpell != null) {
                proficiency = charSpell.getProficiency();
                extraParams.put("proficiency", String.valueOf(proficiency));
            }

            // Compute scaled cooldown based on spell-level and effect-level cooldowns (use the largest)
            int finalCooldown = (int) Math.round(matchedSpell.getCooldown());
            for (String effId : matchedSpell.getEffectIds()) {
                com.example.tassmud.effect.EffectDefinition def = com.example.tassmud.effect.EffectRegistry.getDefinition(effId);
                if (def == null) continue;
                double effectCd = def.getCooldownSeconds();
                if (effectCd <= 0) continue;
                int cdSecs;
                if (def.getProficiencyImpact().contains(com.example.tassmud.effect.EffectDefinition.ProficiencyImpact.COOLDOWN)) {
                    cdSecs = com.example.tassmud.util.AbilityCheck.computeScaledCooldown(effectCd, proficiency);
                } else {
                    cdSecs = (int) Math.round(effectCd);
                }
                if (cdSecs > finalCooldown) finalCooldown = cdSecs;
            }

            // Apply the computed cooldown for this spell
            com.example.tassmud.util.AbilityCheck.applyPlayerSpellCooldown(name, matchedSpell, finalCooldown);

            // Dispatch to spell handler via SpellRegistry
            Combat activeCombat = CombatManager.getInstance().getCombatForCharacter(charId);
            com.example.tassmud.spell.SpellContext spellCtx = new com.example.tassmud.spell.SpellContext(
                ctx, activeCombat, charId, matchedSpell, targets, extraParams);
            
            com.example.tassmud.spell.SpellHandler handler = com.example.tassmud.spell.SpellRegistry.get(matchedSpell.getName());
            boolean castSuccess = false;
            if (handler != null) {
                // Invoke the registered spell handler
                castSuccess = handler.cast(charId, targetArg, spellCtx);
                if (!castSuccess) {
                    logger.debug("[cast] Spell handler for '{}' returned false", matchedSpell.getName());
                }
            } else {
                // No handler registered - fall back to direct effect application
                logger.debug("[cast] No handler for '{}', applying effects directly", matchedSpell.getName());
                java.util.List<String> effectedNames = new java.util.ArrayList<>();
                for (String effId : matchedSpell.getEffectIds()) {
                    com.example.tassmud.effect.EffectDefinition def = com.example.tassmud.effect.EffectRegistry.getDefinition(effId);
                    for (Integer tgt : targets) {
                        com.example.tassmud.effect.EffectInstance inst = com.example.tassmud.effect.EffectRegistry.apply(effId, charId, tgt, extraParams);
                        if (inst != null && def != null) {
                            CharacterDAO.CharacterRecord recT = dao.findById(tgt);
                            if (recT != null) effectedNames.add(recT.name + "(" + def.getName() + ")");
                            castSuccess = true; // At least one effect applied
                        }
                    }
                }
                if (!effectedNames.isEmpty()) out.println("Effects applied: " + String.join(", ", effectedNames));
            }
            
            // Deduct MP only on successful cast
            if (castSuccess) {
                if (!dao.deductManaPoints(name, mpCost)) {
                    logger.warn("[cast] Failed to deduct {} MP from {} after successful spell", mpCost, name);
                } else {
                    out.println("Spell completed! (-" + mpCost + " MP)");
                }
            } else {
                out.println("The spell fizzles.");
            }
        }
        
        return true;
    }

    private boolean handleFleeCommand(CommandContext ctx) {
        String name = ctx.playerName;
        Integer currentRoomId = ctx.currentRoomId;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);

        // FLEE - innate combat skill to escape from combat
        Integer charId = ctx.characterId;
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You are not in combat.");
            return true;
        }

        Combatant userCombatant = activeCombat.findByCharacterId(charId);
        if (userCombatant == null) {
            out.println("Combat error: could not find your combatant.");
            return true;
        }

        // Get current room and available exits
        Room curRoom = dao.getRoomById(currentRoomId);
        if (curRoom == null) {
            out.println("You can't flee - you don't know where you are!");
            return true;
        }

        // Build list of available exits
        java.util.List<String> availableExits = new java.util.ArrayList<>();
        java.util.Map<String, Integer> exitRooms = new java.util.HashMap<>();
        if (curRoom.getExitN() != null) { availableExits.add("north"); exitRooms.put("north", curRoom.getExitN()); }
        if (curRoom.getExitE() != null) { availableExits.add("east"); exitRooms.put("east", curRoom.getExitE()); }
        if (curRoom.getExitS() != null) { availableExits.add("south"); exitRooms.put("south", curRoom.getExitS()); }
        if (curRoom.getExitW() != null) { availableExits.add("west"); exitRooms.put("west", curRoom.getExitW()); }
        if (curRoom.getExitU() != null) { availableExits.add("up"); exitRooms.put("up", curRoom.getExitU()); }
        if (curRoom.getExitD() != null) { availableExits.add("down"); exitRooms.put("down", curRoom.getExitD()); }

        if (availableExits.isEmpty()) {
            out.println("There's nowhere to flee to!");
            return true;
        }

        // Get user's level for opposed check
        CharacterClassDAO fleeClassDao = new CharacterClassDAO();
        int userLevel = rec.currentClassId != null 
            ? fleeClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;

        // Find the highest level opponent for opposed check
        int opponentLevel = 1;
        for (Combatant c : activeCombat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                int cLevel;
                if (c.isPlayer()) {
                    Integer cCharId = c.getCharacterId();
                    CharacterRecord cRec = dao.getCharacterById(cCharId);
                    cLevel = cRec != null && cRec.currentClassId != null 
                        ? fleeClassDao.getCharacterClassLevel(cCharId, cRec.currentClassId) : 1;
                } else if (c.getMobile() != null) {
                    cLevel = c.getMobile().getLevel();
                } else {
                    cLevel = 1;
                }
                if (cLevel > opponentLevel) {
                    opponentLevel = cLevel;
                }
            }
        }

        // Perform opposed check at 100% proficiency (innate skill)
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1;
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(
            userLevel, opponentLevel, 100);

        boolean fleeSucceeded = roll <= successChance;

        if (!fleeSucceeded) {
            // Failed to flee
            out.println("You try to flee but your opponents block your escape!");
            if (!ctx.handler.gmInvisible) {
                ClientHandler.roomAnnounce(currentRoomId, name + " tries to flee but fails!", charId, true);
            }
            return true;
        }

        // Flee succeeded - pick a random exit
        String fleeDirection = availableExits.get((int)(ThreadLocalRandom.current().nextDouble() * availableExits.size()));
        Integer destRoomId = exitRooms.get(fleeDirection);

        // Check movement cost
        int moveCost = dao.getMoveCostForRoom(destRoomId);

        if (rec.mvCur < moveCost) {
            // Insufficient MV - fall prone instead of escaping
            userCombatant.setProne();
            out.println("You break free but stumble and fall prone from exhaustion!");
            if (!ctx.handler.gmInvisible) {
                ClientHandler.roomAnnounce(currentRoomId, name + " tries to flee but collapses from exhaustion!", charId, true);
            }
            return true;
        }

        // Deduct movement points
        if (!dao.deductMovementPoints(name, moveCost)) {
            // Shouldn't happen, but handle it
            userCombatant.setProne();
            out.println("You break free but stumble and fall prone from exhaustion!");
            if (!ctx.handler.gmInvisible) {
                ClientHandler.roomAnnounce(currentRoomId, name + " tries to flee but collapses from exhaustion!", charId, true);
            }
            return true;
        }

        // Remove from combat
        activeCombat.removeCombatant(userCombatant);

        // Announce departure
        out.println("You flee " + fleeDirection + "!");
        if (!ctx.handler.gmInvisible) {
            ClientHandler.roomAnnounce(currentRoomId, name + " flees " + fleeDirection + "!", charId, true);
        }

        // Move to new room
        boolean moved = dao.updateCharacterRoom(name, destRoomId);
        if (!moved) {
            out.println("Something strange happened during your escape.");
            return true;
        }

        // Update cached room and show new location
        rec = dao.findByName(name);
        ctx.handler.currentRoomId = rec != null ? rec.currentRoom : null;
        Room newRoom = dao.getRoomById(destRoomId);

        // Announce arrival
        if (!ctx.handler.gmInvisible) {
            ClientHandler.roomAnnounce(destRoomId, ClientHandler.makeArrivalMessage(name, fleeDirection), charId, true);
        }

        if (newRoom != null) {
            MovementCommandHandler.showRoom(newRoom, destRoomId, ctx);
        }

        // Combat will check shouldEnd() on next tick and end if no opponents remain
        return true;
    }

    private boolean handleKillCommand(CommandContext ctx) {
        String name = ctx.playerName;
        Integer characterId = ctx.characterId;
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        CharacterRecord rec = dao.findByName(name);
        // Initiate combat against a mobile in the current room
        if (rec == null) { out.println("You must be logged in to attack."); return true; }
        String targetArg = ctx.getArgs();
        if (targetArg == null || targetArg.trim().isEmpty()) {
            out.println("Usage: kill <target>");
            return true;
        }
        if (characterId == null) {
            out.println("Unable to determine your character."); return true;
        }

        // Check stance allows initiating combat
        Stance s = RegenerationService.getInstance().getPlayerStance(characterId);
        if (!s.canInitiateCombat()) {
            out.println("You must be standing to initiate combat.");
            return true;
        }
        
        // Check blindness - can't target for new combat if blind
        if (com.example.tassmud.effect.EffectRegistry.isBlind(characterId)) {
            out.println("You can't see to target " + targetArg + "!");
            return true;
        }

        Integer roomId = rec.currentRoom;
        if (roomId == null) { out.println("You are nowhere to attack from."); return true; }
        
        // Check SAFE room flag - no combat allowed
        if (dao.isRoomSafe(roomId)) {
            out.println("You cannot fight here. This is a safe area.");
            return true;
        }
        
        MobileDAO mobDao = new MobileDAO();
        java.util.List<Mobile> mobs = mobDao.getMobilesInRoom(roomId);
        if (mobs == null || mobs.isEmpty()) { out.println("There are no creatures here to attack."); return true; }

        String search = targetArg.trim().toLowerCase();
        Mobile matched = null;
        for (Mobile m : mobs) {
            if (m.isDead()) continue;
            if (m.getName() != null && m.getName().toLowerCase().startsWith(search)) { matched = m; break; }
            if (m.getShortDesc() != null && m.getShortDesc().toLowerCase().startsWith(search)) { matched = m; break; }
        }
        if (matched == null) {
            // Try template keyword match
            for (Mobile m : mobs) {
                if (m.isDead()) continue;
                MobileTemplate mt = mobDao.getTemplateById(m.getTemplateId());
                if (mt != null && mt.matchesKeyword(search)) { matched = m; break; }
            }
        }

        if (matched == null) { out.println("No such target here: " + targetArg); return true; }

        // Build attacker and initiate combat
        GameCharacter attacker = ClientHandler.buildCharacterForCombat(rec, characterId);
        if (attacker == null) { out.println("Failed to prepare you for combat."); return true; }
        CombatManager cm = CombatManager.getInstance();
        cm.initiateCombat(attacker, characterId, matched, roomId);
        // Refresh record after initiating combat
        rec = dao.findByName(name);
        return true;
    }
}
