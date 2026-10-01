package com.astralrealms.skyblock.command;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.kyori.adventure.text.Component;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class SkyblockCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Default
    public void onDefault(Player player) {
        Island island = this.plugin.members()
                .findPlayerIsland(player.getUniqueId())
                .orElse(null);
        if (island == null) {
            // No island yet: offer the blueprints rather than saying nothing.
            this.plugin.menus()
                    .computeAndOpen(player, "island-creation",
                            Map.of("blueprints", ItemProvider.of(List.copyOf(this.plugin.blueprints().all()))))
                    .exceptionally(throwable -> {
                        this.plugin.getSLF4JLogger().error("Failed to open island creation menu for {}", player.getName(), throwable);
                        ASMessages.UNEXPECTED_ERROR.message(player);
                        return null;
                    });
            return;
        }

        this.plugin.menus()
                .computeAndOpen(player, "island-main", Map.of("island", island))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open island main menu for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    @Subcommand("create")
    @Description("Creates a new island")
    @Syntax("<name> <blueprint>")
    @CommandCompletion("@nothing @islandBlueprints")
    public void onCreate(Player player, @Nullable @Optional String name, @Nullable @Optional IslandBlueprint blueprint) {
        IslandBlueprint finalBlueprint = blueprint == null ? this.plugin.blueprints().defaultBlueprint() : blueprint;
        this.plugin.islands().create(player, name, finalBlueprint);
    }

    @Subcommand("delete")
    @Description("Deletes your island")
    @Syntax("<island>")
    @CommandCompletion("@islands")
    public void onDelete(Player player, Island island) {
        if (!island.isOwnerOrStaff(player)) {
            ASMessages.NOT_ISLAND_OWNER.message(player);
            return;
        }

        // Irreversible, so it goes through the same confirmation as the menu button.
        this.plugin.menus()
                .computeAndOpen(player, "island-confirm-disband", Map.of("island", island))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open the disband confirmation for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    @Subcommand("go")
    @Description("Teleports you to your island")
    @CommandCompletion("@islands")
    @Syntax("<island>")
    public void onGo(Player player, Island island) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player).registerPlaceholder(island);

        // Refused up front, as warps do: arrival would bounce them anyway, after a world load and
        // possibly a server switch.
        if (this.plugin.bans().isBanned(island.uniqueId(), player.getUniqueId())) {
            ASMessages.BANNED_FROM_ISLAND.message(player, placeholders);
            return;
        }
        if (island.locked() && !this.plugin.islands().mayEnterClosed(island, player)) {
            ASMessages.ISLAND_IS_CLOSED.message(player, placeholders);
            return;
        }

        this.plugin.islands()
                .spawnIsland(island)
                .thenCompose(location -> {
                    if (location == null)
                        throw new IllegalStateException("No island server can host island " + island.uniqueId());
                    return AstralPaperAPI.getService(TeleportationService.class)
                            .orElseThrow(() -> new IllegalStateException("TeleportationService not found"))
                            .teleport(player.getUniqueId(), location);
                })
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to send {} to island {}", player.getName(), island.uniqueId(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                    return null;
                });
    }

    @Subcommand("rename|setname")
    @Description("Renames your island")
    @Syntax("<name>")
    public void onRename(Player player, String name) {
        withIsland(player, island -> this.plugin.islands().rename(player, island, name));
    }

    @Subcommand("sethome")
    @Description("Moves your island spawn to where you are standing")
    public void onSetHome(Player player) {
        withIsland(player, island -> this.plugin.islands().setHome(player, island));
    }

    @Subcommand("close|lock")
    @Description("Closes your island to visitors")
    public void onClose(Player player) {
        withIsland(player, island -> this.plugin.islands().setLocked(player, island, true));
    }

    @Subcommand("open|unlock")
    @Description("Opens your island to visitors")
    public void onOpen(Player player) {
        withIsland(player, island -> this.plugin.islands().setLocked(player, island, false));
    }

    @Subcommand("expel")
    @Description("Sends a visitor off your island")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onExpel(Player player, MinecraftPlayer target) {
        withIsland(player, island -> this.plugin.islands().expel(player, island, target.uniqueId()));
    }

    @Subcommand("biome")
    @Description("Changes the biome of your island")
    @Syntax("<biome>")
    @CommandCompletion("@biomes")
    public void onBiome(Player player, String biome) {
        withIsland(player, island -> this.plugin.biomes().setBiome(player, island, biome));
    }

    /**
     * Runs {@code action} against the caller's island, or tells them they have none. The services
     * behind these subcommands all do their own permission check, so this only resolves the island.
     */
    private void withIsland(Player player, Consumer<Island> action) {
        Island island = this.plugin.members()
                .findPlayerIsland(player.getUniqueId())
                .orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        action.accept(island);
    }

    @Subcommand("reload")
    @CommandPermission("skyblock.reload")
    @Description("Reloads the plugin configuration")
    public void onReload(CommandSender sender) {
        sender.sendMessage(Component.text("Reloading configuration..."));
        try {
            plugin.loadConfiguration();
            sender.sendMessage(Component.text("Configuration reloaded successfully."));
        } catch (Exception e) {
            sender.sendMessage(Component.text("An error occurred while reloading the configuration: " + e.getMessage()));
        }
    }
}
