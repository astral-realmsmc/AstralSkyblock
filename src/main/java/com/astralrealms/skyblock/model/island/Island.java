package com.astralrealms.skyblock.model.island;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.Location;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.core.storage.annotation.*;
import com.astralrealms.core.storage.model.SQLAccessor;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandMember;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("islands")
@NoArgsConstructor
@AllArgsConstructor
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

    public IslandMember owner() {
        return this.members()
                .stream()
                .filter(IslandMember::isOwner)
                .findFirst()
                .orElse(null);
    }

    @Unmodifiable
    public Collection<IslandMember> members() {
        return AstralSkyblock.get()
                .members()
                .findIslandMembers(this.uniqueId);
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
            case "owner" -> this.owner();
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
