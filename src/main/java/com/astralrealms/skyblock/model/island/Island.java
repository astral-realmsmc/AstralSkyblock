package com.astralrealms.skyblock.model.island;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.core.storage.annotation.*;
import com.astralrealms.core.storage.model.SQLAccessor;
import com.astralrealms.skyblock.model.IslandSettings;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity("islands")
@NoArgsConstructor
public class Island implements Unique, ComplexPlaceholder {

    @Id
    @Column("id")
    private UUID uniqueId;
    private String name;
    private boolean locked;
    private int level;
    // Spawn
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;
    // Dates
    @UpdatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long updatedAt;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    // Relationships — populated for active islands by IslandService#hydrate, cleared on deactivation.
    // Excluded from persistence (transient) and from L2/Gson serialization, so they are per-server state.
    @Setter
    private transient IslandMember owner;
    @Setter
    private transient Collection<IslandMember> members;
    @Setter
    private transient Collection<IslandRole> roles;
    @Setter
    private transient EnumSet<IslandSettings> settings;

    public Island(UUID uniqueId, String name, boolean locked, int level,
                  double spawnX, double spawnY, double spawnZ, float spawnYaw, float spawnPitch,
                  long updatedAt, long createdAt) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.locked = locked;
        this.level = level;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
        this.updatedAt = updatedAt;
        this.createdAt = createdAt;
    }

    // Relationship accessors coalesce null to empty so reads are safe before hydration (or on an
    // island restored from L2, where transient fields are absent).
    public Collection<IslandMember> members() {
        return this.members == null ? List.of() : this.members;
    }

    public Collection<IslandRole> roles() {
        return this.roles == null ? List.of() : this.roles;
    }

    // Spawn
    public void location(Location location) {
        this.spawnX = location.getX();
        this.spawnY = location.getY();
        this.spawnZ = location.getZ();
        this.spawnYaw = location.getYaw();
        this.spawnPitch = location.getPitch();
    }

    // Placeholders
    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> uniqueId;
            case "name" -> name;
            case "locked" -> locked;
            case "level" -> level;
            case "members" -> ItemProvider.of(members());
            case "owner" -> this.owner;
            case "roles" -> ItemProvider.of(roles());
            case "settings" -> this.settings;
            case "updatedAt" -> updatedAt;
            case "createdAt" -> createdAt;
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "island";
    }
}
