package win.kakchuserver.streaks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class RewardCommand implements CommandExecutor, TabCompleter {

    private static final String[] REWARD_BASES = {
            "axrewards.login-streaks.rewards",
            "login_streaks.rewards"
    };

    private final JavaPlugin plugin;
    private final LoginStreakManager manager;

    public RewardCommand(JavaPlugin plugin, LoginStreakManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String label, String @NonNull [] args) {

        if (sender instanceof org.bukkit.entity.Player p) {
            if (!p.isOp() && !p.hasPermission("kakchuplugin.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /kakchureward <player> <type>");
            return true;
        }

        String targetName = args[0];
        String typeInput = args[1].trim();

        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(targetName);
        if (offline == null || (!offline.isOnline() && !offline.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer not found.");
            return true;
        }

        PlayerStreak ps = manager.get(offline.getUniqueId(), offline.getName() != null ? offline.getName() : targetName);
        int currentStreak = (ps == null) ? 0 : ps.current;

        String typePath = resolveTypePath(typeInput);
        if (typePath == null) {
            sender.sendMessage("§7No rewards configured for type: " + typeInput);
            return true;
        }

        ConfigurationSection bonusesSection = plugin.getConfig().getConfigurationSection(typePath + ".bonuses");
        if (bonusesSection == null) {
            sender.sendMessage("§7No rewards configured for type: " + typeInput);
            return true;
        }

        List<Integer> keys = bonusesSection.getKeys(false).stream().map(k -> {
            try { return Integer.parseInt(k); } catch (NumberFormatException ex) { return null; }
        }).filter(Objects::nonNull).sorted().collect(Collectors.toList());

        Integer selectedThreshold = null;
        for (Integer k : keys) {
            if (k <= currentStreak) selectedThreshold = k;
        }

        if (selectedThreshold == null) {
            sender.sendMessage("§7No reward configured for streak " + currentStreak + " (" + typeInput + ").");

            return shouldIncrement(sender, targetName, offline, ps, typePath);
        }

        String commandsPath = typePath + ".bonuses." + selectedThreshold + ".commands";
        List<String> configuredCommands = plugin.getConfig().getStringList(commandsPath);

        if (configuredCommands == null || configuredCommands.isEmpty()) {
            Object raw = plugin.getConfig().get(typePath + ".bonuses." + selectedThreshold);
            if (raw instanceof List<?>) {
                configuredCommands = ((List<?>) raw).stream().map(Object::toString).collect(Collectors.toList());
            } else if (raw instanceof String) {
                configuredCommands = Collections.singletonList((String) raw);
            }
        }

        if (configuredCommands == null || configuredCommands.isEmpty()) {
            sender.sendMessage("§7No commands configured for threshold " + selectedThreshold + " (" + typeInput + ").");
            return true;
        }

        for (String c : configuredCommands) {
            if (c == null || c.isBlank()) continue;
            String replaced = c.replace("%player%", offline.getName() != null ? offline.getName() : targetName);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), replaced);
        }

        sender.sendMessage("§aExecuted configured reward commands for " + (offline.getName() != null ? offline.getName() : targetName) + " (threshold " + selectedThreshold + ", " + typeInput + ").");

        return shouldIncrement(sender, targetName, offline, ps, typePath);
    }

    private boolean shouldIncrement(@NonNull CommandSender sender, String targetName, OfflinePlayer offline, PlayerStreak ps, String typePath) {
        boolean shouldIncrement = plugin.getConfig().getBoolean(typePath + ".streak", false);
        if (shouldIncrement) {
            ps.current = ps.current + 1;
            if (ps.current > ps.highest) ps.highest = ps.current;
            manager.save(ps);
            sender.sendMessage("§aIncremented streak for " + (offline.getName() != null ? offline.getName() : targetName) + " to " + ps.current);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command cmd, @NonNull String alias, String @NonNull [] args) {
        if (!cmd.getName().equalsIgnoreCase("kakchureward")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .collect(Collectors.toList());
            return StringUtil.copyPartialMatches(args[0], names, new ArrayList<>());
        }

        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], getRewardTypes(), new ArrayList<>());
        }

        return Collections.emptyList();
    }

    private String resolveTypePath(String typeInput) {
        if (typeInput == null || typeInput.isBlank()) return null;

        for (String base : REWARD_BASES) {
            ConfigurationSection rewards = plugin.getConfig().getConfigurationSection(base);
            if (rewards == null) continue;

            for (String key : rewards.getKeys(false)) {
                if (key.equalsIgnoreCase(typeInput)) {
                    return base + "." + key;
                }
            }
        }

        return null;
    }

    private List<String> getRewardTypes() {
        Set<String> types = new LinkedHashSet<>();

        for (String base : REWARD_BASES) {
            ConfigurationSection rewards = plugin.getConfig().getConfigurationSection(base);
            if (rewards != null) {
                types.addAll(rewards.getKeys(false));
            }
        }

        return new ArrayList<>(types);
    }
}