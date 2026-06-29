package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.repository.BanRepository;

import lombok.Getter;

@Getter
public class BanService {

    private final AstralSkyblock plugin;
    private final BanRepository repository;

    public BanService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new BanRepository(plugin);
    }

    @Unmodifiable
    public Collection<IslandBan> bans(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Whether the player is banned from the island, per the local cache. */
    public boolean isBanned(UUID islandId, UUID playerUuid) {
        return this.repository.findCachedById(new IslandPlayerKey(islandId, playerUuid)).isPresent();
    }

    public CompletableFuture<IslandBan> ban(IslandBan ban) {
        return this.repository.ban(ban);
    }

    public CompletableFuture<IslandBan> unban(UUID islandId, UUID playerUuid) {
        return this.repository.unban(islandId, playerUuid);
    }
}
