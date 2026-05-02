package win.shamserver;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import win.shamserver.streaks.RewardCommand;
import win.shamserver.streaks.StreakCommands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ShamCommand implements CommandExecutor, TabCompleter {

    private final UptimeTracker.UptimeCommand uptimeCommand;
    private final StreakCommands streakCommands;
    private final RewardCommand rewardCommand;

    public ShamCommand(UptimeTracker.UptimeCommand uptimeCommand,
                       StreakCommands streakCommands,
                       RewardCommand rewardCommand) {
        this.uptimeCommand = uptimeCommand;
        this.streakCommands = streakCommands;
        this.rewardCommand = rewardCommand;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sendRootUsage(sender);
            return true;
        }

        String branch = args[0].toLowerCase(Locale.ROOT);
        String[] remaining = Arrays.copyOfRange(args, 1, args.length);

        if (branch.equals("uptime")) {
            if (uptimeCommand == null) {
                sender.sendMessage("§cUptime tracking is unavailable.");
                return true;
            }
            if (!sender.hasPermission("shamplugin.uptime")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
            return uptimeCommand.handleCommand(sender, remaining);
        }

        if (branch.equals("streak")) {
            if (streakCommands == null) {
                sender.sendMessage("§cLogin streaks are unavailable.");
                return true;
            }
            return handleStreakCommand(sender, remaining);
        }

        sendRootUsage(sender);
        return true;
    }

    private boolean handleStreakCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("shamplugin.streak")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
            return streakCommands.handleSelfStatus(sender);
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String[] remaining = Arrays.copyOfRange(args, 1, args.length);

        if (action.equals("get")) {
            if (!hasAdminPermission(sender)) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
            if (remaining.length < 1) {
                sender.sendMessage("§cUsage: /sham streak get <player>");
                return true;
            }
            return streakCommands.handleGetStatus(sender, remaining[0]);
        }

        if (action.equals("set")) {
            if (!hasAdminPermission(sender)) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
            return streakCommands.handleSet(sender, remaining);
        }

        if (action.equals("reward")) {
            if (!hasAdminPermission(sender)) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
            if (rewardCommand == null) {
                sender.sendMessage("§cLogin rewards are unavailable.");
                return true;
            }
            return rewardCommand.handleReward(sender, remaining);
        }

        sender.sendMessage("§cUsage: /sham streak [get|set|reward]");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], rootOptions(sender), new ArrayList<>());
        }

        String branch = args[0].toLowerCase(Locale.ROOT);

        if (branch.equals("uptime")) {
            if (!sender.hasPermission("shamplugin.uptime") || uptimeCommand == null) {
                return Collections.emptyList();
            }
            if (args.length == 2) {
                return uptimeCommand.completePeriods(args[1]);
            }
            return Collections.emptyList();
        }

        if (!branch.equals("streak") || streakCommands == null) {
            return Collections.emptyList();
        }

        if (args.length == 2) {
            List<String> options = new ArrayList<>();
            if (hasAdminPermission(sender)) {
                options.add("get");
                options.add("set");
                if (rewardCommand != null) {
                    options.add("reward");
                }
            }
            return StringUtil.copyPartialMatches(args[1], options, new ArrayList<>());
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if ((action.equals("get") || action.equals("set") || action.equals("reward")) && !hasAdminPermission(sender)) {
            return Collections.emptyList();
        }

        if (args.length == 3 && (action.equals("get") || action.equals("set") || action.equals("reward"))) {
            return streakCommands.completePlayerNames(args[2]);
        }

        if (args.length == 4 && action.equals("reward") && rewardCommand != null) {
            return rewardCommand.completeRewardTypes(args[3]);
        }

        return Collections.emptyList();
    }

    private List<String> rootOptions(CommandSender sender) {
        List<String> options = new ArrayList<>();
        if (uptimeCommand != null && sender.hasPermission("shamplugin.uptime")) {
            options.add("uptime");
        }
        if (streakCommands != null && (sender.hasPermission("shamplugin.streak") || hasAdminPermission(sender))) {
            options.add("streak");
        }
        return options;
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("shamplugin.admin");
    }

    private void sendRootUsage(CommandSender sender) {
        sender.sendMessage("§6Sham Commands:");
        if (uptimeCommand != null && sender.hasPermission("shamplugin.uptime")) {
            sender.sendMessage("§e/sham uptime <period>");
        }
        if (streakCommands != null && sender.hasPermission("shamplugin.streak")) {
            sender.sendMessage("§e/sham streak");
        }
        if (streakCommands != null && hasAdminPermission(sender)) {
            sender.sendMessage("§e/sham streak get <player>");
            sender.sendMessage("§e/sham streak set <player> <value>");
            if (rewardCommand != null) {
                sender.sendMessage("§e/sham streak reward <player> <type>");
            }
        }
    }
}