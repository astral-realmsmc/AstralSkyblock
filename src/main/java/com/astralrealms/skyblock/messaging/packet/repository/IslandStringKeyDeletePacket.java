package com.astralrealms.skyblock.messaging.packet.repository;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandStringKeyDeletePacket implements Packet {

    private UUID islandId;
    private String key;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeString(key);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.key = binaryMessage.readString();
    }
}
