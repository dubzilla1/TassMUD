package com.example.tassmud.net.commands;


import com.example.tassmud.persistence.DaoProvider;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import java.util.concurrent.ThreadLocalRandom;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.Stat;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.LootGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegates GM commands to ClientHandler.handleGmCommand
 * NOTE: Only list commands that are actually implemented in handleGmCommand().
 * Handled commands include cflag, cset, cskill, cspell, dbinfo, debug, genmap, gmchat,
 * gminvis, goto, ifind, ilist, istat, mstat, peace, promote, restore, slay, spawn, system.
 */
public class GmCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(GmCommandHandler.class);

    private final GmCharacterHandler charHandler = new GmCharacterHandler();
    private final GmWorldHandler worldHandler = new GmWorldHandler();
    private final GmInfoHandler infoHandler = new GmInfoHandler();

private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.GM).stream()
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
            // Character manipulation
            case "cflag": return charHandler.handleCflagCommand(ctx);
            case "cset": return charHandler.handleCsetCommand(ctx);
            case "cskill": return charHandler.handleCskillCommand(ctx);
            case "cspell": return charHandler.handleCspellCommand(ctx);
            case "promote": return charHandler.handlePromoteCommand(ctx);
            case "restore": return charHandler.handleRestoreCommand(ctx);
            // World manipulation
            case "checktemplate": return worldHandler.handleCheckTemplateCommand(ctx);
            case "seedtemplates": return worldHandler.handleSeedTemplatesCommand(ctx);
            case "gminvis": return worldHandler.handleGminvisCommand(ctx);
            case "system": return worldHandler.handleSystemCommand(ctx);
            case "spawn": return worldHandler.handleSpawnCommand(ctx);
            case "slay": return worldHandler.handleSlayCommand(ctx);
            case "peace": return worldHandler.handlePeaceCommand(ctx);
            case "goto": return worldHandler.handleGotoCommand(ctx);
            case "setweather": return worldHandler.handleSetWeatherCommand(ctx);
            // Info & lookup
            case "dbinfo": return infoHandler.handleDbinfoCommand(ctx);
            case "debug": return infoHandler.handleDebugCommand(ctx);
            case "genmap": return infoHandler.handleGenmapCommand(ctx);
            case "gmchat": return infoHandler.handleGmchatCommand(ctx);
            case "ifind": return infoHandler.handleIfindCommand(ctx);
            case "ilist": return infoHandler.handleIlistCommand(ctx);
            case "mlist": return infoHandler.handleMlistCommand(ctx);
            case "mfind": return infoHandler.handleMfindCommand(ctx);
            case "istat": return infoHandler.handleIstatCommand(ctx);
            case "mstat": return infoHandler.handleMstatCommand(ctx);
            default:
                return false;
        }
    }
}
