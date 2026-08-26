package com.astralrealms.skyblock.utils;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Normalisation for free text a player supplies and other players then see — warp display names and
 * descriptions, ban reasons.
 *
 * <p>These land in menu item names and lore, which are rendered as MiniMessage, so raw input would
 * let a player inject formatting, {@code <hover>} and {@code <click:run_command:...>} into a menu
 * somebody else opens. Tags are therefore escaped at the point of entry (the stored value is inert
 * and renders exactly what was typed) and legacy section signs are stripped for the same reason.
 *
 * <p>Escaping roughly doubles the worst-case length, so every raw limit here sits at or below half
 * of its column width in {@code schema.sql}.
 */
@UtilityClass
public class PlayerText {

    /** Raw character limit of a warp's display name ({@code island_warps.display_name}, 128). */
    public static final int WARP_DISPLAY_NAME_LIMIT = 64;
    /** Raw character limit of a warp's description ({@code island_warps.description}, 512). */
    public static final int WARP_DESCRIPTION_LIMIT = 256;
    /** Raw character limit of a ban reason ({@code island_bans.reason}, 255). */
    public static final int BAN_REASON_LIMIT = 120;

    /** Whether {@code input} is short enough to be stored. A null or blank value always is. */
    public static boolean withinLimit(@Nullable String input, int limit) {
        return input == null || input.strip().length() <= limit;
    }

    /**
     * Prepares player input for storage: trims it, drops legacy section signs, and escapes
     * MiniMessage tags so they render as the literal text that was typed.
     *
     * @return the sanitised text, or {@code null} when the input was null or blank
     */
    public static @Nullable String sanitise(@Nullable String input) {
        if (input == null)
            return null;

        String trimmed = input.strip().replace("§", "");
        if (trimmed.isEmpty())
            return null;

        return MiniMessage.miniMessage().escapeTags(trimmed);
    }

    /** Lower-cases and trims an identifier (warp names, upgrade keys). */
    public static String normaliseKey(String input) {
        return input == null ? null : input.strip().toLowerCase(Locale.ROOT);
    }
}
