package pl.foxevents.schedule;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.foxevents.config.PluginConfig;
import pl.foxevents.event.ArenaGemHuntEvent;
import pl.foxevents.event.CoreOverheatEvent;
import pl.foxevents.event.CastleDominationEvent;
import pl.foxevents.event.ParkourCloudEvent;
import pl.foxevents.util.WeightedRandom;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class EventSchedulerService {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final ArenaGemHuntEvent arenaGemHuntEvent;
    private final ParkourCloudEvent parkourCloudEvent;
    private final CoreOverheatEvent coreOverheatEvent;
    private final CastleDominationEvent castleDominationEvent;
    private final Random random = new Random();
    private final File storageFile;

    private BukkitTask tickTask;
    private List<ScheduledEvent> todayEvents = new ArrayList<>();
    private LocalDate loadedDate;

    public EventSchedulerService(JavaPlugin plugin, PluginConfig pluginConfig, ArenaGemHuntEvent arenaGemHuntEvent, ParkourCloudEvent parkourCloudEvent, CoreOverheatEvent coreOverheatEvent, CastleDominationEvent castleDominationEvent) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.arenaGemHuntEvent = arenaGemHuntEvent;
        this.parkourCloudEvent = parkourCloudEvent;
        this.coreOverheatEvent = coreOverheatEvent;
        this.castleDominationEvent = castleDominationEvent;
        this.storageFile = new File(plugin.getDataFolder(), "schedule-data.yml");
    }

    public void start() {
        loadOrGenerateForToday();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
        }
    }

    private void tick() {
        ZoneId zone = pluginConfig.getTimezone();
        LocalDateTime now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
        if (!now.toLocalDate().equals(loadedDate)) {
            generateAndSave(now.toLocalDate());
        }

        boolean changed = false;
        for (ScheduledEvent event : todayEvents) {
            if (event.triggered()) {
                continue;
            }

            int leadMinutes = getLeadMinutes(event.type());
            LocalDateTime setupTime = event.time().minusMinutes(leadMinutes);
            if (!now.isBefore(setupTime)) {
                event.setTriggered(true);
                triggerEvent(event);
                changed = true;
            }
        }

        if (changed) {
            save();
        }
    }

    private int getLeadMinutes(String eventType) {
        if (eventType.equalsIgnoreCase("arena_pvp")) {
            return pluginConfig.getArenaConfig().announcementMinutes();
        }
        if (eventType.equalsIgnoreCase("parkour")) {
            return plugin.getConfig().getInt("events.parkour.announcement-minutes", 15);
        }
        if (eventType.equalsIgnoreCase("core_overheat")) {
            return plugin.getConfig().getInt("events.core_overheat.announcement-minutes", 15);
        }
        if (eventType.equalsIgnoreCase("castle_domination")) {
            return plugin.getConfig().getInt("events.castle_domination.announcement-minutes", 15);
        }
        return 0;
    }

    private void triggerEvent(ScheduledEvent event) {
        if (event.type().equalsIgnoreCase("arena_pvp")) {
            arenaGemHuntEvent.scheduleRun(event.time());
            return;
        }
        if (event.type().equalsIgnoreCase("parkour")) {
            parkourCloudEvent.scheduleRun(event.time(), pluginConfig.getTimezone());
            return;
        }
        if (event.type().equalsIgnoreCase("core_overheat")) {
            coreOverheatEvent.scheduleRun(event.time(), pluginConfig.getTimezone());
            return;
        }
        if (event.type().equalsIgnoreCase("castle_domination")) {
            castleDominationEvent.scheduleRun(event.time(), pluginConfig.getTimezone());
        }
    }

    private void loadOrGenerateForToday() {
        LocalDate today = LocalDate.now(pluginConfig.getTimezone());
        if (!storageFile.exists()) {
            generateAndSave(today);
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        String dateRaw = yaml.getString("date", "");
        if (!today.toString().equals(dateRaw)) {
            generateAndSave(today);
            return;
        }

        loadedDate = today;
        List<Map<?, ?>> maps = yaml.getMapList("events");
        List<ScheduledEvent> loaded = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            String type = Objects.toString(map.get("type"), "arena_pvp");
            LocalDateTime time = LocalDateTime.parse(Objects.toString(map.get("time")));
            boolean triggered = Boolean.parseBoolean(Objects.toString(map.get("triggered"), "false"));
            loaded.add(new ScheduledEvent(type, time, triggered));
        }
        todayEvents = loaded;
    }

    private void generateAndSave(LocalDate date) {
        loadedDate = date;
        todayEvents = generateSchedule(date);
        save();
    }

    private List<ScheduledEvent> generateSchedule(LocalDate date) {
        PluginConfig.DaySettings day = pluginConfig.getDaySettings(date.getDayOfWeek());
        if (!day.active() || day.amountOfEvents() <= 0) {
            return new ArrayList<>();
        }

        int from = day.from().toSecondOfDay();
        int to = day.to().toSecondOfDay();
        int minGap = day.minMinutesBetweenEvents() * 60;

        List<Integer> picks = new ArrayList<>();
        int attempts = 0;
        while (picks.size() < day.amountOfEvents() && attempts < 5_000) {
            attempts++;
            int candidate = from + random.nextInt(Math.max(1, to - from));
            boolean ok = picks.stream().allMatch(existing -> Math.abs(existing - candidate) >= minGap);
            if (ok) {
                picks.add(candidate);
            }
        }

        picks.sort(Integer::compareTo);
        List<PluginConfig.WeightedEventType> weightedTypes = pluginConfig.getWeightedEventTypes().stream()
                .map(w -> new PluginConfig.WeightedEventType(w.key(), Math.max(0, w.weight())))
                .toList();

        List<ScheduledEvent> events = new ArrayList<>();
        for (Integer secondOfDay : picks) {
            WeightedType pick = WeightedRandom.pick(weightedTypes.stream().map(WeightedType::new).toList(), random::nextInt);
            String type = pick == null ? "arena_pvp" : pick.key();
            events.add(new ScheduledEvent(type, date.atStartOfDay().plusSeconds(secondOfDay), false));
        }
        return events;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("date", loadedDate.toString());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScheduledEvent event : todayEvents) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.type());
            row.put("time", event.time().toString());
            row.put("triggered", event.triggered());
            out.add(row);
        }
        yaml.set("events", out);

        try {
            yaml.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot save schedule-data.yml: " + e.getMessage());
        }
    }

    private record WeightedType(String key, int weight) implements WeightedRandom.Weighted {
        WeightedType(PluginConfig.WeightedEventType type) {
            this(type.key(), type.weight());
        }
    }

    private static final class ScheduledEvent {
        private final String type;
        private final LocalDateTime time;
        private boolean triggered;

        private ScheduledEvent(String type, LocalDateTime time, boolean triggered) {
            this.type = type;
            this.time = time;
            this.triggered = triggered;
        }

        public String type() {
            return type;
        }

        public LocalDateTime time() {
            return time;
        }

        public boolean triggered() {
            return triggered;
        }

        public void setTriggered(boolean triggered) {
            this.triggered = triggered;
        }
    }
}
