package serverutils.lib.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;

import serverutils.lib.EnumTeamStatus;

final class ForgeTeamMembership {

    private final Map<ForgePlayer, EnumTeamStatus> statuses = new HashMap<>();
    private final Set<ForgePlayer> requestingInvites = new HashSet<>();
    private final Map<ForgePlayer, EnumTeamStatus> statusesView = Collections.unmodifiableMap(statuses);

    Map<ForgePlayer, EnumTeamStatus> mutableStatuses() {
        return statuses;
    }

    Map<ForgePlayer, EnumTeamStatus> statusesView() {
        return statusesView;
    }

    EnumTeamStatus getStatus(ForgePlayer player) {
        return statuses.get(player);
    }

    EnumTeamStatus putStatus(ForgePlayer player, EnumTeamStatus status) {
        return statuses.put(player, status);
    }

    EnumTeamStatus removeStatus(ForgePlayer player) {
        return statuses.remove(player);
    }

    boolean addInviteRequest(ForgePlayer player) {
        return requestingInvites.add(player);
    }

    boolean removeInviteRequest(ForgePlayer player) {
        return requestingInvites.remove(player);
    }

    boolean isRequestingInvite(ForgePlayer player) {
        return requestingInvites.contains(player);
    }

    void writeTo(NBTTagCompound nbt) {
        NBTTagCompound playerTags = new NBTTagCompound();
        for (Map.Entry<ForgePlayer, EnumTeamStatus> entry : statuses.entrySet()) {
            playerTags.setString(entry.getKey().getName(), entry.getValue().getName());
        }
        nbt.setTag("Players", playerTags);

        NBTTagList inviteRequests = new NBTTagList();
        for (ForgePlayer player : requestingInvites) {
            inviteRequests.appendTag(new NBTTagString(player.getName()));
        }
        nbt.setTag("RequestingInvite", inviteRequests);
    }

    void readFrom(ForgeTeam team, NBTTagCompound nbt) {
        statuses.clear();

        if (nbt.hasKey("Players")) {
            NBTTagCompound playerTags = nbt.getCompoundTag("Players");
            for (String playerName : playerTags.func_150296_c()) {
                ForgePlayer player = team.universe.getPlayer(playerName);
                if (player == null) {
                    continue;
                }

                EnumTeamStatus status = EnumTeamStatus.NAME_MAP.get(playerTags.getString(playerName));
                if (status.canBeSet()) {
                    team.setStatus(player, status);
                }
            }
        }

        NBTTagList inviteRequests = nbt.getTagList("RequestingInvite", Constants.NBT.TAG_STRING);
        for (int i = 0; i < inviteRequests.tagCount(); i++) {
            ForgePlayer player = team.universe.getPlayer(inviteRequests.getStringTagAt(i));
            if (player != null && !team.isMember(player)) {
                team.setRequestingInvite(player, true);
            }
        }

        NBTTagList invitedPlayers = nbt.getTagList("Invited", Constants.NBT.TAG_STRING);
        for (int i = 0; i < invitedPlayers.tagCount(); i++) {
            ForgePlayer player = team.universe.getPlayer(invitedPlayers.getStringTagAt(i));
            if (player != null && !team.isMember(player)) {
                team.setStatus(player, EnumTeamStatus.INVITED);
            }
        }
    }
}
