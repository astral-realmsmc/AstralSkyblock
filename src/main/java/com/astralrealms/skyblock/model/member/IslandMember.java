package com.astralrealms.skyblock.model.member;

import java.util.UUID;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.CreatedAt;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.model.SQLAccessor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("island_members")
@NoArgsConstructor
@AllArgsConstructor
public class IslandMember implements ComplexPlaceholder {

    private UUID islandId;
    private UUID playerUuid;
    private boolean isOwner;
    private Long roleId;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long joinedAt;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "playerId" -> playerUuid;
            case "owner" -> isOwner;
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
