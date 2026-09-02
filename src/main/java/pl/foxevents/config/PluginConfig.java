package pl.foxevents.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

public class PluginConfig {

    private final JavaPlugin plugin;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public ZoneId getTimezone() {
        String id = plugin.getConfig().getString("timezone", "Europe/Warsaw");
        return ZoneId.of(id);
    }

    public DaySettings getDaySettings(DayOfWeek dayOfWeek) {
        String key = dayOfWeek.name().toLowerCase(Locale.ROOT);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("schedule." + key);
        if (section == null) {
            return DaySettings.disabled();
        }
        return new DaySettings(
                section.getBoolean("active", false),
                LocalTime.parse(section.getString("from", "18:00")),
                LocalTime.parse(section.getString("to", "23:00")),
                section.getInt("min-time-between-events", 60),
                section.getInt("amount-of-events", 0)
        );
    }

    public List<WeightedEventType> getWeightedEventTypes() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("event-types");
        if (section == null) {
            return List.of();
        }
        List<WeightedEventType> list = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            int weight = section.getInt(key + ".chance", 0);
            list.add(new WeightedEventType(key, weight));
        }
        return list;
    }

    public ConfigurationSection getRewardPoolsSection() {
        return plugin.getConfig().getConfigurationSection("reward-pools");
    }

    public ConfigurationSection getEventRewardsSection(String eventKey) {
        return plugin.getConfig().getConfigurationSection("events." + eventKey + ".rewards");
    }

    public ArenaConfig getArenaConfig() {
        FileConfiguration c = plugin.getConfig();
        String worldName = c.getString("events.arena_pvp.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalStateException("World not found in config: " + worldName);
        }

        return new ArenaConfig(
                world,
                readLocation(world, "events.arena_pvp.gem-spawn"),
                readLocation(world, "events.arena_pvp.push-center"),
                readLocation(world, "events.arena_pvp.schematic-paste"),
                c.getString("events.arena_pvp.schematics.start", "arena_centrum.schem"),
                c.getString("events.arena_pvp.schematics.end", "arena_centrum_clear.schem"),
                c.getInt("events.arena_pvp.duration-minutes", 15),
                c.getInt("events.arena_pvp.announcement-minutes", 15),
                c.getInt("events.arena_pvp.prepaste-minutes-before-start", 10),
                c.getDouble("events.arena_pvp.push-radius", 7.0),
                c.getDouble("events.arena_pvp.push-target-distance", 11.0),
                readLocation(world, "events.arena_pvp.region.pos1"),
                readLocation(world, "events.arena_pvp.region.pos2")
        );
    }

    private Location readLocation(World world, String path) {
        double x = plugin.getConfig().getDouble(path + ".x");
        double y = plugin.getConfig().getDouble(path + ".y");
        double z = plugin.getConfig().getDouble(path + ".z");
        return new Location(world, x, y, z);
    }

    public record DaySettings(boolean active, LocalTime from, LocalTime to, int minMinutesBetweenEvents,
                              int amountOfEvents) {
        public static DaySettings disabled() {
            return new DaySettings(false, LocalTime.MIN, LocalTime.MIN, 0, 0);
        }
    }

    public record WeightedEventType(String key, int weight) {}

    public record ArenaConfig(World world, Location gemSpawn, Location pushCenter, Location schematicPaste,
                              String startSchematic, String endSchematic, int durationMinutes, int announcementMinutes,
                              int prepasteMinutesBeforeStart, double pushRadius, double pushTargetDistance,
                              Location regionPos1, Location regionPos2) {
    }
}
