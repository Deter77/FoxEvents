package pl.foxevents.event;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import pl.foxevents.reward.RewardService;
import pl.foxevents.util.Cuboid;
import pl.foxevents.util.TimeFormat;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CastleDominationEvent implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String EVENT_KEY = "castle_domination";

    private final JavaPlugin plugin;
    private final RewardService rewardService;

    private final Map<UUID, Double> points = new HashMap<>();
    private final Map<UUID, String> previousSidebarObjectives = new HashMap<>();
    private final Map<UUID, String> eventSidebarObjectives = new HashMap<>();
    private final Map<UUID, BossBar> zoneBars = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();

    private World world;
    private Cuboid castleRegion;
    private List<DominationZone> zones = new ArrayList<>();
    private List<SchematicPaste> startSchematics = new ArrayList<>();
    private List<SchematicPaste> endSchematics = new ArrayList<>();
    private int announcementMinutes;
    private int durationMinutes;
    private int secondsLeft;
    private boolean running;

    private BossBar announcementBar;
    private BukkitTask announcementTask;
    private BossBar timerBar;
    private BukkitTask tickTask;

    public CastleDominationEvent(JavaPlugin plugin, RewardService rewardService) {
        this.plugin = plugin;
        this.rewardService = rewardService;
        reloadConfig();
    }

    public void scheduleRun(LocalDateTime eventTime, ZoneId zoneId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        long untilStart = Math.max(0, Duration.between(now, eventTime).getSeconds());
        long announcementSeconds = announcementMinutes * 60L;
        long announcementDelay = untilStart - announcementSeconds;

        if (announcementDelay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> startAnnouncement((int) announcementSeconds), announcementDelay * 20L);
        } else if (untilStart > 0) {
            startAnnouncement((int) untilStart);
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, untilStart * 20L);
    }

    public boolean startFromCommand() {
        if (running) {
            return false;
        }
        int announcementSeconds = announcementMinutes * 60;
        if (announcementSeconds <= 0) {
            startEventNow();
            return true;
        }
        startAnnouncement(announcementSeconds);
        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, announcementSeconds * 20L);
        return true;
    }

    private void startAnnouncement(int duration) {
        if (announcementTask != null) {
            announcementTask.cancel();
        }
        if (announcementBar != null) {
            Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(announcementBar));
        }

        announcementBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        announcementTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = duration;

            @Override
            public void run() {
                if (left <= 0) {
                    Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(announcementBar));
                    announcementTask.cancel();
                    announcementTask = null;
                    announcementBar = null;
                    return;
                }
                announcementBar.name(LEGACY.deserialize("&fEvent &6Dominacja Zamku &fza &e" + TimeFormat.formatSeconds(left) + " &7(Arena PvP)"));
                announcementBar.progress(Math.max(0.01f, (float) left / duration));
                Bukkit.getOnlinePlayers().forEach(player -> player.showBossBar(announcementBar));
                left--;
            }
        }, 0L, 20L);
    }

    private void startEventNow() {
        if (running) {
            return;
        }
        reloadConfig();
        running = true;
        secondsLeft = durationMinutes * 60;
        points.clear();
        startSchematics.forEach(schematic -> pasteSchematic(schematic, true));

        timerBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        secondsLeft--;
        Map<DominationZone, List<Player>> playersByZone = collectPlayersByZone();
        awardPoints(playersByZone);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inCastle(player.getLocation())) {
                player.hideBossBar(timerBar);
                hideZoneBar(player);
                if (viewers.remove(player.getUniqueId())) {
                    restorePlayerScoreboard(player);
                }
                continue;
            }

            player.showBossBar(timerBar);
            timerBar.name(LEGACY.deserialize("&fEvent kończy się za &e" + TimeFormat.formatSeconds(secondsLeft)));
            timerBar.progress(Math.max(0.01f, (float) secondsLeft / (durationMinutes * 60f)));
            viewers.add(player.getUniqueId());
            showBoard(player);
            showZoneBar(player, playersByZone);
        }

        if (secondsLeft <= 0) {
            stopEvent();
        }
    }

    private Map<DominationZone, List<Player>> collectPlayersByZone() {
        Map<DominationZone, List<Player>> result = new HashMap<>();
        for (DominationZone zone : zones) {
            result.put(zone, new ArrayList<>());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inCastle(player.getLocation())) {
                continue;
            }
            for (DominationZone zone : zones) {
                if (zone.region().contains(player.getLocation())) {
                    result.get(zone).add(player);
                    break;
                }
            }
        }
        return result;
    }

    private void awardPoints(Map<DominationZone, List<Player>> playersByZone) {
        for (Map.Entry<DominationZone, List<Player>> entry : playersByZone.entrySet()) {
            double pointsPerSecond = getPointsPerSecond(entry.getKey(), entry.getValue().size());
            if (pointsPerSecond <= 0) {
                continue;
            }
            for (Player player : entry.getValue()) {
                points.merge(player.getUniqueId(), pointsPerSecond, Double::sum);
            }
        }
    }

    private double getPointsPerSecond(DominationZone zone, int players) {
        if (players <= 0) {
            return 0.0;
        }
        return switch (zone.multiplier()) {
            case 1 -> players <= 3 ? 1.0 : players <= 5 ? 0.5 : 0.0;
            case 2 -> players <= 2 ? 2.0 : players == 3 ? 1.0 : players == 4 ? 0.5 : 0.0;
            case 3 -> players == 1 ? 3.0 : players == 2 ? 2.0 : players == 3 ? 1.0 : 0.0;
            default -> 0.0;
        };
    }

    private void showZoneBar(Player player, Map<DominationZone, List<Player>> playersByZone) {
        DominationZone current = null;
        int players = 0;
        for (Map.Entry<DominationZone, List<Player>> entry : playersByZone.entrySet()) {
            if (entry.getKey().region().contains(player.getLocation())) {
                current = entry.getKey();
                players = entry.getValue().size();
                break;
            }
        }
        if (current == null) {
            hideZoneBar(player);
            return;
        }

        int multiplier = current.multiplier();
        double pps = getPointsPerSecond(current, players);
        BossBar bar = zoneBars.computeIfAbsent(player.getUniqueId(), id -> BossBar.bossBar(Component.text(""), 1f, zoneColor(multiplier), BossBar.Overlay.PROGRESS));
        bar.color(zoneColor(multiplier));
        bar.progress(Math.max(0.01f, (float) Math.min(1.0, pps / Math.max(1, multiplier))));
        if (pps <= 0) {
            bar.name(LEGACY.deserialize("&fStrefa " + zoneLabel(current.multiplier()) + " &7- &cZa dużo graczy! &7(&80pkt/s&7)"));
        } else {
            bar.name(LEGACY.deserialize("&fStrefa " + zoneLabel(current.multiplier()) + " &7- &f" + pointRateText(pps) + " &7(" + players + "graczy)"));
        }
        player.showBossBar(bar);
    }

    private String zoneLabel(int multiplier) {
        return switch (multiplier) {
            case 1 -> "&ax1";
            case 2 -> "&ex2";
            case 3 -> "&6x3";
            default -> "&fx?";
        };
    }

    private String pointRateText(double value) {
        if (value == 0.5) {
            return "&e0,5pkt/s";
        }
        if (value == 1.0) {
            return "&61pkt/s";
        }
        if (value == 2.0) {
            return "&c2pkt/s";
        }
        if (value == 3.0) {
            return "&43pkt/s";
        }
        return "&e" + formatPoints(value) + "pkt/s";
    }

    private BossBar.Color zoneColor(int multiplier) {
        return switch (multiplier) {
            case 1 -> BossBar.Color.GREEN;
            case 2, 3 -> BossBar.Color.YELLOW;
            default -> BossBar.Color.WHITE;
        };
    }

    private void hideZoneBar(Player player) {
        BossBar bar = zoneBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void showBoard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        previousSidebarObjectives.putIfAbsent(uuid, getSidebarObjectiveName(scoreboard));
        String objectiveName = eventSidebarObjectives.computeIfAbsent(uuid, id -> "foxca_" + id.toString().replace("-", "").substring(0, 8));

        Objective existing = scoreboard.getObjective(objectiveName);
        if (existing != null) {
            existing.unregister();
        }

        Objective objective = scoreboard.registerNewObjective(objectiveName, "dummy", LEGACY.deserialize("&6&lDominacja Zamku"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<Map.Entry<UUID, Double>> top = points.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .toList();

        int line = 8;
        for (int i = 0; i < 5; i++) {
            if (i < top.size()) {
                Player topPlayer = Bukkit.getPlayer(top.get(i).getKey());
                String name = topPlayer != null ? topPlayer.getName() : "---";
                objective.getScore(color("&f" + (i + 1) + ". &9" + name + " &7- &e" + formatPoints(top.get(i).getValue()) + "pkt")).setScore(line--);
            } else {
                objective.getScore(color("&f" + (i + 1) + ". &9--- &7- &e---pkt")).setScore(line--);
            }
        }
        objective.getScore(color("&bTwój wynik &7- &e" + formatPoints(points.getOrDefault(uuid, 0.0)) + "pkt")).setScore(line);
    }

    private void stopEvent() {
        running = false;
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (timerBar != null) {
            Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(timerBar));
        }

        List<Map.Entry<UUID, Double>> top = points.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .toList();
        for (int i = 0; i < top.size(); i++) {
            Player player = Bukkit.getPlayer(top.get(i).getKey());
            if (player != null) {
                rewardService.giveRewards(EVENT_KEY, i + 1, player);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(timerBar);
            hideZoneBar(player);
            restorePlayerScoreboard(player);
        }
        viewers.clear();
        previousSidebarObjectives.clear();
        eventSidebarObjectives.clear();
        endSchematics.forEach(schematic -> pasteSchematic(schematic, false));
    }

    private void restorePlayerScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        String eventObjectiveName = eventSidebarObjectives.remove(uuid);
        if (eventObjectiveName != null) {
            Objective objective = scoreboard.getObjective(eventObjectiveName);
            if (objective != null) {
                objective.unregister();
            }
        }

        String previousObjectiveName = previousSidebarObjectives.remove(uuid);
        if (previousObjectiveName == null) {
            scoreboard.clearSlot(DisplaySlot.SIDEBAR);
        } else {
            Objective previous = scoreboard.getObjective(previousObjectiveName);
            if (previous != null) {
                previous.setDisplaySlot(DisplaySlot.SIDEBAR);
            } else {
                scoreboard.clearSlot(DisplaySlot.SIDEBAR);
            }
        }
        player.performCommand("sternalboard toggle");
        player.performCommand("sternalboard toggle");
    }

    private String getSidebarObjectiveName(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(DisplaySlot.SIDEBAR);
        return objective == null ? null : objective.getName();
    }

    private boolean inCastle(Location location) {
        return location.getWorld() != null && location.getWorld().equals(world) && castleRegion.contains(location);
    }

    private String formatPoints(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value).replace('.', ',');
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void pasteSchematic(SchematicPaste schematic, boolean ignoreAir) {
        File file = new File(plugin.getDataFolder(), "schematics/" + schematic.file());
        if (!file.exists()) {
            file = new File("plugins/FastAsyncWorldEdit/schematics/" + schematic.file());
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null || !file.exists()) {
            plugin.getLogger().warning("Cannot find castle schematic: " + schematic.file());
            return;
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            BlockVector3 to = BlockVector3.at(schematic.location().getBlockX(), schematic.location().getBlockY(), schematic.location().getBlockZ());
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(to)
                        .ignoreAirBlocks(ignoreAir)
                        .build();
                Operations.complete(operation);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to paste castle schematic " + schematic.file() + ": " + exception.getMessage());
        }
    }

    private void reloadConfig() {
        FileConfiguration config = plugin.getConfig();
        String worldName = config.getString("events.castle_domination.world", "world");
        world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalStateException("World not found for castle_domination: " + worldName);
        }

        durationMinutes = config.getInt("events.castle_domination.duration-minutes", 15);
        announcementMinutes = config.getInt("events.castle_domination.announcement-minutes", 15);
        castleRegion = readCuboid(config, "events.castle_domination.region");
        zones = readZones(config);
        startSchematics = readSchematics(config, "events.castle_domination.schematics.start", false);
        endSchematics = readSchematics(config, "events.castle_domination.schematics.end", true);
    }

    private List<DominationZone> readZones(FileConfiguration config) {
        List<DominationZone> loaded = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("events.castle_domination.zones");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int multiplier = section.getInt(key + ".multiplier", 1);
                loaded.add(new DominationZone(key, multiplier, readCuboid(config, "events.castle_domination.zones." + key)));
            }
        }
        return loaded;
    }

    private List<SchematicPaste> readSchematics(FileConfiguration config, String path, boolean clear) {
        List<SchematicPaste> loaded = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String file = section.getString(key + ".file", key + (clear ? "_clear.schem" : ".schem"));
                loaded.add(new SchematicPaste(file, readLocation(config, path + "." + key)));
            }
        }
        return loaded;
    }

    private Cuboid readCuboid(FileConfiguration config, String path) {
        return Cuboid.fromCorners(readLocation(config, path + ".pos1"), readLocation(config, path + ".pos2"));
    }

    private Location readLocation(FileConfiguration config, String path) {
        return new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z")
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (announcementBar != null) {
            event.getPlayer().showBossBar(announcementBar);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hideZoneBar(event.getPlayer());
        previousSidebarObjectives.remove(event.getPlayer().getUniqueId());
        eventSidebarObjectives.remove(event.getPlayer().getUniqueId());
    }

    private record DominationZone(String id, int multiplier, Cuboid region) {}
    private record SchematicPaste(String file, Location location) {}
}
