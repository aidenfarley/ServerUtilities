package serverutils.lib.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import serverutils.lib.icon.Icon;
import serverutils.lib.io.DataIn;
import serverutils.lib.io.DataOut;

public abstract class Action {

    public enum Type {

        ENABLED,
        DISABLED,
        INVISIBLE;

        public static Type fromBoolean(boolean value) {
            return value ? ENABLED : DISABLED;
        }

        public boolean isEnabled() {
            return this == ENABLED;
        }

        public boolean isVisible() {
            return this != INVISIBLE;
        }
    }

    public static class Inst implements Comparable<Inst> {

        public static final DataOut.Serializer<Inst> SERIALIZER = (data, object) -> object.writeData(data);
        public static final DataIn.Deserializer<Inst> DESERIALIZER = Inst::new;

        public final ResourceLocation id;
        public final IChatComponent title;
        public final boolean requiresConfirm;
        public final Icon icon;
        public boolean enabled;
        public int order;

        private Inst(DataIn data) {
            id = data.readResourceLocation();
            title = data.readTextComponent();
            requiresConfirm = data.readBoolean();
            icon = data.readIcon();
            enabled = data.readBoolean();
            order = data.readVarInt();
        }

        public Inst(Action action, Action.Type t) {
            id = action.getId();
            title = action.getTitle();
            requiresConfirm = action.requiresConfirmation();
            icon = action.getIcon();
            enabled = t.isEnabled();
            order = action.getOrder();
        }

        private void writeData(DataOut data) {
            data.writeResourceLocation(id);
            data.writeTextComponent(title);
            data.writeBoolean(requiresConfirm);
            data.writeIcon(icon);
            data.writeBoolean(enabled);
            data.writeVarInt(order);
        }

        @Override
        public int compareTo(Inst o) {
            int i = Integer.compare(order, o.order);
            return i == 0 ? title.getUnformattedText().compareToIgnoreCase(o.title.getUnformattedText()) : i;
        }
    }

    private final ResourceLocation id;
    private IChatComponent title;
    private boolean requiresConfirm;
    private Icon icon;
    private int order;

    public Action(ResourceLocation id, IChatComponent title, Icon icon, int order) {
        this.id = id;
        this.title = title;
        requiresConfirm = false;
        this.icon = icon;
        this.order = order;
    }

    public final ResourceLocation getId() {
        return id;
    }

    public abstract Type getType(ForgePlayer player, NBTTagCompound data);

    public abstract void onAction(ForgePlayer player, NBTTagCompound data);

    public Action setTitle(IChatComponent title) {
        this.title = title;
        return this;
    }

    public IChatComponent getTitle() {
        return title;
    }

    public Action setRequiresConfirm() {
        requiresConfirm = true;
        return this;
    }

    @Deprecated
    public boolean getRequireConfirm() {
        return requiresConfirmation();
    }

    public boolean requiresConfirmation() {
        return requiresConfirm;
    }

    public Action setIcon(Icon icon) {
        this.icon = icon;
        return this;
    }

    public Icon getIcon() {
        return icon;
    }

    public Action setOrder(int order) {
        this.order = MathHelper.clamp_int(order, Short.MIN_VALUE, Short.MAX_VALUE);
        return this;
    }

    public int getOrder() {
        return order;
    }

    public final int hashCode() {
        return id.hashCode();
    }

    /** Actions use instance identity for equality; IDs are compared explicitly through {@link #hasSameId(Action)}. */
    public final boolean equals(Object other) {
        return other == this;
    }

    public final boolean hasSameId(Action other) {
        return other != null && id.equals(other.id);
    }

    public final String toString() {
        return id.toString();
    }
}
