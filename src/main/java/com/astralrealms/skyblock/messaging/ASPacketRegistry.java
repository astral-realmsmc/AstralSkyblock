package com.astralrealms.skyblock.messaging;

import com.astralrealms.core.packet.PacketRegistry;
import com.astralrealms.skyblock.messaging.packet.LongObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.LongObjectUpdatePacket;
import com.astralrealms.skyblock.messaging.packet.MemberObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.MemberObjectUpdatePacket;
import com.astralrealms.skyblock.messaging.packet.UniqueObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.UniqueObjectUpdatePacket;

public class ASPacketRegistry extends PacketRegistry {

    public ASPacketRegistry() {
        this.registerPacket(0x00, UniqueObjectUpdatePacket::new);
        this.registerPacket(0x01, UniqueObjectDeletePacket::new);
        this.registerPacket(0x02, LongObjectUpdatePacket::new);
        this.registerPacket(0x03, LongObjectDeletePacket::new);
        this.registerPacket(0x04, MemberObjectUpdatePacket::new);
        this.registerPacket(0x05, MemberObjectDeletePacket::new);
    }
}
