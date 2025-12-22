package com.example.tassmud.net.commands;

import java.io.PrintWriter;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.example.tassmud.util.RegenerationService;

/**
 * Handles combat-related commands by delegating to ClientHandler.
 * Commands handled here include: kill, k, attack, fight, combat, flee, cast, kick, bash, heroic strike, infuse, hide, visible, unhide
 */
public class CombatCommandHandler implements CommandHandler {

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
            case "bash":
                return handleBashCommand(ctx);
            case "heroic strike":
            case "heroic":
            case "heroicstrike":
                return handleHeroicStrikeCommand(ctx);
            case "infuse":
                return handleInfuseCommand(ctx);
            case "hide":
                return handleHideCommand(ctx);
            case "visible":
            case "unhide":
                return handleVisibleCommand(ctx);
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
        int roll = (int)(Math.random() * 100) + 1;
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

        // HEROIC STRIKE - applies Heroism effect (guaranteed crits) to self
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
        
        // Check cooldown and combat traits using unified check
        com.example.tassmud.util.AbilityCheck.CheckResult heroicCheck = 
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, heroicSkill);
        if (heroicCheck.isFailure()) {
            out.println(heroicCheck.getFailureMessage());
            return true;
        }
        
        // Get the active combat (skill requires combat)
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(charId);
        if (activeCombat == null) {
            out.println("You must be in combat to use Heroic Strike.");
            return true;
        }
        
        // Apply the skill's effects to self
        int proficiency = charHeroic.getProficiency();
        com.example.tassmud.util.SkillExecution.EffectResult effectResult = 
            com.example.tassmud.util.SkillExecution.applySkillEffectsToSelf(heroicSkill, charId, proficiency);
        
        if (effectResult.hasAppliedEffects()) {
            out.println("You channel your heroic spirit! " + effectResult.getSummary() + " takes effect!");
            // Broadcast to room (excluding self)
            for (ClientHandler ch : ClientHandler.charIdToSession.values()) {
                if (ch != ctx.handler && ch.currentRoomId != null && ch.currentRoomId.equals(ctx.currentRoomId)) {
                    ch.out.println(name + " is filled with heroic determination!");
                }
            }
        } else {
            out.println("You attempt to summon your heroic spirit, but nothing happens.");
        }
        
        // Apply cooldown and check proficiency growth (skill always "succeeds" if effects apply)
        boolean heroicSucceeded = effectResult.hasAppliedEffects();
        com.example.tassmud.util.SkillExecution.Result heroicResult = 
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, heroicSkill, charHeroic, dao, heroicSucceeded);
        
        if (heroicResult.didProficiencyImprove()) {
            out.println(heroicResult.getProficiencyMessage());
        }
        return true;
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
        int roll = (int)(Math.random() * 100) + 1;
        int proficiency = charBash.getProficiency();
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

        String targetName = targetCombatant.getName();
        boolean bashSucceeded = roll <= successChance;

        if (bashSucceeded) {
            // Success! Apply STUNNED and SLOWED for 1d6 rounds
            int stunDuration = (int)(Math.random() * 6) + 1;
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
        int roll = (int)(Math.random() * 100) + 1;
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
        
        // Calculate MP cost: 2^spellLevel
        int spellLevel = matchedSpell.getLevel();
        int mpCost = (int) Math.pow(2, spellLevel);
        
        // Check if player has enough MP
        if (rec.mpCur < mpCost) {
            out.println("You don't have enough mana to cast " + matchedSpell.getName() + ". (Need " + mpCost + " MP, have " + rec.mpCur + ")");
            return true;
        }
        
        // Deduct MP cost
        if (!dao.deductManaPoints(name, mpCost)) {
            out.println("Failed to expend mana for the spell.");
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
        
        // Build the cast message based on target type
        StringBuilder castMsg = new StringBuilder();
        castMsg.append("You cast ").append(matchedSpell.getName()).append(" (-").append(mpCost).append(" MP)");
        
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
                castMsg.append(", striking all enemies!");
                break;
            case ALL_ALLIES:
                castMsg.append(", affecting all allies.");
                break;
            case EVERYONE:
                castMsg.append(", affecting everyone in the room!");
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
        } else {
            if (targetArg != null && !targetArg.trim().isEmpty()) {
                Integer targetId = dao.getCharacterIdByName(targetArg);
                if (targetId != null) targets.add(targetId);
            }
        }

        if (targets.isEmpty()) {
            out.println("No valid targets found for that spell.");
        } else {
            java.util.List<String> effectedNames = new java.util.ArrayList<>();
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

            for (String effId : matchedSpell.getEffectIds()) {
                com.example.tassmud.effect.EffectDefinition def = com.example.tassmud.effect.EffectRegistry.getDefinition(effId);
                for (Integer tgt : targets) {
                    com.example.tassmud.effect.EffectInstance inst = com.example.tassmud.effect.EffectRegistry.apply(effId, charId, tgt, extraParams);
                    if (inst != null && def != null) {
                        // Notify target if online
                        ClientHandler targetSession = ClientHandler.charIdToSession.get(tgt);
                        if (targetSession != null) {
                            targetSession.sendRaw(def.getName() + " from " + name + " takes effect.");
                        }
                        CharacterDAO.CharacterRecord recT = dao.findById(tgt);
                        if (recT != null) effectedNames.add(recT.name + "(" + def.getName() + ")");
                    }
                }
            }
            if (!effectedNames.isEmpty()) out.println("Effects applied: " + String.join(", ", effectedNames));
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
        int roll = (int)(Math.random() * 100) + 1;
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
        String fleeDirection = availableExits.get((int)(Math.random() * availableExits.size()));
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

        Integer roomId = rec.currentRoom;
        if (roomId == null) { out.println("You are nowhere to attack from."); return true; }
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
