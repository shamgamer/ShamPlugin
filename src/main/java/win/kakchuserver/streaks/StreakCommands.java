package win.kakchuserver.streaks;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

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
            int current = manager.getCurrentStreak(player);
            int highest = manager.getHighestStreak(player);
            player.sendMessage("§aCurrent streak: §e" + current);
            player.sendMessage("§aHighest streak: §e" + highest);
            return true;
        }

        if (name.equals("streaktop")) {
            if (!(sender instanceof Player player)) return true;
            var list = manager.getTopCurrent(10);
            player.sendMessage("§6Top Login Streaks");
            int i = 1;
            for (PlayerStreak s : list) {
                player.sendMessage("§e" + i + ". §f" + s.username + " §7- §a" + s.current);
                i++;
            }
            return true;
        }

        if (name.equals("higheststreaktop")) {
            if (!(sender instanceof Player player)) return true;
            var list = manager.getTopHighest(10);
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

            // basic existence check: allow if online or has played before
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

            PlayerStreak streak = manager.get(offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);

            // set values and update highest if needed
            streak.current = value;
            if (value > streak.highest) streak.highest = value;

            manager.save(streak);

            sender.sendMessage("§aSet " + (offline.getName() != null ? offline.getName() : targetName) + "'s streak to " + value);

            return true;
        }

        return true;
    }
}