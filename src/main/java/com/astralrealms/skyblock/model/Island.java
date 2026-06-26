package com.astralrealms.skyblock.model;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.paper.placeholder.LocationPlaceholder;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.*;
import com.astralrealms.core.storage.model.SQLAccessor;

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
    private String world;
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


    // Spawn
    public void location(Location location) {
        this.spawnX = location.getX();
        this.spawnY = location.getY();
        this.spawnZ = location.getZ();
        this.spawnYaw = location.getYaw();
        this.spawnPitch = location.getPitch();
    }

    public @Nullable Location bukkitLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        return new Location(
                bukkitWorld,
                spawnX,
                spawnY,
                spawnZ,
                spawnYaw,
                spawnPitch
        );
    }

    // Placeholders
    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> uniqueId;
            case "name" -> name;
            case "world" -> world;
            case "locked" -> locked;
            case "level" -> level;
            case "spawn" -> new LocationPlaceholder(bukkitLocation());
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
