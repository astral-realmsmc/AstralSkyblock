package com.astralrealms.skyblock;


import com.astralrealms.core.cache.CacheService;
import com.astralrealms.core.messaging.MessagingService;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.dialog.container.DialogContainer;
import com.astralrealms.core.paper.menu.container.MenuContainer;
import com.astralrealms.core.paper.plugin.AstralPaperPlugin;
import com.astralrealms.core.placeholder.container.RootPlaceholderContainer;
import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.action.island.coop.CoopPlayerAction;
import com.astralrealms.skyblock.action.island.coop.UncoopPlayerAction;
import com.astralrealms.skyblock.action.island.member.DemoteMemberAction;
import com.astralrealms.skyblock.action.island.member.InviteMemberAction;
import com.astralrealms.skyblock.action.island.member.KickMemberAction;
import com.astralrealms.skyblock.action.island.member.PromoteMemberAction;
import com.astralrealms.skyblock.action.island.member.TransferOwnershipAction;
import com.astralrealms.skyblock.action.island.role.ToggleRolePermissionAction;
import com.astralrealms.skyblock.action.island.role.UpdateRolePermissionsAction;
import com.astralrealms.skyblock.action.island.settings.ToggleIslandSettingAction;
import com.astralrealms.skyblock.action.island.settings.UpdateIslandSettingsAction;
import com.astralrealms.skyblock.command.CoopCommand;
import com.astralrealms.skyblock.command.InvitationCommand;
import com.astralrealms.skyblock.command.MemberCommand;
import com.astralrealms.skyblock.command.SkyblockCommand;
import com.astralrealms.skyblock.command.completion.IslandBlueprintCompletionHandler;
import com.astralrealms.skyblock.command.completion.IslandCompletionHandler;
import com.astralrealms.skyblock.command.completion.IslandMemberCompletionHandler;
import com.astralrealms.skyblock.command.context.IslandBlueprintContextResolver;
import com.astralrealms.skyblock.command.context.IslandContextResolver;
import com.astralrealms.skyblock.configuration.*;
import com.astralrealms.skyblock.listener.*;
import com.astralrealms.skyblock.messaging.ASPacketRegistry;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.placeholder.SkyblockPlaceholders;
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
    private BlockValueConfiguration blockValuesConfiguration;

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
    private CoopService coops;
    private InvitationService invitations;
    private ServerService servers;
    private GeneratorService generators;
    private UpgradeService upgrades;
    private MenuContainer menus;
    private DialogContainer dialogs;

    @Override
    public void onEnable() {
        super.onEnable();

        // Instance
        instance = this;

        // Actions
        // -- Roles Permissions
        this.registerAction("toggle-role-permission", ToggleRolePermissionAction.class);
        this.registerAction("update-role-permissions", UpdateRolePermissionsAction.class);
        // -- Settings
        this.registerAction("toggle-island-setting", ToggleIslandSettingAction.class);
        this.registerAction("update-island-settings", UpdateIslandSettingsAction.class);
        // -- Members
        this.registerAction("invite-member", InviteMemberAction.class);
        this.registerAction("kick-member", KickMemberAction.class);
        this.registerAction("promote-member", PromoteMemberAction.class);
        this.registerAction("demote-member", DemoteMemberAction.class);
        this.registerAction("transfer-ownership", TransferOwnershipAction.class);
        // -- Coop
        this.registerAction("coop-player", CoopPlayerAction.class);
        this.registerAction("uncoop-player", UncoopPlayerAction.class);

        // Services
        this.blueprints = new BlueprintService(this);
        this.worlds = new WorldService(this);
        this.menus = new MenuContainer(this);
        this.dialogs = new DialogContainer(this);
        this.generators = new GeneratorService(this);
        this.upgrades = new UpgradeService(this);

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
        this.roles = new RoleService(this);
        this.members = new MemberService(this);
        this.coops = new CoopService(this);
        this.invitations = new InvitationService(this, this.members, this.coops);
        this.islands = new IslandService(this);
        this.players = new PlayerService(this);
        this.servers = new ServerService(this);

        // Commands
        // -- Completion
        this.registerCompletion("islandBlueprints", new IslandBlueprintCompletionHandler(this));
        this.registerCompletion("islands", new IslandCompletionHandler(this));
        this.registerCompletion("islandMembers", new IslandMemberCompletionHandler(this));
        // -- Context
        this.registerContext(IslandBlueprint.class, new IslandBlueprintContextResolver(this));
        this.commands().getCommandContexts().registerIssuerAwareContext(Island.class, new IslandContextResolver(this));
        // -- Commands
        this.registerCommand(new SkyblockCommand());
        this.registerCommand(new InvitationCommand());
        this.registerCommand(new MemberCommand());
        this.registerCommand(new CoopCommand());

        // Listeners
        this.registerListeners(
                new PlayerConnectionListener(this),
                new IslandListener(this)
        );

        // Island group specific listeners
        if (this.configuration.isIslandServer())
            this.registerListeners(
                    new IslandSettingsListener(this),
                    new IslandPermissionsListener(this)
            );
        if (this.configuration.generators().enabled())
            this.registerListener(new GeneratorListener(this));


        // Placeholders
        RootPlaceholderContainer.get().registerPlaceholder(new SkyblockPlaceholders(this));
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
        this.blockValuesConfiguration = this.loadConfiguration("block-values.yml", BlockValueConfiguration.class);

        // Messages
        this.loadEnum("messages.yml", ASMessages.class);

        // Permissions & Settings
        this.loadEnum("settings.yml", IslandSettings.class);
        this.loadEnum("permissions.yml", IslandPermission.class);

        // Services
        this.blueprints.load();
        this.worlds.load();
        this.menus.load();
        this.dialogs.load();
        this.generators.load();
        this.upgrades.load();
    }

    public static AstralSkyblock get() {
        return instance;
    }
}
