package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.intellij.lang.annotations.Language;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.model.member.InvitationType;

/**
 * DB-only repository for island invitations. Invitations are short-lived (15 min TTL) and
 * low-volume, so no local or shared cache is maintained — every query hits the database directly.
 *
 * <p>The underlying table ({@code island_invitations}) carries indexes on {@code island_id} and
 * {@code recipient_id}, so the two indexed queries are index-scans rather than full-table scans.
 */
public class InvitationRepository {

    private final AstralSkyblock plugin;

    public InvitationRepository(AstralSkyblock plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /**
     * All invitations for an island (sent or pending), ordered by the DB engine.
     */
    public CompletableFuture<List<IslandInvitation>> findByIsland(UUID islandId) {
        @Language("SQL") String query = """
                SELECT id, island_id, sender_id, recipient_id, type, expires_at, created_at
                FROM island_invitations WHERE island_id = ?
                """;
        return this.plugin.database().supply(conn -> {
            List<IslandInvitation> result = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, islandId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            }
            return result;
        });
    }

    /**
     * All invitations addressed to a specific player (across all islands).
     */
    public CompletableFuture<List<IslandInvitation>> findByRecipient(UUID recipientId) {
        @Language("SQL") String query = """
                SELECT id, island_id, sender_id, recipient_id, type, expires_at, created_at
                FROM island_invitations WHERE recipient_id = ?
                """;
        return this.plugin.database().supply(conn -> {
            List<IslandInvitation> result = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, recipientId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            }
            return result;
        });
    }

    /**
     * The first non-expired invitation from {@code islandId} to {@code recipientId}, if any.
     * Used to guard against duplicate invitations before sending a new one.
     */
    public CompletableFuture<Optional<IslandInvitation>> findPending(UUID islandId, UUID recipientId) {
        @Language("SQL") String query = """
                SELECT id, island_id, sender_id, recipient_id, type, expires_at, created_at
                FROM island_invitations
                WHERE island_id = ? AND recipient_id = ? AND expires_at > ?
                LIMIT 1
                """;
        return this.plugin.database().supply(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, islandId);
                stmt.setObject(2, recipientId);
                stmt.setLong(3, System.currentTimeMillis());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.<IslandInvitation>empty();
                }
            }
        });
    }

    /**
     * Persists a new invitation row. The caller is responsible for ensuring no duplicate pending
     * invitation exists (check with {@link #findPending} first).
     */
    public CompletableFuture<Void> create(IslandInvitation invitation) {
        @Language("SQL") String query = """
                INSERT INTO island_invitations
                    (id, island_id, sender_id, recipient_id, type, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        return this.plugin.database().run(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, invitation.uniqueId());
                stmt.setObject(2, invitation.islandId());
                stmt.setObject(3, invitation.senderId());
                stmt.setObject(4, invitation.recipientId());
                stmt.setString(5, invitation.type().name());
                stmt.setLong(6, invitation.expiresAt());
                stmt.setLong(7, invitation.createdAt());
                stmt.executeUpdate();
            }
        });
    }

    /**
     * Removes a single invitation by its primary key (accept / decline / revoke).
     */
    public CompletableFuture<Void> delete(UUID invitationId) {
        @Language("SQL") String query = "DELETE FROM island_invitations WHERE id = ?";
        return this.plugin.database().run(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, invitationId);
                stmt.executeUpdate();
            }
        });
    }

    /**
     * Bulk-deletes all rows whose {@code expires_at} timestamp is in the past. Intended to be
     * called periodically (e.g. from a scheduled task in the invitation service).
     */
    public CompletableFuture<Void> pruneExpired() {
        @Language("SQL") String query = "DELETE FROM island_invitations WHERE expires_at <= ?";
        return this.plugin.database().run(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.executeUpdate();
            }
        });
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private IslandInvitation mapRow(ResultSet rs) throws SQLException {
        String senderId = rs.getString("sender_id");
        return new IslandInvitation(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("island_id")),
                senderId != null ? UUID.fromString(senderId) : null,
                UUID.fromString(rs.getString("recipient_id")),
                InvitationType.valueOf(rs.getString("type")),
                rs.getLong("expires_at"),
                rs.getLong("created_at")
        );
    }
}
