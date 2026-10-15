package com.astralrealms.skyblock.model.dimension;

import org.bukkit.PortalType;
import org.jetbrains.annotations.Nullable;

/**
 * The portal that leads into a dimension from the island's overworld, and back out of it. A
 * dimension with {@link #NONE} is only reachable by command.
 */
public enum DimensionPortal {
    NONE,
    NETHER,
    END;

    /** The portal a vanilla portal block stands for; {@code null} for the ones dimensions never use (gateways). */
    @Nullable
    public static DimensionPortal of(PortalType type) {
        return switch (type) {
            case NETHER -> NETHER;
            case ENDER -> END;
            default -> null;
        };
    }
}
