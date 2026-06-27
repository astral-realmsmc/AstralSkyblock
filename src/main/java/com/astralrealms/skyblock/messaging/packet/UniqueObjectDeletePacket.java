package com.astralrealms.skyblock.messaging.packet;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UniqueObjectDeletePacket implements Packet {

    private UUID uniqueId;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(uniqueId);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.uniqueId = binaryMessage.readUUID();
    }
}
