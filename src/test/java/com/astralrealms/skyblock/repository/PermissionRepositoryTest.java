package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.role.RolePermissions;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRepositoryTest {

    @Test
    void evictDropsRoleGrantSetsFromL1() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        PermissionRepository repo = new PermissionRepository(plugin);
        repo.cache().synchronous().put(5L, new RolePermissions(5L, Set.of("island.invite")));

        repo.evict(List.of(5L));

        assertThat(repo.findCachedById(5L)).isEmpty();
    }
}
