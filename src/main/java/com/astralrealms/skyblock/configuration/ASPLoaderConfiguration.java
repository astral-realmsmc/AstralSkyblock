package com.astralrealms.skyblock.configuration;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record ASPLoaderConfiguration(String sqlUrl, String host, int port, String database, String username, String password, boolean useSsl) {
}
