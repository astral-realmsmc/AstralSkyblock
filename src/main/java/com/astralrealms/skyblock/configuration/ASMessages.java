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
    // Misc
    UNEXPECTED_ERROR
    ;

    @Getter
    @Setter
    private ComponentWrapper value;
}
