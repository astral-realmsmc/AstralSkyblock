package com.astralrealms.skyblock.model.island;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;

import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.placeholder.Placeholder;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.CreatedAt;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.core.storage.model.SQLAccessor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A named teleport point on an island, on top of the island's inline spawn.
 *
 * <p>Warps are player-customisable: {@link #icon} is the material of the item shown in the warp
 * menu, {@link #displayName} an optional MiniMessage title shown instead of the raw name, and
 * {@link #description} an optional blurb rendered as lore (lines split on {@code |}).
 */
@Getter
@Entity("island_warps")
@NoArgsConstructor
@AllArgsConstructor
public class IslandWarp implements ComplexPlaceholder {

    /** Icon used when a warp has none stored — every menu can rely on a renderable material. */
    public static final String DEFAULT_ICON = "GRASS_BLOCK";
    /** Separator used to split {@link #description} into lore lines. */
    public static final String DESCRIPTION_SEPARATOR = "|";

    private UUID islandId;
    private String name;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean isPrivate;
    @Setter
    private @Nullable String icon;
    @Setter
    private @Nullable String displayName;
    @Setter
    private @Nullable String description;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    public void isPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    /** Moves the warp to {@code location}; the world is implied by the owning island. */
    public void location(Location location) {
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }

    /** The stored icon, or {@link #DEFAULT_ICON} when the warp has none. */
    public String iconOrDefault() {
        return this.icon == null || this.icon.isBlank() ? DEFAULT_ICON : this.icon;
    }

    /** The stored display name, or the warp's raw name when it has none. */
    public String displayNameOrName() {
        return this.displayName == null || this.displayName.isBlank() ? this.name : this.displayName;
    }

    /** The description split into lore lines; empty when the warp has no description. */
    public List<String> descriptionLines() {
        if (this.description == null || this.description.isBlank())
            return List.of();
        return List.of(this.description.split("\\" + DESCRIPTION_SEPARATOR));
    }

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "islandId" -> islandId;
            case "name" -> name;
            case "displayName" -> displayNameOrName();
            case "icon" -> iconOrDefault();
            case "description" -> description == null ? "" : description;
            // Rendered by an `iterable` lore modifier: each line is exposed as %parameter_line%.
            case "descriptionLines" -> ItemProvider.of(descriptionLines().stream()
                    .map(line -> (Placeholder) Placeholder.dummy("line", line))
                    .toList());
            case "hasDescription" -> description != null && !description.isBlank();
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "yaw" -> yaw;
            case "pitch" -> pitch;
            case "isPrivate" -> isPrivate;
            case "createdAt" -> createdAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "warp";
    }
}
