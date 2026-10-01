package com.astralrealms.skyblock.messaging.packet.island;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Asks {@link #serverId} to load an island's world, or — when {@link #blueprintId} is set — to
 * create it from that blueprint. Broadcast; every other server ignores it.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandLoadRequestPacket implements Packet {

    private UUID islandId;
    private UUID serverId;
    @Nullable
    private String blueprintId;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeUUID(serverId);
        binaryMessage.writeOptional(blueprintId, BinaryMessage::writeString);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.serverId = binaryMessage.readUUID();
        this.blueprintId = binaryMessage.readOptional(BinaryMessage::readString).orElse(null);
    }
}
