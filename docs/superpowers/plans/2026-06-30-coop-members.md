# Coop & Members Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire island coop and member management (invite/accept/kick/leave/promote/demote/transfer/coop/uncoop) as fully persistent, cross-server features.

**Architecture:** One focused service per concern — `InvitationService` (shared invite lifecycle for MEMBER and COOP types), `CoopService` (coop CRUD + cache), expanded `MemberService` write ops. All business changes propagate across servers via typed packets. The island `cascade()` is extended to include coops so `island.hasPermission()` works for coop players.

**Tech Stack:** ACF (`co.aikar.commands`), Caffeine L1 cache, `IndexedSyncedRepository`, `BinaryMessage` packets, Bukkit Events, MySQL.

## Global Constraints

- Package root: `com.astralrealms.skyblock`
- All DB ops must be async (`CompletableFuture`) via `plugin.database().supply()` or `.transactionSupply()`
- All cross-server state changes must broadcast a packet via `plugin.messaging().broadcast(packet)`
- `sharedCacheEnabled()` returns `false` on all new repositories (no Redis L2, matches Member/Role pattern)
- `InvitationRepository` has no cache — always queries DB (invitations are short-lived and low volume)
- Packets: `@Getter @NoArgsConstructor @AllArgsConstructor`, implement `Packet`, manual `BinaryMessage` read/write
- New packet opcodes: `0x102`–`0x105` (island management range, after existing `0x100`/`0x101`)
- Fluent setters: the project uses `island.members(list)` / `island.coops(list)` notation (no `set` prefix) — match existing `populate()` calls
- GUI Actions are records implementing `PaperAction` with `PlaceholderWrapper<T>` fields — match `ToggleRolePermissionAction`
- Commands use ACF `@Subcommand`, `@CommandCompletion`, `@Optional @Nullable`

---

### Task 1: InvitationType + IslandInvitation

**Files:**
- Create: `model/member/InvitationType.java`
- Create: `model/member/IslandInvitation.java`

**Interfaces:**
- Produces: `IslandInvitation.create(UUID, UUID, UUID, InvitationType)`, `IslandInvitation.expired()`, `InvitationType.MEMBER`/`COOP` — consumed by Tasks 6, 9, 11.

- [ ] **Step 1: Create InvitationType**

```java
package com.astralrealms.skyblock.model.member;

public enum InvitationType {
    MEMBER,
    COOP
}
```

- [ ] **Step 2: Create IslandInvitation**

```java
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
```

- [ ] **Step 3: Create the island_invitations table**

Add to your DB schema / migration file:

```sql
CREATE TABLE IF NOT EXISTS island_invitations (
    id           VARCHAR(36) NOT NULL,
    island_id    VARCHAR(36) NOT NULL,
    sender_id    VARCHAR(36) NOT NULL,
    recipient_id VARCHAR(36) NOT NULL,
    type         ENUM('MEMBER', 'COOP') NOT NULL,
    expires_at   BIGINT      NOT NULL,
    created_at   BIGINT      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_island    (island_id),
    INDEX idx_recipient (recipient_id)
);
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/model/member/InvitationType.java \
        src/main/java/com/astralrealms/skyblock/model/member/IslandInvitation.java
git commit -m "feat: add IslandInvitation model and InvitationType enum"
```

---

### Task 2: Events

**Files:**
- Create: `event/IslandMemberJoinEvent.java`
- Create: `event/IslandMemberLeaveEvent.java`
- Create: `event/IslandCoopAddEvent.java`
- Create: `event/IslandCoopRemoveEvent.java`

**Interfaces:**
- Produces: all four events and `IslandMemberLeaveEvent.Reason` enum — consumed by Tasks 7, 8, 11.

- [ ] **Step 1: Create IslandMemberJoinEvent**

```java
package com.astralrealms.skyblock.event;

import com.astralrealms.skyblock.model.island.Island;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class IslandMemberJoinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerId;
    private final UUID invitedBy;

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
```

- [ ] **Step 2: Create IslandMemberLeaveEvent**

```java
package com.astralrealms.skyblock.event;

import com.astralrealms.skyblock.model.island.Island;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class IslandMemberLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerId;
    private final Reason reason;

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    public enum Reason { VOLUNTARY, KICKED, BANNED }
}
```

- [ ] **Step 3: Create IslandCoopAddEvent**

```java
package com.astralrealms.skyblock.event;

import com.astralrealms.skyblock.model.island.Island;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class IslandCoopAddEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerId;
    private final UUID addedBy;

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
```

- [ ] **Step 4: Create IslandCoopRemoveEvent**

```java
package com.astralrealms.skyblock.event;

import com.astralrealms.skyblock.model.island.Island;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class IslandCoopRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerId;

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/event/
git commit -m "feat: add island member and coop events"
```

---

### Task 3: Packets + ASPacketRegistry

**Files:**
- Create: `messaging/packet/island/MemberJoinPacket.java`
- Create: `messaging/packet/island/MemberLeavePacket.java`
- Create: `messaging/packet/island/CoopAddPacket.java`
- Create: `messaging/packet/island/CoopRemovePacket.java`
- Modify: `messaging/ASPacketRegistry.java`

**Interfaces:**
- Produces: four packet classes and their opcodes `0x102`–`0x105` — consumed by Tasks 7 and 8.

- [ ] **Step 1: Create MemberJoinPacket**

```java
package com.astralrealms.skyblock.messaging.packet.island;

import com.astralrealms.core.messaging.BinaryMessage;
import com.astralrealms.core.messaging.packet.Packet;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MemberJoinPacket implements Packet {

    private UUID islandId;
    private UUID playerId;
    private UUID invitedBy;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
        msg.writeUUID(invitedBy);
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId  = msg.readUUID();
        this.playerId  = msg.readUUID();
        this.invitedBy = msg.readUUID();
    }
}
```

- [ ] **Step 2: Create MemberLeavePacket**

```java
package com.astralrealms.skyblock.messaging.packet.island;

import com.astralrealms.core.messaging.BinaryMessage;
import com.astralrealms.core.messaging.packet.Packet;
import com.astralrealms.skyblock.event.IslandMemberLeaveEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MemberLeavePacket implements Packet {

    private UUID islandId;
    private UUID playerId;
    private IslandMemberLeaveEvent.Reason reason;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
        msg.writeString(reason.name());
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId = msg.readUUID();
        this.playerId = msg.readUUID();
        this.reason   = IslandMemberLeaveEvent.Reason.valueOf(msg.readString());
    }
}
```

- [ ] **Step 3: Create CoopAddPacket**

```java
package com.astralrealms.skyblock.messaging.packet.island;

import com.astralrealms.core.messaging.BinaryMessage;
import com.astralrealms.core.messaging.packet.Packet;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CoopAddPacket implements Packet {

    private UUID islandId;
    private UUID playerId;
    private UUID addedBy;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
        msg.writeUUID(addedBy);
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId = msg.readUUID();
        this.playerId = msg.readUUID();
        this.addedBy  = msg.readUUID();
    }
}
```

- [ ] **Step 4: Create CoopRemovePacket**

```java
package com.astralrealms.skyblock.messaging.packet.island;

import com.astralrealms.core.messaging.BinaryMessage;
import com.astralrealms.core.messaging.packet.Packet;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CoopRemovePacket implements Packet {

    private UUID islandId;
    private UUID playerId;

    @Override
    public void write(BinaryMessage msg) {
        msg.writeUUID(islandId);
        msg.writeUUID(playerId);
    }

    @Override
    public void read(BinaryMessage msg) {
        this.islandId = msg.readUUID();
        this.playerId = msg.readUUID();
    }
}
```

- [ ] **Step 5: Register in ASPacketRegistry**

At the end of the `ASPacketRegistry` constructor, after the existing `0x100`/`0x101` registrations:

```java
register(0x102, MemberJoinPacket::new);
register(0x103, MemberLeavePacket::new);
register(0x104, CoopAddPacket::new);
register(0x105, CoopRemovePacket::new);
```

Add imports:

```java
import com.astralrealms.skyblock.messaging.packet.island.MemberJoinPacket;
import com.astralrealms.skyblock.messaging.packet.island.MemberLeavePacket;
import com.astralrealms.skyblock.messaging.packet.island.CoopAddPacket;
import com.astralrealms.skyblock.messaging.packet.island.CoopRemovePacket;
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/messaging/
git commit -m "feat: add member and coop cross-server packets"
```

---

### Task 4: CoopRepository

**Files:**
- Create: `repository/CoopRepository.java`
- Modify: `utils/ASConstants.java` (add two constants)

**Interfaces:**
- Produces: `findByIsland(UUID)`, `isCoop(UUID, UUID)`, `add(IslandCoop)`, `remove(UUID, UUID)`, `cacheLocally(IslandCoop)`, `invalidateLocally(IslandPlayerKey)` — consumed by Tasks 5, 7.

- [ ] **Step 1: Add constants to ASConstants**

In `utils/ASConstants.java`, add:

```java
public static final String COOP_CACHE_KEY      = "island_coops";
public static final String COOP_UPDATE_CHANNEL = "island_coops_update";
```

- [ ] **Step 2: Create CoopRepository**

```java
package com.astralrealms.skyblock.repository;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;
import org.intellij.lang.annotations.Language;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CoopRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandCoop, UUID> {

    // playerUuid -> set of islandIds where the player is coop
    private final Map<UUID, Set<UUID>> playerCoopIslandsMap = new ConcurrentHashMap<>();

    public CoopRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.COOP_CACHE_KEY, ASConstants.COOP_UPDATE_CHANNEL, IslandCoop.class);
    }

    // --- Domain queries ---

    public CompletableFuture<List<IslandCoop>> findByIsland(UUID islandId) {
        return prime(islandId).thenApply(ignored ->
                keysIn(islandId).stream()
                        .map(key -> findCachedById(key).orElse(null))
                        .filter(Objects::nonNull)
                        .toList()
        );
    }

    public boolean isCoop(UUID islandId, UUID playerUuid) {
        return playerCoopIslandsMap
                .getOrDefault(playerUuid, Collections.emptySet())
                .contains(islandId);
    }

    public CompletableFuture<IslandCoop> add(IslandCoop coop) {
        return saveToDatabase(coop).thenApply(saved -> {
            cacheLocally(saved);
            return saved;
        });
    }

    public CompletableFuture<Void> remove(UUID islandId, UUID playerUuid) {
        IslandPlayerKey key = new IslandPlayerKey(islandId, playerUuid);
        return deleteFromDatabase(key).thenAccept(ignored -> invalidateLocally(key));
    }

    // --- SyncedRepository contract ---

    @Override protected boolean sharedCacheEnabled() { return false; }

    @Override
    protected IslandPlayerKey keyFromValue(IslandCoop value) {
        return new IslandPlayerKey(value.islandId(), value.playerUuid());
    }

    @Override
    protected String cacheKey(IslandPlayerKey key) {
        return cacheKey + ":" + key.islandId() + ":" + key.playerUuid();
    }

    @Override
    protected CompletableFuture<IslandCoop> loadById(IslandPlayerKey key) {
        @Language("SQL") String query = """
                SELECT island_id, player_uuid, added_by, created_at
                FROM island_coops WHERE island_id = ? AND player_uuid = ?
                """;
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, key.islandId());
                stmt.setObject(2, key.playerUuid());
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) return null;
                    return mapRow(rs);
                }
            }
        });
    }

    @Override
    protected CompletableFuture<IslandCoop> saveToDatabase(IslandCoop value) {
        @Language("SQL") String query = """
                INSERT INTO island_coops (island_id, player_uuid, added_by, created_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE added_by = VALUES(added_by)
                """;
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, value.islandId());
                stmt.setObject(2, value.playerUuid());
                stmt.setObject(3, value.addedBy());
                stmt.setLong(4, value.createdAt());
                stmt.executeUpdate();
            }
            return value;
        });
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(IslandPlayerKey key) {
        @Language("SQL") String query =
                "DELETE FROM island_coops WHERE island_id = ? AND player_uuid = ?";
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, key.islandId());
                stmt.setObject(2, key.playerUuid());
                stmt.executeUpdate();
            }
            return null;
        });
    }

    @Override
    protected CompletableFuture<List<IslandCoop>> loadByIndex(UUID islandId) {
        @Language("SQL") String query = """
                SELECT island_id, player_uuid, added_by, created_at
                FROM island_coops WHERE island_id = ?
                """;
        return plugin.database().supply(conn -> {
            List<IslandCoop> result = new ArrayList<>();
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, islandId);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            }
            return result;
        });
    }

    @Override protected UUID indexKeyOf(IslandCoop value) { return value.islandId(); }

    @Override
    protected void index(IslandCoop value) {
        super.index(value);
        playerCoopIslandsMap
                .computeIfAbsent(value.playerUuid(), k -> ConcurrentHashMap.newKeySet())
                .add(value.islandId());
    }

    @Override
    protected void deindex(IslandPlayerKey key, IslandCoop value) {
        super.deindex(key, value);
        if (value == null) return;
        Set<UUID> ids = playerCoopIslandsMap.get(value.playerUuid());
        if (ids == null) return;
        ids.remove(value.islandId());
        if (ids.isEmpty()) playerCoopIslandsMap.remove(value.playerUuid());
    }

    // Cache coherency is handled via CoopAddPacket/CoopRemovePacket at the service layer.
    @Override protected void publishUpdate(IslandPlayerKey key, IslandCoop value) {}
    @Override protected void publishInvalidation(IslandPlayerKey key) {}

    private IslandCoop mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String addedBy = rs.getString("added_by");
        return new IslandCoop(
                UUID.fromString(rs.getString("island_id")),
                UUID.fromString(rs.getString("player_uuid")),
                addedBy != null ? UUID.fromString(addedBy) : null,
                rs.getLong("created_at")
        );
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/repository/CoopRepository.java \
        src/main/java/com/astralrealms/skyblock/utils/ASConstants.java
git commit -m "feat: add CoopRepository"
```

---

### Task 5: Island coop integration

**Files:**
- Modify: `model/island/Island.java`
- Modify: `repository/IslandRepository.java`

Wire coops into the island's transient state so `hasPermission()` handles coop players and `cascade()` loads them alongside members/roles.

**Interfaces:**
- Consumes: `CoopRepository.findByIsland(UUID)` via `plugin.coops()` (Task 10 adds the getter — if building incrementally, add a stub or skip compile until Task 10).
- Produces: `island.coops()`, `island.findCoop(UUID)` — consumed by Tasks 7, 11, 12.

- [ ] **Step 1: Add coops field to Island**

In `model/island/Island.java`, add alongside the other transient `@Setter` fields:

```java
@Setter
private transient Collection<IslandCoop> coops = new ArrayList<>();
```

Add import:

```java
import com.astralrealms.skyblock.model.member.IslandCoop;
import java.util.ArrayList;
```

Change the existing `members` and `roles` defaults from `List.of()` to `new ArrayList<>()` as well — they need to be mutable since services mutate them directly after cascade:

```java
@Setter private transient Collection<IslandMember> members = new ArrayList<>();
@Setter private transient Collection<IslandRole> roles    = new ArrayList<>();
```

- [ ] **Step 2: Add findCoop to Island**

```java
public Optional<IslandCoop> findCoop(UUID playerUuid) {
    return coops.stream()
            .filter(c -> c.playerUuid().equals(playerUuid))
            .findFirst();
}
```

- [ ] **Step 3: Extend hasPermission() to handle coop players**

Find the existing `hasPermission(Player player, IslandPermission permission)` method. After the block that checks `findMember()` and before any visitor fallback, insert:

```java
Optional<IslandCoop> coop = findCoop(player.getUniqueId());
if (coop.isPresent()) {
    return roles.stream()
            .filter(r -> r.kind() == IslandRole.Type.COOP)
            .findFirst()
            .map(r -> r.hasPermission(permission))
            .orElse(false);
}
```

The full method now handles four cases in order: admin/owner → member → coop → visitor (VISITOR system role fallback, which was already there).

- [ ] **Step 4: Extend IslandRepository.cascade() to load coops**

Replace the existing `cascade(Island island)` method:

```java
private CompletableFuture<Island> cascade(Island island) {
    UUID islandId = island.uniqueId();
    return this.plugin.roles()
            .findByIsland(islandId)
            .thenCompose(roles -> this.plugin.members().findByIsland(islandId)
                    .thenCompose(members -> this.plugin.coops().findByIsland(islandId)
                            .thenCompose(coops -> this.findSettingsByIsland(islandId)
                                    .thenApply(settings -> {
                                        populate(island, roles, members, coops, settings);
                                        return island;
                                    }))));
}
```

Add import:

```java
import com.astralrealms.skyblock.model.member.IslandCoop;
```

- [ ] **Step 5: Extend populate() to set coops**

Replace the existing `populate()` signature and body:

```java
private void populate(Island island, List<IslandRole> roles, List<IslandMember> members,
                      List<IslandCoop> coops, EnumSet<IslandSettings> settings) {
    Map<Long, IslandRole> rolesById = roles.stream()
            .collect(Collectors.toMap(IslandRole::id, role -> role));

    IslandMember owner = null;
    for (IslandMember member : members) {
        if (member.roleId() != null)
            member.role(rolesById.get(member.roleId()));
        if (member.isOwner())
            owner = member;
    }

    island.roles(roles);
    island.members(members);
    island.coops(new ArrayList<>(coops));
    island.owner(owner);
    island.settings(settings);
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/model/island/Island.java \
        src/main/java/com/astralrealms/skyblock/repository/IslandRepository.java
git commit -m "feat: wire coops into island cascade and permission checks"
```

---

### Task 6: InvitationRepository

**Files:**
- Create: `repository/InvitationRepository.java`

No caching — always queries DB directly. Two indexed queries (by islandId and by recipientId).

**Interfaces:**
- Produces: `findByIsland(UUID)`, `findByRecipient(UUID)`, `findPending(UUID, UUID)`, `create(IslandInvitation)`, `delete(UUID)`, `pruneExpired()` — consumed by Task 9.

- [ ] **Step 1: Create InvitationRepository**

```java
package com.astralrealms.skyblock.repository;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.model.member.InvitationType;
import org.intellij.lang.annotations.Language;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class InvitationRepository {

    private final AstralSkyblock plugin;

    public InvitationRepository(AstralSkyblock plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<List<IslandInvitation>> findByIsland(UUID islandId) {
        @Language("SQL") String query = """
                SELECT id, island_id, sender_id, recipient_id, type, expires_at, created_at
                FROM island_invitations WHERE island_id = ?
                """;
        return plugin.database().supply(conn -> {
            List<IslandInvitation> result = new ArrayList<>();
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, islandId);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            }
            return result;
        });
    }

    public CompletableFuture<List<IslandInvitation>> findByRecipient(UUID recipientId) {
        @Language("SQL") String query = """
                SELECT id, island_id, sender_id, recipient_id, type, expires_at, created_at
                FROM island_invitations WHERE recipient_id = ?
                """;
        return plugin.database().supply(conn -> {
            List<IslandInvitation> result = new ArrayList<>();
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, recipientId);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            }
            return result;
        });
    }

    public CompletableFuture<Optional<IslandInvitation>> findPending(UUID islandId, UUID recipientId) {
        @Language("SQL") String query = """
                SELECT id, island_id, sender_id, recipient_id, type, expires_at, created_at
                FROM island_invitations
                WHERE island_id = ? AND recipient_id = ? AND expires_at > ?
                LIMIT 1
                """;
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, islandId);
                stmt.setObject(2, recipientId);
                stmt.setLong(3, System.currentTimeMillis());
                try (var rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> create(IslandInvitation invitation) {
        @Language("SQL") String query = """
                INSERT INTO island_invitations
                    (id, island_id, sender_id, recipient_id, type, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, invitation.uniqueId());
                stmt.setObject(2, invitation.islandId());
                stmt.setObject(3, invitation.senderId());
                stmt.setObject(4, invitation.recipientId());
                stmt.setString(5, invitation.type().name());
                stmt.setLong(6, invitation.expiresAt());
                stmt.setLong(7, invitation.createdAt());
                stmt.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> delete(UUID invitationId) {
        @Language("SQL") String query = "DELETE FROM island_invitations WHERE id = ?";
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setObject(1, invitationId);
                stmt.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> pruneExpired() {
        @Language("SQL") String query = "DELETE FROM island_invitations WHERE expires_at <= ?";
        return plugin.database().supply(conn -> {
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.executeUpdate();
            }
            return null;
        });
    }

    private IslandInvitation mapRow(ResultSet rs) throws SQLException {
        String addedBy = rs.getString("sender_id");
        return new IslandInvitation(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("island_id")),
                addedBy != null ? UUID.fromString(addedBy) : null,
                UUID.fromString(rs.getString("recipient_id")),
                InvitationType.valueOf(rs.getString("type")),
                rs.getLong("expires_at"),
                rs.getLong("created_at")
        );
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/repository/InvitationRepository.java
git commit -m "feat: add InvitationRepository (DB-only, no cache)"
```

---

### Task 7: CoopService

**Files:**
- Create: `service/CoopService.java`

**Interfaces:**
- Consumes: `CoopRepository` (Task 4), events (Task 2), `CoopAddPacket`/`CoopRemovePacket` (Task 3)
- Produces: `add(Island, UUID, UUID)`, `remove(Island, UUID)`, `isCoop(UUID, UUID)`, `findByIsland(UUID)` — consumed by Tasks 9, 10, 11, 12.

- [ ] **Step 1: Create CoopService**

```java
package com.astralrealms.skyblock.service;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.event.IslandCoopAddEvent;
import com.astralrealms.skyblock.event.IslandCoopRemoveEvent;
import com.astralrealms.skyblock.messaging.packet.island.CoopAddPacket;
import com.astralrealms.skyblock.messaging.packet.island.CoopRemovePacket;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.repository.CoopRepository;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoopService {

    private final AstralSkyblock plugin;
    private final CoopRepository repository;

    public CoopService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new CoopRepository(plugin);
        plugin.messaging().on(CoopAddPacket.class,    this::handleCoopAddPacket);
        plugin.messaging().on(CoopRemovePacket.class, this::handleCoopRemovePacket);
    }

    public CompletableFuture<Void> add(Island island, UUID addedBy, UUID playerUuid) {
        IslandCoop coop = new IslandCoop(island.uniqueId(), playerUuid, addedBy, System.currentTimeMillis());
        return repository.add(coop).thenAccept(saved -> {
            island.coops().add(saved);
            Bukkit.getPluginManager().callEvent(new IslandCoopAddEvent(island, playerUuid, addedBy));
            plugin.messaging().broadcast(new CoopAddPacket(island.uniqueId(), playerUuid, addedBy));
        });
    }

    public CompletableFuture<Void> remove(Island island, UUID playerUuid) {
        return repository.remove(island.uniqueId(), playerUuid).thenAccept(ignored -> {
            island.coops().removeIf(c -> c.playerUuid().equals(playerUuid));
            Bukkit.getPluginManager().callEvent(new IslandCoopRemoveEvent(island, playerUuid));
            plugin.messaging().broadcast(new CoopRemovePacket(island.uniqueId(), playerUuid));
        });
    }

    public boolean isCoop(UUID islandId, UUID playerUuid) {
        return repository.isCoop(islandId, playerUuid);
    }

    public CompletableFuture<List<IslandCoop>> findByIsland(UUID islandId) {
        return repository.findByIsland(islandId);
    }

    private void handleCoopAddPacket(CoopAddPacket packet) {
        IslandCoop coop = new IslandCoop(packet.islandId(), packet.playerId(), packet.addedBy(),
                System.currentTimeMillis());
        repository.cacheLocally(coop);
        plugin.islands().findCachedById(packet.islandId()).ifPresent(island -> {
            island.coops().add(coop);
            Bukkit.getPluginManager().callEvent(
                    new IslandCoopAddEvent(island, packet.playerId(), packet.addedBy()));
        });
    }

    private void handleCoopRemovePacket(CoopRemovePacket packet) {
        repository.invalidateLocally(new IslandPlayerKey(packet.islandId(), packet.playerId()));
        plugin.islands().findCachedById(packet.islandId()).ifPresent(island -> {
            island.coops().removeIf(c -> c.playerUuid().equals(packet.playerId()));
            Bukkit.getPluginManager().callEvent(
                    new IslandCoopRemoveEvent(island, packet.playerId()));
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/service/CoopService.java
git commit -m "feat: add CoopService with cross-server packet handling"
```

---

### Task 8: MemberService write ops

**Files:**
- Modify: `service/MemberService.java`

**Interfaces:**
- Consumes: `MemberRepository` (existing), events (Task 2), `MemberJoinPacket`/`MemberLeavePacket` (Task 3)
- Produces: `addMember(Island, UUID, UUID)`, `kick(Island, Player, UUID)`, `leave(Island, Player)`, `promote(Island, Player, UUID)`, `demote(Island, Player, UUID)`, `transfer(Island, Player, IslandMember)` — consumed by Tasks 9, 11, 12.

- [ ] **Step 1: Register packet listeners in MemberService constructor**

Add to the MemberService constructor (after existing initialization):

```java
plugin.messaging().on(MemberJoinPacket.class,  this::handleMemberJoinPacket);
plugin.messaging().on(MemberLeavePacket.class, this::handleMemberLeavePacket);
```

Add imports:

```java
import com.astralrealms.skyblock.event.IslandMemberJoinEvent;
import com.astralrealms.skyblock.event.IslandMemberLeaveEvent;
import com.astralrealms.skyblock.messaging.packet.island.MemberJoinPacket;
import com.astralrealms.skyblock.messaging.packet.island.MemberLeavePacket;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Comparator;
import java.util.List;
```

- [ ] **Step 2: Add addMember**

```java
public CompletableFuture<Void> addMember(Island island, UUID playerUuid, UUID invitedBy) {
    IslandRole defaultRole = island.roles().stream()
            .filter(IslandRole::isDefault)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No default role on island: " + island.uniqueId()));
    return repository.add(island.uniqueId(), playerUuid, defaultRole.id())
            .thenCompose(member -> plugin.islands().refreshRelationships(island.uniqueId()))
            .thenAccept(ignored -> {
                Bukkit.getPluginManager().callEvent(
                        new IslandMemberJoinEvent(island, playerUuid, invitedBy));
                plugin.messaging().broadcast(
                        new MemberJoinPacket(island.uniqueId(), playerUuid, invitedBy));
            });
}
```

- [ ] **Step 3: Add kick**

```java
public CompletableFuture<Void> kick(Island island, Player kicker, UUID targetUuid) {
    if (!island.hasPermission(kicker, IslandPermission.KICK_MEMBER))
        return CompletableFuture.completedFuture(null);
    IslandMember target = island.findMember(targetUuid).orElse(null);
    if (target == null || target.isOwner()) return CompletableFuture.completedFuture(null);

    IslandMember kickerMember = island.findMember(kicker.getUniqueId()).orElse(null);
    // Non-owners must outrank their target; owners (role == null) may kick anyone
    if (kickerMember != null && !kickerMember.isOwner()
            && kickerMember.role() != null && target.role() != null
            && kickerMember.role().weight() <= target.role().weight())
        return CompletableFuture.completedFuture(null);

    return repository.remove(island.uniqueId(), targetUuid)
            .thenCompose(ignored -> plugin.islands().refreshRelationships(island.uniqueId()))
            .thenAccept(v -> {
                Bukkit.getPluginManager().callEvent(new IslandMemberLeaveEvent(
                        island, targetUuid, IslandMemberLeaveEvent.Reason.KICKED));
                plugin.messaging().broadcast(new MemberLeavePacket(
                        island.uniqueId(), targetUuid, IslandMemberLeaveEvent.Reason.KICKED));
            });
}
```

- [ ] **Step 4: Add leave**

```java
public CompletableFuture<Void> leave(Island island, Player player) {
    IslandMember member = island.findMember(player.getUniqueId()).orElse(null);
    if (member == null || member.isOwner()) return CompletableFuture.completedFuture(null);
    return repository.remove(island.uniqueId(), player.getUniqueId())
            .thenCompose(ignored -> plugin.islands().refreshRelationships(island.uniqueId()))
            .thenAccept(v -> {
                Bukkit.getPluginManager().callEvent(new IslandMemberLeaveEvent(
                        island, player.getUniqueId(), IslandMemberLeaveEvent.Reason.VOLUNTARY));
                plugin.messaging().broadcast(new MemberLeavePacket(
                        island.uniqueId(), player.getUniqueId(), IslandMemberLeaveEvent.Reason.VOLUNTARY));
            });
}
```

- [ ] **Step 5: Add promote and demote**

```java
public CompletableFuture<Void> promote(Island island, Player sender, UUID targetUuid) {
    if (!island.hasPermission(sender, IslandPermission.PROMOTE_MEMBERS))
        return CompletableFuture.completedFuture(null);
    IslandMember target     = island.findMember(targetUuid).orElse(null);
    IslandMember senderMember = island.findMember(sender.getUniqueId()).orElse(null);
    if (target == null || target.isOwner() || senderMember == null)
        return CompletableFuture.completedFuture(null);

    List<IslandRole> ladder = memberRoleLadder(island);
    int idx = findRoleIndex(ladder, target.role());
    if (idx < 0 || idx >= ladder.size() - 1) return CompletableFuture.completedFuture(null);
    IslandRole next = ladder.get(idx + 1);
    // Non-owners cannot promote above themselves
    if (!senderMember.isOwner() && senderMember.role() != null
            && next.weight() >= senderMember.role().weight())
        return CompletableFuture.completedFuture(null);

    return repository.setRole(island.uniqueId(), targetUuid, next.id())
            .thenCompose(ignored -> plugin.islands().refreshRelationships(island.uniqueId()))
            .thenAccept(v -> {});
}

public CompletableFuture<Void> demote(Island island, Player sender, UUID targetUuid) {
    if (!island.hasPermission(sender, IslandPermission.DEMOTE_MEMBERS))
        return CompletableFuture.completedFuture(null);
    IslandMember target     = island.findMember(targetUuid).orElse(null);
    IslandMember senderMember = island.findMember(sender.getUniqueId()).orElse(null);
    if (target == null || target.isOwner() || senderMember == null)
        return CompletableFuture.completedFuture(null);

    List<IslandRole> ladder = memberRoleLadder(island);
    int idx = findRoleIndex(ladder, target.role());
    if (idx <= 0) return CompletableFuture.completedFuture(null); // already lowest
    IslandRole prev = ladder.get(idx - 1);

    return repository.setRole(island.uniqueId(), targetUuid, prev.id())
            .thenCompose(ignored -> plugin.islands().refreshRelationships(island.uniqueId()))
            .thenAccept(v -> {});
}

private List<IslandRole> memberRoleLadder(Island island) {
    return island.roles().stream()
            .filter(r -> r.kind() == IslandRole.Type.MEMBER)
            .sorted(Comparator.comparingInt(IslandRole::weight))
            .toList();
}

private int findRoleIndex(List<IslandRole> ladder, IslandRole role) {
    if (role == null) return -1;
    for (int i = 0; i < ladder.size(); i++)
        if (ladder.get(i).id().equals(role.id())) return i;
    return -1;
}
```

- [ ] **Step 6: Add transfer**

```java
public CompletableFuture<Void> transfer(Island island, Player currentOwner, IslandMember newOwner) {
    if (island.owner() == null || !island.owner().playerUuid().equals(currentOwner.getUniqueId()))
        return CompletableFuture.completedFuture(null);
    IslandRole highestRole = island.roles().stream()
            .filter(r -> r.kind() == IslandRole.Type.MEMBER)
            .max(Comparator.comparingInt(IslandRole::weight))
            .orElseThrow(() -> new IllegalStateException("No MEMBER roles on island: " + island.uniqueId()));
    return repository.transferOwnership(
                    island.uniqueId(), currentOwner.getUniqueId(), highestRole.id(), newOwner.playerUuid())
            .thenCompose(ignored -> plugin.islands().refreshRelationships(island.uniqueId()))
            .thenAccept(v -> {});
}
```

- [ ] **Step 7: Add packet handlers**

```java
private void handleMemberJoinPacket(MemberJoinPacket packet) {
    plugin.islands().findCachedById(packet.islandId()).ifPresent(island ->
            Bukkit.getPluginManager().callEvent(
                    new IslandMemberJoinEvent(island, packet.playerId(), packet.invitedBy())));
}

private void handleMemberLeavePacket(MemberLeavePacket packet) {
    plugin.islands().findCachedById(packet.islandId()).ifPresent(island ->
            Bukkit.getPluginManager().callEvent(
                    new IslandMemberLeaveEvent(island, packet.playerId(), packet.reason())));
}
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/service/MemberService.java
git commit -m "feat: add MemberService write operations"
```

---

### Task 9: InvitationService

**Files:**
- Create: `service/InvitationService.java`

**Interfaces:**
- Consumes: `InvitationRepository` (Task 6), `CoopService.add()` (Task 7), `MemberService.addMember()` (Task 8)
- Produces: `create(Island, UUID, UUID, InvitationType)`, `accept(UUID, UUID)`, `decline(UUID, UUID)`, `cancel(Island, UUID, UUID)`, `findByRecipient(UUID)`, `findPending(UUID, UUID)` — consumed by Tasks 11, 12.

- [ ] **Step 1: Create InvitationService**

```java
package com.astralrealms.skyblock.service;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.repository.InvitationRepository;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class InvitationService {

    private static final long PRUNE_INTERVAL_TICKS = 60 * 20L;

    private final AstralSkyblock plugin;
    private final InvitationRepository repository;

    public InvitationService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new InvitationRepository(plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> repository.pruneExpired(), PRUNE_INTERVAL_TICKS, PRUNE_INTERVAL_TICKS);
    }

    /**
     * Creates an invite. Returns false if a non-expired invite already exists for this recipient on this island.
     */
    public CompletableFuture<Boolean> create(Island island, UUID senderId, UUID recipientId, InvitationType type) {
        return repository.findPending(island.uniqueId(), recipientId)
                .thenCompose(existing -> {
                    if (existing.isPresent()) return CompletableFuture.completedFuture(false);
                    IslandInvitation invitation = IslandInvitation.create(
                            island.uniqueId(), senderId, recipientId, type);
                    return repository.create(invitation).thenApply(ignored -> true);
                });
    }

    /**
     * Accepts the pending invite from islandId for recipientId.
     * Delegates to MemberService or CoopService based on type, then deletes the invitation.
     */
    public CompletableFuture<Boolean> accept(UUID islandId, UUID recipientId) {
        return repository.findPending(islandId, recipientId).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(false);
            IslandInvitation inv = opt.get();
            Island island = plugin.islands().findCachedById(islandId).orElse(null);
            if (island == null) return CompletableFuture.completedFuture(false);

            CompletableFuture<Void> action = inv.type() == InvitationType.MEMBER
                    ? plugin.members().addMember(island, recipientId, inv.senderId())
                    : plugin.coops().add(island, inv.senderId(), recipientId);

            return action
                    .thenCompose(ignored -> repository.delete(inv.uniqueId()))
                    .thenApply(ignored -> true);
        });
    }

    /**
     * Declines the pending invite from islandId for recipientId.
     */
    public CompletableFuture<Boolean> decline(UUID islandId, UUID recipientId) {
        return repository.findPending(islandId, recipientId).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(false);
            return repository.delete(opt.get().uniqueId()).thenApply(ignored -> true);
        });
    }

    /**
     * Cancels an outgoing invite. Only the original sender can cancel.
     */
    public CompletableFuture<Boolean> cancel(Island island, UUID senderId, UUID targetId) {
        return repository.findPending(island.uniqueId(), targetId).thenCompose(opt -> {
            if (opt.isEmpty() || !opt.get().senderId().equals(senderId))
                return CompletableFuture.completedFuture(false);
            return repository.delete(opt.get().uniqueId()).thenApply(ignored -> true);
        });
    }

    public CompletableFuture<List<IslandInvitation>> findByRecipient(UUID recipientId) {
        return repository.findByRecipient(recipientId);
    }

    public CompletableFuture<Optional<IslandInvitation>> findPending(UUID islandId, UUID recipientId) {
        return repository.findPending(islandId, recipientId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/service/InvitationService.java
git commit -m "feat: add InvitationService with 15-min TTL and prune scheduler"
```

---

### Task 10: Plugin wiring

**Files:**
- Modify: `AstralSkyblock.java`

`CoopService` must be initialized **before** `IslandService` because `IslandService`'s constructor triggers `warmup()` which calls `cascade()` which calls `plugin.coops()`.

- [ ] **Step 1: Add service fields**

Add after the `members` field declaration:

```java
private CoopService coops;
private InvitationService invitations;
```

Add imports:

```java
import com.astralrealms.skyblock.service.CoopService;
import com.astralrealms.skyblock.service.InvitationService;
```

- [ ] **Step 2: Initialize in onEnable() before IslandService**

Change the service block from:

```java
this.roles = new RoleService(this);
this.members = new MemberService(this);
this.islands = new IslandService(this);
```

to:

```java
this.roles       = new RoleService(this);
this.members     = new MemberService(this);
this.coops       = new CoopService(this);
this.invitations = new InvitationService(this);
this.islands     = new IslandService(this);
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/AstralSkyblock.java
git commit -m "feat: wire CoopService and InvitationService into plugin"
```

---

### Task 11: Commands

**Files:**
- Modify: `command/SkyblockCommand.java`

Add 11 subcommands. Each resolves the player's island from `members().findPlayerIsland()`, calls the async service, sends a message in the callback. All message strings should be replaced with the appropriate `ASMessages` enum constant when the enum is extended.

Add these imports to `SkyblockCommand.java`:

```java
import com.astralrealms.skyblock.event.IslandMemberLeaveEvent;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.role.IslandPermission;
import co.aikar.commands.annotation.Optional;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import java.util.List;
```

- [ ] **Step 1: Add invite and coop commands**

```java
@Subcommand("invite") @Syntax("<player>") @CommandCompletion("@players")
public void onInvite(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    if (!island.hasPermission(player, IslandPermission.INVITE_MEMBER)) {
        player.sendMessage("You don't have permission to invite members."); return;
    }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    plugin.invitations().create(island, player.getUniqueId(), targetUuid, InvitationType.MEMBER)
            .thenAccept(sent -> player.sendMessage(sent
                    ? "Invitation sent to " + targetName + "."
                    : targetName + " already has a pending invitation."));
}

@Subcommand("coop") @Syntax("<player>") @CommandCompletion("@players")
public void onCoop(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    if (!island.hasPermission(player, IslandPermission.COOP_MEMBER)) {
        player.sendMessage("You don't have permission to coop players."); return;
    }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    plugin.invitations().create(island, player.getUniqueId(), targetUuid, InvitationType.COOP)
            .thenAccept(sent -> player.sendMessage(sent
                    ? "Coop invitation sent to " + targetName + "."
                    : targetName + " already has a pending coop invitation."));
}
```

- [ ] **Step 2: Add accept and decline commands**

```java
@Subcommand("accept") @Syntax("[player]") @CommandCompletion("@players")
public void onAccept(Player player, @Optional @Nullable String senderName) {
    if (senderName != null) {
        UUID senderUuid = Bukkit.getOfflinePlayer(senderName).getUniqueId();
        plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites ->
                invites.stream()
                        .filter(i -> i.senderId().equals(senderUuid) && !i.expired())
                        .findFirst()
                        .ifPresentOrElse(
                                i -> plugin.invitations().accept(i.islandId(), player.getUniqueId())
                                        .thenAccept(ok -> player.sendMessage(ok ? "Accepted!" : "Failed to accept.")),
                                () -> player.sendMessage("No pending invite from " + senderName + ".")));
    } else {
        plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites -> {
            List<IslandInvitation> valid = invites.stream().filter(i -> !i.expired()).toList();
            if (valid.isEmpty())   { player.sendMessage("You have no pending invitations."); return; }
            if (valid.size() > 1)  { player.sendMessage("Multiple invitations — use /is accept <player>."); return; }
            plugin.invitations().accept(valid.get(0).islandId(), player.getUniqueId())
                    .thenAccept(ok -> player.sendMessage(ok ? "Accepted!" : "Failed to accept."));
        });
    }
}

@Subcommand("decline") @Syntax("[player]") @CommandCompletion("@players")
public void onDecline(Player player, @Optional @Nullable String senderName) {
    if (senderName != null) {
        UUID senderUuid = Bukkit.getOfflinePlayer(senderName).getUniqueId();
        plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites ->
                invites.stream()
                        .filter(i -> i.senderId().equals(senderUuid) && !i.expired())
                        .findFirst()
                        .ifPresentOrElse(
                                i -> plugin.invitations().decline(i.islandId(), player.getUniqueId())
                                        .thenAccept(ok -> player.sendMessage("Declined.")),
                                () -> player.sendMessage("No pending invite from " + senderName + ".")));
    } else {
        plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites -> {
            List<IslandInvitation> valid = invites.stream().filter(i -> !i.expired()).toList();
            if (valid.isEmpty())  { player.sendMessage("You have no pending invitations."); return; }
            if (valid.size() > 1) { player.sendMessage("Multiple invitations — use /is decline <player>."); return; }
            plugin.invitations().decline(valid.get(0).islandId(), player.getUniqueId())
                    .thenAccept(ok -> player.sendMessage("Declined."));
        });
    }
}
```

- [ ] **Step 3: Add cancel command**

```java
@Subcommand("cancel") @Syntax("<player>") @CommandCompletion("@players")
public void onCancel(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    plugin.invitations().cancel(island, player.getUniqueId(), targetUuid)
            .thenAccept(cancelled -> player.sendMessage(cancelled
                    ? "Invitation cancelled."
                    : "No outgoing invite for " + targetName + "."));
}
```

- [ ] **Step 4: Add kick, leave, promote, demote, transfer commands**

```java
@Subcommand("kick") @Syntax("<player>") @CommandCompletion("@players")
public void onKick(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    plugin.members().kick(island, player, targetUuid)
            .thenAccept(ignored -> player.sendMessage(targetName + " has been kicked."));
}

@Subcommand("leave")
public void onLeave(Player player) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    plugin.members().leave(island, player)
            .thenAccept(ignored -> player.sendMessage("You left the island."));
}

@Subcommand("promote") @Syntax("<player>") @CommandCompletion("@players")
public void onPromote(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    plugin.members().promote(island, player, targetUuid)
            .thenAccept(ignored -> player.sendMessage(targetName + " has been promoted."));
}

@Subcommand("demote") @Syntax("<player>") @CommandCompletion("@players")
public void onDemote(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    plugin.members().demote(island, player, targetUuid)
            .thenAccept(ignored -> player.sendMessage(targetName + " has been demoted."));
}

@Subcommand("transfer") @Syntax("<player>") @CommandCompletion("@players")
public void onTransfer(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null || island.owner() == null
            || !island.owner().playerUuid().equals(player.getUniqueId())) {
        player.sendMessage("You are not the island owner."); return;
    }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    IslandMember newOwner = island.findMember(targetUuid).orElse(null);
    if (newOwner == null) { player.sendMessage(targetName + " is not a member of your island."); return; }
    plugin.members().transfer(island, player, newOwner)
            .thenAccept(ignored -> player.sendMessage("Island transferred to " + targetName + "."));
}
```

- [ ] **Step 5: Add uncoop command**

```java
@Subcommand("uncoop") @Syntax("<player>") @CommandCompletion("@players")
public void onUncoop(Player player, String targetName) {
    Island island = plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
    if (island == null) { player.sendMessage("You don't have an island."); return; }
    if (!island.hasPermission(player, IslandPermission.UNCOOP_MEMBER)) {
        player.sendMessage("You don't have permission to remove coop players."); return;
    }
    UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
    if (!plugin.coops().isCoop(island.uniqueId(), targetUuid)) {
        player.sendMessage(targetName + " is not coop on your island."); return;
    }
    plugin.coops().remove(island, targetUuid)
            .thenAccept(ignored -> player.sendMessage(targetName + " is no longer coop."));
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/command/SkyblockCommand.java
git commit -m "feat: add member and coop management commands"
```

---

### Task 12: GUI Actions

**Files:**
- Create: `action/island/member/InviteMemberAction.java`
- Create: `action/island/member/KickMemberAction.java`
- Create: `action/island/member/PromoteMemberAction.java`
- Create: `action/island/member/DemoteMemberAction.java`
- Create: `action/island/member/TransferOwnershipAction.java`
- Create: `action/island/coop/CoopPlayerAction.java`
- Create: `action/island/coop/UncoopPlayerAction.java`
- Modify: `AstralSkyblock.java` (register actions)

All actions are records implementing `PaperAction` — same pattern as `ToggleRolePermissionAction`. Use `context.parseWrapper(wrapper)` to resolve placeholders.

- [ ] **Step 1: Create InviteMemberAction**

```java
package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.role.IslandPermission;
import org.bukkit.entity.Player;

import java.util.UUID;

public record InviteMemberAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island  = context.parseWrapper(this.island);
        if (!island.hasPermission(player, IslandPermission.INVITE_MEMBER)) return;
        UUID target = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().invitations()
                .create(island, player.getUniqueId(), target, InvitationType.MEMBER);
    }
}
```

- [ ] **Step 2: Create KickMemberAction**

```java
package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import org.bukkit.entity.Player;

import java.util.UUID;

public record KickMemberAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island  = context.parseWrapper(this.island);
        UUID   target  = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().members().kick(island, player, target);
    }
}
```

- [ ] **Step 3: Create PromoteMemberAction**

```java
package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import org.bukkit.entity.Player;

import java.util.UUID;

public record PromoteMemberAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island  = context.parseWrapper(this.island);
        UUID   target  = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().members().promote(island, player, target);
    }
}
```

- [ ] **Step 4: Create DemoteMemberAction**

```java
package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import org.bukkit.entity.Player;

import java.util.UUID;

public record DemoteMemberAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island  = context.parseWrapper(this.island);
        UUID   target  = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().members().demote(island, player, target);
    }
}
```

- [ ] **Step 5: Create TransferOwnershipAction**

```java
package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import org.bukkit.entity.Player;

public record TransferOwnershipAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<IslandMember> newOwner
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player    = context.executor();
        Island island    = context.parseWrapper(this.island);
        IslandMember to  = context.parseWrapper(this.newOwner);
        AstralSkyblock.get().members().transfer(island, player, to);
    }
}
```

- [ ] **Step 6: Create CoopPlayerAction**

```java
package com.astralrealms.skyblock.action.island.coop;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.role.IslandPermission;
import org.bukkit.entity.Player;

import java.util.UUID;

public record CoopPlayerAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island  = context.parseWrapper(this.island);
        if (!island.hasPermission(player, IslandPermission.COOP_MEMBER)) return;
        UUID target = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().invitations()
                .create(island, player.getUniqueId(), target, InvitationType.COOP);
    }
}
```

- [ ] **Step 7: Create UncoopPlayerAction**

```java
package com.astralrealms.skyblock.action.island.coop;

import com.astralrealms.core.paper.action.ExecutableRunException;
import com.astralrealms.core.paper.action.PaperAction;
import com.astralrealms.core.paper.action.PaperActionContext;
import com.astralrealms.core.placeholder.PlaceholderWrapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import org.bukkit.entity.Player;

import java.util.UUID;

public record UncoopPlayerAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island  = context.parseWrapper(this.island);
        if (!island.hasPermission(player, IslandPermission.UNCOOP_MEMBER)) return;
        UUID target = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().coops().remove(island, target);
    }
}
```

- [ ] **Step 8: Register all actions in AstralSkyblock.onEnable()**

Add after the existing action registrations (after the settings actions):

```java
// Member actions
this.registerAction("invite-member",       InviteMemberAction.class);
this.registerAction("kick-member",         KickMemberAction.class);
this.registerAction("promote-member",      PromoteMemberAction.class);
this.registerAction("demote-member",       DemoteMemberAction.class);
this.registerAction("transfer-ownership",  TransferOwnershipAction.class);
// Coop actions
this.registerAction("coop-player",         CoopPlayerAction.class);
this.registerAction("uncoop-player",       UncoopPlayerAction.class);
```

Add imports to `AstralSkyblock.java`:

```java
import com.astralrealms.skyblock.action.island.member.InviteMemberAction;
import com.astralrealms.skyblock.action.island.member.KickMemberAction;
import com.astralrealms.skyblock.action.island.member.PromoteMemberAction;
import com.astralrealms.skyblock.action.island.member.DemoteMemberAction;
import com.astralrealms.skyblock.action.island.member.TransferOwnershipAction;
import com.astralrealms.skyblock.action.island.coop.CoopPlayerAction;
import com.astralrealms.skyblock.action.island.coop.UncoopPlayerAction;
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/action/ \
        src/main/java/com/astralrealms/skyblock/AstralSkyblock.java
git commit -m "feat: add member and coop GUI actions"
```
