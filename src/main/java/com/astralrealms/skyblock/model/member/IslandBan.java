package com.astralrealms.skyblock.model.member;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
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
@Entity("island_bans")
@NoArgsConstructor
@AllArgsConstructor
public class IslandBan implements ComplexPlaceholder {

    private UUID islandId;
    private UUID playerUuid;
    private UUID bannedBy;
    private @Nullable String reason;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "playerId" -> playerUuid;
            case "player" -> new MinecraftPlayerPlaceholder(playerUuid);
            case "bannedBy" -> bannedBy;
            case "executor" -> new MinecraftPlayerPlaceholder(bannedBy);
            case "reason" -> reason;
            case "createdAt" -> createdAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "ban";
    }
}
