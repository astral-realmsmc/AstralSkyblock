package com.astralrealms.skyblock.messaging.packet.island;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Broadcast when a player must be removed from an island world they may no longer stand in (a ban).
 * Only the server where that player is online acts on it — the island may be hosted anywhere in the
 * group, and the player may be on a different server than the one that issued the ban.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandEvictPacket implements Packet {

    private UUID islandId;
    private UUID playerUuid;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeUUID(playerUuid);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.playerUuid = binaryMessage.readUUID();
    }
}
