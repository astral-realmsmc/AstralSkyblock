package com.astralrealms.skyblock.messaging.packet.repository;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LongObjectDeletePacket implements Packet {

    private long id;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeLong(id);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.id = binaryMessage.readLong();
    }
}
