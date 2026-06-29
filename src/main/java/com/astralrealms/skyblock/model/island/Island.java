package com.astralrealms.skyblock.model.island;

import java.util.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.core.storage.annotation.*;
import com.astralrealms.core.storage.model.SQLAccessor;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.placeholder.settings.IslandSettingsItemProvider;

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
    private transient Collection<IslandMember> members = List.of();
    @Setter
    private transient Collection<IslandRole> roles = List.of();
    @Setter
    private transient EnumSet<IslandSettings> settings = EnumSet.noneOf(IslandSettings.class);
    private transient final Map<IslandSettings, Boolean> dirtySettings = new EnumMap<>(IslandSettings.class);

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

    public boolean canEditRole(Player player, IslandRole role) {
        if (player.hasPermission("skyblock.admin")
            || (this.owner != null && this.owner.playerUuid().equals(player.getUniqueId())))
            return true;

        return this.findMember(player.getUniqueId())
                .map(member -> member.isOwner() || (member.role() != null && member.role().weight() > role.weight()))
                .orElse(false);
    }

    public boolean hasPermission(Player player, IslandPermission permission) {
        if (player.hasPermission("skyblock.admin")
            || (this.owner != null && this.owner.playerUuid().equals(player.getUniqueId())))
            return true;
        return this.findMember(player.getUniqueId())
                .map(member -> member.isOwner() || (member.role() != null && member.role().hasPermission(permission)))
                .orElse(false);
    }

    public Optional<IslandMember> findMember(UUID uniqueId) {
        if (this.members == null)
            return Optional.empty();
        return this.members.stream()
                .filter(member -> member.playerUuid().equals(uniqueId))
                .findFirst();
    }

    public Collection<IslandMember> members() {
        return this.members == null ? List.of() : this.members;
    }

    public Collection<IslandRole> roles() {
        return this.roles == null ? List.of() : this.roles;
    }

    // Settings
    public boolean isSettingEnabled(IslandSettings settings) {
        return this.dirtySettings.getOrDefault(settings, this.settings.contains(settings));
    }

    public boolean toggleSetting(IslandSettings settings) {
        boolean enabled = !this.isSettingEnabled(settings);
        this.dirtySettings.put(settings, enabled);
        return enabled;
    }

    public Map<IslandSettings, Boolean> flushSettings() {
        Map<IslandSettings, Boolean> flushed = Map.copyOf(dirtySettings);
        for (Map.Entry<IslandSettings, Boolean> entry : flushed.entrySet()) {
            if (entry.getValue())
                this.settings.add(entry.getKey());
            else
                this.settings.remove(entry.getKey());
        }
        this.dirtySettings.clear();
        return flushed;
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
            case "hasPermission" -> {
                if (!context.hasNext() || !(context.context() instanceof Player player))
                    yield null;
                yield this.hasPermission(player, IslandPermission.valueOf(context.collapseRemaining()));
            }
            case "settings" -> new IslandSettingsItemProvider(this);
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
