package com.astralrealms.skyblock;


import com.astralrealms.core.cache.CacheService;
import com.astralrealms.core.messaging.MessagingService;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.plugin.AstralPaperPlugin;
import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.command.SkyblockCommand;
import com.astralrealms.skyblock.command.completion.IslandBlueprintCompletionHandler;
import com.astralrealms.skyblock.command.completion.IslandCompletionHandler;
import com.astralrealms.skyblock.command.context.IslandBlueprintContextResolver;
import com.astralrealms.skyblock.command.context.IslandContextResolver;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.configuration.ASPLoaderConfiguration;
import com.astralrealms.skyblock.configuration.RolesConfiguration;
import com.astralrealms.skyblock.configuration.SkyblockConfiguration;
import com.astralrealms.skyblock.listener.IslandListener;
import com.astralrealms.skyblock.listener.PlayerConnectionListener;
import com.astralrealms.skyblock.messaging.ASPacketRegistry;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.IslandPermission;
import com.astralrealms.skyblock.model.IslandSettings;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.service.*;

import lombok.Getter;

@Getter
public final class AstralSkyblock extends AstralPaperPlugin {

    // Instance
    private static AstralSkyblock instance;

    // Configuration
    private SkyblockConfiguration configuration;
    private ASPLoaderConfiguration aspLoaderConfiguration;
    private RolesConfiguration rolesConfiguration;

    // Services
    private DatabaseService database;
    private CacheService cache;
    private MessagingService messaging;
    private BlueprintService blueprints;
    private WorldService worlds;
    private IslandService islands;
    private PlayerService players;
    private RoleService roles;
    private MemberService members;
    private ServerService servers;

    @Override
    public void onEnable() {
        super.onEnable();

        // Instance
        instance = this;

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
        this.players = new PlayerService(this);
        this.roles = new RoleService(this);
        this.members = new MemberService(this);
        this.servers = new ServerService(this);

        // Commands
        // -- Completion
        this.registerCompletion("islandBlueprints", new IslandBlueprintCompletionHandler(this));
        this.registerCompletion("islands", new IslandCompletionHandler(this));
        // -- Context
        this.registerContext(IslandBlueprint.class, new IslandBlueprintContextResolver(this));
        this.registerContext(Island.class, new IslandContextResolver(this));
        // -- Commands
        this.registerCommand(new SkyblockCommand());

        // Listeners
        this.registerListeners(
                new PlayerConnectionListener(this),
                new IslandListener(this)
        );
    }

    @Override
    public void onDisable() {
        super.onDisable();

        // Worlds
        this.worlds.unload();

        // Messaging
        if (this.messaging != null)
            this.messaging.disconnect();

        // Cache
        if (this.cache != null)
            this.cache.disconnect();

        // Database
        if (this.database != null)
            this.database.disconnect();
    }

    @Override
    public void loadConfiguration() {
        this.copyResource("database.properties");

        // Configurations
        this.configuration = this.loadConfiguration("config.yml", SkyblockConfiguration.class);
        this.aspLoaderConfiguration = this.loadConfiguration("loader.yml", ASPLoaderConfiguration.class);
        this.rolesConfiguration = this.loadConfiguration("roles.yml", RolesConfiguration.class);

        // Messages
        this.loadEnum("messages.yml", ASMessages.class);

        // Permissions & Settings
        this.loadEnum("settings.yml", IslandSettings.class);
        this.loadEnum("permissions.yml", IslandPermission.class);

        // Services
        this.blueprints.load();
        this.worlds.load();
    }

    public static AstralSkyblock get() {
        return instance;
    }
}
