package serverutils.lib.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import serverutils.ServerUtilities;
import serverutils.data.ClaimedChunk;
import serverutils.events.team.ForgeTeamConfigEvent;
import serverutils.events.team.ForgeTeamConfigSavedEvent;
import serverutils.events.team.ForgeTeamOwnerChangedEvent;
import serverutils.events.team.ForgeTeamPlayerJoinedEvent;
import serverutils.events.team.ForgeTeamPlayerLeftEvent;
import serverutils.lib.EnumTeamColor;
import serverutils.lib.EnumTeamStatus;
import serverutils.lib.config.ConfigGroup;
import serverutils.lib.config.IConfigCallback;
import serverutils.lib.icon.Icon;
import serverutils.lib.icon.PlayerHeadIcon;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.FinalIDObject;
import serverutils.lib.util.INBTSerializable;
import serverutils.lib.util.StringUtils;

public class ForgeTeam extends FinalIDObject implements INBTSerializable<NBTTagCompound>, IConfigCallback {

    public static final int MAX_TEAM_ID_LENGTH = 35;
    public static final Pattern TEAM_ID_PATTERN = Pattern.compile("^[a-z0-9_]{1," + MAX_TEAM_ID_LENGTH + "}$");

    private final short uid;
    public final Universe universe;
    public final TeamType type;
    /** @deprecated Use {@link #getOwner()} and team ownership methods. */
    @Deprecated
    public ForgePlayer owner;
    private final ForgeTeamPersistence persistence;
    private final ForgeTeamMembership membership;
    /** @deprecated Use status and membership methods or {@link #getPlayerStatusesView()}. */
    @Deprecated
    public final Map<ForgePlayer, EnumTeamStatus> players;
    private ConfigGroup cachedConfig;
    private IChatComponent cachedTitle;
    private Icon cachedIcon;
    /** @deprecated Use {@link #isDirty()}, {@link #markDirty()}, and {@link #markSaved()}. */
    @Deprecated
    public boolean needsSaving;
    /** @deprecated Use claimed-chunk mutation methods and {@link #getClaimedChunksView()}. */
    @Deprecated
    public final Set<ClaimedChunk> claimedChunks = new HashSet<>();

    public ForgeTeam(Universe u, short id, String n, TeamType t) {
        this(u, id, n, t, true);
    }

    ForgeTeam(Universe u, short id, String n, TeamType t, boolean initializePersistence) {
        super(n, t.isNone ? 0 : (StringUtils.FLAG_ID_DEFAULTS | StringUtils.FLAG_ID_ALLOW_EMPTY));
        uid = id;
        universe = u;
        type = t;
        membership = new ForgeTeamMembership();
        players = membership.mutableStatuses();
        persistence = new ForgeTeamPersistence();
        if (initializePersistence) {
            persistence.initialize(this);
        }
        clearCache();
        cachedIcon = null;
        needsSaving = false;
    }

    void initializePersistence() {
        persistence.initialize(this);
    }

    public final short getUID() {
        return uid;
    }

    public final int hashCode() {
        return 31 * System.identityHashCode(universe) + uid;
    }

    public final boolean equals(Object o) {
        return o == this || o instanceof ForgeTeam other && universe == other.universe && uid == other.uid;
    }

    public final String getUIDCode() {
        return String.format(java.util.Locale.ROOT, "%04X", uid);
    }

    @Override
    public NBTTagCompound serializeNBT() {
        return persistence.serialize(this, membership);
    }

    @Override
    public void deserializeNBT(NBTTagCompound nbt) {
        persistence.deserialize(this, membership, nbt);
    }

    public void clearCache() {
        cachedTitle = null;
        cachedIcon = null;
        cachedConfig = null;
        persistence.clearCache();
    }

    public void markDirty() {
        needsSaving = true;
        universe.markChildDirty();
    }

    public boolean isDirty() {
        return needsSaving;
    }

    public void markSaved() {
        needsSaving = false;
    }

    public void initializeOwner(ForgePlayer player) {
        Objects.requireNonNull(player, "player");
        if (!type.isPlayer) {
            throw new IllegalStateException("Only player teams can have an owner");
        }
        requireSameUniverse(player);
        if (owner != null && owner != player) {
            throw new IllegalStateException("Team owner has already been initialized");
        }

        if (player.getTeam() != this) {
            player.setTeam(this);
        }
        if (owner == player) {
            return;
        }

        owner = player;
        universe.clearCache();
        player.markDirty();
        markDirty();
    }

    ForgePlayer getStoredOwner() {
        return owner;
    }

    void setStoredOwner(@Nullable ForgePlayer player) {
        if (player != null) {
            requireSameUniverse(player);
        }
        owner = player;
    }

    private void requireSameUniverse(ForgePlayer player) {
        if (player.getUniverse() != universe) {
            throw new IllegalArgumentException("Player and team belong to different universes");
        }
    }

    public Map<ForgePlayer, EnumTeamStatus> getPlayerStatusesView() {
        return membership.statusesView();
    }

    public Set<ClaimedChunk> getClaimedChunksView() {
        return Collections.unmodifiableSet(claimedChunks);
    }

    public boolean addClaimedChunk(ClaimedChunk chunk) {
        requireOwnedChunk(chunk);
        if (claimedChunks.add(chunk)) {
            clearCache();
            markDirty();
            return true;
        }
        return false;
    }

    public boolean removeClaimedChunk(ClaimedChunk chunk) {
        requireOwnedChunk(chunk);
        if (claimedChunks.remove(chunk)) {
            clearCache();
            markDirty();
            return true;
        }
        return false;
    }

    private void requireOwnedChunk(ClaimedChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (chunk.getTeam() != this) {
            throw new IllegalArgumentException("Claimed chunk belongs to a different team");
        }
    }

    public NBTDataStorage getData() {
        return persistence.data();
    }

    @Nullable
    public ForgePlayer getOwner() {
        return type.isPlayer ? owner : null;
    }

    public IChatComponent getTitle() {
        if (cachedTitle != null) {
            return cachedTitle;
        }

        if (persistence.title().isEmpty()) {
            cachedTitle = getOwner() != null ? getOwner().getDisplayName().appendText("'s Team")
                    : new ChatComponentTranslation("serverutilities.lang.team.no_team");
        } else {
            cachedTitle = new ChatComponentText(persistence.title());
        }

        cachedTitle = StringUtils.color(cachedTitle, getColor().getEnumChatFormatting());
        return cachedTitle;
    }

    public IChatComponent getCommandTitle() {
        IChatComponent component = getTitle().createCopy();

        if (!isValid()) {
            return component;
        }

        component.getChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("/team info " + getId())));
        component.getChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/team info " + getId()));
        component.getChatStyle().setColor(getColor().getEnumChatFormatting());
        return component;
    }

    public void setTitle(String s) {
        if (!persistence.title().equals(s)) {
            persistence.title(s);
            cachedTitle = null;
            markDirty();
        }
    }

    public String getDesc() {
        return persistence.description();
    }

    public void setDesc(String s) {
        if (!persistence.description().equals(s)) {
            persistence.description(s);
            markDirty();
        }
    }

    public EnumTeamColor getColor() {
        return persistence.color();
    }

    public void setColor(EnumTeamColor col) {
        if (persistence.color() != col) {
            persistence.color(col);
            cachedTitle = null;
            cachedIcon = null;
            markDirty();
        }
    }

    public Icon getIcon() {
        if (cachedIcon == null) {
            if (persistence.icon().isEmpty()) {
                if (getOwner() != null) {
                    cachedIcon = new PlayerHeadIcon(getOwner().getProfile().getId());
                } else {
                    cachedIcon = getColor().getColor();
                }
            } else {
                cachedIcon = Icon.getIcon(persistence.icon());
            }
        }

        return cachedIcon;
    }

    public void setIcon(String s) {
        if (!persistence.icon().equals(s)) {
            persistence.icon(s);
            cachedIcon = null;
            markDirty();
        }
    }

    public boolean isFreeToJoin() {
        return persistence.freeToJoin();
    }

    public void setFreeToJoin(boolean b) {
        if (persistence.freeToJoin() != b) {
            persistence.freeToJoin(b);
            markDirty();
        }
    }

    public EnumTeamStatus getFakePlayerStatus(ForgePlayer player) {
        return persistence.fakePlayerStatus();
    }

    public EnumTeamStatus getHighestStatus(@Nullable ForgePlayer player) {
        if (player == null) {
            return EnumTeamStatus.NONE;
        } else if (player.isFake()) {
            return persistence.fakePlayerStatus();
        } else if (isOwner(player)) {
            return EnumTeamStatus.OWNER;
        } else if (isModerator(player)) {
            return EnumTeamStatus.MOD;
        } else if (isMember(player)) {
            return EnumTeamStatus.MEMBER;
        } else if (isEnemy(player)) {
            return EnumTeamStatus.ENEMY;
        } else if (isAlly(player)) {
            return EnumTeamStatus.ALLY;
        } else if (isInvited(player)) {
            return EnumTeamStatus.INVITED;
        }

        return EnumTeamStatus.NONE;
    }

    private EnumTeamStatus getSetStatus(@Nullable ForgePlayer player) {
        if (player == null || !isValid()) {
            return EnumTeamStatus.NONE;
        } else if (player.isFake()) {
            return persistence.fakePlayerStatus();
        } else if (type == TeamType.SERVER && getId().equals("singleplayer")) {
            return EnumTeamStatus.MOD;
        }

        EnumTeamStatus status = membership.getStatus(player);
        return status == null ? EnumTeamStatus.NONE : status;
    }

    public boolean hasStatus(@Nullable ForgePlayer player, EnumTeamStatus status) {
        if (player == null || !isValid()) {
            return false;
        }

        if (player.isFake()) {
            return getFakePlayerStatus(player).isEqualOrGreaterThan(status);
        }

        return switch (status) {
            case NONE -> true;
            case ENEMY -> isEnemy(player);
            case ALLY -> isAlly(player);
            case INVITED -> isInvited(player);
            case MEMBER -> isMember(player);
            case MOD -> isModerator(player);
            case OWNER -> isOwner(player);
            default -> false;
        };
    }

    public boolean setStatus(@Nullable ForgePlayer player, EnumTeamStatus status) {
        if (player == null || !isValid() || player.isFake()) {
            return false;
        } else if (status == EnumTeamStatus.OWNER) {
            if (!isMember(player)) {
                return false;
            }

            if (!player.equalsPlayer(getOwner())) {
                universe.clearCache();
                ForgePlayer oldOwner = getOwner();
                owner = player;
                membership.removeStatus(player);
                new ForgeTeamOwnerChangedEvent(this, oldOwner).post();

                if (oldOwner != null) {
                    oldOwner.markDirty();
                }

                owner.markDirty();
                markDirty();
                return true;
            }

            return false;
        } else if (!status.isNone() && status.canBeSet()) {
            if (membership.putStatus(player, status) != status) {
                universe.clearCache();
                player.markDirty();
                markDirty();
                return true;
            }
        } else if (membership.removeStatus(player) != status) {
            universe.clearCache();
            player.markDirty();
            markDirty();
            return true;
        }

        return false;
    }

    public <C extends Collection<ForgePlayer>> C getPlayersWithStatus(C collection, EnumTeamStatus status) {
        if (!isValid()) {
            return collection;
        }

        for (ForgePlayer player : universe.getPlayers()) {
            if (!player.isFake() && hasStatus(player, status)) {
                collection.add(player);
            }
        }

        return collection;
    }

    public List<ForgePlayer> getPlayersWithStatus(EnumTeamStatus status) {
        return isValid() ? getPlayersWithStatus(new ArrayList<>(), status) : Collections.emptyList();
    }

    public boolean addMember(ForgePlayer player, boolean simulate) {
        if (isValid() && ((isOwner(player) || isInvited(player)) && !isMember(player))) {
            if (!simulate) {
                universe.clearCache();
                player.setTeam(this);
                membership.removeStatus(player);
                membership.removeInviteRequest(player);

                ForgeTeamPlayerJoinedEvent event = new ForgeTeamPlayerJoinedEvent(player);
                event.post();

                if (event.getDisplayGui() != null) {
                    event.getDisplayGui().run();
                }

                player.markDirty();
                markDirty();
            }

            return true;
        }

        return false;
    }

    public boolean removeMember(ForgePlayer player) {
        if (!isValid() || !isMember(player)) {
            return false;
        } else if (getMembers().size() == 1) {
            universe.clearCache();
            postPlayerLeftEvent(player);

            if (type.isPlayer) {
                delete();
            } else {
                setStatus(player, EnumTeamStatus.NONE);
            }

            player.setTeam(universe.getTeam(""));
            player.markDirty();
            markDirty();
            return true;
        } else if (isOwner(player)) {
            return false;
        }

        universe.clearCache();
        postPlayerLeftEvent(player);
        player.setTeam(universe.getTeam(""));
        setStatus(player, EnumTeamStatus.NONE);
        player.markDirty();
        markDirty();
        return true;
    }

    void postPlayerLeftEvent(ForgePlayer player) {
        new ForgeTeamPlayerLeftEvent(player).post();
    }

    public void delete() {
        universe.removeTeam(this);
    }

    public List<ForgePlayer> getMembers() {
        return getPlayersWithStatus(EnumTeamStatus.MEMBER);
    }

    public boolean isMember(@Nullable ForgePlayer player) {
        if (player == null) {
            return false;
        } else if (player.isFake()) {
            return persistence.fakePlayerStatus().isEqualOrGreaterThan(EnumTeamStatus.MEMBER);
        }

        return isValid() && equalsTeam(player.getTeam());
    }

    public boolean isAlly(@Nullable ForgePlayer player) {
        return isValid() && (isMember(player) || getSetStatus(player).isEqualOrGreaterThan(EnumTeamStatus.ALLY));
    }

    public boolean isInvited(@Nullable ForgePlayer player) {
        return isValid() && (isMember(player)
                || ((isFreeToJoin() || getSetStatus(player).isEqualOrGreaterThan(EnumTeamStatus.INVITED))
                        && !isEnemy(player)));
    }

    public boolean setRequestingInvite(@Nullable ForgePlayer player, boolean value) {
        if (player != null && isValid()) {
            if (value) {
                if (membership.addInviteRequest(player)) {
                    player.markDirty();
                    markDirty();
                    return true;
                }
            } else if (membership.removeInviteRequest(player)) {
                player.markDirty();
                markDirty();
                return true;
            }

            return false;
        }

        return false;
    }

    public boolean isRequestingInvite(@Nullable ForgePlayer player) {
        return player != null && isValid()
                && !isMember(player)
                && membership.isRequestingInvite(player)
                && !isEnemy(player);
    }

    public boolean isEnemy(@Nullable ForgePlayer player) {
        return getSetStatus(player) == EnumTeamStatus.ENEMY;
    }

    public boolean isModerator(@Nullable ForgePlayer player) {
        return isOwner(player) || isMember(player) && getSetStatus(player).isEqualOrGreaterThan(EnumTeamStatus.MOD);
    }

    public boolean isOwner(@Nullable ForgePlayer player) {
        return player != null && player.equalsPlayer(getOwner());
    }

    public ConfigGroup getSettings() {
        if (cachedConfig == null) {
            cachedConfig = ConfigGroup.newGroup("team_config");
            cachedConfig.setDisplayName(
                    new ChatComponentTranslation("gui.settings").appendSibling(
                            StringUtils.bold(
                                    StringUtils
                                            .color(new ChatComponentText(" #" + getId()), EnumChatFormatting.DARK_GRAY),
                                    false)));
            ForgeTeamConfigEvent event = new ForgeTeamConfigEvent(this, cachedConfig);
            event.post();

            ConfigGroup main = cachedConfig.getGroup(ServerUtilities.MOD_ID);
            main.setDisplayName(new ChatComponentText(ServerUtilities.MOD_NAME));
            main.addBool("free_to_join", persistence::freeToJoin, persistence::freeToJoin, false);

            ConfigGroup display = main.getGroup("display");
            display.addEnum("color", persistence::color, persistence::color, EnumTeamColor.NAME_MAP);
            display.addEnum(
                    "fake_player_status",
                    persistence::fakePlayerStatus,
                    persistence::fakePlayerStatus,
                    EnumTeamStatus.NAME_MAP_PERMS);
            display.addString("title", persistence::title, persistence::title, "");
            display.addString("desc", persistence::description, persistence::description, "");
        }

        return cachedConfig;
    }

    public boolean isValid() {
        if (type.isNone) {
            return false;
        }

        return type.isServer || getOwner() != null;
    }

    public boolean equalsTeam(@Nullable ForgeTeam team) {
        return equals(team);
    }

    public boolean anyPlayerHasPermission(String permission, EnumTeamStatus status) {
        for (ForgePlayer player : getPlayersWithStatus(status)) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }

        return false;
    }

    public boolean anyMemberHasPermission(String permission) {
        return anyPlayerHasPermission(permission, EnumTeamStatus.MEMBER);
    }

    public File getDataFile(String ext) {
        File dir = new File(universe.dataFolder, "teams/");

        if (ext.isEmpty()) {
            return new File(dir, getId() + ".dat");
        }

        File extFolder = new File(dir, ext);

        if (!extFolder.exists()) {
            extFolder.mkdirs();
        }

        File extFile = new File(extFolder, getId() + ".dat");

        if (!extFile.exists()) {
            File oldExtFile = new File(dir, getId() + "." + ext + ".dat");

            if (oldExtFile.exists()) {
                oldExtFile.renameTo(extFile);
                oldExtFile.deleteOnExit();
            }
        }

        return extFile;
    }

    @Override
    public void onConfigSaved(ConfigGroup group, ICommandSender sender) {
        clearCache();
        markDirty();
        new ForgeTeamConfigSavedEvent(this, group, sender).post();
    }

    public List<EntityPlayerMP> getOnlineMembers() {
        List<EntityPlayerMP> list = new ArrayList<>();

        for (ForgePlayer player : getMembers()) {
            EntityPlayerMP p = player.getNullablePlayer();

            if (p != null) {
                list.add(p);
            }
        }

        return list;
    }

    public long getLastActivity() {
        if (persistence.lastActivity() == 0) {
            long latestActivity = 0;
            for (ForgePlayer player : getMembers()) {
                latestActivity = Math.max(player.getLastTimeSeen(), latestActivity);
            }
            persistence.lastActivity(
                    System.currentTimeMillis() - Ticks.get(universe.ticks.ticks() - latestActivity).millis());
            markDirty();
        }
        return persistence.lastActivity();
    }

    public void refreshActivity() {
        persistence.lastActivity(System.currentTimeMillis());
        markDirty();
    }

    public Ticks getHighestTimer(String node) {
        Ticks highest = Ticks.NO_TICKS;
        for (ForgePlayer player : getMembers()) {
            Ticks ticks = player.getRankConfig(node).getTimer();
            if (ticks.millis() == Ticks.NO_TICKS.millis()) {
                return ticks;
            }

            if (ticks.millis() > highest.millis()) {
                highest = ticks;
            }
        }

        return highest;
    }
}
