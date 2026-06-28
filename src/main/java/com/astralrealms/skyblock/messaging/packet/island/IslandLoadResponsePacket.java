package com.astralrealms.skyblock.messaging.packet.island;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandLoadResponsePacket implements Packet {

    private boolean success;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeBoolean(success);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.success = binaryMessage.readBoolean();
    }
}
