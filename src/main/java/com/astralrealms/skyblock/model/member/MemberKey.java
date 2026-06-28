package com.astralrealms.skyblock.model.member;

import java.util.UUID;

/**
 * Composite cache key for {@link IslandMember}, mirroring the {@code island_members}
 * primary key {@code (island_id, player_uuid)}.
 */
public record MemberKey(UUID islandId, UUID playerUuid) {
}
