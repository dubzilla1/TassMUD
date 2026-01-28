package com.example.tassmud.net.commands;

import com.example.tassmud.model.Group;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.util.GroupManager;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles group/party related commands.
 * Commands: group, invite, accept, decline, leave, disband, kick, leader, follow, unfollow
 */
public class GroupCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.GROUP).stream()
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
            case "group":
                return handleGroupCommand(ctx);
            case "invite":
                return handleInviteCommand(ctx);
            case "accept":
            case "join":
                return handleAcceptCommand(ctx);
            case "decline":
                return handleDeclineCommand(ctx);
            case "leave":
                return handleLeaveCommand(ctx);
            case "disband":
                return handleDisbandCommand(ctx);
            case "boot":
                return handleBootCommand(ctx);
            case "leader":
            case "promote":
                return handleLeaderCommand(ctx);
            case "follow":
                return handleFollowCommand(ctx);
            case "unfollow":
                return handleUnfollowCommand(ctx);
            default:
                return false;
        }
    }

    // ========== GROUP command ==========

    private boolean handleGroupCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to use group commands.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            // Not in a group - create one
            Optional<Group> newGroup = gm.createGroup(charId);
            if (newGroup.isPresent()) {
                out.println("You have formed a new group.");
                out.println("Use 'invite <player>' to invite others to your group.");
            } else {
                out.println("Failed to create group.");
            }
            return true;
        }

        // Show group info
        Group group = groupOpt.get();
        displayGroupInfo(out, dao, group, charId);
        return true;
    }

    private void displayGroupInfo(PrintWriter out, CharacterDAO dao, Group group, int viewerId) {
        out.println("\u001B[36m=== Your Group ===\u001B[0m");
        
        // List members
        for (int memberId : group.getMemberIds()) {
            CharacterRecord memberRec = dao.getCharacterById(memberId);
            String memberName = memberRec != null ? memberRec.name : "Unknown";
            
            StringBuilder line = new StringBuilder();
            line.append("  ");
            
            if (group.isLeader(memberId)) {
                line.append("\u001B[33m[Leader]\u001B[0m ");
            } else if (group.isFollowing(memberId)) {
                line.append("\u001B[32m[Following]\u001B[0m ");
            } else {
                line.append("          ");
            }
            
            line.append(memberName);
            
            if (memberId == viewerId) {
                line.append(" (you)");
            }
            
            // Show HP% if we have access
            if (memberRec != null && memberRec.hpMax > 0) {
                int hpPercent = (memberRec.hpCur * 100) / memberRec.hpMax;
                String hpColor;
                if (hpPercent > 75) hpColor = "\u001B[32m";      // Green
                else if (hpPercent > 50) hpColor = "\u001B[33m"; // Yellow
                else if (hpPercent > 25) hpColor = "\u001B[31m"; // Red
                else hpColor = "\u001B[35m";                      // Magenta (critical)
                line.append(String.format(" - %s%d%%\u001B[0m HP", hpColor, hpPercent));
            }
            
            out.println(line.toString());
        }
        
        // Show pending invites (only to leader)
        if (group.isLeader(viewerId) && !group.getPendingInvites().isEmpty()) {
            out.println("\u001B[36mPending invites:\u001B[0m");
            for (int inviteeId : group.getPendingInvites()) {
                CharacterRecord inviteeRec = dao.getCharacterById(inviteeId);
                String inviteeName = inviteeRec != null ? inviteeRec.name : "Unknown";
                out.println("  " + inviteeName + " (waiting for response)");
            }
        }
        
        out.println("\u001B[36m==================\u001B[0m");
    }

    // ========== INVITE command ==========

    private boolean handleInviteCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String args = ctx.getArgs();

        if (charId == null) {
            out.println("You must be logged in to invite players.");
            return true;
        }

        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: invite <player>");
            return true;
        }

        String targetName = args.trim();
        GroupManager gm = GroupManager.getInstance();

        // Check if we're in a group
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);
        Group group;

        if (groupOpt.isEmpty()) {
            // Create a group first
            Optional<Group> newGroup = gm.createGroup(charId);
            if (newGroup.isEmpty()) {
                out.println("Failed to create group.");
                return true;
            }
            group = newGroup.get();
            out.println("You have formed a new group.");
        } else {
            group = groupOpt.get();
        }

        // Only leader can invite
        if (!group.isLeader(charId)) {
            out.println("Only the group leader can invite new members.");
            return true;
        }

        // Find the target player
        CharacterRecord targetRec = dao.findByName(targetName);
        if (targetRec == null) {
            out.println("No player named '" + targetName + "' found.");
            return true;
        }

        Integer targetId = dao.getCharacterIdByName(targetName);
        if (targetId == null) {
            out.println("Could not find that player.");
            return true;
        }

        // Can't invite yourself
        if (targetId.equals(charId)) {
            out.println("You can't invite yourself.");
            return true;
        }

        // Check if target is online
        ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetId);
        if (targetHandler == null) {
            out.println(targetRec.name + " is not online.");
            return true;
        }

        // Check if already in a group
        if (gm.isInGroup(targetId)) {
            out.println(targetRec.name + " is already in a group.");
            return true;
        }

        // Check if already has pending invite
        if (gm.hasPendingInvite(targetId)) {
            out.println(targetRec.name + " already has a pending group invitation.");
            return true;
        }

        // Send the invitation
        if (gm.inviteToGroup(group, targetId)) {
            CharacterRecord myRec = dao.getCharacterById(charId);
            String myName = myRec != null ? myRec.name : "Someone";
            
            out.println("You have invited " + targetRec.name + " to join your group.");
            targetHandler.out.println("\u001B[36m" + myName + " has invited you to join their group.\u001B[0m");
            targetHandler.out.println("Type 'accept' to join or 'decline' to refuse.");
            ClientHandler.sendPromptToCharacter(targetId);
        } else {
            out.println("Failed to send invitation.");
        }

        return true;
    }

    // ========== ACCEPT command ==========

    private boolean handleAcceptCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to accept invitations.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();

        // Check if already in a group
        if (gm.isInGroup(charId)) {
            out.println("You are already in a group. Leave your current group first.");
            return true;
        }

        // Check for pending invite
        Optional<Group> inviteGroup = gm.getPendingInviteGroup(charId);
        if (inviteGroup.isEmpty()) {
            out.println("You don't have any pending group invitations.");
            return true;
        }

        Group group = inviteGroup.get();
        CharacterRecord myRec = dao.getCharacterById(charId);
        String myName = myRec != null ? myRec.name : "Someone";

        if (gm.acceptInvite(charId)) {
            out.println("You have joined the group!");
            
            // Notify other group members
            notifyGroup(dao, group, myName + " has joined the group.", charId);
        } else {
            out.println("Failed to join the group.");
        }

        return true;
    }

    // ========== DECLINE command ==========

    private boolean handleDeclineCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to decline invitations.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();

        // Check for pending invite
        Optional<Group> inviteGroup = gm.getPendingInviteGroup(charId);
        if (inviteGroup.isEmpty()) {
            out.println("You don't have any pending group invitations.");
            return true;
        }

        Group group = inviteGroup.get();
        int leaderId = group.getLeaderId();
        CharacterRecord myRec = dao.getCharacterById(charId);
        String myName = myRec != null ? myRec.name : "Someone";

        if (gm.declineInvite(charId)) {
            out.println("You have declined the group invitation.");
            
            // Notify the leader
            ClientHandler leaderHandler = ClientHandler.charIdToSession.get(leaderId);
            if (leaderHandler != null) {
                leaderHandler.out.println(myName + " has declined your group invitation.");
                ClientHandler.sendPromptToCharacter(leaderId);
            }
        } else {
            out.println("Failed to decline invitation.");
        }

        return true;
    }

    // ========== LEAVE command ==========

    private boolean handleLeaveCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to leave a group.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            out.println("You are not in a group.");
            return true;
        }

        Group group = groupOpt.get();
        CharacterRecord myRec = dao.getCharacterById(charId);
        String myName = myRec != null ? myRec.name : "Someone";

        if (group.isLeader(charId)) {
            // Leader leaving - will disband the group
            out.println("As the leader, leaving will disband the group.");
            out.println("Use 'disband' to confirm, or 'leader <player>' to transfer leadership first.");
            return true;
        }

        // Notify group before leaving
        notifyGroup(dao, group, myName + " has left the group.", charId);

        if (gm.removeMember(charId)) {
            out.println("You have left the group.");
        } else {
            out.println("Failed to leave the group.");
        }

        return true;
    }

    // ========== DISBAND command ==========

    private boolean handleDisbandCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to disband a group.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            out.println("You are not in a group.");
            return true;
        }

        Group group = groupOpt.get();

        if (!group.isLeader(charId)) {
            out.println("Only the group leader can disband the group.");
            return true;
        }

        CharacterRecord myRec = dao.getCharacterById(charId);
        String myName = myRec != null ? myRec.name : "The leader";

        // Notify all members
        notifyGroup(dao, group, myName + " has disbanded the group.", null);

        if (gm.disbandGroup(group)) {
            out.println("You have disbanded the group.");
        } else {
            out.println("Failed to disband the group.");
        }

        return true;
    }

    // ========== KICK command ==========

    private boolean handleBootCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String args = ctx.getArgs();

        if (charId == null) {
            out.println("You must be logged in to kick members.");
            return true;
        }

        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: kick <player>");
            return true;
        }

        String targetName = args.trim();
        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            out.println("You are not in a group.");
            return true;
        }

        Group group = groupOpt.get();

        if (!group.isLeader(charId)) {
            out.println("Only the group leader can kick members.");
            return true;
        }

        // Find the target
        CharacterRecord targetRec = dao.findByName(targetName);
        if (targetRec == null) {
            out.println("No player named '" + targetName + "' found.");
            return true;
        }

        Integer targetId = dao.getCharacterIdByName(targetName);
        if (targetId == null || !group.isMember(targetId)) {
            out.println(targetRec.name + " is not in your group.");
            return true;
        }

        if (targetId.equals(charId)) {
            out.println("You can't kick yourself. Use 'disband' to disband the group.");
            return true;
        }

        // Notify the kicked player
        ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetId);
        if (targetHandler != null) {
            targetHandler.out.println("\u001B[31mYou have been kicked from the group.\u001B[0m");
            ClientHandler.sendPromptToCharacter(targetId);
        }

        if (gm.kickMember(group, targetId)) {
            out.println("You have kicked " + targetRec.name + " from the group.");
            notifyGroup(dao, group, targetRec.name + " has been kicked from the group.", charId);
        } else {
            out.println("Failed to kick " + targetRec.name + ".");
        }

        return true;
    }

    // ========== LEADER command ==========

    private boolean handleLeaderCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String args = ctx.getArgs();

        if (charId == null) {
            out.println("You must be logged in to transfer leadership.");
            return true;
        }

        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: leader <player>");
            return true;
        }

        String targetName = args.trim();
        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            out.println("You are not in a group.");
            return true;
        }

        Group group = groupOpt.get();

        if (!group.isLeader(charId)) {
            out.println("Only the group leader can transfer leadership.");
            return true;
        }

        // Find the target
        CharacterRecord targetRec = dao.findByName(targetName);
        if (targetRec == null) {
            out.println("No player named '" + targetName + "' found.");
            return true;
        }

        Integer targetId = dao.getCharacterIdByName(targetName);
        if (targetId == null || !group.isMember(targetId)) {
            out.println(targetRec.name + " is not in your group.");
            return true;
        }

        if (targetId.equals(charId)) {
            out.println("You are already the leader.");
            return true;
        }

        CharacterRecord myRec = dao.getCharacterById(charId);
        String myName = myRec != null ? myRec.name : "The former leader";

        if (gm.transferLeadership(group, targetId)) {
            out.println("You have transferred leadership to " + targetRec.name + ".");
            notifyGroup(dao, group, myName + " has transferred leadership to " + targetRec.name + ".", charId);
            
            // Notify new leader specifically
            ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetId);
            if (targetHandler != null) {
                targetHandler.out.println("\u001B[33mYou are now the group leader!\u001B[0m");
                ClientHandler.sendPromptToCharacter(targetId);
            }
        } else {
            out.println("Failed to transfer leadership.");
        }

        return true;
    }

    // ========== FOLLOW command ==========

    private boolean handleFollowCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to follow.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            out.println("You must be in a group to follow someone.");
            return true;
        }

        Group group = groupOpt.get();

        if (group.isLeader(charId)) {
            out.println("You are the leader. You can't follow yourself!");
            return true;
        }

        if (group.isFollowing(charId)) {
            out.println("You are already following the group leader.");
            return true;
        }

        CharacterRecord leaderRec = dao.getCharacterById(group.getLeaderId());
        String leaderName = leaderRec != null ? leaderRec.name : "the leader";

        if (gm.startFollowing(charId)) {
            out.println("You are now following " + leaderName + ".");
            
            // Notify leader
            ClientHandler leaderHandler = ClientHandler.charIdToSession.get(group.getLeaderId());
            if (leaderHandler != null) {
                CharacterRecord myRec = dao.getCharacterById(charId);
                String myName = myRec != null ? myRec.name : "Someone";
                leaderHandler.out.println(myName + " is now following you.");
                ClientHandler.sendPromptToCharacter(group.getLeaderId());
            }
        } else {
            out.println("Failed to start following.");
        }

        return true;
    }

    // ========== UNFOLLOW command ==========

    private boolean handleUnfollowCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;

        if (charId == null) {
            out.println("You must be logged in to stop following.");
            return true;
        }

        GroupManager gm = GroupManager.getInstance();
        Optional<Group> groupOpt = gm.getGroupForCharacter(charId);

        if (groupOpt.isEmpty()) {
            out.println("You are not in a group.");
            return true;
        }

        Group group = groupOpt.get();

        if (!group.isFollowing(charId)) {
            out.println("You are not following anyone.");
            return true;
        }

        CharacterRecord leaderRec = dao.getCharacterById(group.getLeaderId());
        String leaderName = leaderRec != null ? leaderRec.name : "the leader";

        if (gm.stopFollowing(charId)) {
            out.println("You are no longer following " + leaderName + ".");
            
            // Notify leader
            ClientHandler leaderHandler = ClientHandler.charIdToSession.get(group.getLeaderId());
            if (leaderHandler != null) {
                CharacterRecord myRec = dao.getCharacterById(charId);
                String myName = myRec != null ? myRec.name : "Someone";
                leaderHandler.out.println(myName + " is no longer following you.");
                ClientHandler.sendPromptToCharacter(group.getLeaderId());
            }
        } else {
            out.println("Failed to stop following.");
        }

        return true;
    }

    // ========== Utility methods ==========

    /**
     * Send a message to all group members except the excluded one.
     */
    private void notifyGroup(CharacterDAO dao, Group group, String message, Integer excludeCharId) {
        for (int memberId : group.getMemberIds()) {
            if (excludeCharId != null && memberId == excludeCharId) {
                continue;
            }
            ClientHandler handler = ClientHandler.charIdToSession.get(memberId);
            if (handler != null) {
                handler.out.println("\u001B[36m[Group] " + message + "\u001B[0m");
                ClientHandler.sendPromptToCharacter(memberId);
            }
        }
    }
}
