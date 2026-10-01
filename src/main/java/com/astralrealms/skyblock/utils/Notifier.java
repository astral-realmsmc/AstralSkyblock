package com.astralrealms.skyblock.utils;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.LoggerFactory;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.service.impl.ChatService;

import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;

/**
 * Sends a message to a player wherever they are on the network. {@link ChatService} is provided by
 * AstralChat, which is not a dependency: a player on this server is messaged directly, and one
 * elsewhere only when the service is registered. Never throws, so a notification can never fail
 * the action that triggered it.
 */
@UtilityClass
public class Notifier {

    public static void send(UUID playerId, Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
            return;
        }

        AstralPaperAPI.getService(ChatService.class)
                .ifPresentOrElse(chat -> chat.sendMessage(playerId, message)
                                .exceptionally(throwable -> {
                                    LoggerFactory.getLogger(Notifier.class).warn("Failed to deliver a message to {}", playerId, throwable);
                                    return false;
                                }),
                        () -> LoggerFactory.getLogger(Notifier.class).debug("No ChatService registered; message to {} dropped", playerId));
    }
}
