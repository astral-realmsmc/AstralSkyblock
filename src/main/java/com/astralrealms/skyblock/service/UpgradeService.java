package com.astralrealms.skyblock.service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpgradeService {

    private final AstralSkyblock plugin;
    private final Map<UpgradeType, IslandUpgrade> blueprints = new HashMap<>();

    public void load() {
        this.plugin.getSLF4JLogger().info("Loading island upgrade blueprints...");
        this.blueprints.clear();

        Path dataPath = this.plugin.getDataPath().resolve("upgrades");
        Collection<IslandUpgrade> upgrades = this.plugin.configurationManager().loadFolder(dataPath, IslandUpgrade.class);
        for (IslandUpgrade upgrade : upgrades) {
            if (this.blueprints.containsKey(upgrade.type())) {
                this.plugin.getSLF4JLogger().warn("Duplicate island upgrade blueprint found for type: {}", upgrade.type());
                continue;
            }

            this.blueprints.put(upgrade.type(), upgrade);
        }

        this.plugin.getSLF4JLogger().info("Loaded {} island upgrade blueprints.", this.blueprints.size());
    }

    public Optional<IslandUpgrade> findByType(UpgradeType type) {
        return Optional.ofNullable(this.blueprints.get(type));
    }

    @Unmodifiable
    public Collection<IslandUpgrade> blueprints() {
        return this.blueprints.values();
    }
}
