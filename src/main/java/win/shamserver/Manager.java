package win.shamserver;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import win.shamserver.streaks.LoginStreakManager;
import win.shamserver.streaks.RewardCommand;
import win.shamserver.streaks.StreakCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Manager extends JavaPlugin {

    private static Manager instance;
    private Alerts alertsHandler;
    private UptimeTracker tracker;
    private LoginStreakManager streakManager;

    private volatile String updateAvailableMessage;
    private volatile String updateAvailableVersion;

    public String getUpdateAvailableMessage() {
        return updateAvailableMessage;
    }

    public String getUpdateAvailableVersion() {
        return updateAvailableVersion;
    }

    public void setUpdateAvailable(String version, String message) {
        this.updateAvailableVersion = version;
        this.updateAvailableMessage = message;
    }

    public void clearUpdateAvailable() {
        this.updateAvailableVersion = null;
        this.updateAvailableMessage = null;
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        enableDiscordAlertsEarly();

        getLogger().info("Sham Plugin enabled!");

        if (getConfig().getBoolean("update-checker.enabled", true)) {
            long hours = Math.max(1, getConfig().getLong("update-checker.interval-hours", 12));
            long periodTicks = hours * 60L * 60L * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, new UpdateChecker(this), 20L * 10L, periodTicks);
            Bukkit.getPluginManager().registerEvents(new UpdateLoginNotifier(this), this);
        }

        try {
            tracker = new UptimeTracker(this);
            tracker.start();
            getLogger().info("✅ UptimeTracker started.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to start UptimeTracker: " + e.getMessage(), e);
            tracker = null;
        }

        registerCommand("map", new Commands());
        registerCommand("help", new Commands());

        if (tracker != null) {
            UptimeTracker.UptimeCommand uptimeCommand = new UptimeTracker.UptimeCommand(tracker);
            registerCommand("uptime", uptimeCommand);

            PluginCommand uptime = Objects.requireNonNull(getCommand("uptime"), "Command 'uptime' missing from plugin.yml");
            uptime.setTabCompleter(uptimeCommand);
        }

        long startDelay = getConfig().getLong("restarter.start-delay-ticks", 432000L);
        long interval = getConfig().getLong("restarter.interval-ticks", 10L);

        try {
            Bukkit.getScheduler().runTaskTimer(this, new Restarter(this), startDelay, interval);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to schedule Restarter task: " + e.getMessage(), e);
        }

        if (getConfig().getBoolean("axrewards.login-streaks.enabled", true)) {
            streakManager = new LoginStreakManager(this);
            if (!streakManager.init()) {
                getLogger().severe("Login streaks disabled because the streak database failed to initialize.");
                streakManager.shutdown();
                streakManager = null;
            } else {
                StreakCommands streakCommands = new StreakCommands(this, streakManager);

                Objects.requireNonNull(getCommand("streak"), "Command 'streak' missing from plugin.yml").setExecutor(streakCommands);
                Objects.requireNonNull(getCommand("streaktop"), "Command 'streaktop' missing from plugin.yml").setExecutor(streakCommands);
                Objects.requireNonNull(getCommand("higheststreaktop"), "Command 'higheststreaktop' missing from plugin.yml").setExecutor(streakCommands);
                Objects.requireNonNull(getCommand("streakset"), "Command 'streakset' missing from plugin.yml").setExecutor(streakCommands);

                RewardCommand rewardCommand = new RewardCommand(this, streakManager);
                Objects.requireNonNull(getCommand("streakreward"), "Command 'streakreward' missing from plugin.yml").setExecutor(rewardCommand);
                Objects.requireNonNull(getCommand("streakreward"), "Command 'streakreward' missing from plugin.yml").setTabCompleter(rewardCommand);

                new PlayerNotifier(this, streakManager).register();
            }
        }
    }

    private void enableDiscordAlertsEarly() {

        final var cfg = getConfig();

        String token = trimToEmpty(cfg.getString("discord alerts.token", ""));
        String channelId = trimToEmpty(cfg.getString("discord alerts.channel", ""));
        String pingType = trimToEmpty(cfg.getString("discord alerts.ping", ""));

        if (pingType.isEmpty())
            pingType = "@everyone";

        List<String> ignore = new ArrayList<>(cfg.getStringList("discord alerts.ignore"));

        if (token.isBlank() || channelId.isBlank()) {
            getLogger().warning("⚠️ Discord alert token/channel not set in config.yml.");
            return;
        }

        try {

            alertsHandler = new Alerts(token, channelId, pingType, ignore);

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(alertsHandler);

            getLogger().info("✅ Discord alerts enabled.");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to enable Discord alerts: " + e.getMessage(), e);
        }
    }

    private static String trimToEmpty(String s) {
        return (s == null) ? "" : s.trim();
    }

    @Override
    public void onDisable() {

        if (tracker != null) {
            try {
                tracker.stop();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to stop UptimeTracker cleanly: " + e.getMessage(), e);
            }
        }

        if (streakManager != null) {
            try {
                streakManager.shutdown();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to stop LoginStreakManager cleanly: " + e.getMessage(), e);
            }
        }

        if (alertsHandler != null) {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.removeHandler(alertsHandler);
            alertsHandler.close();
        }

        getLogger().info("Sham Plugin disabled!");
    }

    private void registerCommand(String name, CommandExecutor executor) {

        var cmd = getCommand(name);

        if (cmd != null)
            cmd.setExecutor(executor);
        else
            getLogger().warning("Command '" + name + "' not found in plugin.yml");
    }

    public static Manager getInstance() {
        return instance;
    }
}