package com.astralrealms.skyblock.model.island;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.core.storage.annotation.*;
import com.astralrealms.core.storage.model.SQLAccessor;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;
import com.astralrealms.skyblock.placeholder.settings.IslandSettingsItemProvider;
import com.astralrealms.skyblock.placeholder.upgrade.IslandUpgradeItemProvider;

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
    @Setter
    private String name;
    /** Whether visitors are barred from the island's world. Enforced by {@code IslandListener}. */
    @Setter
    private boolean locked;
    /** Cached rank metric — {@link #value} divided by the configured points per level. */
    @Setter
    private long level;
    /** Sum of the island's block values, as of its last scan. */
    @Setter
    private long value;
    // Spawn
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;
    // Fixed centre of the world border and of the level and biome scans. Taken from the blueprint
    // spawn at creation; unlike the spawn, players cannot move it — a border that followed the
    // spawn could be walked anywhere with /is sethome.
    private double centerX;
    private double centerZ;
    // Dates
    @UpdatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long updatedAt;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    // Relationships — populated by IslandRepository#cascade on every load and refresh. Excluded from
    // persistence (transient) and from L2/Gson serialization, so they are per-server state. Volatile:
    // a cascade swaps them in on a database thread while the main thread reads them.
    @Setter
    private transient volatile IslandMember owner;
    private transient volatile Collection<IslandMember> members = new ArrayList<>();
    // The same members by player: findMember runs on every protected event, and was a stream.
    private transient volatile Map<UUID, IslandMember> membersByPlayer = Map.of();
    @Setter
    private transient volatile Collection<IslandRole> roles = new ArrayList<>();
    @Setter
    private transient volatile Collection<IslandCoop> coops = new CopyOnWriteArrayList<>();
    @Setter
    private transient volatile Collection<IslandBan> bans = new CopyOnWriteArrayList<>();
    @Setter
    private transient volatile Collection<IslandWarp> warps = new CopyOnWriteArrayList<>();
    @Setter
    private transient volatile EnumSet<IslandSettings> settings = EnumSet.noneOf(IslandSettings.class);
    // Unsaved settings-menu edits, per editor. Enforcement never reads them: a toggle only takes
    // effect once it is saved, and two editors never see (or save) each other's.
    private transient final Map<UUID, Map<IslandSettings, Boolean>> pendingSettings = new ConcurrentHashMap<>();
    @Setter
    private transient volatile Map<UpgradeType, Integer> upgrades = new EnumMap<>(UpgradeType.class);

    public Island(UUID uniqueId, String name, boolean locked, long level, long value,
                  double spawnX, double spawnY, double spawnZ, float spawnYaw, float spawnPitch,
                  long updatedAt, long createdAt) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.locked = locked;
        this.level = level;
        this.value = value;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
        this.centerX = spawnX;
        this.centerZ = spawnZ;
        this.updatedAt = updatedAt;
        this.createdAt = createdAt;
    }

    /** The island owner, or a staff member holding {@code skyblock.admin}. */
    public boolean isOwnerOrStaff(Player player) {
        return player.hasPermission("skyblock.admin")
               || (this.owner != null && this.owner.playerUuid().equals(player.getUniqueId()));
    }

    public boolean canEditRole(Player player, IslandRole role) {
        if (isOwnerOrStaff(player))
            return true;

        return this.findMember(player.getUniqueId())
                .map(member -> member.isOwner() || (member.role() != null && member.role().weight() > role.weight()))
                .orElse(false);
    }

    /**
     * Whether the player may do {@code permission} on this island. Resolution follows the schema's
     * contract: the owner (and a staff member holding {@code skyblock.admin}) bypasses every check,
     * a member resolves through their own role, a coop through the island's COOP role, and everyone
     * else — a visitor — through its VISITOR role.
     */
    public boolean hasPermission(Player player, IslandPermission permission) {
        if (player.hasPermission("skyblock.admin")
            || (this.owner != null && this.owner.playerUuid().equals(player.getUniqueId())))
            return true;

        Optional<IslandMember> member = this.findMember(player.getUniqueId());
        if (member.isPresent())
            return member.get().isOwner() || (member.get().role() != null && member.get().role().hasPermission(permission));

        IslandRole.Type kind = findCoop(player.getUniqueId()).isPresent()
                ? IslandRole.Type.COOP
                : IslandRole.Type.VISITOR;
        return systemRole(kind)
                .map(role -> role.hasPermission(permission))
                .orElse(false);
    }

    /**
     * The island's single role of a system kind ({@code VISITOR}/{@code COOP}). The schema keeps
     * exactly one of each per island; an island whose roles have not been hydrated yet has none,
     * and then every non-member is denied.
     */
    public Optional<IslandRole> systemRole(IslandRole.Type kind) {
        return roles().stream()
                .filter(role -> role.kind() == kind)
                .findFirst();
    }

    public Optional<IslandMember> findMember(UUID uniqueId) {
        return Optional.ofNullable(this.membersByPlayer.get(uniqueId));
    }

    /** Replaces the island's members (a cascade or refresh); the lookup index follows. */
    public void members(Collection<IslandMember> members) {
        Map<UUID, IslandMember> byPlayer = new HashMap<>();
        if (members != null)
            members.forEach(member -> byPlayer.put(member.playerUuid(), member));
        this.membersByPlayer = Map.copyOf(byPlayer);
        this.members = members;
    }

    public Optional<IslandCoop> findCoop(UUID playerUuid) {
        if (this.coops == null)
            return Optional.empty();
        return this.coops.stream()
                .filter(c -> c.playerUuid().equals(playerUuid))
                .findFirst();
    }

    public Collection<IslandMember> members() {
        return this.members == null ? List.of() : this.members;
    }

    public Collection<IslandRole> roles() {
        return this.roles == null ? List.of() : this.roles;
    }

    public Collection<IslandCoop> coops() {
        return this.coops == null ? List.of() : this.coops;
    }

    public Collection<IslandBan> bans() {
        return this.bans == null ? List.of() : this.bans;
    }

    public Collection<IslandWarp> warps() {
        return this.warps == null ? List.of() : this.warps;
    }

    /** Whether the player is banned from this island, from the island's own cascaded snapshot. */
    public boolean isBanned(UUID playerUuid) {
        return this.bans != null && this.bans.stream()
                .anyMatch(ban -> ban.playerUuid().equals(playerUuid));
    }

    /** A warp of this island by name, case-insensitively. */
    public Optional<IslandWarp> findWarp(String name) {
        if (this.warps == null || name == null)
            return Optional.empty();
        return this.warps.stream()
                .filter(warp -> warp.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Whether the player belongs to this island — a member (including the owner) or a coop — and so
     * may see its private warps.
     */
    public boolean isInsider(Player player) {
        return player.hasPermission("skyblock.admin")
               || this.findMember(player.getUniqueId()).isPresent()
               || this.findCoop(player.getUniqueId()).isPresent();
    }

    /** The warps this player may see: public ones, plus private ones when they belong to the island. */
    public Collection<IslandWarp> visibleWarps(Player player) {
        if (isInsider(player))
            return warps();
        return warps().stream()
                .filter(warp -> !warp.isPrivate())
                .toList();
    }

    // Upgrades
    public Map<UpgradeType, Integer> upgrades() {
        return this.upgrades == null ? Map.of() : this.upgrades;
    }

    /** The island's level for an upgrade; 0 when it was never purchased (the override-only default). */
    public int upgradeLevel(UpgradeType type) {
        return this.upgrades == null ? 0 : this.upgrades.getOrDefault(type, 0);
    }

    // Settings
    /** Whether {@code setting} is on, as saved. */
    public boolean isSettingEnabled(IslandSettings setting) {
        return this.settings.contains(setting);
    }

    /** Whether {@code setting} is on, as {@code editor} sees it in the menu: their unsaved edit, else the saved value. */
    public boolean isSettingEnabled(UUID editor, IslandSettings setting) {
        Map<IslandSettings, Boolean> pending = this.pendingSettings.get(editor);
        Boolean value = pending == null ? null : pending.get(setting);
        return value != null ? value : isSettingEnabled(setting);
    }

    /** Flips {@code setting} in {@code editor}'s unsaved edits (switching off conflicting ones) and returns the new value. */
    public boolean toggleSetting(UUID editor, IslandSettings setting) {
        Map<IslandSettings, Boolean> pending = this.pendingSettings.computeIfAbsent(editor, _ -> new ConcurrentHashMap<>());
        boolean enabled = !isSettingEnabled(editor, setting);
        if (enabled)
            for (IslandSettings conflicting : setting.conflictingSettings())
                if (isSettingEnabled(editor, conflicting))
                    pending.put(conflicting, false);
        pending.put(setting, enabled);
        return enabled;
    }

    /** Removes {@code editor}'s unsaved edits and returns those that actually change the island. */
    public Map<IslandSettings, Boolean> takePendingSettings(UUID editor) {
        Map<IslandSettings, Boolean> pending = this.pendingSettings.remove(editor);
        if (pending == null)
            return Map.of();
        Map<IslandSettings, Boolean> changes = new EnumMap<>(IslandSettings.class);
        pending.forEach((setting, enabled) -> {
            if (enabled != isSettingEnabled(setting))
                changes.put(setting, enabled);
        });
        return changes;
    }

    /** Applies settings that were just persisted. */
    public void applySettings(Map<IslandSettings, Boolean> changes) {
        EnumSet<IslandSettings> updated = EnumSet.copyOf(this.settings);
        changes.forEach((setting, enabled) -> {
            if (enabled)
                updated.add(setting);
            else
                updated.remove(setting);
        });
        this.settings = updated;
    }

    /** Takes every persisted column from {@code other}, a fresh read of this same island's row. */
    public void copyScalarsFrom(Island other) {
        this.name = other.name;
        this.locked = other.locked;
        this.level = other.level;
        this.value = other.value;
        this.spawnX = other.spawnX;
        this.spawnY = other.spawnY;
        this.spawnZ = other.spawnZ;
        this.spawnYaw = other.spawnYaw;
        this.spawnPitch = other.spawnPitch;
        this.centerX = other.centerX;
        this.centerZ = other.centerZ;
        this.updatedAt = other.updatedAt;
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
            case "value" -> value;
            case "members" -> ItemProvider.of(members());
            case "owner" -> this.owner;
            case "roles" -> ItemProvider.of(roles());
            // What a member can be given: the member roles, by rank. Visitor and Co-Op are held by
            // non-members only, so offering them only ever led to a refusal.
            case "assignableRoles" -> ItemProvider.of(roles().stream()
                    .filter(role -> role.kind() == IslandRole.Type.MEMBER)
                    .sorted(java.util.Comparator.comparingInt(IslandRole::weight).reversed())
                    .toList());
            case "hasPermission" -> {
                if (!context.hasNext() || !(context.context() instanceof Player player))
                    yield null;
                yield this.hasPermission(player, IslandPermission.valueOf(context.collapseRemaining()));
            }
            // Resolved against the viewer, like hasPermission: menus use it to hide the actions the
            // owner cannot take (leaving) and the ones only they can (disbanding).
            case "isOwner" -> context.context() instanceof Player player
                              && this.owner != null
                              && this.owner.playerUuid().equals(player.getUniqueId());
            case "settings" -> new IslandSettingsItemProvider(this);
            case "bans" -> ItemProvider.of(bans());
            case "coops" -> ItemProvider.of(coops());
            case "warps" -> {
                if (context.context() instanceof Player player)
                    yield ItemProvider.of(visibleWarps(player));
                yield ItemProvider.of(warps());
            }
            case "warpCount" -> warps().size();
            case "upgrades" -> new IslandUpgradeItemProvider(this);
            case "upgradeLevel" -> {
                if (!context.hasNext())
                    yield null;
                yield this.upgradeLevel(UpgradeType.valueOf(context.collapseRemaining()));
            }
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
