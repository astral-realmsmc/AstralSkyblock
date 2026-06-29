package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.repository.CoopRepository;

import lombok.Getter;

@Getter
public class CoopService {

    private final AstralSkyblock plugin;
    private final CoopRepository repository;

    public CoopService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new CoopRepository(plugin);
    }

    @Unmodifiable
    public Collection<IslandCoop> coops(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public boolean isCoop(UUID islandId, UUID playerUuid) {
        return this.repository.findCachedById(new IslandPlayerKey(islandId, playerUuid)).isPresent();
    }

    public CompletableFuture<IslandCoop> add(IslandCoop coop) {
        return this.repository.add(coop);
    }

    public CompletableFuture<IslandCoop> remove(UUID islandId, UUID playerUuid) {
        return this.repository.remove(islandId, playerUuid);
    }
}
