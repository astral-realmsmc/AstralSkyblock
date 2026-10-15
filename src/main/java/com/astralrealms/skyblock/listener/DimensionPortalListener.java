package com.astralrealms.skyblock.listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.dimension.DimensionPortal;
import com.astralrealms.skyblock.model.dimension.IslandDimension;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

/**
 * Takes players through the portals of an island: from its overworld to the dimension declaring
 * that portal, and from a dimension back home through its own portal. Players arrive at the
 * dimension's configured spawn, or at the island spawn on the way back; no portal is searched for
 * or built.
 *
 * <p>Vanilla portal travel never applies in island worlds — it would lead to this server's own
 * nether and end, if it has any. Entering a portal is caught instead ({@link EntityPortalEnterEvent}
 * fires every tick an entity stands in one, whether or not the server has the vanilla dimension),
 * and a nether portal still makes survival players wait in it, like vanilla does. Other entities do
 * not travel. Island servers only; with dimensions disabled, portals are left to vanilla.
 */
@RequiredArgsConstructor
public class DimensionPortalListener implements Listener {

    private final AstralSkyblock plugin;
    // Player -> the ticks they entered and were last seen in a nether portal. Main thread only.
    private final Map<UUID, Stay> stays = new HashMap<>();
    // Player -> the tick from which portals take them again.
    private final Map<UUID, Integer> cooldowns = new HashMap<>();
    // Players whose destination is loading: the portal keeps firing meanwhile.
    private final Set<UUID> travelling = new HashSet<>();

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (!this.plugin.dimensions().enabled())
            return;
        World world = event.getLocation().getWorld();
        Island island = this.plugin.worlds().findByWorld(world).orElse(null);
        if (island == null && this.plugin.worlds().findIslandIdByWorld(world).isEmpty())
            return;

        event.setCancelled(true);
        if (island == null || !(event.getEntity() instanceof Player player))
            return;

        DimensionPortal portal = DimensionPortal.of(event.getPortalType());
        String target = this.plugin.dimensions().route(this.plugin.worlds().findDimension(world).orElse(null), portal);
        if (target == null)
            return;

        UUID playerId = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        if (this.travelling.contains(playerId))
            return;
        Integer readyAt = this.cooldowns.get(playerId);
        if (readyAt != null && tick < readyAt)
            return;

        if (portal == DimensionPortal.NETHER && !instant(player)) {
            Stay stay = this.stays.get(playerId);
            // A gap means they stepped out: the wait starts over.
            if (stay == null || tick - stay.lastTick() > 2)
                stay = new Stay(tick, tick);
            this.stays.put(playerId, new Stay(stay.firstTick(), tick));
            if (tick - stay.firstTick() < this.plugin.dimensions().configuration().portalDelayTicks())
                return;
        }

        this.stays.remove(playerId);
        this.cooldowns.put(playerId, tick + this.plugin.dimensions().configuration().portalCooldownTicks());
        travel(player, island, target);
    }

    /** Creative and spectator players go through a nether portal at once, as in vanilla. */
    private static boolean instant(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private void travel(Player player, Island island, String target) {
        if (target.equals(IslandDimension.OVERWORLD)) {
            this.plugin.worlds()
                    .findByIslandId(island.uniqueId())
                    .ifPresent(home -> player.teleportAsync(island.spawnLocation(home.getBukkitWorld()), PlayerTeleportEvent.TeleportCause.PLUGIN));
            return;
        }

        IslandDimension dimension = this.plugin.dimensions().findById(target).orElse(null);
        if (dimension == null)
            return;

        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island)
                .registerDirect("dimension", dimension.id())
                .registerDirect("dimension_name", dimension.displayName());
        ASMessages denial = this.plugin.dimensions().denial(player, island, dimension);
        if (denial != null) {
            denial.message(player, placeholders);
            return;
        }

        UUID playerId = player.getUniqueId();
        this.travelling.add(playerId);
        this.plugin.worlds()
                .loadDimension(island, dimension)
                .whenComplete((instance, throwable) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.travelling.remove(playerId);
                    if (!player.isOnline())
                        return;
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to load the {} world of island {} for {}",
                                dimension.id(), island.uniqueId(), player.getName(), throwable);
                        ASMessages.DIMENSION_NOT_HOSTED.message(player, placeholders);
                        return;
                    }
                    player.teleportAsync(this.plugin.dimensions().spawn(dimension, instance.getBukkitWorld()),
                            PlayerTeleportEvent.TeleportCause.PLUGIN);
                }));
    }

    /** Whatever vanilla portal travel still starts in an island world is refused; see the class comment. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (this.plugin.dimensions().enabled() && this.plugin.worlds().findIslandIdByWorld(event.getFrom().getWorld()).isPresent())
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (this.plugin.dimensions().enabled() && this.plugin.worlds().findIslandIdByWorld(event.getFrom().getWorld()).isPresent())
            event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.stays.remove(playerId);
        this.cooldowns.remove(playerId);
    }

    private record Stay(int firstTick, int lastTick) {
    }
}
