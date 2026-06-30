package com.astralrealms.skyblock.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ASConstants {

    // Cache keys
    public static final String ISLAND_CACHE_KEY = "skyblock:islands";
    public static final String ISLAND_SERVER_KEY = "skyblock:islands:server";
    public static final String ISLAND_LOCK_KEY = "skyblock:islands:lock";
    public static final String PLAYER_CACHE_KEY = "skyblock:players";
    public static final String ROLE_CACHE_KEY = "skyblock:roles";
    public static final String PERMISSION_CACHE_KEY = "skyblock:permissions";
    public static final String MEMBER_CACHE_KEY = "skyblock:members";
    public static final String SERVER_CACHE_KEY = "skyblock:servers";
    public static final String WARP_CACHE_KEY = "skyblock:warps";
    public static final String UPGRADE_CACHE_KEY = "skyblock:upgrades";
    public static final String FLAG_CACHE_KEY = "skyblock:flags";
    public static final String BAN_CACHE_KEY = "skyblock:bans";
    public static final String COOP_CACHE_KEY = "skyblock:coops";

    // Warmup
    public static final int ISLAND_WARMUP_PAGE_SIZE = 500;

    // Messaging channels
    public static final String ISLAND_UPDATE_CHANNEL = "skyblock.island.update";
    public static final String ISLAND_MANAGEMENT_CHANNEL = "skyblock.island.management";
    public static final String PLAYER_UPDATE_CHANNEL = "skyblock.player.update";
    public static final String ROLE_UPDATE_CHANNEL = "skyblock.role.update";
    public static final String PERMISSION_UPDATE_CHANNEL = "skyblock.permission.update";
    public static final String MEMBER_UPDATE_CHANNEL = "skyblock.member.update";
    public static final String WARP_UPDATE_CHANNEL = "skyblock.warp.update";
    public static final String UPGRADE_UPDATE_CHANNEL = "skyblock.upgrade.update";
    public static final String FLAG_UPDATE_CHANNEL = "skyblock.flag.update";
    public static final String BAN_UPDATE_CHANNEL = "skyblock.ban.update";
    public static final String COOP_UPDATE_CHANNEL = "skyblock.coop.update";
    public static final String COOP_SYNC_CHANNEL = "skyblock.coop.sync";
}
