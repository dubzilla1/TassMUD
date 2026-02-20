package com.example.tassmud.net.commands;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.MobileDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;

/**
 * Handles the 'cast' spell command, delegated from CombatCommandHandler.
 */
class SpellCastHandler {

    private static final Logger logger = LoggerFactory.getLogger(SpellCastHandler.class);

    boolean handleCastCommand(CommandContext ctx) {
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

        // Cannot cast spells while stunned
        CombatManager stunCheckMgr = CombatManager.getInstance();
        Combat stunCheckCombat = stunCheckMgr.getCombatForCharacter(charId);
        if (stunCheckCombat != null) {
            Combatant stunCheckCombatant = stunCheckCombat.findByCharacterId(charId);
            if (stunCheckCombatant != null && stunCheckCombatant.isStunned()) {
                out.println("You are too stunned to cast spells!");
                return true;
            }
        }
        
        // Get all spells the character knows
        java.util.List<CharacterSpell> knownSpells = DaoProvider.spells().getAllCharacterSpells(charId);
        if (knownSpells.isEmpty()) {
            out.println("You don't know any spells.");
            return true;
        }
        
        // Parse: spell name and optional target
        // We need to find the spell first, then remaining args are target
        
        // Build list of spell definitions for spells we know
        java.util.List<Spell> knownSpellDefs = new java.util.ArrayList<>();
        for (CharacterSpell cs : knownSpells) {
            Spell spellDef = DaoProvider.spells().getSpellById(cs.getSpellId());
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
                        Combatant target = enemies.get(0);
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
                    Mobile mob = com.example.tassmud.util.MobileMatchingService.findInRoom(ctx.currentRoomId, targetArg);
                    if (mob != null) {
                        // Use negative instance ID as target ID for mobs
                        targetId = -(int)mob.getInstanceId();
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
                            Combatant target = enemies.get(0);
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
            com.example.tassmud.model.CharacterSpell charSpell = DaoProvider.spells().getCharacterSpell(charId, matchedSpell.getId());
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
                            CharacterRecord recT = dao.findById(tgt);
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
}
