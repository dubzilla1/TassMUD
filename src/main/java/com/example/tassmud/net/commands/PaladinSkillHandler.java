package com.example.tassmud.net.commands;

import com.example.tassmud.persistence.DaoProvider;
import java.io.PrintWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Skill;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;

/**
 * Delegate handler for Paladin skill commands, extracted from CombatCommandHandler.
 * Methods are package-private so CombatCommandHandler can call them directly.
 */
class PaladinSkillHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaladinSkillHandler.class);

    /**
     * LAY ON HANDS / LOH
     *
     * Usage: lay [target]
     *
     * Without a target: restores the Paladin's own HP to full.
     * With a target: heals the target for the Paladin's max HP, capped at the target's own max HP.
     * Requires the Lay on Hands skill (id 311). Cooldown: 600 seconds.
     */
    boolean handleLayOnHandsCommand(CommandContext ctx) {
        final int LAY_ON_HANDS_SKILL_ID = 311;

        String name = ctx.playerName;
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = dao.findByName(name);
        if (rec == null) {
            out.println("You must be logged in to use Lay on Hands.");
            return true;
        }
        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }

        Skill skill = DaoProvider.skills().getSkillById(LAY_ON_HANDS_SKILL_ID);
        if (skill == null) {
            out.println("Lay on Hands skill not found in database.");
            return true;
        }
        CharacterSkill charSkill = DaoProvider.skills().getCharacterSkill(charId, LAY_ON_HANDS_SKILL_ID);
        if (charSkill == null) {
            out.println("You have not been granted the divine gift of Lay on Hands.");
            return true;
        }

        // Check cooldown
        com.example.tassmud.util.AbilityCheck.CheckResult check =
            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, skill);
        if (check.isFailure()) {
            out.println(check.getFailureMessage());
            return true;
        }

        String args = ctx.getArgs();
        boolean targetSelf = (args == null || args.trim().isEmpty());
        String targetName = targetSelf ? name : args.trim();

        // --- SELF HEAL ---
        if (targetSelf || targetName.equalsIgnoreCase(name)) {
            dao.saveCharacterStateByName(name, rec.hpMax, rec.mpCur, rec.mvCur, rec.currentRoom);
            // Update in-combat combatant if active
            updateCombatantHp(charId, rec.hpMax);
            out.println("You channel holy light through your hands — a warm radiance suffuses your body.");
            out.println("Your wounds close. You are fully restored!");
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId,
                name + " places their hands on themselves. A golden light flares — their wounds seal shut.", charId);
        } else {
            // --- TARGET HEAL ---
            // Target must be in the same room
            CharacterRecord targetRec = dao.findByName(targetName);
            if (targetRec == null) {
                out.println("No player named '" + targetName + "' found.");
                return true;
            }
            if (!ctx.currentRoomId.equals(targetRec.currentRoom)) {
                out.println(targetName + " is not in this room.");
                return true;
            }
            Integer targetCharId = dao.getCharacterIdByName(targetName);
            if (targetCharId == null) {
                out.println("Unable to resolve character ID for " + targetName + ".");
                return true;
            }

            // Heal for paladin's hpMax, capped at target's hpMax
            int healAmount = rec.hpMax;
            int newHp = Math.min(targetRec.hpCur + healAmount, targetRec.hpMax);
            dao.saveCharacterStateByName(targetName, newHp, targetRec.mpCur, targetRec.mvCur, targetRec.currentRoom);
            updateCombatantHp(targetCharId, newHp);

            int actualHealed = newHp - targetRec.hpCur;
            out.println("You lay your hands on " + targetRec.name + ", channeling holy power into their wounds.");
            out.println("You heal " + targetRec.name + " for " + actualHealed + " HP.");
            ClientHandler.sendToCharacter(targetCharId,
                name + " places glowing hands upon you. Holy warmth floods through your wounds. (" + actualHealed + " HP healed)");
            ClientHandler.roomAnnounceFromActor(ctx.currentRoomId,
                name + " places glowing hands upon " + targetRec.name + ". A golden light flares, sealing their wounds.", charId);
        }

        // Send updated prompt to paladin
        ClientHandler.sendPromptToCharacter(charId);

        // Record skill use for proficiency growth
        com.example.tassmud.util.SkillExecution.Result result =
            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                name, charId, skill, charSkill, dao, true);
        if (result.didProficiencyImprove()) {
            out.println(result.getProficiencyMessage());
        }

        return true;
    }

    /** Update the HP of a combatant in active combat to match the saved value. */
    private void updateCombatantHp(Integer charId, int newHp) {
        if (charId == null) return;
        Combat combat = CombatManager.getInstance().getCombatForCharacter(charId);
        if (combat == null) return;
        Combatant combatant = combat.findByCharacterId(charId);
        if (combatant == null) return;
        GameCharacter c = combatant.getAsCharacter();
        if (c != null) {
            c.setHpCur(newHp);
        }
    }
}
