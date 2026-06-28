package com.astralrealms.skyblock.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ASConstants {

    // Cache keys
    public static final String ISLAND_CACHE_KEY = "skyblock:islands";
    public static final String PLAYER_CACHE_KEY = "skyblock:players";
    public static final String ROLE_CACHE_KEY = "skyblock:roles";
    public static final String PERMISSION_CACHE_KEY = "skyblock:permissions";
    public static final String MEMBER_CACHE_KEY = "skyblock:members";

    // Warmup
    public static final int ISLAND_WARMUP_PAGE_SIZE = 500;

    // Messaging channels
    public static final String ISLAND_UPDATE_CHANNEL = "skyblock.island.update";
    public static final String PLAYER_UPDATE_CHANNEL = "skyblock.player.update";
    public static final String ROLE_UPDATE_CHANNEL = "skyblock.role.update";
    public static final String PERMISSION_UPDATE_CHANNEL = "skyblock.permission.update";
    public static final String MEMBER_UPDATE_CHANNEL = "skyblock.member.update";
}
