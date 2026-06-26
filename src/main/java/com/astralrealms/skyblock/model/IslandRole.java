package com.astralrealms.skyblock.model;

import java.util.UUID;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.CreatedAt;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.annotation.Id;
import com.astralrealms.core.storage.model.SQLAccessor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("island_roles")
@NoArgsConstructor
@AllArgsConstructor
public class IslandRole implements Unique, ComplexPlaceholder {

    @Id
    @Column("id")
    private UUID uniqueId;
    private UUID islandId;
    private Type kind;
    private String name;
    private int weight;
    private boolean isDefault;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> uniqueId;
            case "islandId" -> islandId;
            case "kind" -> kind;
            case "name" -> name;
            case "weight" -> weight;
            case "default" -> isDefault;
            case "createdAt" -> createdAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "role";
    }

    public enum Type {
        MEMBER,
        VISITOR,
        COOP
    }
}
