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
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;
import pl.foxevents.reward.RewardService;
import pl.foxevents.util.Cuboid;
import pl.foxevents.util.TimeFormat;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class CoreOverheatEvent implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String EVENT_KEY = "core_overheat";

    private final JavaPlugin plugin;
    private final RewardService rewardService;
    private final Random random = new Random();

    private World world;
    private Cuboid region;
    private Location pasteLoc;
    private int announcementMinutes;
    private int durationMinutes;
    private String startSchematic;
    private String endSchematic;

    private boolean running;
    private int secondsLeft;
    private double corePercent;

    private BossBar announcementBar;
    private BukkitTask announcementTask;
    private BossBar timerBar;
    private BukkitTask tickTask;

    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Double> heat = new HashMap<>();
    private final Map<UUID, String> previousSidebarObjectives = new HashMap<>();
    private final Map<UUID, String> eventSidebarObjectives = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final Map<UUID, Long> underwaterSince = new HashMap<>();
    private final Map<UUID, BossBar> coreBars = new HashMap<>();
    private final Map<UUID, BossBar> collectorBars = new HashMap<>();
    private final Map<UUID, BukkitTask> activeCoolingTasks = new HashMap<>();

    public CoreOverheatEvent(JavaPlugin plugin, RewardService rewardService) {
        this.plugin = plugin;
        this.rewardService = rewardService;
        reloadConfig();
    }

    public void scheduleRun(LocalDateTime eventTime, ZoneId zoneId) {
        LocalDateTime now = LocalDateTime.now(zoneId);
        long untilStart = Math.max(0, Duration.between(now, eventTime).getSeconds());
        long announceSeconds = announcementMinutes * 60L;
        long delay = untilStart - announceSeconds;
        if (delay > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> startAnnouncement((int) announceSeconds), delay * 20L);
        else if (untilStart > 0) startAnnouncement((int) untilStart);

        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, untilStart * 20L);
    }

    public boolean startFromCommand() {
        if (running) return false;
        int announce = announcementMinutes * 60;
        if (announce <= 0) {
            startEventNow();
            return true;
        }
        startAnnouncement(announce);
        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, announce * 20L);
        return true;
    }

    private void startAnnouncement(int duration) {
        if (announcementTask != null) announcementTask.cancel();
        if (announcementBar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(announcementBar));

        announcementBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
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
                announcementBar.name(LEGACY.deserialize("&fEvent &5Przegrzanie Rdzenia &fza &e" + TimeFormat.formatSeconds(left) + " &7(Jaskinie)"));
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
        secondsLeft = durationMinutes * 60;
        corePercent = 100.0;
        points.clear();
        heat.clear();
        underwaterSince.clear();

        pasteSchematic(startSchematic, true);
        timerBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inRegion(p.getLocation())) giveCollectorIfMissing(p);
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        secondsLeft--;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (hasCollector(p)) {
                coolOverTime(p);
                showCollectorBarIfHolding(p);
            } else {
                hideCollectorBar(p);
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inRegion(p.getLocation())) {
                p.hideBossBar(timerBar);
                hideCoreBar(p);
                if (viewers.remove(p.getUniqueId())) restorePlayerScoreboard(p);
                continue;
            }

            viewers.add(p.getUniqueId());
            giveCollectorIfMissing(p);
            p.showBossBar(timerBar);
            timerBar.name(LEGACY.deserialize("&fEvent kończy się za &e" + TimeFormat.formatSeconds(secondsLeft)));
            timerBar.progress(Math.max(0.01f, (float) secondsLeft / (durationMinutes * 60f)));
            showBoard(p);
            showCoreBar(p);
            stripElytraAndEffects(p);
        }

        if (secondsLeft <= 0 || corePercent <= 0.0) {
            stopEvent();
        }
    }

    private void stripElytraAndEffects(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && chest.getType() == Material.ELYTRA) {
            player.getInventory().setChestplate(null);
            HashMap<Integer, ItemStack> left = player.getInventory().addItem(chest);
            left.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
        }
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING);
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
    }

    private void coolOverTime(Player player) {
        UUID id = player.getUniqueId();
        heat.putIfAbsent(id, 0.0);

        if (secondsLeft % 5 == 0) {
            // intentionally left empty, passive cooling moved to 15s below
        }
        if (secondsLeft % 15 == 0) {
            heat.put(id, Math.max(0.0, heat.get(id) - 1.0));
        }

        boolean submerged = player.getLocation().getBlock().isLiquid() && player.getEyeLocation().getBlock().isLiquid();
        if (submerged && isCollector(player.getInventory().getItemInMainHand())) {
            long now = System.currentTimeMillis();
            underwaterSince.putIfAbsent(id, now);
            if (now - underwaterSince.get(id) >= 1500) {
                heat.put(id, Math.max(0.0, heat.get(id) - 5.0));
            }
        } else {
            underwaterSince.remove(id);
        }

        updateCollectorItem(player);
    }

    private void showCoreBar(Player player) {
        double clamped = Math.max(0.0, Math.min(100.0, corePercent));
        BossBar bar = coreBars.computeIfAbsent(player.getUniqueId(), id -> BossBar.bossBar(Component.text(""), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
        bar.name(LEGACY.deserialize("&5Przegrzanie Rdzenia &7- &c" + String.format(Locale.US, "%.1f", clamped) + "%"));
        bar.progress((float) (clamped / 100.0));
        player.showBossBar(bar);
    }

    private void showCollectorBarIfHolding(Player player) {
        if (!isCollector(player.getInventory().getItemInMainHand()) && !isCollector(player.getInventory().getItemInOffHand())) {
            hideCollectorBar(player);
            return;
        }
        double value = heat.getOrDefault(player.getUniqueId(), 0.0);
        String colorCode = value > 100 ? "&4" : value >= 80 ? "&6" : "&b";
        BossBar.Color color = value > 100 ? BossBar.Color.RED : value >= 80 ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        BossBar bar = collectorBars.computeIfAbsent(player.getUniqueId(), id -> BossBar.bossBar(Component.text(""), 0f, color, BossBar.Overlay.PROGRESS));
        bar.color(color);
        bar.name(LEGACY.deserialize("&fNagrzanie Zbieracza Ciepła &7- " + colorCode + String.format(Locale.US, "%.1f", Math.min(value, 150.0)) + "%"));
        bar.progress((float) Math.max(0.0, Math.min(1.0, value / 100.0)));
        player.showBossBar(bar);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (announcementBar != null) event.getPlayer().showBossBar(announcementBar);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hideCoreBar(event.getPlayer());
        hideCollectorBar(event.getPlayer());
        BukkitTask task = activeCoolingTasks.remove(event.getPlayer().getUniqueId());
        if (task != null) task.cancel();
        removeCollector(event.getPlayer());
        previousSidebarObjectives.remove(event.getPlayer().getUniqueId());
        eventSidebarObjectives.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!running || event.getTo() == null) return;
        if (inRegion(event.getTo())) giveCollectorIfMissing(event.getPlayer());
    }

    @EventHandler
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!running || !(event.getEntity() instanceof Player victim)) return;
        if (inRegion(victim.getLocation())) event.setCancelled(true);
    }

    @EventHandler
    public void onFall(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!running || !(event.getEntity() instanceof Player p)) return;
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL && inRegion(p.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!running || event.getTo() == null) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && inRegion(event.getTo())) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!running || event.getItem() == null) return;
        Player player = event.getPlayer();
        if (isCollector(event.getItem()) && (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK)) {
            event.setCancelled(true);
            return;
        }

        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) && isCollector(event.getItem())) {
            Block clicked = event.getClickedBlock();
            if (clicked == null) {
                RayTraceResult ray = player.rayTraceBlocks(6.0);
                if (ray != null && ray.getHitBlock() != null) {
                    clicked = ray.getHitBlock();
                }
            }
            if (clicked == null) {
                return;
            }

            if (clicked.getType() == Material.SNOW_BLOCK) {
                coolPlayer(player, 2.0);
                event.setCancelled(true);
                return;
            }

            if (clicked.getType() == Material.MAGMA_BLOCK || clicked.getType() == Material.NETHERRACK) {
                int required = clicked.getType() == Material.MAGMA_BLOCK ? 10 : 5;
                startCoolingProcess(player, clicked, required);
                event.setCancelled(true);
                return;
            }
        }

        if ((event.getItem().getType() == Material.ENDER_PEARL || event.getItem().getType() == Material.WIND_CHARGE)
                && inRegion(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isCollector(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (!isCollector(current) && !isCollector(cursor)) return;

        // Allow normal movement only inside player's own inventory.
        if (event.getClickedInventory() == null) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory().getType() != InventoryType.PLAYER) {
            event.setCancelled(true);
            return;
        }
        if (event.getSlotType() == InventoryType.SlotType.OUTSIDE) {
            event.setCancelled(true);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (running) {
                giveCollectorIfMissing(player);
                updateCollectorItem(player);
            }
        });
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (isCollector(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    private void tryCompleteCooling(Player player, Block block) {
        if (!running || !player.isOnline() || !isCollector(player.getInventory().getItemInMainHand())) return;
        if (!inRegion(player.getLocation())) return;
        Material type = block.getType();
        if (type != Material.MAGMA_BLOCK && type != Material.NETHERRACK) return;

        if (type == Material.MAGMA_BLOCK) {
            block.setType(Material.AMETHYST_BLOCK);
            addPoints(player, 3);
            corePercent = Math.max(0.0, corePercent - 0.3);
            heatPlayer(player, 15.0);
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 2.0f, 1.0f);
        } else {
            block.setType(Material.STONE);
            addPoints(player, 1);
            corePercent = Math.max(0.0, corePercent - 0.1);
            heatPlayer(player, 8.0);
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 1.0f);
        }
        updateCollectorItem(player);
    }

    private void heatPlayer(Player player, double amount) {
        UUID id = player.getUniqueId();
        double newHeat = Math.min(150.0, heat.getOrDefault(id, 0.0) + amount);
        heat.put(id, newHeat);

        double chance = newHeat > 150 ? 1.0 : newHeat > 130 ? 0.75 : newHeat > 115 ? 0.50 : newHeat > 100 ? 0.25 : 0.0;
        if (chance > 0 && random.nextDouble() < chance) {
            double reduce = 50 + random.nextInt(26);
            heat.put(id, Math.max(0.0, newHeat - reduce));
            int lose = 20 + random.nextInt(11);
            points.put(id, Math.max(0, points.getOrDefault(id, 0) - lose));
            player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.02);
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
            player.getWorld().createExplosion(player.getLocation(), 2.0f, false, false, player);
        }
    }

    private void coolPlayer(Player player, double amount) {
        UUID id = player.getUniqueId();
        heat.put(id, Math.max(0.0, heat.getOrDefault(id, 0.0) - amount));
        updateCollectorItem(player);
    }

    private void addPoints(Player player, int amount) {
        points.merge(player.getUniqueId(), amount, Integer::sum);
    }

    private void showBoard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        previousSidebarObjectives.putIfAbsent(uuid, getSidebarObjectiveName(scoreboard));
        String objectiveName = eventSidebarObjectives.computeIfAbsent(uuid, id -> "foxco_" + id.toString().replace("-", "").substring(0, 8));

        Objective existing = scoreboard.getObjective(objectiveName);
        if (existing != null) existing.unregister();

        Objective objective = scoreboard.registerNewObjective(objectiveName, "dummy", LEGACY.deserialize("&5Przegrzanie Rdzenia"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<Map.Entry<UUID, Integer>> top = points.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue(), a.getValue())).limit(5).toList();
        int line = 8;
        for (int i = 0; i < 5; i++) {
            if (i < top.size()) {
                Player p = Bukkit.getPlayer(top.get(i).getKey());
                String name = p != null ? p.getName() : "---";
                objective.getScore(color("&f" + (i + 1) + ". &9" + name + " &7- &e" + top.get(i).getValue() + "pkt")).setScore(line--);
            } else {
                objective.getScore(color("&f" + (i + 1) + ". &9--- &7- &e---pkt")).setScore(line--);
            }
        }
        objective.getScore(color("&bTwóje statystyki &7- &e" + points.getOrDefault(player.getUniqueId(), 0) + "pkt")).setScore(line);
    }

    private void stopEvent() {
        running = false;
        if (tickTask != null) tickTask.cancel();
        if (timerBar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(timerBar));

        List<Map.Entry<UUID, Integer>> top = points.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue(), a.getValue())).limit(5).toList();
        for (int i = 0; i < top.size(); i++) {
            Player p = Bukkit.getPlayer(top.get(i).getKey());
            if (p != null) rewardService.giveRewards(EVENT_KEY, i + 1, p);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            restorePlayerScoreboard(p);
            hideCoreBar(p);
            hideCollectorBar(p);
            removeCollector(p);
        }
        activeCoolingTasks.values().forEach(BukkitTask::cancel);
        activeCoolingTasks.clear();

        viewers.clear();
        previousSidebarObjectives.clear();
        eventSidebarObjectives.clear();
        pasteSchematic(endSchematic, false);
    }

    private void hideCoreBar(Player player) {
        BossBar bar = coreBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    private void hideCollectorBar(Player player) {
        BossBar bar = collectorBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    private void startCoolingProcess(Player player, Block block, int requiredSeconds) {
        UUID id = player.getUniqueId();
        BukkitTask old = activeCoolingTasks.remove(id);
        if (old != null) old.cancel();

        final int[] elapsed = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running || !player.isOnline() || !isCollector(player.getInventory().getItemInMainHand())) {
                cancelCooling(id);
                return;
            }
            if (player.getLocation().distance(block.getLocation().add(0.5, 0.5, 0.5)) > 2.5) {
                cancelCooling(id);
                return;
            }
            if (block.getType() != Material.MAGMA_BLOCK && block.getType() != Material.NETHERRACK) {
                cancelCooling(id);
                return;
            }
            Location eye = player.getEyeLocation();
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            Vector dir = center.toVector().subtract(eye.toVector());
            Location fixed = player.getLocation().clone();
            fixed.setDirection(dir);
            player.teleport(fixed, PlayerTeleportEvent.TeleportCause.PLUGIN);
            elapsed[0] += 1;
            player.sendTitle("", buildCoolingSubtitle(elapsed[0], requiredSeconds), 0, 20, 0);
            if (elapsed[0] >= requiredSeconds) {
                tryCompleteCooling(player, block);
                cancelCooling(id);
            }
        }, 0L, 20L);
        activeCoolingTasks.put(id, task);
    }

    private void cancelCooling(UUID id) {
        BukkitTask task = activeCoolingTasks.remove(id);
        if (task != null) task.cancel();
    }

    private String buildCoolingSubtitle(int elapsed, int total) {
        int totalBars = 20;
        int blue = Math.min(totalBars, (int) Math.round((elapsed / (double) total) * totalBars));
        int red = totalBars - blue;
        return color("&fSchładzanie &7- " + "&c" + "|".repeat(red) + "&b" + "|".repeat(blue));
    }

    private void giveCollectorIfMissing(Player player) {
        if (!running) return;
        if (hasCollector(player)) return;

        ItemStack item = createCollectorItem(player.getUniqueId());
        HashMap<Integer, ItemStack> left = player.getInventory().addItem(item);
        left.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
        heat.putIfAbsent(player.getUniqueId(), 0.0);
    }

    private ItemStack createCollectorItem(UUID playerId) {
        double v = heat.getOrDefault(playerId, 0.0);
        ItemStack item = new ItemStack(Material.HONEYCOMB);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&x&2&D&C&3&D&7&lZ&x&3&A&B&8&C&A&lb&x&4&7&A&E&B&D&li&x&5&4&A&3&B&0&le&x&6&2&9&9&A&2&lr&x&6&F&8&E&9&5&la&x&7&C&8&4&8&8&lc&x&8&9&7&9&7&B&lz &x&A&3&6&4&6&1&lC&x&B&0&5&9&5&4&li&x&B&E&4&F&4&6&le&x&C&B&4&4&3&9&lp&x&D&8&3&A&2&C&lł&x&E&5&2&F&1&F&la"));

        int model = v < 40 ? 450001 : v < 70 ? 650001 : v < 100 ? 850001 : 1050001;
        meta.setCustomModelData(model);
        if (v < 100) {
            meta.setLore(List.of(
                    color("&fPrzytrzymaj na &cprzegrzanych blokach &ePPM,"),
                    color("&faby je &bschłodzić")
            ));
        } else {
            meta.setLore(List.of(
                    color("&fAby schłodzić ten przedmiot,"),
                    color("&fwejdź z nim do &9wody"),
                    color("&falbo klikaj &ePPM &fna śnieg")
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    private void updateCollectorItem(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isCollector(it)) {
                inv.setItem(i, createCollectorItem(player.getUniqueId()));
            }
        }
        if (isCollector(inv.getItemInOffHand())) {
            inv.setItemInOffHand(createCollectorItem(player.getUniqueId()));
        }
    }

    private boolean hasCollector(Player player) {
        if (isCollector(player.getItemOnCursor())) return true;
        for (ItemStack it : player.getInventory().getContents()) {
            if (isCollector(it)) return true;
        }
        return isCollector(player.getInventory().getItemInOffHand());
    }

    private boolean isCollector(ItemStack item) {
        return item != null && item.getType() == Material.HONEYCOMB && item.hasItemMeta() && item.getItemMeta().hasCustomModelData()
                && List.of(450001, 650001, 850001, 1050001).contains(item.getItemMeta().getCustomModelData());
    }

    private void removeCollector(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isCollector(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isCollector(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    private void restorePlayerScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        String eventObjective = eventSidebarObjectives.remove(uuid);
        if (eventObjective != null) {
            Objective o = scoreboard.getObjective(eventObjective);
            if (o != null) o.unregister();
        }

        String prev = previousSidebarObjectives.remove(uuid);
        if (prev == null) scoreboard.clearSlot(DisplaySlot.SIDEBAR);
        else {
            Objective po = scoreboard.getObjective(prev);
            if (po != null) po.setDisplaySlot(DisplaySlot.SIDEBAR);
            else scoreboard.clearSlot(DisplaySlot.SIDEBAR);
        }
        player.performCommand("sternalboard toggle");
        player.performCommand("sternalboard toggle");
    }

    private String getSidebarObjectiveName(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(DisplaySlot.SIDEBAR);
        return objective == null ? null : objective.getName();
    }

    private boolean inRegion(Location location) {
        return location.getWorld() != null && location.getWorld().equals(world) && region.contains(location);
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    private void pasteSchematic(String schematic, boolean ignoreAir) {
        File file = new File(plugin.getDataFolder(), "schematics/" + schematic);
        if (!file.exists()) file = new File("plugins/FastAsyncWorldEdit/schematics/" + schematic);
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null || !file.exists()) {
            plugin.getLogger().warning("Cannot find core-overheat schematic: " + schematic);
            return;
        }
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            BlockVector3 to = BlockVector3.at(pasteLoc.getBlockX(), pasteLoc.getBlockY(), pasteLoc.getBlockZ());
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation operation = new ClipboardHolder(clipboard).createPaste(editSession).to(to).ignoreAirBlocks(ignoreAir).build();
                Operations.complete(operation);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to paste core-overheat schematic: " + exception.getMessage());
        }
    }

    private void reloadConfig() {
        FileConfiguration c = plugin.getConfig();
        String worldName = c.getString("events.core_overheat.world", "world");
        world = Bukkit.getWorld(worldName);
        if (world == null) throw new IllegalStateException("World not found: " + worldName);

        region = new Cuboid(
                Math.min(c.getDouble("events.core_overheat.region.pos1.x"), c.getDouble("events.core_overheat.region.pos2.x")),
                Math.min(c.getDouble("events.core_overheat.region.pos1.y"), c.getDouble("events.core_overheat.region.pos2.y")),
                Math.min(c.getDouble("events.core_overheat.region.pos1.z"), c.getDouble("events.core_overheat.region.pos2.z")),
                Math.max(c.getDouble("events.core_overheat.region.pos1.x"), c.getDouble("events.core_overheat.region.pos2.x")),
                Math.max(c.getDouble("events.core_overheat.region.pos1.y"), c.getDouble("events.core_overheat.region.pos2.y")),
                Math.max(c.getDouble("events.core_overheat.region.pos1.z"), c.getDouble("events.core_overheat.region.pos2.z"))
        );

        pasteLoc = new Location(world,
                c.getDouble("events.core_overheat.schematic-paste.x", -32),
                c.getDouble("events.core_overheat.schematic-paste.y", 131),
                c.getDouble("events.core_overheat.schematic-paste.z", 116));

        startSchematic = c.getString("events.core_overheat.schematics.start", "caves.schem");
        endSchematic = c.getString("events.core_overheat.schematics.end", "caves_clear.schem");
        durationMinutes = c.getInt("events.core_overheat.duration-minutes", 30);
        announcementMinutes = c.getInt("events.core_overheat.announcement-minutes", 15);
    }
}
