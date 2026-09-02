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
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
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
import java.util.*;

public class ParkourCloudEvent implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String EVENT_KEY = "parkour";

    private final JavaPlugin plugin;
    private final RewardService rewardService;

    private final Map<UUID, String> previousSidebarObjectives = new HashMap<>();
    private final Map<UUID, String> eventSidebarObjectives = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final List<Result> results = new ArrayList<>();
    private final Set<UUID> finished = new HashSet<>();

    private boolean running;
    private int secondsLeft;
    private long startEpochSecond;
    private Cuboid region;
    private Location finishLoc;
    private Location pasteLoc;
    private Location teleportLoc;
    private World world;
    private int durationMinutes;
    private int announcementMinutes;
    private String startSchematic;
    private String endSchematic;

    private BossBar announcementBar;
    private BukkitTask announcementTask;
    private BossBar runningBar;
    private BukkitTask runningTask;

    public ParkourCloudEvent(JavaPlugin plugin, RewardService rewardService) {
        this.plugin = plugin;
        this.rewardService = rewardService;
        reloadConfig();
    }

    public void scheduleRun(LocalDateTime eventTime, ZoneId zoneId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        long untilStart = Math.max(0, Duration.between(now, eventTime).getSeconds());
        long announceWindow = announcementMinutes * 60L;
        long announceDelay = untilStart - announceWindow;

        if (announceDelay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> startAnnouncement((int) announceWindow), announceDelay * 20L);
        } else if (untilStart > 0) {
            startAnnouncement((int) untilStart);
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, untilStart * 20L);
    }

    public boolean startFromCommand() {
        if (running) return false;
        int announceSeconds = announcementMinutes * 60;
        if (announceSeconds <= 0) {
            startEventNow();
            return true;
        }
        startAnnouncement(announceSeconds);
        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, announceSeconds * 20L);
        return true;
    }

    private void startAnnouncement(int duration) {
        if (announcementTask != null) announcementTask.cancel();
        if (announcementBar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(announcementBar));

        announcementBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(announcementBar));

        announcementTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = duration;
            @Override public void run() {
                if (left <= 0) {
                    Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(announcementBar));
                    announcementTask.cancel();
                    announcementTask = null;
                    announcementBar = null;
                    return;
                }
                announcementBar.name(LEGACY.deserialize("&fEvent Parkour w Chmurach za &e" + TimeFormat.formatSeconds(left) + " &7(Szczyt Góry)"));
                announcementBar.progress(Math.max(0.01f, (float) left / duration));
                Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(announcementBar));
                left--;
            }
        }, 0L, 20L);
    }

    private void startEventNow() {
        if (running) return;
        reloadConfig();
        running = true;
        results.clear();
        finished.clear();
        secondsLeft = durationMinutes * 60;
        startEpochSecond = System.currentTimeMillis() / 1000;

        pasteSchematic(startSchematic, true);

        runningBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        runningTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick();
            secondsLeft--;
            if (secondsLeft <= 0) stopEvent();
        }, 20L, 20L);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean inside = inRegion(player.getLocation());
            if (inside) {
                runningBar.name(LEGACY.deserialize("&fEvent kończy się za &e" + TimeFormat.formatSeconds(secondsLeft)));
                runningBar.progress(Math.max(0.01f, (float) secondsLeft / (durationMinutes * 60f)));
                player.showBossBar(runningBar);
                viewers.add(player.getUniqueId());
                enforceRegionRules(player);
                showBoard(player);
            } else {
                player.hideBossBar(runningBar);
                if (viewers.remove(player.getUniqueId())) {
                    restorePlayerScoreboard(player);
                }
            }
        }
    }

    private void enforceRegionRules(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack chest = inv.getChestplate();
        if (chest != null && chest.getType() == Material.ELYTRA) {
            inv.setChestplate(null);
            HashMap<Integer, ItemStack> left = inv.addItem(chest);
            left.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
        }

        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
        player.removePotionEffect(PotionEffectType.LEVITATION);

        checkFinish(player);
    }

    private void checkFinish(Player player) {
        if (finished.contains(player.getUniqueId()) || results.size() >= 5) return;
        if (player.getLocation().getWorld() == null || !player.getLocation().getWorld().equals(world)) return;
        if (player.getLocation().distance(finishLoc) > 1.5) return;

        finished.add(player.getUniqueId());
        int elapsed = (int) ((System.currentTimeMillis() / 1000) - startEpochSecond);
        results.add(new Result(player.getUniqueId(), elapsed));

        int place = results.size();
        rewardService.giveRewards(EVENT_KEY, place, player);
        player.teleport(teleportLoc);
        showBoard(player);
    }

    private void stopEvent() {
        running = false;
        if (runningTask != null) runningTask.cancel();
        if (runningBar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(runningBar));

        for (UUID viewer : viewers) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) restorePlayerScoreboard(player);
        }
        viewers.clear();
        previousSidebarObjectives.clear();
        eventSidebarObjectives.clear();

        pasteSchematic(endSchematic, false);
    }

    private void showBoard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        previousSidebarObjectives.putIfAbsent(uuid, getSidebarObjectiveName(scoreboard));
        String objectiveName = eventSidebarObjectives.computeIfAbsent(uuid, id -> "foxpk_" + id.toString().replace("-", "").substring(0, 8));

        Objective existing = scoreboard.getObjective(objectiveName);
        if (existing != null) existing.unregister();

        Objective objective = scoreboard.registerNewObjective(objectiveName, "dummy", LEGACY.deserialize("&f&lParkour w Chmurach"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int line = 8;
        for (int i = 0; i < 5; i++) {
            if (i < results.size()) {
                Result r = results.get(i);
                Player p = Bukkit.getPlayer(r.playerId());
                String name = p != null ? p.getName() : "---";
                objective.getScore(color("&f" + (i + 1) + ". &9" + name + " &7- &e" + TimeFormat.formatSeconds(r.seconds()))).setScore(line--);
            } else {
                objective.getScore(color("&f" + (i + 1) + ". &9--- &7- &e---")).setScore(line--);
            }
        }
    }

    private void restorePlayerScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        String eventObjectiveName = eventSidebarObjectives.remove(uuid);
        if (eventObjectiveName != null) {
            Objective objective = scoreboard.getObjective(eventObjectiveName);
            if (objective != null) objective.unregister();
        }
        String previousObjectiveName = previousSidebarObjectives.remove(uuid);
        if (previousObjectiveName == null) {
            scoreboard.clearSlot(DisplaySlot.SIDEBAR);
            refreshSternalBoard(player);
            return;
        }
        Objective previous = scoreboard.getObjective(previousObjectiveName);
        if (previous != null) previous.setDisplaySlot(DisplaySlot.SIDEBAR);
        else scoreboard.clearSlot(DisplaySlot.SIDEBAR);
        refreshSternalBoard(player);
    }

    private void refreshSternalBoard(Player player) {
        player.performCommand("sternalboard toggle");
        player.performCommand("sternalboard toggle");
    }

    private String getSidebarObjectiveName(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(DisplaySlot.SIDEBAR);
        return objective == null ? null : objective.getName();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private boolean inRegion(Location loc) {
        return loc.getWorld() != null && loc.getWorld().equals(world) && region.contains(loc);
    }

    private void pasteSchematic(String schematic, boolean ignoreAir) {
        File file = new File(plugin.getDataFolder(), "schematics/" + schematic);
        if (!file.exists()) file = new File("plugins/FastAsyncWorldEdit/schematics/" + schematic);

        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null || !file.exists()) {
            plugin.getLogger().warning("Cannot find parkour schematic: " + schematic);
            return;
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            BlockVector3 to = BlockVector3.at(pasteLoc.getBlockX(), pasteLoc.getBlockY(), pasteLoc.getBlockZ());
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation op = new ClipboardHolder(clipboard).createPaste(editSession).to(to).ignoreAirBlocks(ignoreAir).build();
                Operations.complete(op);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to paste parkour schematic: " + e.getMessage());
        }
    }

    private void reloadConfig() {
        FileConfiguration c = plugin.getConfig();
        String worldName = c.getString("events.parkour.world", "world");
        world = Bukkit.getWorld(worldName);
        if (world == null) throw new IllegalStateException("World not found for parkour: " + worldName);

        region = new Cuboid(
                Math.min(c.getDouble("events.parkour.region.pos1.x"), c.getDouble("events.parkour.region.pos2.x")),
                Math.min(c.getDouble("events.parkour.region.pos1.y"), c.getDouble("events.parkour.region.pos2.y")),
                Math.min(c.getDouble("events.parkour.region.pos1.z"), c.getDouble("events.parkour.region.pos2.z")),
                Math.max(c.getDouble("events.parkour.region.pos1.x"), c.getDouble("events.parkour.region.pos2.x")),
                Math.max(c.getDouble("events.parkour.region.pos1.y"), c.getDouble("events.parkour.region.pos2.y")),
                Math.max(c.getDouble("events.parkour.region.pos1.z"), c.getDouble("events.parkour.region.pos2.z"))
        );

        finishLoc = new Location(world, c.getDouble("events.parkour.finish.x"), c.getDouble("events.parkour.finish.y"), c.getDouble("events.parkour.finish.z"));
        teleportLoc = new Location(world, c.getDouble("events.parkour.teleport-after-finish.x"), c.getDouble("events.parkour.teleport-after-finish.y"), c.getDouble("events.parkour.teleport-after-finish.z"));
        pasteLoc = new Location(world, c.getDouble("events.parkour.schematic-paste.x"), c.getDouble("events.parkour.schematic-paste.y"), c.getDouble("events.parkour.schematic-paste.z"));

        startSchematic = c.getString("events.parkour.schematics.start", "parkour.schem");
        endSchematic = c.getString("events.parkour.schematics.end", "parkour_clear.schem");
        durationMinutes = c.getInt("events.parkour.duration-minutes", 15);
        announcementMinutes = c.getInt("events.parkour.announcement-minutes", 15);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (announcementBar != null) event.getPlayer().showBossBar(announcementBar);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        previousSidebarObjectives.remove(event.getPlayer().getUniqueId());
        eventSidebarObjectives.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!running || event.getTo() == null) return;
        if (inRegion(event.getTo())) enforceRegionRules(event.getPlayer());
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!running || !(event.getEntity() instanceof Player p)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && inRegion(p.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!running || !(event.getEntity() instanceof Player victim)) return;
        if (!inRegion(victim.getLocation())) return;

        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            event.setCancelled(true);
            return;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!running) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && inRegion(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWindOrPearlUse(PlayerInteractEvent event) {
        if (!running || event.getItem() == null) return;
        Material type = event.getItem().getType();
        if ((type == Material.WIND_CHARGE || type == Material.ENDER_PEARL) && inRegion(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }

    private record Result(UUID playerId, int seconds) {}
}
