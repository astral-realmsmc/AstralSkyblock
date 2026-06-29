package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the cascade-population logic ({@link IslandRepository#populate}): each member is
 * wired to the role it holds, the owner is detected, and the snapshot lands on the island.
 */
class IslandRepositoryTest {

    private static Island island() {
        return new Island(UUID.randomUUID(), "Test", false, 0, 0, 64, 0, 0, 0, 0L, 0L);
    }

    private static IslandRole role(long id, UUID islandId, String name) {
        return new IslandRole(id, islandId, IslandRole.Type.MEMBER, name, 0, false, 0L);
    }

    @Test
    void freshIslandExposesEmptyRelationshipsInsteadOfNull() {
        Island island = island();
        assertThat(island.members()).isEmpty();
        assertThat(island.roles()).isEmpty();
        assertThat(island.owner()).isNull();
    }

    @Test
    void populateResolvesEachMemberRoleDetectsOwnerAndSetsSnapshot() {
        Island island = island();
        UUID islandId = island.uniqueId();
        UUID ownerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();

        IslandRole memberRole = role(1L, islandId, "Member");
        IslandRole adminRole = role(2L, islandId, "Admin");

        // Owner holds no role (role_id NULL); the member holds the admin role.
        IslandMember owner = new IslandMember(islandId, ownerUuid, true, null, 0L);
        IslandMember member = new IslandMember(islandId, memberUuid, false, 2L, 0L);

        IslandRepository.populate(island, List.of(memberRole, adminRole), List.of(owner, member));

        assertThat(island.owner()).isSameAs(owner);
        assertThat(island.roles()).containsExactly(memberRole, adminRole);
        assertThat(island.members()).containsExactly(owner, member);
        assertThat(owner.role()).isNull();
        assertThat(member.role()).isSameAs(adminRole);
    }

    @Test
    void populateLeavesRoleNullWhenRoleIdHasNoMatch() {
        Island island = island();
        UUID islandId = island.uniqueId();

        // role_id points at a role that is not in the loaded set (e.g. just deleted) -> null, no throw.
        IslandMember member = new IslandMember(islandId, UUID.randomUUID(), false, 99L, 0L);

        IslandRepository.populate(island, List.of(role(1L, islandId, "Member")), List.of(member));

        assertThat(member.role()).isNull();
        assertThat(island.owner()).isNull();
    }
}
