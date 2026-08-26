package com.astralrealms.skyblock.utils;

import java.sql.PreparedStatement;

import org.intellij.lang.annotations.Language;

import com.astralrealms.skyblock.AstralSkyblock;

import lombok.experimental.UtilityClass;

/**
 * Additive schema changes applied to databases created from an older {@code schema.sql}.
 *
 * <p>Every statement is idempotent (MariaDB {@code ADD COLUMN IF NOT EXISTS}) and runs
 * <b>synchronously, before any repository reads</b> — the island warmup cascades over these columns
 * during {@code onEnable}, so an asynchronous migration would race it and leave the cache empty.
 * A failure is logged and enable continues: the columns may already exist and the database user
 * simply lacks {@code ALTER}.
 */
@UtilityClass
public class SchemaMigrations {

    @Language("SQL")
    private static final String[] MIGRATIONS = {
            // Player-customisable warps: icon, display name and description.
            """
            ALTER TABLE island_warps
                ADD COLUMN IF NOT EXISTS icon VARCHAR(64) NULL,
                ADD COLUMN IF NOT EXISTS display_name VARCHAR(128) NULL,
                ADD COLUMN IF NOT EXISTS description VARCHAR(512) NULL
            """,
            // Island scoring: the summed block value behind the cached level.
            "ALTER TABLE islands ADD COLUMN IF NOT EXISTS value BIGINT NOT NULL DEFAULT 0"
    };

    public static void applyAll(AstralSkyblock plugin) {
        for (String migration : MIGRATIONS) {
            try {
                plugin.database().runSync(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(migration)) {
                        statement.executeUpdate();
                    }
                });
            } catch (Exception exception) {
                plugin.getSLF4JLogger().error("Schema migration failed; features relying on it may be unavailable:\n{}",
                        migration.strip(), exception);
            }
        }
    }
}
