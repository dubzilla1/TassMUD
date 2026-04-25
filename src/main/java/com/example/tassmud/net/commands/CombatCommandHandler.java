package com.example.tassmud.net.commands;


import com.example.tassmud.persistence.DaoProvider;
import java.io.PrintWriter;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.RegenerationService;

/**
 * Handles combat-related commands. Delegates rogue skills to {@link RogueSkillHandler},
 * melee skills to {@link MeleeSkillHandler}, and spell casting to {@link SpellCastHandler}.
 */
public class CombatCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(CombatCommandHandler.class);

    private final RogueSkillHandler rogueSkills = new RogueSkillHandler();
    private final MeleeSkillHandler meleeSkills = new MeleeSkillHandler();
    private final SpellCastHandler spellCasting = new SpellCastHandler();
    private final PaladinSkillHandler paladinSkills = new PaladinSkillHandler();

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
                return spellCasting.handleCastCommand(ctx);
            case "kick":
                return meleeSkills.handleKickCommand(ctx);
            case "disarm":
                return meleeSkills.handleDisarmCommand(ctx);
            case "trip":
                return meleeSkills.handleTripCommand(ctx);
            case "bash":
                return meleeSkills.handleBashCommand(ctx);
            case "heroic strike":
            case "heroic":
            case "heroicstrike":
                return meleeSkills.handleHeroicStrikeCommand(ctx);
            case "taunt":
                return meleeSkills.handleTauntCommand(ctx);
            case "feign":
                return meleeSkills.handleFeignCommand(ctx);
            case "infuse":
                return meleeSkills.handleInfuseCommand(ctx);
            case "flurry":
                return meleeSkills.handleFlurryCommand(ctx);
            case "stun":
                return meleeSkills.handleStunningFistCommand(ctx);
            case "hide":
                return rogueSkills.handleHideCommand(ctx);
            case "visible":
            case "unhide":
                return rogueSkills.handleVisibleCommand(ctx);
            case "sneak":
                return rogueSkills.handleSneakCommand(ctx);
            case "backstab":
            case "bs":
                return rogueSkills.handleBackstabCommand(ctx);
            case "circle":
                return rogueSkills.handleCircleCommand(ctx);
            case "assassinate":
                return rogueSkills.handleAssassinateCommand(ctx);
            case "shadow":
            case "ss":
                return rogueSkills.handleShadowCommand(ctx);
            case "pick":
                return rogueSkills.handlePickCommand(ctx);
            case "poison":
                return rogueSkills.handlePoisonWeaponCommand(ctx);
            case "lay":
            case "loh":
                return paladinSkills.handleLayOnHandsCommand(ctx);
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

        // Cannot flee while stunned
        if (userCombatant.isStunned()) {
            out.println("You are too stunned to flee!");
            return true;
        }

        // Get current room and available exits
        Room curRoom = DaoProvider.rooms().getRoomById(currentRoomId);
        if (curRoom == null) {
            out.println("You can't flee - you don't know where you are!");
            return true;
        }

        // Build list of available exits
        java.util.List<String> availableExits = new java.util.ArrayList<>();
        java.util.Map<String, Integer> exitRooms = new java.util.HashMap<>();
        for (var entry : curRoom.getExits().entrySet()) {
            availableExits.add(entry.getKey().fullName());
            exitRooms.put(entry.getKey().fullName(), entry.getValue());
        }

        if (availableExits.isEmpty()) {
            out.println("There's nowhere to flee to!");
            return true;
        }

        // Get user's level for opposed check
        CharacterClassDAO fleeClassDao = DaoProvider.classes();
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
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
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
        String fleeDirection = availableExits.get(ThreadLocalRandom.current().nextInt(availableExits.size()));
        Integer destRoomId = exitRooms.get(fleeDirection);

        // Check movement cost
        int moveCost = DaoProvider.rooms().getMoveCostForRoom(destRoomId);

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
        Room newRoom = DaoProvider.rooms().getRoomById(destRoomId);

        // Aura room-change: update recipient sets for sanctuary-like auras
        com.example.tassmud.effect.AuraManager.getInstance()
                .onPlayerRoomChange(charId, currentRoomId, destRoomId);

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
        if (DaoProvider.rooms().isRoomSafe(roomId)) {
            out.println("You cannot fight here. This is a safe area.");
            return true;
        }
        
        Mobile matched = com.example.tassmud.util.MobileMatchingService.findInRoom(roomId, targetArg);
        if (matched == null) {
            // Distinguish empty room from no match
            java.util.List<Mobile> mobs = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(roomId);
            if (mobs == null || mobs.isEmpty()) {
                out.println("There are no creatures here to attack.");
            } else {
                out.println("No such target here: " + targetArg);
            }
            return true;
        }

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
