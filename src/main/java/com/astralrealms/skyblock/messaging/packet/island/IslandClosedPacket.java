package com.astralrealms.skyblock.messaging.packet.island;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Broadcast when an island is closed to visitors, so that whichever server hosts its world sends the
 * visitors already standing on it away. Closing is enforced on entry by
 * {@link com.astralrealms.skyblock.listener.IslandListener}, but the player who closes the island is
 * not necessarily on the server hosting it — without this, closing would only keep out the visitors
 * who had not arrived yet.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandClosedPacket implements Packet {

    private UUID islandId;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
    }
}
