package com.astralrealms.skyblock.model.role;

import com.astralrealms.core.configuration.ConfigurationEnum;
import com.astralrealms.core.paper.model.itemstack.ItemStackWrapper;

import lombok.Getter;
import lombok.Setter;

public enum IslandPermission implements ConfigurationEnum<ItemStackWrapper> {
    ALL,
    ANIMAL_BREED,
    ANIMAL_SHEAR,
    BAN_MEMBER,
    BREAK,
    BRUSH,
    BUILD,
    CHANGE_NAME,
    CHORUS_FRUIT,
    CLOSE_BYPASS,
    CLOSE_ISLAND,
    COOP_MEMBER,
    DELETE_WARP,
    DEMOTE_MEMBERS,
    DISBAND_ISLAND,
    DROP_ITEMS,
    DYE_SHEEP,
    ENDER_PEARL,
    ENTITY_RIDE,
    EXPEL_BYPASS,
    EXPEL_PLAYERS,
    FERTILIZE,
    FISH,
    FLY,
    IGNITE_CREEPER,
    INVITE_MEMBER,
    KICK_MEMBER,
    LEASH,
    MINECART_ENTER,
    MINECART_OPEN,
    NAME_ENTITY,
    OPEN_ISLAND,
    PICKUP_DROPS,
    PROMOTE_MEMBERS,
    RANKUP,
    SADDLE_ENTITY,
    SCULK_SENSOR,
    SET_BIOME,
    SET_HOME,
    SET_PERMISSION,
    SET_ROLE,
    SET_SETTINGS,
    SET_WARP,
    SPAWNER_BREAK,
    UNCOOP_MEMBER,
    VALUABLE_BREAK,
    VILLAGER_TRADING,
    WIND_CHARGE;

    @Setter
    @Getter
    private ItemStackWrapper value;

    @Override
    public Class<ItemStackWrapper> type() {
        return ItemStackWrapper.class;
    }
}
