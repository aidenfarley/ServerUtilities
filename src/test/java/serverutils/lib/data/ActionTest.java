package serverutils.lib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import serverutils.lib.icon.Icon;

class ActionTest {

    @Test
    void instancesSortByOrderThenTitle() {
        Action.Inst later = instance("later", "Alpha", 10);
        Action.Inst firstAlphabetically = instance("alpha", "Alpha", 5);
        Action.Inst secondAlphabetically = instance("beta", "Beta", 5);

        assertEquals(1, Integer.signum(later.compareTo(firstAlphabetically)));
        assertEquals(-1, Integer.signum(firstAlphabetically.compareTo(secondAlphabetically)));
    }

    @Test
    void clearerAliasesPreserveIdentitySemantics() {
        Action first = action("same", "First", 0).setRequiresConfirm();
        Action sameId = action("same", "Second", 1);

        assertTrue(first.requiresConfirmation());
        assertTrue(first.getRequireConfirm());
        assertTrue(first.hasSameId(sameId));
        assertFalse(first.equals(sameId));
    }

    private static Action.Inst instance(String id, String title, int order) {
        return new Action.Inst(action(id, title, order), Action.Type.ENABLED);
    }

    private static Action action(String id, String title, int order) {
        return new Action(new ResourceLocation("serverutils", id), new ChatComponentText(title), Icon.EMPTY, order) {

            @Override
            public Type getType(ForgePlayer player, NBTTagCompound data) {
                return Type.ENABLED;
            }

            @Override
            public void onAction(ForgePlayer player, NBTTagCompound data) {}
        };
    }
}
