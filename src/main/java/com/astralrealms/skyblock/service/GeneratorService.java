package com.astralrealms.skyblock.service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.GeneratorConfiguration;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GeneratorService {

    private final AstralSkyblock plugin;
    private final Map<String, GeneratorConfiguration> blueprints = new HashMap<>();

    public void load() {
        this.plugin.getSLF4JLogger().info("Loading generator blueprints...");
        this.blueprints.clear();

        Path dataPath = this.plugin.getDataPath().resolve("generators");
        Collection<GeneratorConfiguration> loadedGenerators = this.plugin.configurationManager().loadFolder(dataPath, GeneratorConfiguration.class);
        for (GeneratorConfiguration loadedGenerator : loadedGenerators) {
            if (this.blueprints.containsKey(loadedGenerator.id())) {
                this.plugin.getSLF4JLogger().warn("Duplicate generator blueprint ID: {}. Skipping.", loadedGenerator.id());
                continue;
            }

            this.blueprints.put(loadedGenerator.id(), loadedGenerator);
        }

        // Ensure default generator exists
        String defaultGeneratorId = this.plugin.configuration().generators().defaultGenerator();
        if (!this.blueprints.containsKey(defaultGeneratorId))
            this.plugin.getSLF4JLogger().warn("Default generator blueprint '{}' does not exist.", defaultGeneratorId);

        this.plugin.getSLF4JLogger().info("Loaded {} generator blueprints.", this.blueprints.size());
    }

    public GeneratorConfiguration defaultGenerator() {
        return this.blueprints.get(this.plugin.configuration().generators().defaultGenerator());
    }

    public Optional<GeneratorConfiguration> findById(String id) {
        return Optional.ofNullable(this.blueprints.get(id));
    }
}
