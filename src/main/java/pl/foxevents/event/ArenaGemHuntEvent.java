package pl.foxevents.event;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import pl.foxevents.config.PluginConfig;
import pl.foxevents.reward.RewardService;
import pl.foxevents.util.Cuboid;
import pl.foxevents.util.TimeFormat;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class ArenaGemHuntEvent implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String EVENT_KEY = "arena_pvp";
    private final NamespacedKey gemKey;

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final RewardService rewardService;

    private final Map<UUID, Integer> holdTimeSeconds = new HashMap<>();
    private final Set<UUID> arenaViewers = new HashSet<>();
    private final Map<UUID, String> previousSidebarObjectives = new HashMap<>();
    private final Map<UUID, String> eventSidebarObjectives = new HashMap<>();

    private PluginConfig.ArenaConfig arena;
    private Cuboid cuboid;
    private ItemStack gemItem;

    private boolean running;
    private UUID carrier;
    private BukkitTask runTick;
    private int secondsLeft;
    private BossBar countdownBar;
    private BossBar announcementBar;
    private BukkitTask announcementTask;

    public ArenaGemHuntEvent(JavaPlugin plugin, PluginConfig pluginConfig, RewardService rewardService) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.rewardService = rewardService;
        this.gemKey = new NamespacedKey(plugin, "arena_gem");
        reloadArena();
    }

    public void scheduleRun(LocalDateTime eventTime) {
        ZoneId zone = pluginConfig.getTimezone();
        LocalDateTime now = LocalDateTime.now(zone);
        long untilStart = Math.max(0, Duration.between(now, eventTime).getSeconds());

        long announceWindow = arena.announcementMinutes() * 60L;
        long announceDelay = untilStart - announceWindow;
        if (announceDelay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> startAnnouncement((int) announceWindow), announceDelay * 20L);
        } else if (untilStart > 0) {
            startAnnouncement((int) untilStart);
        }

        long prepasteWindow = arena.prepasteMinutesBeforeStart() * 60L;
        long prepasteDelay = untilStart - prepasteWindow;
        if (prepasteDelay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> pasteSchematic(arena.startSchematic()), prepasteDelay * 20L);
        } else if (untilStart > 0 && untilStart <= prepasteWindow) {
            pasteSchematic(arena.startSchematic());
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, untilStart * 20L);
    }

    public boolean startFromCommand() {
        if (running) {
            return false;
        }
        int announceSeconds = arena.announcementMinutes() * 60;
        if (announceSeconds <= 0) {
            startEventNow();
            return true;
        }
        int prepasteSeconds = arena.prepasteMinutesBeforeStart() * 60;
        int prepasteDelay = announceSeconds - prepasteSeconds;
        if (prepasteDelay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> pasteSchematic(arena.startSchematic()), prepasteDelay * 20L);
        } else {
            pasteSchematic(arena.startSchematic());
        }
        startAnnouncement(announceSeconds);
        Bukkit.getScheduler().runTaskLater(plugin, this::startEventNow, announceSeconds * 20L);
        return true;
    }

    private void startAnnouncement(int duration) {
        clearAllGemInstances();

        if (announcementTask != null) {
            announcementTask.cancel();
        }
        if (announcementBar != null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(announcementBar));
        }

        announcementBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(announcementBar));

        announcementTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = duration;

            @Override
            public void run() {
                if (left <= 0) {
                    Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(announcementBar));
                    announcementTask.cancel();
                    announcementTask = null;
                    announcementBar = null;
                    return;
                }
                String text = "&fEvent &4Pogoń za klejnotem &fza &e" + TimeFormat.formatSeconds(left) + " &7(Arena PvP)";
                announcementBar.name(LEGACY.deserialize(text));
                announcementBar.progress(Math.max(0.01f, Math.min(1f, (float) left / duration)));
                Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(announcementBar));
                left--;
            }
        }, 0L, 20L);
    }

    private void startEventNow() {
        if (running) {
            return;
        }
        reloadArena();
        running = true;
        holdTimeSeconds.clear();
        carrier = null;
        secondsLeft = arena.durationMinutes() * 60;

        clearAllGemInstances();
        spawnGem();
        pushPlayersFromCenter();
        countdownBar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        runTick = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateStatePerSecond();
            secondsLeft--;
            if (secondsLeft <= 0) {
                stopEvent();
            }
        }, 20L, 20L);
    }

    private void updateStatePerSecond() {
        if (carrier != null) {
            holdTimeSeconds.merge(carrier, 1, Integer::sum);
            Player player = Bukkit.getPlayer(carrier);
            if (player != null && player.isOnline()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 40, 0, true, false));
                removeElytraFromCarrier(player);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean inside = isInArena(player.getLocation());
            if (inside) {
                player.showBossBar(countdownBar);
                countdownBar.name(LEGACY.deserialize("&fEvent kończy się za &e" + TimeFormat.formatSeconds(secondsLeft)));
                countdownBar.progress(Math.max(0.01f, (float) secondsLeft / (arena.durationMinutes() * 60f)));
                showBoard(player);
                arenaViewers.add(player.getUniqueId());
            } else {
                player.hideBossBar(countdownBar);
                if (arenaViewers.remove(player.getUniqueId())) {
                    restorePlayerScoreboard(player);
                }
            }
        }
    }

    private void stopEvent() {
        running = false;
        if (runTick != null) {
            runTick.cancel();
        }
        if (countdownBar != null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(countdownBar));
        }
        clearCarrier();
        clearAllGemInstances();
        pasteSchematic(arena.endSchematic());
        rewardTopPlayers();

        for (UUID viewer : arenaViewers) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) {
                restorePlayerScoreboard(player);
            }
        }
        arenaViewers.clear();
        previousSidebarObjectives.clear();
        eventSidebarObjectives.clear();
    }

    private void rewardTopPlayers() {
        List<Map.Entry<UUID, Integer>> top = holdTimeSeconds.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .toList();

        for (int i = 0; i < top.size(); i++) {
            Player p = Bukkit.getPlayer(top.get(i).getKey());
            if (p != null) {
                rewardService.giveRewards(EVENT_KEY, i + 1, p);
            }
        }
    }

    private void showBoard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();

        previousSidebarObjectives.putIfAbsent(uuid, getSidebarObjectiveName(scoreboard));
        String objectiveName = eventSidebarObjectives.computeIfAbsent(uuid, id -> "foxev_" + id.toString().replace("-", "").substring(0, 8));

        Objective existing = scoreboard.getObjective(objectiveName);
        if (existing != null) {
            existing.unregister();
        }

        Objective objective = scoreboard.registerNewObjective(objectiveName, "dummy", LEGACY.deserialize("&4&lPogoń za klejnotem"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<Map.Entry<UUID, Integer>> top = holdTimeSeconds.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .toList();

        int line = 8;
        for (int i = 0; i < 5; i++) {
            if (i < top.size()) {
                Player p = Bukkit.getPlayer(top.get(i).getKey());
                String name = p != null ? p.getName() : "-";
                objective.getScore(color("&f" + (i + 1) + ". &9" + name + " &7- &e" + TimeFormat.formatSeconds(top.get(i).getValue()))).setScore(line--);
            } else {
                objective.getScore(color("&f" + (i + 1) + ". &9- &7- &e0s")).setScore(line--);
            }
        }

        String carrierName = carrier == null ? "->" : Objects.requireNonNullElse(Bukkit.getPlayer(carrier), player).getName();
        objective.getScore(color("&6Klejnot Walki &ftrzyma &9" + carrierName)).setScore(line--);
        objective.getScore(color("&bTwój wynik &7- &e" + TimeFormat.formatSeconds(holdTimeSeconds.getOrDefault(player.getUniqueId(), 0)))).setScore(line);
    }

    private void pushPlayersFromCenter() {
        for (Player player : arena.world().getPlayers()) {
            if (player.getLocation().distance(arena.pushCenter()) > arena.pushRadius()) {
                continue;
            }
            Vector dir = player.getLocation().toVector().subtract(arena.pushCenter().toVector());
            if (dir.lengthSquared() < 0.1) {
                dir = new Vector(1, 0, 0);
            }
            dir.setY(0).normalize().multiply(1.35);
            dir.setY(0.15);
            player.setVelocity(dir);
        }
    }

    private void spawnGem() {
        ItemStack stack = gemItem.clone();
        Item dropped = arena.world().dropItemNaturally(arena.gemSpawn(), stack);
        dropped.setUnlimitedLifetime(true);
        dropped.setCanMobPickup(false);
        dropped.getPersistentDataContainer().set(gemKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void setCarrier(Player player) {
        clearCarrier();
        carrier = player.getUniqueId();
        removeElytraFromCarrier(player);

        PlayerInventory inv = player.getInventory();
        ItemStack previous = inv.getItemInOffHand();
        if (previous != null && previous.getType() != Material.AIR) {
            HashMap<Integer, ItemStack> left = inv.addItem(previous);
            left.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        inv.setItemInOffHand(gemItem.clone());
        player.updateInventory();
    }

    private void clearCarrier() {
        if (carrier == null) {
            return;
        }
        Player player = Bukkit.getPlayer(carrier);
        if (player != null) {
            removeGemFromPlayer(player);
            player.removePotionEffect(PotionEffectType.GLOWING);
            player.removePotionEffect(PotionEffectType.STRENGTH);
        }
        carrier = null;
    }

    private boolean isGem(ItemStack item) {
        if (item == null || item.getType() != Material.HONEYCOMB || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.hasCustomModelData() && meta.getCustomModelData() == 250001;
    }

    private boolean isInArena(Location loc) {
        return loc.getWorld() != null && loc.getWorld().equals(arena.world()) && cuboid.contains(loc);
    }

    private void respawnGem() {
        clearCarrier();
        clearAllGemInstances();
        spawnGem();
    }

    private void pasteSchematic(String schematic) {
        File file = new File(plugin.getDataFolder(), "schematics/" + schematic);
        if (!file.exists()) {
            file = new File("plugins/FastAsyncWorldEdit/schematics/" + schematic);
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null || !file.exists()) {
            plugin.getLogger().warning("Cannot find schematic: " + schematic + ". Put it in plugins/FastAsyncWorldEdit/schematics or plugins/FoxEvents/schematics.");
            return;
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(arena.world());
            BlockVector3 to = BlockVector3.at(
                    arena.schematicPaste().getBlockX(),
                    arena.schematicPaste().getBlockY(),
                    arena.schematicPaste().getBlockZ()
            );
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(to)
                        .ignoreAirBlocks(false)
                        .build();
                Operations.complete(operation);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to paste schematic " + schematic + ": " + exception.getMessage());
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void reloadArena() {
        arena = pluginConfig.getArenaConfig();
        cuboid = Cuboid.fromCorners(arena.regionPos1(), arena.regionPos2());
        gemItem = new ItemStack(Material.HONEYCOMB);
        ItemMeta meta = gemItem.getItemMeta();
        meta.setDisplayName(color("&x&C&3&1&B&1&4&lK&x&C&7&1&D&1&5&ll&x&C&A&1&F&1&6&le&x&C&E&2&1&1&8&lj&x&D&2&2&3&1&9&ln&x&D&5&2&5&1&A&lo&x&D&9&2&7&1&B&lt &x&E&0&2&B&1&D&lW&x&E&4&2&D&1&F&la&x&E&8&2&F&2&0&ll&x&E&B&3&1&2&1&lk&x&E&F&3&3&2&2&li"));
        meta.setCustomModelData(250001);
        gemItem.setItemMeta(meta);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!running || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (isGem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
            setCarrier(player);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (running && event.getPlayer().getUniqueId().equals(carrier) && isGem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (running && event.getPlayer().getUniqueId().equals(carrier)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!running || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.getUniqueId().equals(carrier)) {
            return;
        }

        if (event.getClick() == ClickType.SWAP_OFFHAND || isGem(event.getCurrentItem()) || isGem(event.getCursor())) {
            event.setCancelled(true);
        }

        if (event.getSlotType() == InventoryType.SlotType.ARMOR
                && event.getSlot() == 38
                && event.getCursor() != null
                && event.getCursor().getType() == Material.ELYTRA) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!running || !event.getPlayer().getUniqueId().equals(carrier) || event.getTo() == null) {
            return;
        }
        if (!isInArena(event.getTo())) {
            Vector pushBack = arena.pushCenter().toVector().subtract(event.getPlayer().getLocation().toVector());
            if (pushBack.lengthSquared() > 0.01) {
                event.getPlayer().setVelocity(pushBack.normalize().multiply(0.7).setY(0.12));
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!running || !event.getPlayer().getUniqueId().equals(carrier) || event.getItem() == null) {
            return;
        }
        Material type = event.getItem().getType();
        if (type == Material.ENDER_PEARL || type == Material.WIND_CHARGE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!running || carrier == null || !event.getEntity().getUniqueId().equals(carrier)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (killer != null && killer.isOnline()) {
                setCarrier(killer);
            } else {
                respawnGem();
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        previousSidebarObjectives.remove(event.getPlayer().getUniqueId());
        eventSidebarObjectives.remove(event.getPlayer().getUniqueId());
        if (running && event.getPlayer().getUniqueId().equals(carrier)) {
            removeGemFromPlayer(event.getPlayer());
            Bukkit.getScheduler().runTask(plugin, this::respawnGem);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        removeGemFromPlayer(event.getPlayer());
        if (announcementBar != null) {
            event.getPlayer().showBossBar(announcementBar);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        // Hook extension point for future event-specific combat modifiers.
    }

    private void clearAllGemInstances() {
        for (Item item : arena.world().getEntitiesByClass(Item.class)) {
            if (isGem(item.getItemStack())) {
                item.remove();
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeGemFromPlayer(player);
        }
    }

    private void restorePlayerScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = player.getScoreboard();
        String eventObjectiveName = eventSidebarObjectives.remove(uuid);
        if (eventObjectiveName != null) {
            Objective eventObjective = scoreboard.getObjective(eventObjectiveName);
            if (eventObjective != null) {
                eventObjective.unregister();
            }
        }
        String previousObjectiveName = previousSidebarObjectives.remove(uuid);
        if (previousObjectiveName == null) {
            scoreboard.clearSlot(DisplaySlot.SIDEBAR);
            refreshSternalBoard(player);
            return;
        }

        Objective previousObjective = scoreboard.getObjective(previousObjectiveName);
        if (previousObjective != null) {
            previousObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            scoreboard.clearSlot(DisplaySlot.SIDEBAR);
        }

        refreshSternalBoard(player);
    }

    private String getSidebarObjectiveName(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(DisplaySlot.SIDEBAR);
        return objective == null ? null : objective.getName();
    }

    private void refreshSternalBoard(Player player) {
        player.performCommand("sternalboard toggle");
        player.performCommand("sternalboard toggle");
    }

    private void removeGemFromPlayer(Player player) {
        PlayerInventory inv = player.getInventory();
        if (isGem(inv.getItemInOffHand())) {
            inv.setItemInOffHand(null);
        }
        ItemStack chest = inv.getChestplate();
        if (chest != null && isGem(chest)) {
            inv.setChestplate(null);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (isGem(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
        player.updateInventory();
    }

    private void removeElytraFromCarrier(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack chest = inv.getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            return;
        }
        inv.setChestplate(null);
        HashMap<Integer, ItemStack> left = inv.addItem(chest);
        left.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }
}
