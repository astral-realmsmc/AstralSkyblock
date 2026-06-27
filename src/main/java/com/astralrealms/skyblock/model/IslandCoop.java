package com.astralrealms.skyblock.model;

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
@Entity("island_coops")
@NoArgsConstructor
@AllArgsConstructor
public class IslandCoop implements ComplexPlaceholder {

    private UUID islandId;
    private UUID playerUuid;
    private @Nullable UUID addedBy;
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
            case "addedBy" -> addedBy;
            case "executor" -> new MinecraftPlayerPlaceholder(addedBy);
            case "createdAt" -> createdAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "coop";
    }
}
