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
 * create it from that blueprint. With a {@link #dimension}, that dimension's world is loaded (or
 * created) too, once the island is. Broadcast; every other server ignores it.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandLoadRequestPacket implements Packet {

    private UUID islandId;
    private UUID serverId;
    @Nullable
    private String blueprintId;
    @Nullable
    private String dimension;

    public IslandLoadRequestPacket(UUID islandId, UUID serverId, @Nullable String blueprintId) {
        this(islandId, serverId, blueprintId, null);
    }

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeUUID(serverId);
        binaryMessage.writeOptional(blueprintId, BinaryMessage::writeString);
        binaryMessage.writeOptional(dimension, BinaryMessage::writeString);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.serverId = binaryMessage.readUUID();
        this.blueprintId = binaryMessage.readOptional(BinaryMessage::readString).orElse(null);
        this.dimension = binaryMessage.readOptional(BinaryMessage::readString).orElse(null);
    }
}
