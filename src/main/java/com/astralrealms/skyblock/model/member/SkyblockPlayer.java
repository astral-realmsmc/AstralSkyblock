package com.astralrealms.skyblock.model.member;

import java.util.UUID;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.annotation.Id;
import com.astralrealms.core.storage.model.SQLAccessor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("players")
@NoArgsConstructor
@AllArgsConstructor
public class SkyblockPlayer implements Unique, ComplexPlaceholder {

    @Id
    @Column("uuid")
    private UUID uniqueId;
    private String name;
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long firstSeen;
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long lastSeen;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> uniqueId;
            case "name" -> name;
            case "firstSeen" -> firstSeen;
            case "lastSeen" -> lastSeen;
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "player";
    }
}
