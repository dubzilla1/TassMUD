package com.example.tassmud.net.commands;

import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.persistence.CharacterDAO;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles SYSTEM category commands: save, quit, prompt, motd, train, autoloot, autogold, autosac, autoflee, autojunk.
* This class extracts system-related command logic from ClientHandler to reduce
 * method size and improve maintainability.
 */
public class SystemCommandHandler implements CommandHandler {
    
    private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.SYSTEM).stream()
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
            case "save":
                return handleSaveCommand(ctx);
            case "quit":
                return handleQuitCommand(ctx);
            case "prompt":
                return handlePromptCommand(ctx);
            case "motd":
                return handleMotdCommand(ctx);
            case "train":
                return handleTrainCommand(ctx);
            case "autoloot":
                return handleAutolootCommand(ctx);
            case "autogold":
                return handleAutogoldCommand(ctx);
            case "autosac":
                return handleAutosacCommand(ctx);
            case "autojunk":
                return handleAutojunkCommand(ctx);
            case "autoflee":
                return handleAutofleeCommand(ctx);
            default:
                return false;
        }
    }

    private boolean handleAutofleeCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        String autofleeArgs = ctx.getArgs();
        Integer charId = ctx.characterId;
        
        // AUTOFLEE - Set/view automatic flee threshold
        if (rec == null) {
            out.println("You must be logged in to use autoflee.");
            return true;
        }
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        if (charId == null) {
            out.println("Unable to find your character.");
            return true;
        }
        
        if (autofleeArgs == null || autofleeArgs.trim().isEmpty()) {
            // Display current autoflee value
            int currentAutoflee = dao.getAutoflee(charId);
            if (currentAutoflee <= 0) {
                out.println("Autoflee is disabled (set to 0).");
            } else {
                out.println("Autoflee is set to " + currentAutoflee + "% HP.");
            }
        } else {
            // Set new autoflee value
            try {
                int newValue = Integer.parseInt(autofleeArgs.trim());
                if (newValue < 0 || newValue > 100) {
                    out.println("Autoflee must be between 0 and 100.");
                    return true;
                }
                boolean success = dao.setAutoflee(charId, newValue);
                if (success) {
                    if (newValue == 0) {
                        out.println("Autoflee disabled.");
                    } else {
                        out.println("Autoflee set to " + newValue + "% HP.");
                        out.println("You will automatically attempt to flee when HP drops below " + newValue + "%.");
                    }
                } else {
                    out.println("Failed to set autoflee.");
                }
            } catch (NumberFormatException e) {
                out.println("Usage: autoflee <0-100>");
            }
        }
        return true;
    }

    private boolean handleSaveCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // Persist mutable character state for this player
        if (rec == null) { out.println("No character record found."); return true; }
        boolean ok = dao.saveCharacterStateByName(name, rec.hpCur, rec.mpCur, rec.mvCur, rec.currentRoom);
        if (ok) out.println("Character saved."); else out.println("Failed to save character.");
        return true;
    }

    private boolean handleQuitCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        out.println("Goodbye!");
        try {
            ctx.handler.getSocket().close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return true;
    }

    private boolean handlePromptCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        String promptArgs = ctx.getArgs();
        if (promptArgs == null || promptArgs.trim().isEmpty()) {
            out.println("Prompt: " + ctx.handler.getPromptFormat());
        } else {
            // set new prompt format for this session
            ctx.handler.setPromptFormat(promptArgs);
            out.println("Prompt set.");
        }
        return true;
    }

    private boolean handleMotdCommand(CommandContext ctx) {
        CharacterDAO dao = ctx.dao;
        PrintWriter out = ctx.out;
        String margs = ctx.getArgs();
        String name = ctx.playerName;

        if (margs == null || margs.trim().isEmpty()) {
            String motd = dao.getSetting("motd");
            if (motd == null || motd.trim().isEmpty()) out.println("No MOTD is set.");
            else {
                out.println("--- Message of the Day ---");
                String[] motdLines = motd.split("\\r?\\n");
                for (String ml : motdLines) out.println(ml);
                out.println("--- End of MOTD ---");
            }
        } else {
            // GM-only: motd set <message>
            String[] ma = margs.trim().split("\\s+", 2);
            String verb = ma[0].toLowerCase();
            if (verb.equals("set")) {
                if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to set the MOTD."); return true; }
                String val = ma.length > 1 ? ma[1] : "";
                // support literal "\n" sequences for multi-line MOTD
                try {
                    if (val != null) {
                        val = val.replace("\\r\\n", "\n").replace("\\n", "\n");
                    }
                } catch (Exception ignored) {}
                boolean ok = dao.setSetting("motd", val == null ? "" : val);
                if (ok) out.println("MOTD updated (\n sequences allowed for newlines)."); else out.println("Failed to update MOTD.");
            } else if (verb.equals("clear") || verb.equals("remove")) {
                if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to clear the MOTD."); return true; }
                boolean ok = dao.setSetting("motd", "");
                if (ok) out.println("MOTD cleared."); else out.println("Failed to clear MOTD.");
            } else {
                out.println("Usage: motd [set <message>|clear]");
            }
        }
        
        return true;
    }

    private boolean handleTrainCommand(CommandContext ctx) {
        // TRAIN [ability|skill|spell] <name> - Spend talent points
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        String trainArgs = ctx.getArgs();
        
        if (rec == null) {
            out.println("You must be logged in to train.");
            return true;
        }
        Integer trainCharId = dao.getCharacterIdByName(name);
        if (trainCharId == null) {
            out.println("Failed to find your character.");
            return true;
        }
        
        int talentPoints = dao.getTalentPoints(trainCharId);
        
        // No args: show status
        if (trainArgs == null || trainArgs.trim().isEmpty()) {
            // Display talent points, ability scores, and trainable skills/spells
            StringBuilder trainInfo = new StringBuilder();
            trainInfo.append("\n===================================================================\n");
            trainInfo.append("  TRAINING STATUS\n");
            trainInfo.append("===================================================================\n");
            trainInfo.append(String.format("  Talent Points Available: %d\n", talentPoints));
            trainInfo.append("-------------------------------------------------------------------\n");
            trainInfo.append("  [ ABILITY SCORES ] (base + trained = total)\n");
            
            // Calculate modifiers for display
            int strTotal = rec.getStrTotal();
            int dexTotal = rec.getDexTotal();
            int conTotal = rec.getConTotal();
            int intTotal = rec.getIntTotal();
            int wisTotal = rec.getWisTotal();
            int chaTotal = rec.getChaTotal();
            
            trainInfo.append(String.format("  STR: %2d + %d = %2d (%+d)    Cost: %s\n",
                rec.str, rec.trainedStr, strTotal, (strTotal - 10) / 2, ClientHandler.formatTrainCost(strTotal)));
            trainInfo.append(String.format("  DEX: %2d + %d = %2d (%+d)    Cost: %s\n",
                rec.dex, rec.trainedDex, dexTotal, (dexTotal - 10) / 2, ClientHandler.formatTrainCost(dexTotal)));
            trainInfo.append(String.format("  CON: %2d + %d = %2d (%+d)    Cost: %s\n",
                rec.con, rec.trainedCon, conTotal, (conTotal - 10) / 2, ClientHandler.formatTrainCost(conTotal)));
            trainInfo.append(String.format("  INT: %2d + %d = %2d (%+d)    Cost: %s\n",
                rec.intel, rec.trainedInt, intTotal, (intTotal - 10) / 2, ClientHandler.formatTrainCost(intTotal)));
            trainInfo.append(String.format("  WIS: %2d + %d = %2d (%+d)    Cost: %s\n",
                rec.wis, rec.trainedWis, wisTotal, (wisTotal - 10) / 2, ClientHandler.formatTrainCost(wisTotal)));
            trainInfo.append(String.format("  CHA: %2d + %d = %2d (%+d)    Cost: %s\n",
                rec.cha, rec.trainedCha, chaTotal, (chaTotal - 10) / 2, ClientHandler.formatTrainCost(chaTotal)));
            
            // Show trainable skills (under 80%)
            java.util.List<CharacterSkill> trainSkills = dao.getAllCharacterSkills(trainCharId);
            java.util.List<String> trainableSkills = new java.util.ArrayList<>();
            for (CharacterSkill cs : trainSkills) {
                if (cs.getProficiency() < 80) {
                    Skill skillDef = dao.getSkillById(cs.getSkillId());
                    String skillName = skillDef != null ? skillDef.getName() : "Skill #" + cs.getSkillId();
                    trainableSkills.add(String.format("%-22s %3d%%", skillName, cs.getProficiency()));
                }
            }
            if (!trainableSkills.isEmpty()) {
                trainInfo.append("-------------------------------------------------------------------\n");
                trainInfo.append("  [ TRAINABLE SKILLS ] (< 80%: costs 1 point for +5%)\n");
                java.util.Collections.sort(trainableSkills, String.CASE_INSENSITIVE_ORDER);
                for (String s : trainableSkills) {
                    trainInfo.append("  ").append(s).append("\n");
                }
            }
            
            // Show trainable spells (under 80%)
            java.util.List<CharacterSpell> trainSpells = dao.getAllCharacterSpells(trainCharId);
            java.util.List<String> trainableSpells = new java.util.ArrayList<>();
            for (CharacterSpell cs : trainSpells) {
                if (cs.getProficiency() < 80) {
                    Spell spellDef = dao.getSpellById(cs.getSpellId());
                    String spellName = spellDef != null ? spellDef.getName() : "Spell #" + cs.getSpellId();
                    trainableSpells.add(String.format("%-22s %3d%%", spellName, cs.getProficiency()));
                }
            }
            if (!trainableSpells.isEmpty()) {
                trainInfo.append("-------------------------------------------------------------------\n");
                trainInfo.append("  [ TRAINABLE SPELLS ] (< 80%: costs 1 point for +5%)\n");
                java.util.Collections.sort(trainableSpells, String.CASE_INSENSITIVE_ORDER);
                for (String s : trainableSpells) {
                    trainInfo.append("  ").append(s).append("\n");
                }
            }
            
            trainInfo.append("===================================================================\n");
            trainInfo.append("  Usage: TRAIN ABILITY <str|dex|con|int|wis|cha>\n");
            trainInfo.append("         TRAIN SKILL <skill_name>\n");
            trainInfo.append("         TRAIN SPELL <spell_name>\n");
            trainInfo.append("===================================================================\n");
            out.print(trainInfo.toString());
            return true;
        }
        
        // Parse args: train ability|skill|spell <name>
        String[] trainParts = trainArgs.trim().split("\\s+", 2);
        if (trainParts.length < 2) {
            out.println("Usage: TRAIN ABILITY <str|dex|con|int|wis|cha>");
            out.println("       TRAIN SKILL <skill_name>");
            out.println("       TRAIN SPELL <spell_name>");
            return true;
        }
        
        String trainType = trainParts[0].toLowerCase();
        String trainTarget = trainParts[1].trim();
        
        if (talentPoints < 1) {
            out.println("You have no talent points to spend.");
            return true;
        }
        
        switch (trainType) {
            case "ability":
            case "stat":
            case "attr":
            case "attribute": {
                // Train an ability score
                String abilityName = trainTarget.toLowerCase();
                int currentTotal = 0;
                String displayName = null;
                switch (abilityName) {
                    case "str": case "strength": 
                        currentTotal = rec.getStrTotal(); displayName = "Strength"; break;
                    case "dex": case "dexterity": 
                        currentTotal = rec.getDexTotal(); displayName = "Dexterity"; break;
                    case "con": case "constitution": 
                        currentTotal = rec.getConTotal(); displayName = "Constitution"; break;
                    case "int": case "intel": case "intelligence": 
                        currentTotal = rec.getIntTotal(); displayName = "Intelligence"; break;
                    case "wis": case "wisdom": 
                        currentTotal = rec.getWisTotal(); displayName = "Wisdom"; break;
                    case "cha": case "charisma": 
                        currentTotal = rec.getChaTotal(); displayName = "Charisma"; break;
                }
                
                if (displayName == null) {
                    out.println("Unknown ability: " + trainTarget);
                    out.println("Valid abilities: STR, DEX, CON, INT, WIS, CHA");
                    break;
                }
                
                int cost = CharacterDAO.getAbilityTrainingCost(currentTotal);
                if (cost < 0) {
                    out.println("Your " + displayName + " is already at maximum (20). You cannot train it further with talent points.");
                    break;
                }
                if (talentPoints < cost) {
                    out.println("Training " + displayName + " from " + currentTotal + " to " + (currentTotal + 1) + " costs " + cost + " talent points.");
                    out.println("You only have " + talentPoints + " talent point" + (talentPoints == 1 ? "" : "s") + ".");
                    break;
                }
                
                // Deduct points and increment ability
                dao.setTalentPoints(trainCharId, talentPoints - cost);
                dao.incrementTrainedAbility(trainCharId, abilityName);
                
                int newTotal = currentTotal + 1;
                int newMod = (newTotal - 10) / 2;
                out.println("You train your " + displayName + "!");
                out.println(displayName + " increased from " + currentTotal + " to " + newTotal + " (" + (newMod >= 0 ? "+" : "") + newMod + " modifier).");
                out.println("Spent " + cost + " talent point" + (cost == 1 ? "" : "s") + ". Remaining: " + (talentPoints - cost));
                break;
            }
            case "skill": {
                // Train a skill
                Skill targetSkill = null;
                CharacterSkill charSkill = null;
                java.util.List<CharacterSkill> allSkills = dao.getAllCharacterSkills(trainCharId);
                for (CharacterSkill cs : allSkills) {
                    Skill def = dao.getSkillById(cs.getSkillId());
                    if (def != null && def.getName().equalsIgnoreCase(trainTarget)) {
                        targetSkill = def;
                        charSkill = cs;
                        break;
                    }
                }
                
                // Try partial match if exact match failed
                if (targetSkill == null) {
                    String targetLower = trainTarget.toLowerCase();
                    for (CharacterSkill cs : allSkills) {
                        Skill def = dao.getSkillById(cs.getSkillId());
                        if (def != null && def.getName().toLowerCase().startsWith(targetLower)) {
                            targetSkill = def;
                            charSkill = cs;
                            break;
                        }
                    }
                }
                
                if (targetSkill == null || charSkill == null) {
                    out.println("You don't know a skill called '" + trainTarget + "'.");
                    break;
                }
                
                int currentProf = charSkill.getProficiency();
                if (currentProf >= 80) {
                    out.println(targetSkill.getName() + " is already at " + currentProf + "%. Skills cannot be trained above 80% - you must practice them in use.");
                    break;
                }
                
                // Training costs 1 point for +5% proficiency
                int newProf = Math.min(80, currentProf + 5);
                dao.setCharacterSkillLevel(trainCharId, charSkill.getSkillId(), newProf);
                dao.setTalentPoints(trainCharId, talentPoints - 1);
                
                out.println("You train " + targetSkill.getName() + "!");
                out.println("Proficiency increased from " + currentProf + "% to " + newProf + "%.");
                out.println("Spent 1 talent point. Remaining: " + (talentPoints - 1));
                break;
            }
            case "spell": {
                // Train a spell
                Spell targetSpell = null;
                CharacterSpell charSpell = null;
                java.util.List<CharacterSpell> allSpells = dao.getAllCharacterSpells(trainCharId);
                for (CharacterSpell cs : allSpells) {
                    Spell def = dao.getSpellById(cs.getSpellId());
                    if (def != null && def.getName().equalsIgnoreCase(trainTarget)) {
                        targetSpell = def;
                        charSpell = cs;
                        break;
                    }
                }
                
                // Try partial match if exact match failed
                if (targetSpell == null) {
                    String targetLower = trainTarget.toLowerCase();
                    for (CharacterSpell cs : allSpells) {
                        Spell def = dao.getSpellById(cs.getSpellId());
                        if (def != null && def.getName().toLowerCase().startsWith(targetLower)) {
                            targetSpell = def;
                            charSpell = cs;
                            break;
                        }
                    }
                }
                
                if (targetSpell == null || charSpell == null) {
                    out.println("You don't know a spell called '" + trainTarget + "'.");
                    break;
                }
                
                int currentProf = charSpell.getProficiency();
                if (currentProf >= 80) {
                    out.println(targetSpell.getName() + " is already at " + currentProf + "%. Spells cannot be trained above 80% - you must practice them in use.");
                    break;
                }
                
                // Training costs 1 point for +5% proficiency
                int newProf = Math.min(80, currentProf + 5);
                dao.setCharacterSpellLevel(trainCharId, charSpell.getSpellId(), newProf);
                dao.setTalentPoints(trainCharId, talentPoints - 1);
                
                out.println("You train " + targetSpell.getName() + "!");
                out.println("Proficiency increased from " + currentProf + "% to " + newProf + "%.");
                out.println("Spent 1 talent point. Remaining: " + (talentPoints - 1));
                break;
            }
            default:
                out.println("Unknown training type: " + trainType);
                out.println("Usage: TRAIN ABILITY <str|dex|con|int|wis|cha>");
                out.println("       TRAIN SKILL <skill_name>");
                out.println("       TRAIN SPELL <spell_name>");
        }
        return true;
    }

    private boolean handleAutolootCommand(CommandContext ctx) {
        // AUTOLOOT - Toggle automatic looting of items from corpses
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;

        if (rec == null) {
            out.println("You must be logged in to use autoloot.");
            return true;
        }
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        if (charId == null) {
            out.println("Unable to find your character.");
            return true;
        }
        
        // Toggle current value
        boolean currentValue = rec.autoloot;
        boolean newValue = !currentValue;
        boolean success = dao.setAutoloot(charId, newValue);
        if (success) {
            if (newValue) {
                out.println("Autoloot enabled. You will automatically loot items from corpses.");
            } else {
                out.println("Autoloot disabled. You must manually loot items from corpses.");
            }
            // Refresh rec
            rec = dao.findByName(name);
        } else {
            out.println("Failed to toggle autoloot.");
        }
        return true;
    }

    private boolean handleAutogoldCommand(CommandContext ctx) {
        // AUTOGOLD - Toggle automatic looting of gold from corpses
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;

        if (rec == null) {
            out.println("You must be logged in to use autogold.");
            return true;
        }
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        if (charId == null) {
            out.println("Unable to find your character.");
            return true;
        }
        
        // Toggle current value
        boolean currentValue = rec.autogold;
        boolean newValue = !currentValue;
        boolean success = dao.setAutogold(charId, newValue);
        if (success) {
            if (newValue) {
                out.println("Autogold enabled. You will automatically loot gold from corpses.");
            } else {
                out.println("Autogold disabled. You must manually loot gold from corpses.");
            }
            // Refresh rec
            rec = dao.findByName(name);
        } else {
            out.println("Failed to toggle autogold.");
        }
        return true;
    }

    private boolean handleAutosacCommand(CommandContext ctx) {
        // AUTOSAC - Toggle automatic sacrifice of empty corpses
        // Requires both autoloot and autogold to be enabled
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;
        
        if (rec == null) {
            out.println("You must be logged in to use autosac.");
            return true;
        }
        
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        if (charId == null) {
            out.println("Unable to find your character.");
            return true;
        }
        
        // Toggle current value
        boolean currentValue = rec.autosac;
        boolean newValue = !currentValue;
        
        // If trying to enable, check that autoloot and autogold are both enabled
        if (newValue && (!rec.autoloot || !rec.autogold)) {
            out.println("You must enable both autoloot and autogold before you can enable autosac.");
            return true;
        }
        
        boolean success = dao.setAutosac(charId, newValue);
        if (success) {
            if (newValue) {
                out.println("Autosac enabled. You will automatically sacrifice empty corpses for 1 XP.");
            } else {
                out.println("Autosac disabled. Corpses will remain after looting.");
            }
            // Refresh rec
            rec = dao.findByName(name);
        } else {
            out.println("Failed to toggle autosac.");
        }
        
        return true;
    }

    private boolean handleAutojunkCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;

        if (rec == null) {
            out.println("You must be logged in to use autojunk.");
            return true;
        }
        
        if (charId == null && name != null) {
            charId = dao.getCharacterIdByName(name);
        }
        if (charId == null) {
            out.println("Unable to find your character.");
            return true;
        }
        
        // Toggle current value
        boolean currentValue = rec.autojunk;
        boolean newValue = !currentValue;
        
        boolean success = dao.setAutojunk(charId, newValue);
        if (success) {
            if (newValue) {
                out.println("Autojunk enabled. You will automatically junk items marked as junk.");
            } else {
                out.println("Autojunk disabled. Items marked as junk will remain in your inventory.");
            }
            // Refresh rec
            rec = dao.findByName(name);
        } else {
            out.println("Failed to toggle autojunk.");
        }
        return true;
    }
    
    
}
