package com.astralrealms.skyblock.model.member;

import java.util.UUID;

import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A pending member or coop invitation ({@code island_invitations} in schema.sql). Mapped by hand in
 * {@code InvitationRepository}; both timestamps are epoch milliseconds stored as BIGINT.
 */
@Getter
@Entity("island_invitations")
@NoArgsConstructor
@AllArgsConstructor
public class IslandInvitation {

    @Id
    @Column("id")
    private UUID uniqueId;
    private UUID islandId;
    private UUID senderId;
    private UUID recipientId;
    private InvitationType type;
    private long expiresAt;
    private long createdAt;

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public static IslandInvitation create(UUID islandId, UUID senderId, UUID recipientId, InvitationType type) {
        long now = System.currentTimeMillis();
        return new IslandInvitation(UUID.randomUUID(), islandId, senderId, recipientId, type,
                now + 15 * 60 * 1000L, now);
    }
}
