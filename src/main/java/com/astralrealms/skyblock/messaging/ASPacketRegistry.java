package com.astralrealms.skyblock.messaging;

import com.astralrealms.core.packet.PacketRegistry;
import com.astralrealms.skyblock.messaging.packet.UniqueObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.UniqueObjectUpdatePacket;

public class ASPacketRegistry extends PacketRegistry {

    public ASPacketRegistry() {
        this.registerPacket(0x00, UniqueObjectUpdatePacket::new);
        this.registerPacket(0x01, UniqueObjectDeletePacket::new);
    }
}
