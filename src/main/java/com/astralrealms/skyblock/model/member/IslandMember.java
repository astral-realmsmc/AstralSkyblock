package com.astralrealms.skyblock.model.member;

import java.util.UUID;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.CreatedAt;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.model.SQLAccessor;
import com.astralrealms.skyblock.model.role.IslandRole;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity("island_members")
@NoArgsConstructor
public class IslandMember implements ComplexPlaceholder {

    private UUID islandId;
    private UUID playerUuid;
    private boolean isOwner;
    private Long roleId;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long joinedAt;

    // Relationships — resolved from the island's roles by IslandService#hydrate (owner holds no role).
    @Setter
    private transient IslandRole role;

    public IslandMember(UUID islandId, UUID playerUuid, boolean isOwner, Long roleId, long joinedAt) {
        this.islandId = islandId;
        this.playerUuid = playerUuid;
        this.isOwner = isOwner;
        this.roleId = roleId;
        this.joinedAt = joinedAt;
    }

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "playerId" -> playerUuid;
            case "owner" -> isOwner;
            case "role" -> role;
            case "roleId" -> roleId;
            case "joinedAt" -> joinedAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "member";
    }
}
