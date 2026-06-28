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
public class IslandLoadRequestPacket implements Packet {

    private UUID islandId;
    private UUID serverId;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeUUID(serverId);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.serverId = binaryMessage.readUUID();
    }
}
