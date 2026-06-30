package com.astralrealms.skyblock.messaging;

import com.astralrealms.core.packet.PacketRegistry;
import com.astralrealms.skyblock.messaging.packet.island.CoopAddPacket;
import com.astralrealms.skyblock.messaging.packet.island.CoopRemovePacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadRequestPacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadResponsePacket;
import com.astralrealms.skyblock.messaging.packet.island.MemberJoinPacket;
import com.astralrealms.skyblock.messaging.packet.island.MemberLeavePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyUpdatePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
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
        this.registerPacket(0x06, IslandStringKeyUpdatePacket::new);
        this.registerPacket(0x07, IslandStringKeyDeletePacket::new);
        this.registerPacket(0x08, IslandPlayerKeyUpdatePacket::new);
        this.registerPacket(0x09, IslandPlayerKeyDeletePacket::new);

        // Islands (0x100 - 0x1FF)
        this.registerPacket(0x100, IslandLoadRequestPacket::new);
        this.registerPacket(0x101, IslandLoadResponsePacket::new);
        this.registerPacket(0x102, MemberJoinPacket::new);
        this.registerPacket(0x103, MemberLeavePacket::new);
        this.registerPacket(0x104, CoopAddPacket::new);
        this.registerPacket(0x105, CoopRemovePacket::new);
    }
}
