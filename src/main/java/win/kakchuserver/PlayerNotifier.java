package win.kakchuserver;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerNotifier {

    private final JavaPlugin plugin;

    public PlayerNotifier(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(
                new LoginRewardReminder(plugin),
                plugin
        );
    }
}

/* ===================== INTERNAL NOTIFIERS ===================== */

class LoginRewardReminder implements Listener {

    private static final String PERMISSION = "kakchuplugin.loginrewards";

    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    LoginRewardReminder(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("login-rewards.enabled", true)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) return;

        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                || !Bukkit.getPluginManager().isPluginEnabled("AxRewards")) {
            return;
        }

        long delay = plugin.getConfig().getLong("login-rewards.delay-ticks", 2L);
        String placeholder = plugin.getConfig().getString(
                "login-rewards.placeholder",
                "%axrewards_collectable%"
        );
        String command = plugin.getConfig().getString(
                "login-rewards.command",
                "/daily"
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String raw = PlaceholderAPI.setPlaceholders(player, placeholder).trim();
            if (raw.isEmpty()) return;

            int count;
            try {
                count = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return;
            }

            if (count <= 0) return;

            String template = count == 1
                    ? plugin.getConfig().getString("login-rewards.message-single")
                    : plugin.getConfig().getString("login-rewards.message-multiple");

            if (template == null || template.isEmpty()) return;

            String rendered = template
                    .replace("<count>", String.valueOf(count))
                    .replace("<cmd>", command);

            Component message = mini.deserialize(rendered)
                    .replaceText(builder ->
                            builder.matchLiteral(command)
                                    .replacement(
                                            Component.text(command)
                                                    .clickEvent(ClickEvent.runCommand(command))
                                    )
                    );

            player.sendMessage(message);

            playSound(player);

        }, delay);
    }

    private void playSound(Player player) {
        if (!plugin.getConfig().getBoolean("login-rewards.sound.enabled", false)) return;

        String soundName = plugin.getConfig().getString(
                "login-rewards.sound.name",
                "entity.experience_orb.pickup"
        ).toLowerCase();

        NamespacedKey key;
        if (soundName.contains(":")) {
            key = NamespacedKey.fromString(soundName);
        } else {
            key = NamespacedKey.minecraft(soundName);
        }

        if (key == null) return;

        Sound sound = Registry.SOUNDS.get(key);
        if (sound == null) return;

        float volume = (float) plugin.getConfig().getDouble("login-rewards.sound.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("login-rewards.sound.pitch", 1.0);

        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}