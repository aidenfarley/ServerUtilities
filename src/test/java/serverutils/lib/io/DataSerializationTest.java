package serverutils.lib.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class DataSerializationTest {

    @Test
    void primitivesAndIdentifiersRoundTrip() {
        ByteBuf buffer = Unpooled.buffer();
        UUID expectedId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        DataOut output = new DataOut(buffer);

        output.writeBoolean(true);
        output.writeVarInt(123456);
        output.writeString("Server Utilities");
        output.writeUUID(expectedId);

        DataIn input = new DataIn(buffer);
        assertTrue(input.readBoolean());
        assertEquals(123456, input.readVarInt());
        assertEquals("Server Utilities", input.readString());
        assertEquals(expectedId, input.readUUID());
        assertFalse(input.isReadable());
    }
}
