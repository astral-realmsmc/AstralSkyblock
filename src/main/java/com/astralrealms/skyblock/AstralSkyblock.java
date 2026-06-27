package com.astralrealms.skyblock;


import com.astralrealms.core.cache.CacheService;
import com.astralrealms.core.messaging.MessagingService;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.plugin.AstralPaperPlugin;
import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.messaging.ASPacketRegistry;

import lombok.Getter;

@Getter
public final class AstralSkyblock extends AstralPaperPlugin {

    // Configuration

    // Services
    private DatabaseService database;
    private CacheService cache;
    private MessagingService messaging;

    @Override
    public void onEnable() {
        super.onEnable();

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
    }

    @Override
    public void loadConfiguration() {
        this.copyResource("database.properties");

        // Messages
        this.loadEnum("messages.yml", ASMessages.class);
    }
}
