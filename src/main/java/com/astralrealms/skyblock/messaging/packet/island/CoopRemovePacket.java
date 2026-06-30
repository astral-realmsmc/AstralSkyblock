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
public class CoopRemovePacket implements Packet {

    private UUID islandId;
    private UUID playerId;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId = msg.readUUID();
        this.playerId = msg.readUUID();
    }
}
