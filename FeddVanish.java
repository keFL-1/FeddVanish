package ru.fedd.vanish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class FeddVanish extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<UUID> vanishedPlayers = new HashSet<>();
    private LegacyComponentSerializer serializer;
    // Ключ метаданных для других плагинов (TAB, CMI, Essentials, NearManager)
    private static final String METADATA_KEY = "vanished";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Поддержка цветов через &
        serializer = LegacyComponentSerializer.legacyAmpersand();

        getCommand("vanish").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        // Задача Actionbar (каждую секунду)
        new BukkitRunnable() {
            @Override
            public void run() {
                String actionbarText = getConfig().getString("messages.actionbar");
                if (actionbarText == null || actionbarText.isEmpty()) return;

                Component component = serializer.deserialize(actionbarText);

                for (UUID uuid : vanishedPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        p.sendActionBar(component);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков!");
            return true;
        }

        if (!player.hasPermission("feddvanish.use")) {
            player.sendMessage(formatMsg(getConfig().getString("messages.no-permission")));
            return true;
        }

        if (vanishedPlayers.contains(player.getUniqueId())) {
            disableVanish(player);
        } else {
            enableVanish(player);
        }

        return true;
    }

    private void enableVanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());

        // 1. Метаданные для плагинов (TAB, NearManager и др.)
        player.setMetadata(METADATA_KEY, new FixedMetadataValue(this, true));

        // 2. Скрываем игрока от всех онлайн игроков
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.getUniqueId().equals(player.getUniqueId())) {
                onlinePlayer.hidePlayer(this, player);
            }
        }

        // 3. Сбрасываем агро мобов, которые УЖЕ преследуют игрока
        for (Entity entity : player.getNearbyEntities(50, 50, 50)) {
            if (entity instanceof Creature creature) {
                if (creature.getTarget() != null && creature.getTarget().equals(player)) {
                    creature.setTarget(null);
                }
            }
        }

        // Доп: Игнорируем для сна (фантомы не спавнятся, ночь скипается)
        player.setSleepingIgnored(true);
        // Доп: Отключаем подбор предметов (опционально, но удобно в ванише)
        player.setCanPickupItems(false); 

        player.sendMessage(formatMsg(getConfig().getString("messages.vanish-enabled")));
    }

    private void disableVanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());

        // 1. Убираем метаданные
        player.removeMetadata(METADATA_KEY, this);

        // 2. Показываем всем
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.showPlayer(this, player);
        }

        player.setSleepingIgnored(false);
        player.setCanPickupItems(true);

        player.sendMessage(formatMsg(getConfig().getString("messages.vanish-disabled")));
    }

    // --- СОБЫТИЯ ---

    // Событие: Мобы перестают видеть игрока
    @EventHandler
    public void onMobTarget(EntityTargetEvent event) {
        // Если целью является игрок
        if (event.getTarget() instanceof Player player) {
            // И этот игрок в ванише
            if (vanishedPlayers.contains(player.getUniqueId())) {
                // Отменяем событие (моб теряет интерес)
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();

        // Скрываем от зашедшего всех, кто сейчас в ванише
        for (UUID uuid : vanishedPlayers) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null) {
                joiner.hidePlayer(this, vanishedPlayer);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (vanishedPlayers.contains(uuid)) {
            vanishedPlayers.remove(uuid);
            event.getPlayer().removeMetadata(METADATA_KEY, this);
        }
    }

    private Component formatMsg(String text) {
        if (text == null) return Component.empty();
        return serializer.deserialize(text);
    }
}