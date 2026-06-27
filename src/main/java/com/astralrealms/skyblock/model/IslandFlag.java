package com.astralrealms.skyblock.model;

import java.util.UUID;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("island_flags")
@NoArgsConstructor
@AllArgsConstructor
public class IslandFlag implements ComplexPlaceholder {

    private UUID islandId;
    private String flag;
    private boolean allowed;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "flag" -> flag;
            case "allowed" -> allowed;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "flag";
    }
}
