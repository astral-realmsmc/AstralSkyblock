package com.astralrealms.skyblock.model.island;

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
@Entity("island_warps")
@NoArgsConstructor
@AllArgsConstructor
public class IslandWarp implements ComplexPlaceholder {

    private UUID islandId;
    private String name;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean isPrivate;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "name" -> name;
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "yaw" -> yaw;
            case "pitch" -> pitch;
            case "isPrivate" -> isPrivate;
            case "createdAt" -> createdAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "warp";
    }
}
