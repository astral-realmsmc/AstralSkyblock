package com.astralrealms.skyblock.model.island;

import java.util.UUID;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("island_upgrades")
@NoArgsConstructor
@AllArgsConstructor
public class IslandUpgrade implements ComplexPlaceholder {

    private UUID islandId;
    private String upgrade;
    private int level;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "upgrade" -> upgrade;
            case "level" -> level;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "upgrade";
    }
}
