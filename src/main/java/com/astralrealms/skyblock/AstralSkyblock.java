package com.astralrealms.skyblock;


import com.astralrealms.core.cache.CacheService;
import com.astralrealms.core.messaging.MessagingService;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.plugin.AstralPaperPlugin;
import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.command.SkyblockCommand;
import com.astralrealms.skyblock.command.completion.IslandBlueprintCompletionHandler;
import com.astralrealms.skyblock.command.context.IslandBlueprintContextResolver;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.configuration.ASPLoaderConfiguration;
import com.astralrealms.skyblock.messaging.ASPacketRegistry;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.service.BlueprintService;
import com.astralrealms.skyblock.service.IslandService;
import com.astralrealms.skyblock.service.WorldService;

import lombok.Getter;

@Getter
public final class AstralSkyblock extends AstralPaperPlugin {

    // Instance
    private static AstralSkyblock instance;

    // Configuration
    private ASPLoaderConfiguration aspLoaderConfiguration;

    // Services
    private DatabaseService database;
    private CacheService cache;
    private MessagingService messaging;
    private BlueprintService blueprints;
    private WorldService worlds;
    private IslandService islands;

    @Override
    public void onEnable() {
        super.onEnable();

        // Services
        this.blueprints = new BlueprintService(this);
        this.worlds = new WorldService(this);

        // Configuration
        this.loadConfiguration();

        // Database
        this.database = new DatabaseService(this);
        this.database.connect();

        // Cache
        this.cache = new CacheService(this, AstralPaperAPI.credentialsProvider());
        this.cache.connect();

        // Messaging
        this.messaging = new MessagingService(this, AstralPaperAPI.credentialsProvider(), new ASPacketRegistry());
        this.messaging.connect();

        // Services
        this.islands = new IslandService(this);

        // Commands
        // -- Completion
        this.registerCompletion("islandBlueprints", new IslandBlueprintCompletionHandler(this));
        // -- Context
        this.registerContext(IslandBlueprint.class, new IslandBlueprintContextResolver(this));
        // -- Commands
        this.registerCommand(new SkyblockCommand());

        // Listeners

        // Instance
        instance = this;
    }

    @Override
    public void onDisable() {
        super.onDisable();

        // Messaging
        if (this.messaging != null)
            this.messaging.disconnect();

        // Cache
        if (this.cache != null)
            this.cache.disconnect();

        // Database
        if (this.database != null)
            this.database.disconnect();

        // Worlds
        this.worlds.unload();
    }

    @Override
    public void loadConfiguration() {
        this.copyResource("database.properties");

        // Configurations
        this.aspLoaderConfiguration = this.loadConfiguration("loader.yml", ASPLoaderConfiguration.class);

        // Messages
        this.loadEnum("messages.yml", ASMessages.class);

        // Services
        this.blueprints.load();
        this.worlds.load();
    }

    public static AstralSkyblock get() {
        return instance;
    }
}
