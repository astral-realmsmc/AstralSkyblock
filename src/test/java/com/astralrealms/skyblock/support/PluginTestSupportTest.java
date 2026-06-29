package com.astralrealms.skyblock.support;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;

import static org.assertj.core.api.Assertions.assertThat;

class PluginTestSupportTest {

    @Test
    void mockPluginExposesStubbedCollaborators() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        assertThat(plugin.cache()).isNotNull();
        assertThat(plugin.messaging()).isNotNull();
        assertThat(plugin.database()).isNotNull();
        assertThat(plugin.cache().get("anything").join()).isNull();
    }
}
