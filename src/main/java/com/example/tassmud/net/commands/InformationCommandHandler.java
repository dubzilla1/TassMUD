package com.example.tassmud.net.commands;

import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.HelpManager;
import com.example.tassmud.util.RegenerationService;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles INFORMATION category commands: help, score, who, look, inventory, skills, spells, consider.
 * 
 * This class extracts item-related command logic from ClientHandler to reduce
 * method size and improve maintainability.
 */
public class InformationCommandHandler implements CommandHandler {
    
    private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.INFORMATION).stream()
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
            case "help":
                return handleHelpCommand(ctx);
            case "score":
                return handleScoreCommand(ctx);
            case "who":
                return handleWhoCommand(ctx);
            case "inventory":
                return handleInventoryCommand(ctx);
            case "skills":
                return handleSkillsCommand(ctx);
            case "spells":
                return handleSpellsCommand(ctx);
            case "consider":
                return handleConsiderCommand(ctx);
            default:
                return false;
        }
    }
    
    private boolean handleConsiderCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        Integer charId = ctx.characterId;

        if (rec == null) {
            out.println("You must be logged in to consider targets.");
            return true;
        }
        String conArgs = ctx.getArgs();
        if (conArgs == null || conArgs.trim().isEmpty()) {
            out.println("Consider whom?");
            return true;
        }
        
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get player's level
        CharacterClassDAO classDao = new CharacterClassDAO();
        Integer currentClassId = rec.currentClassId;
        int playerLevel = currentClassId != null ? classDao.getCharacterClassLevel(charId, currentClassId) : 1;
        
        // Search for target mob in the room
        String targetName = conArgs.trim().toLowerCase();
        MobileDAO mobileDao = new MobileDAO();
        java.util.List<Mobile> mobsInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
        
        Mobile targetMob = null;
        for (Mobile mob : mobsInRoom) {
            String mobNameLower = mob.getName().toLowerCase();
            if (mobNameLower.equals(targetName) || 
                mobNameLower.startsWith(targetName) || 
                mobNameLower.contains(targetName)) {
                targetMob = mob;
                break;
            }
        }
        
        if (targetMob == null) {
            out.println("You don't see '" + conArgs.trim() + "' here.");
            return true;
        }
        
        // Calculate level difference: player - target
        int targetLevel = targetMob.getLevel();
        int levelDiff = playerLevel - targetLevel;
        
        // Clamp to -5 to +5 range
        levelDiff = Math.max(-5, Math.min(5, levelDiff));
        
        String opponentName = targetMob.getName();
        String message;
        switch (levelDiff) {
            case 5:
                message = "Don't bother, " + opponentName + " is not worth your time.";
                break;
            case 4:
                message = "You would wipe the floor with " + opponentName + ".";
                break;
            case 3:
                message = "It should be an easy fight.";
                break;
            case 2:
                message = opponentName + " wouldn't pose much trouble.";
                break;
            case 1:
                message = "You're stronger than " + opponentName + ", but not by much.";
                break;
            case 0:
                message = "You and " + opponentName + " are well matched.";
                break;
            case -1:
                message = "You're at a disadvantage, but you can probably swing it.";
                break;
            case -2:
                message = opponentName + " would be a tough fight.";
                break;
            case -3:
                message = "You should pass on this one if you know what's good for you.";
                break;
            case -4:
                message = "Not a chance in hell, which is where you'd end up.";
                break;
            case -5:
            default:
                message = "Death appreciates your sacrifice.";
                break;
        }
        out.println(message);
        return true;
    }

    private boolean handleSpellsCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        // List all spells the character knows
        if (rec == null) {
            out.println("You must be logged in to view spells.");
            return true;
        }
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        java.util.List<CharacterSpell> knownSpells = dao.getAllCharacterSpells(charId);
        if (knownSpells.isEmpty()) {
            out.println("You don't know any spells yet.");
            return true;
        }
        
        // Group spells by school for nice display
        java.util.Map<Spell.SpellSchool, java.util.List<Spell>> bySchool = new java.util.LinkedHashMap<>();
        for (Spell.SpellSchool school : Spell.SpellSchool.values()) {
            bySchool.put(school, new java.util.ArrayList<>());
        }
        
        for (CharacterSpell cs : knownSpells) {
            Spell spellDef = dao.getSpellById(cs.getSpellId());
            if (spellDef != null) {
                bySchool.get(spellDef.getSchool()).add(spellDef);
            }
        }
        
        out.println("=== Known Spells ===");
        boolean anySpells = false;
        for (java.util.Map.Entry<Spell.SpellSchool, java.util.List<Spell>> entry : bySchool.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                anySpells = true;
                out.println("\n" + entry.getKey().displayName + " Magic:");
                // Sort by level then name
                entry.getValue().sort((sp1, sp2) -> {
                    int cmp = Integer.compare(sp1.getLevel(), sp2.getLevel());
                    return cmp != 0 ? cmp : sp1.getName().compareToIgnoreCase(sp2.getName());
                });
                for (Spell sp : entry.getValue()) {
                    // Find proficiency
                    CharacterSpell cs = null;
                    for (CharacterSpell csp : knownSpells) {
                        if (csp.getSpellId() == sp.getId()) {
                            cs = csp;
                            break;
                        }
                    }
                    String prof = cs != null ? cs.getProficiencyDisplay() : "";
                    out.println(String.format("  [%d] %-20s %s", sp.getLevel(), sp.getName(), prof));
                }
            }
        }
        if (!anySpells) {
            out.println("You don't know any spells yet.");
        }
        return true;
    }

    private boolean handleSkillsCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (rec == null) {
            out.println("You must be logged in to view skills.");
            return true;
        }
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        java.util.List<CharacterSkill> knownSkills = dao.getAllCharacterSkills(charId);
        if (knownSkills.isEmpty()) {
            out.println("You don't know any skills yet.");
            return true;
        }
        
        // Build list of skill name + proficiency pairs
        java.util.List<String> skillDisplays = new java.util.ArrayList<>();
        for (CharacterSkill cs : knownSkills) {
            Skill skillDef = dao.getSkillById(cs.getSkillId());
            if (skillDef != null) {
                String display = String.format("%s %3d%%", skillDef.getName(), cs.getProficiency());
                skillDisplays.add(display);
            }
        }
        
        // Sort alphabetically
        skillDisplays.sort(String::compareToIgnoreCase);
        
        out.println();
        out.println("===================================================================");
        out.println("                          KNOWN SKILLS");
        out.println("===================================================================");
        out.println();
        
        // Print in rows of 3, formatted nicely
        for (int i = 0; i < skillDisplays.size(); i += 3) {
            String c1 = skillDisplays.get(i);
            String c2 = (i + 1 < skillDisplays.size()) ? skillDisplays.get(i + 1) : "";
            String c3 = (i + 2 < skillDisplays.size()) ? skillDisplays.get(i + 2) : "";
            out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
        }
        
        out.println();
        out.println("-------------------------------------------------------------------");
        out.println(String.format("  Total: %d skill%s known", skillDisplays.size(), skillDisplays.size() == 1 ? "" : "s"));
        out.println("===================================================================");
        out.println();
        return true;
    }

    private boolean handleInventoryCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        
        if (rec == null) {
            out.println("No character record found.");
            return true;
        }
        ItemDAO itemDao = new ItemDAO();
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get equipped item instance IDs to exclude
        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
        for (Long iid : equippedMap.values()) {
            if (iid != null) equippedInstanceIds.add(iid);
        }

        // Get all items owned by character
        java.util.List<ItemDAO.RoomItem> allItems = itemDao.getItemsByCharacter(charId);

        // Filter: exclude equipped items and items inside containers
        java.util.List<String> itemNames = new java.util.ArrayList<>();
        for (ItemDAO.RoomItem ri : allItems) {
            // Skip equipped items
            if (equippedInstanceIds.contains(ri.instance.instanceId)) continue;
            // Skip items inside containers (container_instance_id is set)
            if (ri.instance.containerInstanceId != null) continue;
            // Add name (prefer customName for generated items)
            itemNames.add(ClientHandler.getItemDisplayName(ri));
        }

        // Get gold amount
        long gold = dao.getGold(charId);

        if (itemNames.isEmpty()) {
            out.println("You are not carrying anything.");
        } else {
            // Sort alphabetically
            java.util.Collections.sort(itemNames, String.CASE_INSENSITIVE_ORDER);

            out.println("You are carrying:");
            for (String n : itemNames) {
                out.println("  " + n);
            }
        }

        // Always show gold
        out.println("");
        out.println("Gold: " + gold + " gp");
        
        return true;
    }

    private boolean handleScoreCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;

            // CHARACTER SHEET - Display full character information
            if (rec == null) {
                out.println("You must be logged in to view your character sheet.");
                return true;
            }
            if (charId == null) {
                out.println("Failed to locate your character record.");
                return true;
            }
            
            // Get class information
            CharacterClassDAO classDao = new CharacterClassDAO();
            try {
                classDao.loadClassesFromYamlResource("/data/classes.yaml");
            } catch (Exception ignored) {}
            try {
            Integer currentClassId = rec.currentClassId;
            CharacterClass currentClass = currentClassId != null ? classDao.getClassById(currentClassId) : null;
            int classLevel = currentClassId != null ? classDao.getCharacterClassLevel(charId, currentClassId) : 0;
            int classXp = currentClassId != null ? classDao.getCharacterClassXp(charId, currentClassId) : 0;
            // classXp is progress within current level (0 to XP_PER_LEVEL-1), so xpToNext is simply the remainder
            int xpToNext = classLevel < CharacterClass.MAX_HERO_LEVEL ? CharacterClass.XP_PER_LEVEL - classXp : 0;
            
            // Get all class progress for multiclass display
            java.util.List<CharacterClassDAO.ClassProgress> allProgress = classDao.getCharacterClassProgress(charId);
            
            // Get equipment for AC and weapon damage
            ItemDAO itemDao = new ItemDAO();
            java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
            
            // Calculate ability modifiers using totals (base + trained)
            int strTotal = rec.getStrTotal();
            int dexTotal = rec.getDexTotal();
            int conTotal = rec.getConTotal();
            int intTotal = rec.getIntTotal();
            int wisTotal = rec.getWisTotal();
            int chaTotal = rec.getChaTotal();
            int strMod = (strTotal - 10) / 2;
            int dexMod = (dexTotal - 10) / 2;
            int conMod = (conTotal - 10) / 2;
            int intMod = (intTotal - 10) / 2;
            int wisMod = (wisTotal - 10) / 2;
            int chaMod = (chaTotal - 10) / 2;
            
            // Build the character sheet
            StringBuilder sheet = new StringBuilder();
            String divider = "===================================================================";
            String thinDiv = "-------------------------------------------------------------------";
            
            // === HEADER ===
            sheet.append("\n").append(divider).append("\n");
            sheet.append(String.format("  %-30s %35s\n", name.toUpperCase(), "Age: " + rec.age));
            sheet.append(String.format("  %-30s %35s\n", 
                currentClass != null ? currentClass.name + " Level " + classLevel : "(No Class)",
                rec.description != null && !rec.description.isEmpty() ? "\"" + ClientHandler.truncate(rec.description, 30) + "\"" : ""));
            sheet.append(divider).append("\n");
            
            // ═══ VITALS ═══
            sheet.append("\n  [ VITALS ]\n");
            sheet.append(String.format("  HP: %4d / %-4d    MP: %4d / %-4d    MV: %4d / %-4d\n",
                rec.hpCur, rec.hpMax, rec.mpCur, rec.mpMax, rec.mvCur, rec.mvMax));
            
            // Stance info
            Stance playerStance = RegenerationService.getInstance().getPlayerStance(charId);
            String stanceLabel = playerStance.getDisplayName().substring(0, 1).toUpperCase() + playerStance.getDisplayName().substring(1);
            int regenPct = playerStance.getRegenPercent();
            sheet.append(String.format("  Stance: %-10s (%d%% regen out of combat)\n", stanceLabel, regenPct));
            
            // XP bar - shows progress within current level (0 to XP_PER_LEVEL)
            if (currentClass != null && classLevel < CharacterClass.MAX_HERO_LEVEL) {
                // classXp is already the progress within the current level (resets to 0 on level-up)
                int xpNeeded = CharacterClass.XP_PER_LEVEL;
                int pct = (classXp * 100) / xpNeeded;
                sheet.append(String.format("  XP: %d / %d  [%s%s] %d%%  (%d TNL)\n",
                    classXp, xpNeeded,
                    ClientHandler.repeat("#", pct / 5), ClientHandler.repeat(".", 20 - pct / 5),
                    pct, xpToNext));
            } else if (currentClass != null) {
                sheet.append(String.format("  XP: %d  [MAX LEVEL]\n", classXp));
            }
            
            // ═══ ABILITY SCORES ═══
            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
            sheet.append("  [ ABILITY SCORES ]");
            if (rec.talentPoints > 0) {
                sheet.append("  (").append(rec.talentPoints).append(" Talent Point").append(rec.talentPoints == 1 ? "" : "s").append(")");
            }
            sheet.append("\n");
            sheet.append(String.format("  STR: %2d (%+d)    DEX: %2d (%+d)    CON: %2d (%+d)\n",
                strTotal, strMod, dexTotal, dexMod, conTotal, conMod));
            sheet.append(String.format("  INT: %2d (%+d)    WIS: %2d (%+d)    CHA: %2d (%+d)\n",
                intTotal, intMod, wisTotal, wisMod, chaTotal, chaMod));
            
            // ═══ SAVES & DEFENSES ═══
            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
            sheet.append("  [ SAVES & DEFENSES ]\n");
            sheet.append(String.format("  Armor: %2d (%+d equip)    Fortitude: %2d (%+d equip)\n",
                rec.getArmorTotal(), rec.armorEquipBonus, rec.getFortitudeTotal(), rec.fortitudeEquipBonus));
            sheet.append(String.format("  Reflex: %2d (%+d equip)   Will: %2d (%+d equip)\n",
                rec.getReflexTotal(), rec.reflexEquipBonus, rec.getWillTotal(), rec.willEquipBonus));
            
            // ═══ COMBAT ═══
            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
            sheet.append("  [ COMBAT ]\n");
            
            // Get weapon info from hands
            Long mainHandId = equippedMap.get(EquipmentSlot.MAIN_HAND.getId());
            Long offHandId = equippedMap.get(EquipmentSlot.OFF_HAND.getId());
            
            boolean hasWeapon = false;
            boolean isTwoHanded = mainHandId != null && mainHandId.equals(offHandId);
            
            if (mainHandId != null) {
                ItemInstance mainInst = itemDao.getInstance(mainHandId);
                if (mainInst != null) {
                    ItemTemplate mainTmpl = itemDao.getTemplateById(mainInst.templateId);
                    // Use effective stats (instance overrides if present, otherwise template)
                    int effectiveBaseDie = mainInst.getEffectiveBaseDie(mainTmpl);
                    int effectiveMultiplier = mainInst.getEffectiveMultiplier(mainTmpl);
                    double effectiveAbilityMult = mainInst.getEffectiveAbilityMultiplier(mainTmpl);
                    String abilityScore = mainTmpl != null ? mainTmpl.abilityScore : "STR";
                    if (effectiveBaseDie > 0) {
                        // Hit bonus: stat modifier (same stat as damage)
                        int hitBonus = ClientHandler.getStatModifier(abilityScore, rec);
                        String hitStr = ClientHandler.formatHitBonus(hitBonus);
                        String dmgStr = ClientHandler.formatDamage(effectiveMultiplier, effectiveBaseDie, 
                            ClientHandler.getAbilityBonus(abilityScore, effectiveAbilityMult, rec));
                        String handLabel = isTwoHanded ? "Both Hands:" : "Main Hand: ";
                        String weaponName = ClientHandler.getItemDisplayName(mainInst, mainTmpl);
                        sheet.append(String.format("  %s %-16s  Hit: %-9s  Damage: %s\n", 
                            handLabel, ClientHandler.truncate(weaponName, 16), hitStr, dmgStr));
                        hasWeapon = true;
                    }
                }
            }
            if (offHandId != null && !isTwoHanded) {
                ItemInstance offInst = itemDao.getInstance(offHandId);
                if (offInst != null) {
                    ItemTemplate offTmpl = itemDao.getTemplateById(offInst.templateId);
                    // Use effective stats (instance overrides if present, otherwise template)
                    int effectiveBaseDie = offInst.getEffectiveBaseDie(offTmpl);
                    int effectiveMultiplier = offInst.getEffectiveMultiplier(offTmpl);
                    double effectiveAbilityMult = offInst.getEffectiveAbilityMultiplier(offTmpl);
                    String abilityScore = offTmpl != null ? offTmpl.abilityScore : "STR";
                    if (effectiveBaseDie > 0) {
                        // Hit bonus: stat modifier (same stat as damage)
                        int hitBonus = ClientHandler.getStatModifier(abilityScore, rec);
                        String hitStr = ClientHandler.formatHitBonus(hitBonus);
                        String dmgStr = ClientHandler.formatDamage(effectiveMultiplier, effectiveBaseDie,
                            ClientHandler.getAbilityBonus(abilityScore, effectiveAbilityMult, rec));
                        String weaponName = ClientHandler.getItemDisplayName(offInst, offTmpl);
                        sheet.append(String.format("  Off Hand:  %-16s  Hit: %-9s  Damage: %s\n",
                            ClientHandler.truncate(weaponName, 16), hitStr, dmgStr));
                    } else {
                        // Shield or non-weapon (show armor bonus)
                        String itemName = ClientHandler.getItemDisplayName(offInst, offTmpl);
                        sheet.append(String.format("  Off Hand:  %-16s  (Shield)\n",
                            ClientHandler.truncate(itemName, 16)));
                    }
                    hasWeapon = true;
                }
            }
            if (!hasWeapon) {
                // Unarmed combat - 1d4 + STR
                String hitStr = ClientHandler.formatHitBonus(strMod);
                String unarmedDmg = ClientHandler.formatDamage(1, 4, strMod);
                sheet.append(String.format("  Unarmed:   %-16s  Hit: %-9s  Damage: %s\n", "Fists", hitStr, unarmedDmg));
            }
            
            // ═══ CLASS PROGRESSION ═══
            if (!allProgress.isEmpty()) {
                sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                sheet.append("  [ CLASS PROGRESSION ]\n");
                for (CharacterClassDAO.ClassProgress cp : allProgress) {
                    CharacterClass cls = classDao.getClassById(cp.classId);
                    String className = cls != null ? cls.name : "Unknown";
                    String marker = cp.isCurrent ? " *" : "";
                    sheet.append(String.format("  %-15s Lv %2d  XP: %5d%s\n", 
                        className, cp.level, cp.xp, marker));
                }
                sheet.append("  (* = active class)\n");
            }
            
            // ═══ SKILLS ═══
            java.util.List<CharacterSkill> knownSkills = dao.getAllCharacterSkills(charId);
            if (!knownSkills.isEmpty()) {
                sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                sheet.append("  [ SKILLS ]\n");
                // Sort by name
                java.util.List<String> skillLines = new java.util.ArrayList<>();
                for (CharacterSkill cs : knownSkills) {
                    Skill skillDef = dao.getSkillById(cs.getSkillId());
                    String skillName = skillDef != null ? skillDef.getName() : "Skill #" + cs.getSkillId();
                    int prof = cs.getProficiency();
                    String profStr = prof >= 100 ? "MASTERED" : prof + "%";
                    skillLines.add(String.format("%-22s %8s", ClientHandler.truncate(skillName, 22), profStr));
                }
                java.util.Collections.sort(skillLines, String.CASE_INSENSITIVE_ORDER);
                // Print in 2 columns
                for (int i = 0; i < skillLines.size(); i += 2) {
                    String col1 = skillLines.get(i);
                    String col2 = i + 1 < skillLines.size() ? skillLines.get(i + 1) : "";
                    sheet.append(String.format("  %s  %s\n", col1, col2));
                }
            } else {
                sheet.append("\n  [ SKILLS ]\n");
                sheet.append("  No skills learned yet.\n");
            }
            
            // ═══ SPELLS ═══
            java.util.List<CharacterSpell> knownSpells = dao.getAllCharacterSpells(charId);
            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
            sheet.append("  [ SPELLS ]\n");
            if (!knownSpells.isEmpty()) {
                // Group by spell level
                java.util.Map<Integer, java.util.List<String>> spellsByLevel = new java.util.TreeMap<>();
                for (CharacterSpell cs : knownSpells) {
                    Spell spellDef = dao.getSpellById(cs.getSpellId());
                    if (spellDef != null) {
                        int lvl = spellDef.getLevel();
                        spellsByLevel.computeIfAbsent(lvl, k -> new java.util.ArrayList<>());
                        int prof = cs.getProficiency();
                        String profStr = prof >= 100 ? "M" : prof + "%";
                        spellsByLevel.get(lvl).add(spellDef.getName() + " [" + profStr + "]");
                    }
                }
                for (java.util.Map.Entry<Integer, java.util.List<String>> entry : spellsByLevel.entrySet()) {
                    sheet.append(String.format("  Level %d: ", entry.getKey()));
                    java.util.Collections.sort(entry.getValue(), String.CASE_INSENSITIVE_ORDER);
                    sheet.append(String.join(", ", entry.getValue())).append("\n");
                }
            } else {
                sheet.append("  No spells known yet.\n");
            }
            
            // ═══ AUTO SETTINGS ═══
            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
            sheet.append("  [ AUTO SETTINGS ]\n");
            int autoflee = rec.autoflee;
            if (autoflee > 0) {
                sheet.append(String.format("  Autoflee: %d%% HP\n", autoflee));
            } else {
                sheet.append("  Autoflee: disabled\n");
            }
            sheet.append(String.format("  Autoloot: %s    Autogold: %s    Autosac: %s    Autojunk: %s\n",
                rec.autoloot ? "ON" : "OFF", rec.autogold ? "ON" : "OFF", rec.autosac ? "ON" : "OFF", rec.autojunk ? "ON" : "OFF"));
            
            // ═══ ACTIVE EFFECTS ═══
            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
            sheet.append("  [ ACTIVE EFFECTS ]\n");
            // Attempt to get live modifiers (combat) else load from DB
            java.util.List<com.example.tassmud.model.Modifier> activeMods = new java.util.ArrayList<>();
            com.example.tassmud.combat.Combatant combatant = com.example.tassmud.combat.CombatManager.getInstance().getCombatantForCharacter(charId);
            if (combatant != null && combatant.getAsCharacter() != null) {
                activeMods = combatant.getAsCharacter().getAllModifiers();
            } else {
                activeMods = dao.getModifiersForCharacter(charId);
            }
            // Filter expired
            long nowMs = System.currentTimeMillis();
            activeMods.removeIf(com.example.tassmud.model.Modifier::isExpired);
            
            // Also get flag-based effects from EffectRegistry (e.g., Insight, Invisibility)
            java.util.List<com.example.tassmud.effect.EffectInstance> flagEffects = 
                com.example.tassmud.effect.EffectRegistry.getActiveForTarget(charId);
            // Filter to only show flag-based effects (those without stat modifiers)
            // Stat-modifying effects are already shown via activeMods
            flagEffects.removeIf(ei -> {
                com.example.tassmud.effect.EffectDefinition def = 
                    com.example.tassmud.effect.EffectRegistry.getDefinition(ei.getDefId());
                if (def == null) return true;
                java.util.Map<String, String> params = def.getParams();
                // If it has stat and value params, it's a stat modifier (shown elsewhere)
                return params != null && params.containsKey("stat") && params.containsKey("value");
            });

            if (activeMods.isEmpty() && flagEffects.isEmpty()) {
                sheet.append("  (No active spell effects)\n");
            } else {
                // Show stat modifiers
                for (com.example.tassmud.model.Modifier m : activeMods) {
                    long remaining = Math.max(0, m.expiresAtMillis() - nowMs) / 1000L;
                    String timeStr = ClientHandler.formatDuration(remaining);
                    String sign = m.op() == com.example.tassmud.model.Modifier.Op.ADD && m.value() > 0 ? "+" : "";
                    sheet.append(String.format("  - %s : %s%1.0f %s  (%s)\n",
                        m.source(), sign, m.value(), m.stat().name(), timeStr));
                }
                // Show flag-based effects (Insight, Invisibility, etc.)
                for (com.example.tassmud.effect.EffectInstance ei : flagEffects) {
                    com.example.tassmud.effect.EffectDefinition def = 
                        com.example.tassmud.effect.EffectRegistry.getDefinition(ei.getDefId());
                    String effectName = def != null ? def.getName() : "Unknown Effect";
                    long remaining = Math.max(0, ei.getExpiresAtMs() - nowMs) / 1000L;
                    String timeStr = ClientHandler.formatDuration(remaining);
                    sheet.append(String.format("  - %s  (%s)\n", effectName, timeStr));
                }
            }
            
            // ═══ FOOTER ═══
            sheet.append("\n").append(divider).append("\n");
            
            out.print(sheet.toString());
        } catch (Exception e) {
            out.println("An error occurred while generating your character sheet.");
            e.printStackTrace(out);
        }
        return true;
    }

    /**
     * Handle the help command - display available commands or command details.
     */
    private boolean handleHelpCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String args = ctx.getArgs();

        boolean isGm = dao.isCharacterFlagTrueByName(ctx.playerName, "is_gm");
        if (args == null || args.trim().isEmpty()) {
            // Display categorized command list
            displayHelpCommandList(isGm, out);
        } else {
            String arg0 = args.trim().split("\\s+",2)[0];
            if (arg0.equalsIgnoreCase("commands")) {
                displayHelpCommandList(isGm, out);
                return true;
            }
            if (arg0.equalsIgnoreCase("reload")) {
                if (!isGm) {
                    out.println("You do not have permission to reload help files.");
                } else {
                    HelpManager.reloadPages();
                    out.println("Help pages reloaded.");
                }
            } else {
                String page = HelpManager.getPage(arg0, isGm);
                if (page == null) {
                    out.println("No help available for '" + args + "'");
                } else {
                    // print man-style page
                    String[] lines = page.split("\\n");
                    for (String L : lines) out.println(L);
                }
            }
        
        }
        return true;
    }

    /**
     * Display a formatted, categorized list of available commands.
     */
    private void displayHelpCommandList(boolean isGm, PrintWriter out) {
        out.println();
        out.println("===================================================================");
        out.println("                        AVAILABLE COMMANDS");
        out.println("===================================================================");
        
        // Display commands by category from the registry
        for (CommandDefinition.Category category : CommandDefinition.Category.values()) {
            // Skip GM category if not a GM
            if (category == CommandDefinition.Category.GM && !isGm) {
                continue;
            }
            
            java.util.List<CommandDefinition> cmds = CommandRegistry.getCommandsByCategory(category);
            if (cmds.isEmpty()) continue;
            
            out.println();
            out.println("  [ " + category.getDisplayName() + " ]");
            
            // Collect display names
            java.util.List<String> displayNames = new java.util.ArrayList<>();
            for (CommandDefinition cmd : cmds) {
                displayNames.add(cmd.getDisplayName());
            }
            
            // Print in rows of 3
            for (int i = 0; i < displayNames.size(); i += 3) {
                String c1 = displayNames.get(i);
                String c2 = (i + 1 < displayNames.size()) ? displayNames.get(i + 1) : "";
                String c3 = (i + 2 < displayNames.size()) ? displayNames.get(i + 2) : "";
                printCommandRow(c1, c2, c3, out);
            }
        }
        
        out.println();
        out.println("-------------------------------------------------------------------");
        out.println("  Type 'help <command>' for detailed information on any command.");
        out.println("===================================================================");
        out.println();
    }

    /**
     * Print a row of up to 3 commands, evenly spaced.
     */
    private void printCommandRow(String cmd1, String cmd2, String cmd3, PrintWriter out) {
        out.println(String.format("    %-20s %-20s %-20s", cmd1, cmd2, cmd3));
    }

    private boolean handleWhoCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        out.println();
        out.println("===================================================================");
        out.println("  Players Online");
        out.println("===================================================================");
        
        CharacterClassDAO classDao = new CharacterClassDAO();
        try {
            classDao.loadClassesFromYamlResource("/data/classes.yaml");
        } catch (Exception ignored) {}
        
        // Check if I'm a GM (for seeing GM-invisible players)
        boolean iAmGm = dao.isCharacterFlagTrueByName(name, "is_gm");
        
        int count = 0;
        for (ClientHandler session : ClientHandler.sessions) {
            String pName = session.playerName;
            if (pName == null) continue; // Not yet logged in
            
            // Look up character info
            CharacterRecord pRec = dao.findByName(pName);
            if (pRec == null) continue;
            
            Integer pCharId = dao.getCharacterIdByName(pName);
            
            // Check GM invisibility - only other GMs can see GM-invisible players
            if (session.gmInvisible && session != ctx.handler && !iAmGm) {
                continue; // Can't see GM-invisible players in who list
            }
            
            // Check normal invisibility - skip if we can't see them (unless it's ourselves)
            boolean isInvis = com.example.tassmud.effect.EffectRegistry.isInvisible(pCharId);
            boolean canSee = session == ctx.handler || com.example.tassmud.effect.EffectRegistry.canSee(ctx.characterId, pCharId);
            if (isInvis && !canSee) {
                continue; // Can't see them in who list
            }
            
            Integer pClassId = pRec.currentClassId;
            CharacterClass pClass = pClassId != null ? classDao.getClassById(pClassId) : null;
            int pLevel = (pClassId != null && pCharId != null) 
                ? classDao.getCharacterClassLevel(pCharId, pClassId) : 0;
            
            String className = pClass != null ? pClass.name : "Adventurer";
            String desc = pRec.description != null && !pRec.description.isEmpty() 
                ? pRec.description : "";
            
            // Add invisibility tag if invisible but we can see them
            String invisTag = "";
            if (session.gmInvisible && session != ctx.handler) {
                invisTag = " (GM-INVIS)";
            } else if (isInvis && canSee && session != ctx.handler) {
                invisTag = " (INVIS)";
            }
            
            // Format: [Lv ##] ClassName     PlayerName - Description
            out.println(String.format("  [Lv %2d] %-12s  %-15s%s %s",
                pLevel, className, pName, invisTag,
                desc.isEmpty() ? "" : "- " + ClientHandler.truncate(desc, 30)));
            count++;
        }
        
        out.println("-------------------------------------------------------------------");
        out.println(String.format("  %d player%s online", count, count == 1 ? "" : "s"));
        out.println("===================================================================");
        out.println();

        return true;
    }
}
