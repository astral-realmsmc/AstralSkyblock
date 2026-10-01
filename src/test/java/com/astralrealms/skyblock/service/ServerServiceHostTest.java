package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ServerServiceHostTest {

    private final UUID server = UUID.randomUUID();

    @Test
    void parsesALoadedHost() {
        ServerService.Host host = ServerService.Host.parse(server.toString());
        assertEquals(server, host.server());
        assertFalse(host.loading());
    }

    @Test
    void parsesAHostStillLoading() {
        ServerService.Host host = ServerService.Host.parse(server + "|loading");
        assertEquals(server, host.server());
        assertTrue(host.loading());
    }
}
