package com.astralrealms.skyblock.model;

import java.util.UUID;

import com.astralrealms.core.model.Unique;

public record IslandServer(UUID uniqueId, int loadedIslands, int maximumIslands) implements Unique {
}
