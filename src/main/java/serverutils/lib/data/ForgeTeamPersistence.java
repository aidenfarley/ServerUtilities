package serverutils.lib.data;

import net.minecraft.nbt.NBTTagCompound;

import serverutils.events.team.ForgeTeamDataEvent;
import serverutils.lib.EnumTeamColor;
import serverutils.lib.EnumTeamStatus;

final class ForgeTeamPersistence {

    private final NBTDataStorage dataStorage;
    private String title;
    private String description;
    private EnumTeamColor color;
    private String icon;
    private boolean freeToJoin;
    private EnumTeamStatus fakePlayerStatus;
    private long lastActivity;
    private boolean initialized;

    ForgeTeamPersistence() {
        dataStorage = new NBTDataStorage();
        title = "";
        description = "";
        color = EnumTeamColor.BLUE;
        icon = "";
        freeToJoin = false;
        fakePlayerStatus = EnumTeamStatus.ALLY;
        lastActivity = 0L;
        initialized = false;
    }

    void initialize(ForgeTeam team) {
        if (initialized) {
            throw new IllegalStateException("Team persistence has already been initialized");
        }
        initialized = true;
        new ForgeTeamDataEvent(team, dataStorage).post();
    }

    NBTTagCompound serialize(ForgeTeam team, ForgeTeamMembership membership) {
        NBTTagCompound nbt = new NBTTagCompound();
        if (team.getStoredOwner() != null) {
            nbt.setString("Owner", team.getStoredOwner().getName());
        }

        nbt.setString("Title", title);
        nbt.setString("Desc", description);
        nbt.setString("Color", EnumTeamColor.NAME_MAP.getName(color));
        nbt.setString("Icon", icon);
        nbt.setBoolean("FreeToJoin", freeToJoin);
        nbt.setString("FakePlayerStatus", EnumTeamStatus.NAME_MAP_PERMS.getName(fakePlayerStatus));
        nbt.setLong("LastActivity", lastActivity);
        membership.writeTo(nbt);
        nbt.setTag("Data", dataStorage.serializeNBT());
        return nbt;
    }

    void deserialize(ForgeTeam team, ForgeTeamMembership membership, NBTTagCompound nbt) {
        team.setStoredOwner(team.universe.getPlayer(nbt.getString("Owner")));
        if (!team.isValid()) {
            return;
        }

        title = nbt.getString("Title");
        description = nbt.getString("Desc");
        color = EnumTeamColor.NAME_MAP.get(nbt.getString("Color"));
        icon = nbt.getString("Icon");
        freeToJoin = nbt.getBoolean("FreeToJoin");
        fakePlayerStatus = EnumTeamStatus.NAME_MAP_PERMS.get(nbt.getString("FakePlayerStatus"));
        lastActivity = nbt.getLong("LastActivity");
        membership.readFrom(team, nbt);
        dataStorage.deserializeNBT(nbt.getCompoundTag("Data"));
    }

    NBTDataStorage data() {
        return dataStorage;
    }

    void clearCache() {
        dataStorage.clearCache();
    }

    String title() {
        return title;
    }

    void title(String value) {
        title = value;
    }

    String description() {
        return description;
    }

    void description(String value) {
        description = value;
    }

    EnumTeamColor color() {
        return color;
    }

    void color(EnumTeamColor value) {
        color = value;
    }

    String icon() {
        return icon;
    }

    void icon(String value) {
        icon = value;
    }

    boolean freeToJoin() {
        return freeToJoin;
    }

    void freeToJoin(boolean value) {
        freeToJoin = value;
    }

    EnumTeamStatus fakePlayerStatus() {
        return fakePlayerStatus;
    }

    void fakePlayerStatus(EnumTeamStatus value) {
        fakePlayerStatus = value;
    }

    long lastActivity() {
        return lastActivity;
    }

    void lastActivity(long value) {
        lastActivity = value;
    }
}
