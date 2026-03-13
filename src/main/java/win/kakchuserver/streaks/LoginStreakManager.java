package win.kakchuserver.streaks;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.WeekFields;
import java.util.*;

public class LoginStreakManager {

    private final JavaPlugin plugin;
    private Connection connection;

    private final Map<UUID, PlayerStreak> cache = new HashMap<>();

    private List<PlayerStreak> cachedTopCurrent = new ArrayList<>();
    private List<PlayerStreak> cachedTopHighest = new ArrayList<>();

    private long lastLeaderboardUpdate = 0;

    public LoginStreakManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {

        try {

            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + plugin.getDataFolder() + "/streaks.db"
            );

            Statement stmt = connection.createStatement();

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS streaks (
                uuid TEXT PRIMARY KEY,
                username TEXT,
                current_streak INTEGER,
                highest_streak INTEGER,
                last_claim BIGINT,
                grace_used INTEGER,
                grace_week INTEGER
                )
            """);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public PlayerStreak get(UUID uuid, String username) {

        if (cache.containsKey(uuid)) {

            PlayerStreak cached = cache.get(uuid);

            if (!Objects.equals(cached.username, username)) {
                cached.username = username;
                save(cached);
            }

            return cached;
        }

        try {

            PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM streaks WHERE uuid=?"
            );

            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {

                PlayerStreak streak = new PlayerStreak(
                        uuid,
                        rs.getString("username"),
                        rs.getInt("current_streak"),
                        rs.getInt("highest_streak"),
                        rs.getLong("last_claim"),
                        rs.getInt("grace_used"),
                        rs.getInt("grace_week")
                );

                cache.put(uuid, streak);

                return streak;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        int currentWeek = getCurrentWeek();

        PlayerStreak streak = new PlayerStreak(
                uuid,
                username,
                0,
                0,
                0,
                0,
                currentWeek
        );

        cache.put(uuid, streak);

        return streak;

    }

    public void save(PlayerStreak streak) {

        try {

            PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO streaks
                (uuid, username, current_streak, highest_streak, last_claim, grace_used, grace_week)
                VALUES (?,?,?,?,?,?,?)
            """);

            ps.setString(1, streak.uuid.toString());
            ps.setString(2, streak.username);
            ps.setInt(3, streak.current);
            ps.setInt(4, streak.highest);
            ps.setLong(5, streak.lastClaim);
            ps.setInt(6, streak.graceUsed);
            ps.setInt(7, streak.graceWeek);

            ps.executeUpdate();

        } catch (Exception e) {
            e.printStackTrace();
        }

        invalidateLeaderboardCache();

    }

    /**
     * Handle a claim event (called when a player claims a reward)
     * countsForStreak - whether this claim should count towards the streak (config-driven).
     */
    public boolean handleClaim(Player player, boolean countsForStreak) {

        PlayerStreak streak = get(player.getUniqueId(), player.getName());

        if (!countsForStreak)
            return false;

        long now = Instant.now().getEpochSecond();

        int currentWeek = getCurrentWeek();

        if (streak.graceWeek != currentWeek) {

            streak.graceWeek = currentWeek;
            streak.graceUsed = 0;

        }

        if (streak.lastClaim == 0) {

            streak.current = 1;
            streak.highest = 1;
            streak.lastClaim = now;

            save(streak);
            return true;

        }

        long seconds = now - streak.lastClaim;

        long twoDays = Duration.ofDays(2).toSeconds();
        long threeDays = Duration.ofDays(3).toSeconds();

        int maxGrace = getConfigIntWithFallback("grace_per_week", 2);

        if (seconds < twoDays) {

            streak.current++;

        } else if (seconds < threeDays && streak.graceUsed < maxGrace) {

            streak.graceUsed++;

        } else {

            streak.current = 1;
            streak.graceUsed = 0;

        }

        if (streak.current > streak.highest)
            streak.highest = streak.current;

        streak.lastClaim = now;

        save(streak);

        return true;

    }

    public int getCurrentStreak(Player player) {

        return get(player.getUniqueId(), player.getName()).current;

    }

    public int getHighestStreak(Player player) {

        return get(player.getUniqueId(), player.getName()).highest;

    }

    public List<PlayerStreak> getTopCurrent(int limit) {

        updateLeaderboardsIfNeeded();

        return cachedTopCurrent.stream().limit(limit).toList();

    }

    public List<PlayerStreak> getTopHighest(int limit) {

        updateLeaderboardsIfNeeded();

        return cachedTopHighest.stream().limit(limit).toList();

    }

    private void updateLeaderboardsIfNeeded() {

        long intervalMinutes = getConfigLongWithFallback("leaderboard_update_interval_minutes", 10L);

        long intervalMillis = intervalMinutes * 60 * 1000;

        long now = System.currentTimeMillis();

        if (now - lastLeaderboardUpdate < intervalMillis)
            return;

        cachedTopCurrent = queryTop("current_streak");
        cachedTopHighest = queryTop("highest_streak");

        lastLeaderboardUpdate = now;

    }

    private List<PlayerStreak> queryTop(String column) {

        List<PlayerStreak> list = new ArrayList<>();

        try {

            Statement st = connection.createStatement();

            ResultSet rs = st.executeQuery(
                    "SELECT * FROM streaks ORDER BY " + column + " DESC LIMIT 100"
            );

            while (rs.next()) {

                list.add(new PlayerStreak(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("username"),
                        rs.getInt("current_streak"),
                        rs.getInt("highest_streak"),
                        rs.getLong("last_claim"),
                        rs.getInt("grace_used"),
                        rs.getInt("grace_week")
                ));

            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;

    }

    private void invalidateLeaderboardCache() {

        lastLeaderboardUpdate = 0;

    }

    private int getCurrentWeek() {

        return Instant.now()
                .atZone(java.time.ZoneId.systemDefault())
                .get(WeekFields.ISO.weekOfWeekBasedYear());

    }

    // ----------------- Config helpers (prefer axrewards.login-streaks, fallback to login_streaks) -----------------

    private String joinBase(String key) {
        // prefer "axrewards.login-streaks.<key>" else "login_streaks.<key>"
        if (plugin.getConfig().contains("axrewards.login-streaks." + key)) {
            return "axrewards.login-streaks." + key;
        }
        return "login_streaks." + key;
    }

    private int getConfigIntWithFallback(String key, int def) {
        String axPath = "axrewards.login-streaks." + key;
        String oldPath = "login_streaks." + key;
        if (plugin.getConfig().contains(axPath)) return plugin.getConfig().getInt(axPath);
        return plugin.getConfig().getInt(oldPath, def);
    }

    private long getConfigLongWithFallback(String key, long def) {
        String axPath = "axrewards.login-streaks." + key;
        String oldPath = "login_streaks." + key;
        if (plugin.getConfig().contains(axPath)) return plugin.getConfig().getLong(axPath);
        return plugin.getConfig().getLong(oldPath, def);
    }

}