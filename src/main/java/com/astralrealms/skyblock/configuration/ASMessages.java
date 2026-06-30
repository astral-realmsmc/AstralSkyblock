package com.astralrealms.skyblock.configuration;

import com.astralrealms.core.configuration.MessageEnum;
import com.astralrealms.core.placeholder.wrapper.impl.component.ComponentWrapper;

import lombok.Getter;
import lombok.Setter;

public enum ASMessages implements MessageEnum {
    // Creation
    NAME_ALREADY_TAKEN,
    ISLAND_CREATED,
    // Deletion
    ISLAND_DELETED,
    // Roles
    ROLE_PERMISSION_HIGHER,
    // Role permissions
    ROLE_PERMISSION_UPDATE_SUCCESS,
    ROLE_PERMISSION_UPDATE_FAILED,
    // Settings
    SETTINGS_UPDATE_SUCCESS,
    SETTINGS_UPDATE_FAILED,
    // Misc
    UNEXPECTED_ERROR,
    NO_PERMISSION
    ;

    @Getter
    @Setter
    private ComponentWrapper value;
}
