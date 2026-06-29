package com.astralrealms.skyblock.messaging;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.packet.binary.BinaryMessage;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;

import static org.assertj.core.api.Assertions.assertThat;

class PacketRoundTripTest {

    @Test
    void islandStringKeyPacketRoundTrips() {
        UUID island = UUID.randomUUID();
        IslandStringKeyUpdatePacket original = new IslandStringKeyUpdatePacket(island, "home");

        // BinaryMessage has no no-arg constructor; use factory + prepareRead() before decoding
        BinaryMessage buffer = BinaryMessage.create();
        original.write(buffer);
        buffer.prepareRead();

        IslandStringKeyUpdatePacket decoded = new IslandStringKeyUpdatePacket();
        decoded.read(buffer);

        assertThat(decoded.islandId()).isEqualTo(island);
        assertThat(decoded.key()).isEqualTo("home");
    }
}
