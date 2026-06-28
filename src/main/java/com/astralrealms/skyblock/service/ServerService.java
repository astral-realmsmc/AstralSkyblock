package com.astralrealms.skyblock.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;

import com.astralrealms.core.cache.CacheRepository;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.IslandServer;
import com.astralrealms.skyblock.utils.ASConstants;

public class ServerService {

    private final AstralSkyblock plugin;
    private final CacheRepository<IslandServer> repository;

    public ServerService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new CacheRepository<>(plugin.cache(), ASConstants.SERVER_CACHE_KEY, IslandServer.class, Duration.ofMinutes(1));

        // Update task
        if (AstralPaperAPI.serverInformation().group().equals(this.plugin.configuration().islandsGroup()))
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::update, 0, 300);
    }

    private void update() {
        this.repository.set(new IslandServer(
                AstralPaperAPI.serverInformation().uniqueId(),
                plugin.worlds().getLoadedWorlds().size(),
                plugin.configuration().maximumIslands()
        ));
    }

    public CompletableFuture<IslandServer> findEmptiestServer() {
        return this.repository.findAll()
                .thenApply(servers -> servers.stream()
                        .filter(server -> server.loadedIslands() < server.maximumIslands())
                        .min(Comparator.comparingInt(IslandServer::loadedIslands))
                        .orElse(null));
    }

    public CompletableFuture<Void> deleteHostServer(UUID island) {
        return this.plugin.cache()
                .del(ASConstants.ISLAND_SERVER_KEY + ":" + island.toString())
                .thenApply(ignored -> null);
    }

    public CompletableFuture<Void> setHostServer(UUID island, UUID server) {
        return this.plugin.cache()
                .set(ASConstants.ISLAND_SERVER_KEY + ":" + island.toString(), server.toString())
                .thenApply(ignored -> null);
    }

    public CompletableFuture<UUID> findHostServer(UUID island) {
        return this.plugin.cache()
                .get(ASConstants.ISLAND_SERVER_KEY + ":" + island.toString())
                .thenApply(serverId -> serverId != null ? UUID.fromString(serverId) : null);
    }
}
