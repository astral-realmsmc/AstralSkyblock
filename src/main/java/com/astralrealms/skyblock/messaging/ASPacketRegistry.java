package com.astralrealms.skyblock.messaging;

import com.astralrealms.core.packet.PacketRegistry;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadRequestPacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadResponsePacket;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectUpdatePacket;
import com.astralrealms.skyblock.messaging.packet.repository.MemberObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.MemberObjectUpdatePacket;
import com.astralrealms.skyblock.messaging.packet.repository.UniqueObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.UniqueObjectUpdatePacket;

public class ASPacketRegistry extends PacketRegistry {

    public ASPacketRegistry() {
        // Repositories (0x00 - 0xFF)
        this.registerPacket(0x00, UniqueObjectUpdatePacket::new);
        this.registerPacket(0x01, UniqueObjectDeletePacket::new);
        this.registerPacket(0x02, LongObjectUpdatePacket::new);
        this.registerPacket(0x03, LongObjectDeletePacket::new);
        this.registerPacket(0x04, MemberObjectUpdatePacket::new);
        this.registerPacket(0x05, MemberObjectDeletePacket::new);

        // Islands (0x100 - 0x1FF)
        this.registerPacket(0x10, IslandLoadRequestPacket::new);
        this.registerPacket(0x11, IslandLoadResponsePacket::new);
    }
}
