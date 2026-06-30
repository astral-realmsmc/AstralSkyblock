package com.astralrealms.skyblock.messaging.packet.island;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CoopAddPacket implements Packet {

    private UUID islandId;
    private UUID playerId;
    private UUID addedBy;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
        msg.writeUUID(addedBy);
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId = msg.readUUID();
        this.playerId = msg.readUUID();
        this.addedBy  = msg.readUUID();
    }
}
