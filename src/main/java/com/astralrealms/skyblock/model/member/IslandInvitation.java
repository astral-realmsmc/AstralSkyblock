// Schema:
// CREATE TABLE IF NOT EXISTS island_invitations (
//     id           VARCHAR(36) NOT NULL,
//     island_id    VARCHAR(36) NOT NULL,
//     sender_id    VARCHAR(36) NOT NULL,
//     recipient_id VARCHAR(36) NOT NULL,
//     type         ENUM('MEMBER', 'COOP') NOT NULL,
//     expires_at   BIGINT      NOT NULL,
//     created_at   BIGINT      NOT NULL,
//     PRIMARY KEY (id),
//     INDEX idx_island    (island_id),
//     INDEX idx_recipient (recipient_id)
// );

package com.astralrealms.skyblock.model.member;

import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.CreatedAt;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.annotation.Id;
import com.astralrealms.core.storage.model.SQLAccessor;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Entity("island_invitations")
@NoArgsConstructor
@AllArgsConstructor
public class IslandInvitation {

    @Id
    private UUID uniqueId;
    private UUID islandId;
    private UUID senderId;
    private UUID recipientId;
    private InvitationType type;
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long expiresAt;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
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
