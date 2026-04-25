package win.kakchuserver.streaks;

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

            PlayerStreak streak = manager.get(player.getUniqueId(), player.getName());
            int current = streak.current;
            int highest = streak.highest;
            int availableGraces = manager.getAvailableGraces(player);
            int maxGraces = manager.getMaxGracePerCycle();
            Duration streakReset = manager.getTimeUntilStreakReset(player);
            Duration graceReset = manager.getTimeUntilGraceReset();

            player.sendMessage("§aAvailable graces: §e" + availableGraces + "/" + maxGraces + " §7(" + (streakReset == null ? "N/A" : formatDuration(streakReset)) + ")");
            player.sendMessage("§aGraces reset in: §e" + formatDuration(graceReset));
            player.sendMessage("§aActive Streak: §e" + current + " §7| §aHighest Streak: §e" + highest);
            return true;
        }

        if (name.equals("streaktop")) {
            if (!(sender instanceof Player player)) return true;

            var list = manager.getTopCurrent(plugin.getConfig().getInt("axrewards.login-streaks.leaderboard_display_length", 10));
            player.sendMessage("§6Top Login Streaks");

            if (list.isEmpty()) {
                player.sendMessage("§7No active streaks yet.");
                return true;
            }

            int i = 1;
            for (PlayerStreak s : list) {
                player.sendMessage("§e" + i + ". §f" + s.username + " §7- §a" + s.current);
                i++;
            }
            return true;
        }

        if (name.equals("higheststreaktop")) {
            if (!(sender instanceof Player player)) return true;
            var list = manager.getTopHighest(plugin.getConfig().getInt("axrewards.login-streaks.leaderboard_display_length", 10));
            player.sendMessage("§6Highest Streaks");
            int i = 1;
            for (PlayerStreak s : list) {
                player.sendMessage("§e" + i + ". §f" + s.username + " §7- §a" + s.highest);
                i++;
            }
            return true;
        }

        if (name.equals("streakset")) {

            if (!sender.isOp() && !sender.hasPermission("kakchuplugin.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage("§cUsage: /streakset <player> <value>");
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

            PlayerStreak streak = manager.get(offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);

            streak.current = value;
            if (value > streak.highest) streak.highest = value;

            manager.save(streak);

            sender.sendMessage("§aSet " + (offline.getName() != null ? offline.getName() : targetName) + "'s streak to " + value);

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