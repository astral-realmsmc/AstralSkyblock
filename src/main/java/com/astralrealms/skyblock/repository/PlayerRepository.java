package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.SkyblockPlayer;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Canonical UUID &lt;-&gt; name directory. Cached because protection checks, GUIs and command
 * tab-completion resolve player identities constantly.
 */
public class PlayerRepository extends UUIDSyncedRepository<SkyblockPlayer> {

    public PlayerRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.PLAYER_CACHE_KEY,
                ASConstants.PLAYER_UPDATE_CHANNEL,
                cacheLoader -> Caffeine.newBuilder()
                        .maximumSize(100_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .buildAsync(cacheLoader),
                SkyblockPlayer.class
        );
    }

    /**
     * Resolves the most recently seen player carrying the given name (case-insensitive via the
     * column collation). Players who have since renamed away from it are not returned.
     */
    public CompletableFuture<Optional<SkyblockPlayer>> findByName(String name) {
        return this.repository.findByColumn("name", name);
    }

    /**
     * Upserts the player on join: inserts a fresh row, or refreshes the stored name and bumps
     * {@code last_seen} for a returning player — without disturbing {@code first_seen}. The cache
     * is invalidated globally so every server reloads the canonical row on next access.
     */
    public CompletableFuture<SkyblockPlayer> recordSeen(UUID uuid, String name) {
        String query = """
                INSERT INTO players (uuid, name) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = CURRENT_TIMESTAMP(3)
                """;
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, uuid);
                        statement.setString(2, name);
                        statement.executeUpdate();
                    }
                })
                .thenCompose(ignored -> {
                    invalidateGlobally(uuid);
                    return findById(uuid);
                });
    }
}
