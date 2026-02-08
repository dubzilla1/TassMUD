package com.example.tassmud.net.commands;

import com.example.tassmud.persistence.DaoProvider;
import java.io.PrintWriter;
import java.util.List;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegate handler for GM character-manipulation commands extracted from GmCommandHandler.
 * This is NOT a standalone CommandHandler — GmCommandHandler calls these methods directly.
 */
class GmCharacterHandler {

    private static final Logger log = LoggerFactory.getLogger(GmCharacterHandler.class);

    boolean handleRestoreCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // GM-only: RESTORE [character] - restore HP/MP/MV to full
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use restore.");
            return true;
        }
        String restoreArgs = ctx.getArgs();
        String targetName = (restoreArgs == null || restoreArgs.trim().isEmpty()) 
            ? name : restoreArgs.trim();
        
        CharacterDAO.CharacterRecord targetRec = dao.findByName(targetName);
        if (targetRec == null) {
            out.println("Character '" + targetName + "' not found.");
            return true;
        }
        
        // Restore to full
        dao.saveCharacterStateByName(targetName, 
            targetRec.hpMax, targetRec.mpMax, targetRec.mvMax, 
            targetRec.currentRoom);
        
        // If target is in combat, also update their combatant
        Integer targetCharId = dao.getCharacterIdByName(targetName);
        if (targetCharId != null) {
            Combat combat = CombatManager.getInstance().getCombatForCharacter(targetCharId);
            if (combat != null) {
                Combatant combatant = combat.findByCharacterId(targetCharId);
                if (combatant != null) {
                    GameCharacter c = combatant.getAsCharacter();
                    if (c != null) {
                        c.setHpCur(c.getHpMax());
                        c.setMpCur(c.getMpMax());
                        c.setMvCur(c.getMvMax());
                    }
                }
            }
        }
        
        if (targetName.equalsIgnoreCase(name)) {
            out.println("You feel fully restored!");
        } else {
            out.println("You restore " + targetRec.name + " to full health.");
            // Notify target if online
            ClientHandler targetHandler = ClientHandler.nameToSession.get(targetName.toLowerCase());
            if (targetHandler != null) {
                targetHandler.sendRaw("You feel a surge of divine energy and are fully restored!");
            }
        }
        return true;
    }

    boolean handlePromoteCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // GM-only: PROMOTE <char> [level_target]
        // Levels up a character. If level_target specified, levels them up to that level.
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use promote.");
            return true;
        }
        String promoteArgs = ctx.getArgs();
        if (promoteArgs == null || promoteArgs.trim().isEmpty()) {
            out.println("Usage: PROMOTE <character> [level_target]");
            out.println("  Levels up a character once, or to the specified level.");
            return true;
        }
        
        String[] promoteParts = promoteArgs.trim().split("\\s+");
        String promoteTargetName = promoteParts[0];
        Integer targetLevel = null;
        
        if (promoteParts.length > 1) {
            try {
                targetLevel = Integer.parseInt(promoteParts[1]);
            } catch (NumberFormatException e) {
                out.println("Invalid level target: " + promoteParts[1]);
                return true;
            }
        }
        
        // Find character
        Integer promoteCharId = dao.getCharacterIdByName(promoteTargetName);
        if (promoteCharId == null) {
            out.println("Character '" + promoteTargetName + "' not found.");
            return true;
        }
        
        CharacterClassDAO classDAO = DaoProvider.classes();
        Integer promoteClassId = classDAO.getCharacterCurrentClassId(promoteCharId);
        if (promoteClassId == null) {
            out.println(promoteTargetName + " has no class assigned.");
            return true;
        }
        
        int currentLevel = classDAO.getCharacterClassLevel(promoteCharId, promoteClassId);
        
        // Validate target level if specified
        if (targetLevel != null) {
            if (targetLevel < 1 || targetLevel > 60) {
                out.println("Level target must be between 1 and 60.");
                return true;
            }
            if (targetLevel <= currentLevel) {
                out.println(promoteTargetName + " is already level " + currentLevel + 
                    ". Cannot promote to level " + targetLevel + " (demotion not implemented).");
                return true;
            }
        } else {
            // Default: promote by one level
            targetLevel = currentLevel + 1;
            if (targetLevel > 60) {
                out.println(promoteTargetName + " is already at maximum level.");
                return true;
            }
        }
        
        // Get target handler for messaging
        ClientHandler promoteTargetHandler = ClientHandler.nameToSession.get(promoteTargetName.toLowerCase());
        java.util.function.Consumer<String> msgCallback = null;
        if (promoteTargetHandler != null) {
            final ClientHandler handler = promoteTargetHandler;
            msgCallback = msg -> handler.sendRaw(msg);
        }
        
        // Loop: set XP to 1000 and trigger level-up until we reach target level
        int levelsGained = 0;
        while (currentLevel < targetLevel) {
            // Set XP to trigger level-up
            boolean leveledUp = classDAO.addXpToCurrentClass(promoteCharId, CharacterClass.XP_PER_LEVEL);
            if (!leveledUp) {
                out.println("Failed to level up " + promoteTargetName + " (unexpected error).");
                break;
            }
            
            currentLevel++;
            levelsGained++;
            
            // Notify target of level-up
            if (promoteTargetHandler != null) {
                promoteTargetHandler.sendRaw("You have reached level " + currentLevel + "!");
            }
            
            // Process level-up benefits
            classDAO.processLevelUp(promoteCharId, currentLevel, msgCallback);
        }
        
        if (levelsGained > 0) {
            out.println("Promoted " + promoteTargetName + " by " + levelsGained + 
                " level(s) to level " + currentLevel + ".");
        }
        return true;
    }

    boolean handleCspellCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao; 
        // GM-only: CSPELL <character> <spell_id> [amount]  OR  CSPELL LIST
        // Grants a spell to a character at a given proficiency (default 100%)
        if (!ensureGm(ctx)) return true;
        String cspellArgs = ctx.getArgs();
        if (cspellArgs == null || cspellArgs.trim().isEmpty()) {
            out.println("Usage: CSPELL <character> <spell_id> [amount]");
            out.println("       CSPELL LIST - List all available spells");
            out.println("  Grants a spell to a character at the specified proficiency.");
            out.println("  Amount defaults to 100 (mastered) if not specified.");
            return true;
        }
        String[] cspellParts = cspellArgs.trim().split("\\s+");
        
        // Handle CSPELL LIST
        if (cspellParts[0].equalsIgnoreCase("list")) {
            java.util.List<Spell> allSpells = DaoProvider.spells().getAllSpells();
            if (allSpells.isEmpty()) {
                out.println("No spells found in the database.");
                return true;
            }
            
            // Sort alphabetically by name
            allSpells.sort((sp1, sp2) -> sp1.getName().compareToIgnoreCase(sp2.getName()));
            
            out.println();
            out.println("===================================================================");
            out.println("                       AVAILABLE SPELLS");
            out.println("===================================================================");
            out.println();
            
            // Format: "SpellName (ID)" in 3 columns
            java.util.List<String> spellDisplays = new java.util.ArrayList<>();
            for (Spell sp : allSpells) {
                spellDisplays.add(String.format("%s (%d)", sp.getName(), sp.getId()));
            }
            
            // Print in rows of 3
            for (int i = 0; i < spellDisplays.size(); i += 3) {
                String c1 = spellDisplays.get(i);
                String c2 = (i + 1 < spellDisplays.size()) ? spellDisplays.get(i + 1) : "";
                String c3 = (i + 2 < spellDisplays.size()) ? spellDisplays.get(i + 2) : "";
                out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
            }
            
            out.println();
            out.println("-------------------------------------------------------------------");
            out.println(String.format("  Total: %d spells", allSpells.size()));
            out.println("===================================================================");
            out.println();
            return true;
        }
        
        if (cspellParts.length < 2) {
            out.println("Usage: CSPELL <character> <spell_id> [amount]");
            out.println("       CSPELL LIST - List all available spells");
            return true;
        }
        String targetSpellCharName = cspellParts[0];
        int spellId;
        try {
            spellId = Integer.parseInt(cspellParts[1]);
        } catch (NumberFormatException e) {
            out.println("Invalid spell ID. Must be a number.");
            return true;
        }
        int spellAmount = 100; // default to mastered
        if (cspellParts.length >= 3) {
            try {
                spellAmount = Integer.parseInt(cspellParts[2]);
                if (spellAmount < 1) spellAmount = 1;
                if (spellAmount > 100) spellAmount = 100;
            } catch (NumberFormatException e) {
                out.println("Invalid amount. Must be a number between 1 and 100.");
                return true;
            }
        }
        // Look up target character
        Integer targetSpellCharId = dao.getCharacterIdByName(targetSpellCharName);
        if (targetSpellCharId == null) {
            out.println("Character '" + targetSpellCharName + "' not found.");
            return true;
        }
        // Check if spell exists
        Spell spellDef = DaoProvider.spells().getSpellById(spellId);
        String spellName = spellDef != null ? spellDef.getName() : "Spell #" + spellId;
        // Set the spell level (will create if doesn't exist, or update if it does)
        boolean spellOk = DaoProvider.spells().setCharacterSpellLevel(targetSpellCharId, spellId, spellAmount);
        if (spellOk) {
            String spellProfStr = spellAmount >= 100 ? "MASTERED" : spellAmount + "%";
            out.println("Granted " + targetSpellCharName + " the spell '" + spellName + "' at " + spellProfStr + ".");
        } else {
            out.println("Failed to grant spell to " + targetSpellCharName + ".");
        }
        return true;
    }

    boolean handleCskillCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao; 
        // GM-only: CSKILL <character> <skill_id> [amount]  OR  CSKILL LIST
        // Grants a skill to a character at a given proficiency (default 100%)

        if (!ensureGm(ctx)) return true;
        String cskillArgs = ctx.getArgs();
        if (cskillArgs == null || cskillArgs.trim().isEmpty()) {
            out.println("Usage: CSKILL <character> <skill_id> [amount]");
            out.println("       CSKILL LIST - List all available skills");
            out.println("  Grants a skill to a character at the specified proficiency.");
            out.println("  Amount defaults to 100 (mastered) if not specified.");
            return true;
        }
        String[] cskillParts = cskillArgs.trim().split("\\s+");
        
        // Handle CSKILL LIST
        if (cskillParts[0].equalsIgnoreCase("list")) {
            java.util.List<Skill> allSkills = DaoProvider.skills().getAllSkills();
            if (allSkills.isEmpty()) {
                out.println("No skills found in the database.");
                return true;
            }
            
            out.println();
            out.println("===================================================================");
            out.println("                       AVAILABLE SKILLS");
            out.println("===================================================================");
            out.println();
            
            // Format: "SkillName (ID)" in 3 columns
            java.util.List<String> skillDisplays = new java.util.ArrayList<>();
            for (Skill sk : allSkills) {
                skillDisplays.add(String.format("%s (%d)", sk.getName(), sk.getId()));
            }
            
            // Print in rows of 3
            for (int i = 0; i < skillDisplays.size(); i += 3) {
                String c1 = skillDisplays.get(i);
                String c2 = (i + 1 < skillDisplays.size()) ? skillDisplays.get(i + 1) : "";
                String c3 = (i + 2 < skillDisplays.size()) ? skillDisplays.get(i + 2) : "";
                out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
            }
            
            out.println();
            out.println("-------------------------------------------------------------------");
            out.println(String.format("  Total: %d skills", allSkills.size()));
            out.println("===================================================================");
            out.println();
            return true;
        }
        
        if (cskillParts.length < 2) {
            out.println("Usage: CSKILL <character> <skill_id> [amount]");
            out.println("       CSKILL LIST - List all available skills");
            return true;
        }
        String targetCharName = cskillParts[0];
        int skillId;
        try {
            skillId = Integer.parseInt(cskillParts[1]);
        } catch (NumberFormatException e) {
            out.println("Invalid skill ID. Must be a number.");
            return true;
        }
        int amount = 100; // default to mastered
        if (cskillParts.length >= 3) {
            try {
                amount = Integer.parseInt(cskillParts[2]);
                if (amount < 1) amount = 1;
                if (amount > 100) amount = 100;
            } catch (NumberFormatException e) {
                out.println("Invalid amount. Must be a number between 1 and 100.");
                return true;
            }
        }
        // Look up target character
        Integer targetCharId = dao.getCharacterIdByName(targetCharName);
        if (targetCharId == null) {
            out.println("Character '" + targetCharName + "' not found.");
            return true;
        }
        // Check if skill exists
        Skill skillDef = DaoProvider.skills().getSkillById(skillId);
        String skillName = skillDef != null ? skillDef.getName() : "Skill #" + skillId;
        // Set the skill level (will create if doesn't exist, or update if it does)
        boolean ok = DaoProvider.skills().setCharacterSkillLevel(targetCharId, skillId, amount);
        if (ok) {
            String profStr = amount >= 100 ? "MASTERED" : amount + "%";
            out.println("Granted " + targetCharName + " the skill '" + skillName + "' at " + profStr + ".");
        } else {
            out.println("Failed to grant skill to " + targetCharName + ".");
        }
        return true;
    }

    boolean handleCsetCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;       
        
        // GM-only: CSET <char> <attribute> <value>  OR  CSET LIST
        if (!ensureGm(ctx)) return true;
        String csetArgs = ctx.getArgs();
        if (csetArgs == null || csetArgs.trim().isEmpty()) {
            out.println("Usage: CSET <character> <attribute> <value>");
            out.println("       CSET LIST - Show all settable attributes");
            return true;
        }
        String[] csetParts = csetArgs.trim().split("\\s+", 3);
        
        // Handle CSET LIST
        if (csetParts[0].equalsIgnoreCase("list")) {
            out.println();
            out.println("═══════════════════════════════════════════════════════════════");
            out.println("                    SETTABLE ATTRIBUTES");
            out.println("═══════════════════════════════════════════════════════════════");
            out.println();
            out.println("  [ VITALS ]");
            out.println("    hp, hpmax, mp, mpmax, mv, mvmax");
            out.println();
            out.println("  [ BASE ABILITIES ]");
            out.println("    str, dex, con, int, wis, cha");
            out.println();
            out.println("  [ TRAINED ABILITIES ]");
            out.println("    trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha");
            out.println("    (aliases: tstr, tdex, tcon, tint, twis, tcha)");
            out.println();
            out.println("  [ SAVES ]");
            out.println("    armor (ac), fortitude (fort), reflex (ref), will");
            out.println();
            out.println("  [ EQUIPMENT BONUSES ]");
            out.println("    armor_equip, fort_equip, reflex_equip, will_equip");
            out.println();
            out.println("  [ CLASS & PROGRESSION ]");
            out.println("    class, level, xp");
            out.println();
            out.println("  [ OTHER ]");
            out.println("    age, room, autoflee, talents, gold, description");
            out.println();
            out.println("═══════════════════════════════════════════════════════════════");
            out.println();
            return true;
        }
        
        if (csetParts.length < 3) {
            out.println("Usage: CSET <character> <attribute> <value>");
            out.println("       CSET LIST - Show all settable attributes");
            return true;
        }
        
        String targetName = csetParts[0];
        String attrName = csetParts[1];
        String attrValue = csetParts[2];
        
        // Find the character
        Integer targetCharId = dao.getCharacterIdByName(targetName);
        if (targetCharId == null) {
            out.println("Character '" + targetName + "' not found.");
            return true;
        }
        
        // Set the attribute
        String result = dao.setCharacterAttribute(targetCharId, attrName, attrValue);
        out.println(targetName + ": " + result);
        
        // Notify the target if they're online and it's not self
        if (!targetName.equalsIgnoreCase(ctx.playerName)) {
            ClientHandler targetHandler = ClientHandler.nameToSession.get(targetName.toLowerCase());
            if (targetHandler != null) {
                targetHandler.sendRaw("[GM] Your " + attrName + " has been modified.");
            }
        }
        return true;
    }

    boolean handleCflagCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        
        // GM-only: CFLAG SET <char> <flag> <value>  OR  CFLAG CHECK <char> <flag>
        if (!ensureGm(ctx)) return true;
        String cfArgs2b = ctx.getArgs();
        if (cfArgs2b == null || cfArgs2b.trim().isEmpty()) {
            out.println("Usage: CFLAG SET <char> <flag> <value>   |   CFLAG CHECK <char> <flag>");
            return true;
        }
        String[] parts = cfArgs2b.trim().split("\\s+");
        String verb = parts.length > 0 ? parts[0].toLowerCase() : "";
        if (verb.equals("set")) {
            if (parts.length < 4) { out.println("Usage: CFLAG SET <char> <flag> <value>"); return true; }
            String targetName2 = parts[1];
            String flagName2 = parts[2];
            // join remaining parts as the value to allow spaces
            StringBuilder sbv = new StringBuilder();
            for (int i = 3; i < parts.length; i++) { if (sbv.length() > 0) sbv.append(' '); sbv.append(parts[i]); }
            String flagVal2 = sbv.toString();
            boolean ok3 = dao.setCharacterFlagByName(targetName2, flagName2, flagVal2);
            if (ok3) out.println("Flag set for " + targetName2 + ": " + flagName2 + " = " + flagVal2);
            else out.println("Failed to set flag for " + targetName2 + ".");
            return true;
        } else if (verb.equals("check") || verb.equals("get")) {
            if (parts.length < 3) { out.println("Usage: CFLAG CHECK <char> <flag>"); return true; }
            String targetName3 = parts[1];
            String flagName3 = parts[2];
            String v = dao.getCharacterFlagByName(targetName3, flagName3);
            if (v == null || v.isEmpty()) {
                out.println("No flag set for " + targetName3 + " (" + flagName3 + ")");
            } else {
                out.println("Flag for " + targetName3 + ": " + flagName3 + " = " + v);
            }
            return true;
        } else {
            out.println("Usage: CFLAG SET <char> <flag> <value>   |   CFLAG CHECK <char> <flag>");
            return true;
        }
    }

    private static boolean ensureGm(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        if (rec == null) { out.println("No character record found."); return false; }
        try {
            if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                out.println("You do not have permission to use GM commands.");
                return false;
            }
        } catch (Exception ignored) {
            out.println("Permission check failed.");
            return false;
        }
        return true;
    }
}
