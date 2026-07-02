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
    // Invitations
    PLAYER_ALREADY_MEMBER,
    PLAYER_ALREADY_COOP,
    INVITATION_ALREADY_SENT,
    INVITATION_SENT,
    INVITATION_RECEIVED,
    INVITATION_ACCEPTED_SENDER,
    INVITATION_DECLINED_SENDER,
    INVITATION_CANCELLED_SENDER,
    INVITATION_ACCEPTED_RECIPIENT,
    INVITATION_DECLINED_RECIPIENT,
    INVITATION_CANCELLED_RECIPIENT,
    NO_PENDING_INVITATION,
    MULTIPLE_PENDING_INVITATIONS,
    INVITATION_NOT_FOUND,
    // Settings
    SETTINGS_UPDATE_SUCCESS,
    SETTINGS_UPDATE_FAILED,
    // Misc
    UNEXPECTED_ERROR,
    NO_PERMISSION,
    NO_ISLAND
    ;

    @Getter
    @Setter
    private ComponentWrapper value;
}
