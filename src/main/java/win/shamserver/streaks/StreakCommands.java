package win.shamserver.streaks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.time.Duration;

public class StreakCommands implements CommandExecutor {

    private final LoginStreakManager manager;
    private final JavaPlugin plugin;

    public StreakCommands(JavaPlugin plugin, LoginStreakManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command cmd, @NonNull String label, String @NonNull [] args) {

        String name = cmd.getName().toLowerCase();

        if (name.equals("streak")) {
            if (!(sender instanceof Player player)) return true;

            manager.getStatusAsync(player.getUniqueId(), player.getName())
                    .whenComplete((status, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (throwable != null) {
                            plugin.getLogger().warning("Failed to load streak for " + player.getName() + ": " + throwable.getMessage());
                            player.sendMessage("§cCould not load your streak right now.");
                            return;
                        }

                        Duration graceReset = manager.getTimeUntilGraceReset();
                        player.sendMessage("§aAvailable graces: §e" + status.availableGraces() + "/" + status.maxGraces() + " §7(" + formatDuration(status.timeUntilReset()) + ")");
                        player.sendMessage("§aGraces reset in: §e" + formatDuration(graceReset));
                        player.sendMessage("§aActive Streak: §e" + status.current() + " §7| §aHighest Streak: §e" + status.highest());
                    }));
            return true;
        }

        if (name.equals("streaktop")) {
            if (!(sender instanceof Player player)) return true;

            int limit = plugin.getConfig().getInt("axrewards.login-streaks.leaderboard_display_length", 10);
            manager.getTopCurrentAsync(limit)
                    .whenComplete((list, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (throwable != null) {
                            plugin.getLogger().warning("Failed to load current streak leaderboard: " + throwable.getMessage());
                            player.sendMessage("§cCould not load the streak leaderboard right now.");
                            return;
                        }

                        player.sendMessage("§6Top Login Streaks");
                        if (list.isEmpty()) {
                            player.sendMessage("§7No active streaks yet.");
                            return;
                        }

                        int index = 1;
                        for (PlayerStreak streak : list) {
                            player.sendMessage("§e" + index + ". §f" + streak.username + " §7- §a" + streak.current);
                            index++;
                        }
                    }));
            return true;
        }

        if (name.equals("higheststreaktop")) {
            if (!(sender instanceof Player player)) return true;

            int limit = plugin.getConfig().getInt("axrewards.login-streaks.leaderboard_display_length", 10);
            manager.getTopHighestAsync(limit)
                    .whenComplete((list, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (throwable != null) {
                            plugin.getLogger().warning("Failed to load highest streak leaderboard: " + throwable.getMessage());
                            player.sendMessage("§cCould not load the highest streak leaderboard right now.");
                            return;
                        }

                        player.sendMessage("§6Highest Streaks");
                        if (list.isEmpty()) {
                            player.sendMessage("§7No streak history yet.");
                            return;
                        }

                        int index = 1;
                        for (PlayerStreak streak : list) {
                            player.sendMessage("§e" + index + ". §f" + streak.username + " §7- §a" + streak.highest);
                            index++;
                        }
                    }));
            return true;
        }

        if (name.equals("streakset")) {

            if (!sender.isOp() && !sender.hasPermission("shamplugin.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage("§cUsage: /sham streak set <player> <value>");
                return true;
            }

            String targetName = args[0];
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(targetName);

            if (offline == null || (!offline.isOnline() && !offline.hasPlayedBefore())) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            int value;
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid number.");
                return true;
            }

            if (value < 0) {
                sender.sendMessage("§cStreak value cannot be negative.");
                return true;
            }

            String resolvedName = offline.getName() != null ? offline.getName() : targetName;
            manager.setStreakAsync(offline.getUniqueId(), resolvedName, value)
                    .whenComplete((streak, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (throwable != null) {
                            plugin.getLogger().warning("Failed to set streak for " + resolvedName + ": " + throwable.getMessage());
                            sender.sendMessage("§cCould not set the streak for " + resolvedName + ".");
                            return;
                        }

                        sender.sendMessage("§aSet " + resolvedName + "'s streak to " + streak.current);
                    }));

            return true;
        }

        return true;
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "N/A";
        }

        long seconds = Math.max(0L, duration.getSeconds());

        long days = seconds / 86_400;
        seconds %= 86_400;

        long hours = seconds / 3_600;
        seconds %= 3_600;

        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder out = new StringBuilder();

        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0) {
            out.append(minutes).append("m ");
        }
        if (seconds > 0 || out.isEmpty()) {
            out.append(seconds).append("s");
        }

        return out.toString().trim();
    }
}
