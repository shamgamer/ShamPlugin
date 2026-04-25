package win.kakchuserver.streaks;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class LoginStreakManager {

    private static final ZoneOffset GRACE_ZONE = ZoneOffset.UTC;
    private static final long WEEK_SECONDS = Duration.ofDays(7).toSeconds();
    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();
    private static final long MILLIS_THRESHOLD = 1_000_000_000_000L;
    private static final DateTimeFormatter GRACE_TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

    private final JavaPlugin plugin;
    private Connection connection;

    private final Map<UUID, PlayerStreak> cache = new HashMap<>();

    private List<PlayerStreak> cachedTopCurrent = new ArrayList<>();
    private List<PlayerStreak> cachedTopHighest = new ArrayList<>();

    private long lastLeaderboardUpdate = 0;

    public LoginStreakManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() {

        try {

            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + plugin.getDataFolder() + "/streaks.db"
            );

            try (Statement stmt = connection.createStatement()) {
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
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public synchronized PlayerStreak get(UUID uuid, String username) {

        if (cache.containsKey(uuid)) {

            PlayerStreak cached = cache.get(uuid);

            if (!Objects.equals(cached.username, username)) {
                cached.username = username;
                save(cached);
            }

            return cached;
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM streaks WHERE uuid=?"
        )) {

            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {

                    long storedLastClaim = readStoredLastClaim(rs);
                    long normalizedLastClaim = normalizeEpochSeconds(storedLastClaim);

                    PlayerStreak streak = new PlayerStreak(
                            uuid,
                            rs.getString("username"),
                            rs.getInt("current_streak"),
                            rs.getInt("highest_streak"),
                            normalizedLastClaim,
                            rs.getInt("grace_used"),
                            readStoredGraceCycleKey(rs)
                    );

                    cache.put(uuid, streak);

                    if (normalizedLastClaim != storedLastClaim) {
                        save(streak);
                    }

                    return streak;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        int currentGraceCycle = getCurrentGraceCycleKey();

        PlayerStreak streak = new PlayerStreak(
                uuid,
                username,
                0,
                0,
                0,
                0,
                currentGraceCycle
        );

        cache.put(uuid, streak);

        return streak;

    }

    public synchronized void save(PlayerStreak streak) {

        streak.lastClaim = normalizeEpochSeconds(streak.lastClaim);

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO streaks
            (uuid, username, current_streak, highest_streak, last_claim, grace_used, grace_week)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                current_streak = excluded.current_streak,
                highest_streak = excluded.highest_streak,
                last_claim = excluded.last_claim,
                grace_used = excluded.grace_used,
                grace_week = excluded.grace_week
        """)) {

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
     * Handle a claim event after AxRewards has already decided the reward is claimable.
     * This method never gates claim availability. It only updates streak state after a claim happens.
     */
    public synchronized boolean handleClaim(Player player, boolean countsForStreak) {
        return handleClaim(player.getUniqueId(), player.getName(), countsForStreak);
    }

    public synchronized boolean handleClaim(UUID uuid, String username, boolean countsForStreak) {

        if (!countsForStreak) {
            return false;
        }

        PlayerStreak streak = get(uuid, username);

        long now = Instant.now().getEpochSecond();

        int currentGraceCycle = getCurrentGraceCycleKey();

        if (streak.graceWeek != currentGraceCycle) {
            streak.graceWeek = currentGraceCycle;
            streak.graceUsed = 0;
        }

        if (streak.lastClaim == 0) {

            streak.current = 1;
            if (streak.highest < 1) {
                streak.highest = 1;
            }
            streak.lastClaim = now;

            save(streak);
            return true;

        }

        long lastClaim = normalizeEpochSeconds(streak.lastClaim);
        long elapsed = now - lastClaim;

        if (elapsed < 0) {
            elapsed = 0;
        }

        // Missed claim windows are counted in 24h units.
        // Examples:
        // 24h  -> 0 missed windows
        // 48h  -> 1 missed window
        // 72h  -> 2 missed windows
        long missedWindows = (elapsed / DAY_SECONDS) - 1L;
        if (missedWindows < 0) {
            missedWindows = 0;
        }

        int maxGrace = getMaxGracePerCycle();
        int availableGraces = Math.max(0, maxGrace - streak.graceUsed);

        if (missedWindows <= availableGraces) {

            streak.current++;
            streak.graceUsed += (int) missedWindows;

        } else {
            if (!plugin.getConfig().getBoolean("axrewards.login-streaks.pause", false)) { // prevent streak reset if pause is true
                // Grace limit exceeded: streak is broken and starts again from 1 on this claim.
                streak.current = 1;
                streak.graceUsed = 0;
            }
        }

        if (streak.current > streak.highest) {
            streak.highest = streak.current;
        }

        streak.lastClaim = now;

        save(streak);

        return true;

    }

    public synchronized int getCurrentStreak(Player player) {
        return get(player.getUniqueId(), player.getName()).current;
    }

    public synchronized int getHighestStreak(Player player) {
        return get(player.getUniqueId(), player.getName()).highest;
    }

    public synchronized int getMaxGracePerCycle() {
        return Math.max(0, getConfigIntWithFallback("grace_per_week", 2));
    }

    public synchronized int getAvailableGraces(Player player) {
        PlayerStreak streak = get(player.getUniqueId(), player.getName());
        int maxGrace = getMaxGracePerCycle();
        int currentGraceCycle = getCurrentGraceCycleKey();

        if (streak.graceWeek != currentGraceCycle) {
            return maxGrace;
        }

        return Math.max(0, maxGrace - streak.graceUsed);
    }


    /// Informational only. This is just an estimate based on config and database.
    public synchronized Duration getTimeUntilStreakReset(Player player) {
        PlayerStreak streak = get(player.getUniqueId(), player.getName());

        if (streak.lastClaim <= 0) {
            return null;
        }

        long now = Instant.now().getEpochSecond();
        long lastClaim = normalizeEpochSeconds(streak.lastClaim);
        long elapsed = now - lastClaim;

        if (elapsed < 0) {
            elapsed = 0;
        }

        long resetWindow = (long) (getAvailableGraces(player) + 2L) * DAY_SECONDS;
        long remaining = Math.max(0L, resetWindow - elapsed);

        return Duration.ofSeconds(remaining);
    }

    /**
     * Returns the remaining time until the next grace cycle begins.
     * Uses UTC and the configured grace_reset_day / grace_reset_time values.
     */
    public synchronized Duration getTimeUntilGraceReset() {
        ZonedDateTime now = ZonedDateTime.now(GRACE_ZONE);
        ZonedDateTime nextReset = getNextGraceResetInstant(now);
        Duration remaining = Duration.between(now, nextReset);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public synchronized List<PlayerStreak> getTopCurrent(int limit) {

        updateLeaderboardsIfNeeded();

        return cachedTopCurrent.stream().limit(limit).toList();

    }

    public synchronized List<PlayerStreak> getTopHighest(int limit) {

        updateLeaderboardsIfNeeded();

        return cachedTopHighest.stream().limit(limit).toList();

    }

    private synchronized void updateLeaderboardsIfNeeded() {

        long intervalMinutes = getConfigLongWithFallback("leaderboard_update_interval_minutes", 10L);
        long intervalMillis = intervalMinutes * 60 * 1000;
        long now = System.currentTimeMillis();

        if (now - lastLeaderboardUpdate < intervalMillis)
            return;

        cachedTopCurrent = queryTop("current_streak", true);
        cachedTopHighest = queryTop("highest_streak", false);

        lastLeaderboardUpdate = now;

    }

    private List<PlayerStreak> queryTop(String column, boolean activeOnly) {

        if (!"current_streak".equals(column) && !"highest_streak".equals(column)) {
            throw new IllegalArgumentException("Invalid leaderboard column: " + column);
        }

        List<PlayerStreak> list = new ArrayList<>();

        String sql = "SELECT * FROM streaks "
                + (activeOnly ? "WHERE current_streak > 0 " : "")
                + "ORDER BY " + column + " DESC LIMIT ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, 100);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long storedLastClaim = readStoredLastClaim(rs);
                    list.add(new PlayerStreak(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("username"),
                            rs.getInt("current_streak"),
                            rs.getInt("highest_streak"),
                            normalizeEpochSeconds(storedLastClaim),
                            rs.getInt("grace_used"),
                            readStoredGraceCycleKey(rs)
                    ));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;

    }

    private synchronized void invalidateLeaderboardCache() {
        lastLeaderboardUpdate = 0;
    }

    private synchronized int getCurrentGraceCycleKey() {
        ZonedDateTime now = ZonedDateTime.now(GRACE_ZONE);
        ZonedDateTime currentCycleStart = getPreviousGraceResetInstant(now);
        ZonedDateTime anchor = getGraceResetAnchorInstant();

        long currentCycleSeconds = currentCycleStart.toEpochSecond();
        long anchorSeconds = anchor.toEpochSecond();

        long cycleIndex = Math.floorDiv(currentCycleSeconds - anchorSeconds, WEEK_SECONDS);
        return Math.toIntExact(cycleIndex);
    }

    private ZonedDateTime getGraceResetAnchorInstant() {
        ZonedDateTime epoch = Instant.EPOCH.atZone(GRACE_ZONE);
        DayOfWeek resetDay = getConfiguredGraceResetDayOfWeek();
        LocalTime resetTime = getConfiguredGraceResetTime();

        ZonedDateTime anchor = epoch
                .with(TemporalAdjusters.nextOrSame(resetDay))
                .with(resetTime)
                .withSecond(0)
                .withNano(0);

        if (anchor.isBefore(epoch)) {
            anchor = anchor.plusWeeks(1);
        }

        return anchor;
    }

    private ZonedDateTime getPreviousGraceResetInstant(ZonedDateTime now) {
        DayOfWeek resetDay = getConfiguredGraceResetDayOfWeek();
        LocalTime resetTime = getConfiguredGraceResetTime();

        ZonedDateTime candidate = now
                .with(TemporalAdjusters.previousOrSame(resetDay))
                .with(resetTime)
                .withSecond(0)
                .withNano(0);

        if (candidate.isAfter(now)) {
            candidate = candidate.minusWeeks(1);
        }

        return candidate;
    }

    private ZonedDateTime getNextGraceResetInstant(ZonedDateTime now) {
        DayOfWeek resetDay = getConfiguredGraceResetDayOfWeek();
        LocalTime resetTime = getConfiguredGraceResetTime();

        ZonedDateTime candidate = now
                .with(TemporalAdjusters.nextOrSame(resetDay))
                .with(resetTime)
                .withSecond(0)
                .withNano(0);

        if (!candidate.isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }

        return candidate;
    }

    private DayOfWeek getConfiguredGraceResetDayOfWeek() {
        int rawDay = getConfigIntWithFallback("grace_reset_day", 0);
        rawDay = Math.max(0, Math.min(6, rawDay));

        return switch (rawDay) {
            case 0 -> DayOfWeek.SUNDAY;
            case 1 -> DayOfWeek.MONDAY;
            case 2 -> DayOfWeek.TUESDAY;
            case 3 -> DayOfWeek.WEDNESDAY;
            case 4 -> DayOfWeek.THURSDAY;
            case 5 -> DayOfWeek.FRIDAY;
            case 6 -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private LocalTime getConfiguredGraceResetTime() {
        String raw = getConfigStringWithFallback("grace_reset_time", "0:00");

        try {
            return LocalTime.parse(raw.trim(), GRACE_TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            return LocalTime.MIDNIGHT;
        }
    }

    private long normalizeEpochSeconds(long value) {
        if (value >= MILLIS_THRESHOLD) {
            return value / 1000L;
        }
        return value;
    }

    private long readStoredLastClaim(ResultSet rs) throws Exception {
        return rs.getLong("last_claim");
    }

    /**
     * Reads the stored grace cycle key.
     *
     * New format: schedule cycle index counted from the first configured reset instant after the Unix epoch.
     *
     * Legacy format: plain week number (1-53) or old year-week-style values.
     * For legacy rows, we reset to the current cycle key so the player is aligned to the new config-driven system.
     */
    private int readStoredGraceCycleKey(ResultSet rs) throws Exception {
        int storedGraceWeek = rs.getInt("grace_week");

        // New cycle keys from this implementation are small, relative counters.
        // Old values were either 1-53 or 6-digit year-week codes.
        if (storedGraceWeek > 53 && storedGraceWeek < 10000) {
            return storedGraceWeek;
        }

        return getCurrentGraceCycleKey();
    }

    // ----------------- Config helpers (prefer axrewards.login-streaks, fallback to login_streaks) -----------------

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

    private String getConfigStringWithFallback(String key, String def) {
        String axPath = "axrewards.login-streaks." + key;
        String oldPath = "login_streaks." + key;
        if (plugin.getConfig().contains(axPath)) return plugin.getConfig().getString(axPath, def);
        return plugin.getConfig().getString(oldPath, def);
    }

}