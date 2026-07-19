package serverutils.lib.data;

import java.util.function.Predicate;

import net.minecraft.event.ClickEvent;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import serverutils.ServerUtilities;
import serverutils.lib.EnumTeamStatus;
import serverutils.lib.gui.GuiIcons;
import serverutils.lib.util.StringUtils;
import serverutils.net.MessageMyTeamPlayerList;

public class ServerUtilitiesTeamGuiActions {

    private static final Predicate<EnumTeamStatus> NO_ENEMIES_PREDICATE = status -> status != EnumTeamStatus.ENEMY;
    private static final Predicate<EnumTeamStatus> MEMBERS_PREDICATE = status -> status
            .isEqualOrGreaterThan(EnumTeamStatus.MEMBER);
    private static final Predicate<EnumTeamStatus> ALLIES_PREDICATE = MEMBERS_PREDICATE.negate()
            .and(NO_ENEMIES_PREDICATE);
    private static final Predicate<EnumTeamStatus> ENEMIES_PREDICATE = status -> status == EnumTeamStatus.ENEMY
            || status == EnumTeamStatus.NONE;

    public static final TeamAction CONFIG = new TeamAction(ServerUtilities.MOD_ID, "config", GuiIcons.SETTINGS, -100) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return player.getTeam().isModerator(player) ? Type.ENABLED : Type.DISABLED;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            ServerUtilitiesAPI.editServerConfig(player.getPlayer(), player.getTeam().getSettings(), player.getTeam());
        }
    }.setTitle(new ChatComponentTranslation("gui.settings"));

    public static final TeamAction INFO = new TeamAction(ServerUtilities.MOD_ID, "info", GuiIcons.INFO, 0) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return Type.INVISIBLE;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            // TODO: Open info gui
        }
    }.setTitle(new ChatComponentTranslation("gui.info"));

    public static final TeamAction MEMBERS = new TeamAction(ServerUtilities.MOD_ID, "members", GuiIcons.FRIENDS, 30) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return (player.getTeam().isModerator(player) && player.getUniverse().getPlayers().size() > 1) ? Type.ENABLED
                    : Type.DISABLED;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            if (data.hasNoTags()) {
                new MessageMyTeamPlayerList(getId(), player, NO_ENEMIES_PREDICATE).sendTo(player.getPlayer());
                return;
            }

            ForgePlayer p = getPayloadPlayer(player, data);

            if (p == null || p == player) {
                return;
            }

            switch (data.getString("action")) {
                case "kick": {
                    if (player.getTeam().isMember(p)) {
                        player.getTeam().removeMember(p);
                        player.getTeam().setRequestingInvite(p, true);
                    }

                    break;
                }
                case "invite": {
                    player.getTeam().setStatus(p, EnumTeamStatus.INVITED);

                    if (player.getTeam().isRequestingInvite(p)) {
                        if (p.hasTeam()) {
                            player.getTeam().setRequestingInvite(p, false);
                        } else {
                            player.getTeam().addMember(p, false);
                        }
                    } else if (p.isOnline()) {
                        IChatComponent component = new ChatComponentTranslation(
                                "serverutilities.lang.team.invited_you",
                                player.getTeam(),
                                player.getDisplayName());
                        component.getChatStyle().setChatClickEvent(
                                new ClickEvent(
                                        ClickEvent.Action.RUN_COMMAND,
                                        "/team join " + player.getTeam().getId()));
                        p.getPlayer().addChatComponentMessage(component);
                    }

                    break;
                }
                case "cancel_invite": {
                    if (player.getTeam().getHighestStatus(p) == EnumTeamStatus.INVITED) {
                        player.getTeam().setStatus(p, EnumTeamStatus.NONE);
                    }

                    break;
                }
                case "deny_request": {
                    player.getTeam().setRequestingInvite(p, false);
                    break;
                }
            }
        }
    };

    public static final TeamAction ALLIES = new TeamAction(ServerUtilities.MOD_ID, "allies", GuiIcons.STAR, 40) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return (player.getTeam().isModerator(player) && player.getUniverse().getPlayers().size() > 1) ? Type.ENABLED
                    : Type.DISABLED;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            if (data.hasNoTags()) {
                new MessageMyTeamPlayerList(getId(), player, ALLIES_PREDICATE).sendTo(player.getPlayer());
            }

            ForgePlayer p = getPayloadPlayer(player, data);

            if (p != null && p != player) {
                player.getTeam().setStatus(p, data.getBoolean("add") ? EnumTeamStatus.ALLY : EnumTeamStatus.NONE);
            }
        }
    };

    public static final TeamAction MODERATORS = new TeamAction(
            ServerUtilities.MOD_ID,
            "moderators",
            GuiIcons.SHIELD,
            50) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return (player.getTeam().isOwner(player) && player.getTeam().getMembers().size() > 1) ? Type.ENABLED
                    : Type.DISABLED;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            if (data.hasNoTags()) {
                new MessageMyTeamPlayerList(getId(), player, MEMBERS_PREDICATE).sendTo(player.getPlayer());
                return;
            }

            ForgePlayer p = getPayloadPlayer(player, data);

            if (p != null && p != player) {
                player.getTeam().setStatus(p, data.getBoolean("add") ? EnumTeamStatus.MOD : EnumTeamStatus.NONE);
            }
        }
    };

    public static final TeamAction ENEMIES = new TeamAction(ServerUtilities.MOD_ID, "enemies", GuiIcons.CLOSE, 60) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return (player.getTeam().isModerator(player) && player.getUniverse().getPlayers().size() > 1) ? Type.ENABLED
                    : Type.DISABLED;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            if (data.hasNoTags()) {
                new MessageMyTeamPlayerList(getId(), player, ENEMIES_PREDICATE).sendTo(player.getPlayer());
            }

            ForgePlayer p = getPayloadPlayer(player, data);

            if (p != null && p != player) {
                player.getTeam().setStatus(p, data.getBoolean("add") ? EnumTeamStatus.ENEMY : EnumTeamStatus.NONE);
            }
        }
    };

    public static final TeamAction LEAVE = new TeamAction(ServerUtilities.MOD_ID, "leave", GuiIcons.REMOVE, 10000) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return (!player.getTeam().isOwner(player) || player.getTeam().getMembers().size() <= 1) ? Type.ENABLED
                    : Type.INVISIBLE;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            player.getTeam().removeMember(player);
            ServerUtilitiesAPI.sendCloseGuiPacket(player.getPlayer());
        }
    }.setRequiresConfirm();

    public static final TeamAction TRANSFER_OWNERSHIP = new TeamAction(
            ServerUtilities.MOD_ID,
            "transfer_ownership",
            GuiIcons.RIGHT,
            10000) {

        @Override
        public Type getType(ForgePlayer player, NBTTagCompound data) {
            return (!player.getTeam().isOwner(player) || player.getTeam().getMembers().size() <= 1) ? Type.INVISIBLE
                    : Type.ENABLED;
        }

        @Override
        public void onAction(ForgePlayer player, NBTTagCompound data) {
            if (data.hasNoTags()) {
                new MessageMyTeamPlayerList(getId(), player, MEMBERS_PREDICATE).sendTo(player.getPlayer());
            }

            ForgePlayer p = getPayloadPlayer(player, data);

            if (p != null && p != player) {
                player.getTeam().setStatus(p, EnumTeamStatus.OWNER);
            }
        }
    };

    static ForgePlayer getPayloadPlayer(ForgePlayer actor, NBTTagCompound data) {
        String identifier = data.getString("player");
        ForgePlayer player = actor.getUniverse().getPlayer(StringUtils.fromString(identifier));
        return player == null ? actor.getUniverse().getPlayer(identifier) : player;
    }
}
