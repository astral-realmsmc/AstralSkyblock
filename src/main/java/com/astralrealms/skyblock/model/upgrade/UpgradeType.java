package com.astralrealms.skyblock.model.upgrade;

/**
 * The purchasable upgrades of an island. Every constant here must have a blueprint under
 * {@code upgrades/} and an effect applied by {@link com.astralrealms.skyblock.service.UpgradeService}
 * or {@link com.astralrealms.skyblock.listener.UpgradeEffectsListener}; a type with neither is only
 * reachable through {@code /is upgrade set}, where it would write a row nothing ever reads.
 *
 * <p>A missing blueprint is not fatal — every effect falls back to the baseline in the
 * {@code defaults} block of {@code config.yml} — but it does make the upgrade unbuyable.
 */
public enum UpgradeType {
    /** Border diameter, in blocks. Applied to the island world's {@code WorldBorder}. */
    WORLDBORDER_SIZE,
    /** Member cap. Enforced by {@code MemberService} where the membership row is written. */
    MEMBERS_LIMIT,
    /** Coop cap. Enforced by {@code CoopService} where the coop row is written. */
    COOP_LIMIT,
    /** Cobble/basalt generator tier. The level's {@code key} names a generator blueprint. */
    GENERATOR,
    /** Cap on hoppers placed inside the island's border. */
    HOPPERS_LIMIT,
    /** Cap on minecarts existing inside the island's world. */
    MINECART_LIMITS,
    /** Multiplier on how fast crops advance a growth stage. {@code 1} is vanilla. */
    CROP_GROWTH_SPEED,
    /** Multiplier on how fast monster spawners re-arm. {@code 1} is vanilla. */
    SPAWNERS_RATE,
    /** Multiplier on the items mobs drop when they die on the island. {@code 1} is vanilla. */
    MOB_DROPS
}
