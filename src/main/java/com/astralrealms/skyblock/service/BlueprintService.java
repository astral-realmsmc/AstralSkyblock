package com.astralrealms.skyblock.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.IslandBlueprint;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BlueprintService {

    private final AstralSkyblock plugin;
    private final Map<String, IslandBlueprint> blueprints = new HashMap<>();
    @Getter
    private IslandBlueprint defaultBlueprint;

    public void load() {
        this.plugin.getSLF4JLogger().info("Loading island blueprints...");
        this.blueprints.clear();

        Path sourceWorldsFolder = this.plugin.getDataPath().resolve("sourceWorlds");
        if (!Files.exists(sourceWorldsFolder)) {
            try {
                Files.createDirectories(sourceWorldsFolder);
            } catch (Exception e) {
                this.plugin.getSLF4JLogger().error("Failed to create sourceWorlds folder: {}", sourceWorldsFolder, e);
            }
        }

        Path dataFolder = this.plugin.getDataPath().resolve("blueprints");
        Collection<IslandBlueprint> loadedBlueprints = this.plugin.configurationManager().loadFolder(dataFolder, IslandBlueprint.class);
        for (IslandBlueprint blueprint : loadedBlueprints) {
            if (blueprints.containsKey(blueprint.id())) {
                this.plugin.getSLF4JLogger().warn("Duplicate blueprint name found: {}. Skipping...", blueprint.id());
                continue;
            }

            // One malformed file must not take the whole plugin down with it.
            if (blueprint.sourceWorld() == null || blueprint.sourceWorld().isBlank() || blueprint.spawnLocation() == null) {
                this.plugin.getSLF4JLogger().warn("Blueprint {} is missing 'source-world' or 'spawn-location'. Skipping...", blueprint.id());
                continue;
            }

            Path schematicPath = sourceWorldsFolder.resolve(blueprint.sourceWorld());
            if (!Files.exists(schematicPath)) {
                this.plugin.getSLF4JLogger().warn("Source world file not found for blueprint {}: {}. Skipping...", blueprint.id(), schematicPath);
                continue;
            }

            this.blueprints.put(blueprint.id(), blueprint);
        }

        // Without a default, island creation is refused but everything else (existing islands,
        // visits, warps) keeps working.
        this.defaultBlueprint = this.blueprints.values()
                .stream()
                .filter(IslandBlueprint::isDefault)
                .findFirst()
                .orElse(null);
        if (this.defaultBlueprint == null)
            this.plugin.getSLF4JLogger().error("No default blueprint loaded: island creation is disabled until a blueprint marked 'default: true' with a valid source world is added to {}.", dataFolder);

        this.plugin.getSLF4JLogger().info("Loaded {} island blueprints.", this.blueprints.size());
    }

    public Optional<IslandBlueprint> findById(String id) {
        return Optional.ofNullable(this.blueprints.get(id));
    }

    @Unmodifiable
    public Collection<String> keys() {
        return this.blueprints.keySet();
    }

    @Unmodifiable
    public Collection<IslandBlueprint> all() {
        return this.blueprints.values();
    }
}
