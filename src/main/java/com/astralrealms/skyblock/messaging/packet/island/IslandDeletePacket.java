package com.astralrealms.skyblock.messaging.packet.island;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Broadcast when an island is deleted so that whichever server currently hosts the island's world
 * drops it from memory <b>without saving</b>. Without this, a remote host would still hold the
 * {@link com.infernalsuite.asp.api.world.SlimeWorldInstance} and re-persist it on its next save,
 * resurrecting the world row this server just deleted from MySQL.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandDeletePacket implements Packet {

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
