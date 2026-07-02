package com.astralrealms.skyblock.messaging.packet.island;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;
import com.astralrealms.skyblock.event.member.IslandMemberLeaveEvent;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MemberLeavePacket implements Packet {

    private UUID islandId;
    private UUID playerId;
    private IslandMemberLeaveEvent.Reason reason;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
        msg.writeString(reason.name());
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId = msg.readUUID();
        this.playerId = msg.readUUID();
        this.reason   = IslandMemberLeaveEvent.Reason.valueOf(msg.readString());
    }
}
