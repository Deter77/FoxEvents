package pl.foxevents;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import pl.foxevents.command.EventStartCommand;
import pl.foxevents.config.PluginConfig;
import pl.foxevents.event.ArenaGemHuntEvent;
import pl.foxevents.event.CoreOverheatEvent;
import pl.foxevents.event.CastleDominationEvent;
import pl.foxevents.event.ParkourCloudEvent;
import pl.foxevents.reward.RewardService;
import pl.foxevents.schedule.EventSchedulerService;

public final class FoxEventsPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private RewardService rewardService;
    private EventSchedulerService eventSchedulerService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);
        this.rewardService = new RewardService(this, pluginConfig);

        ArenaGemHuntEvent arenaGemHuntEvent = new ArenaGemHuntEvent(this, pluginConfig, rewardService);
        ParkourCloudEvent parkourCloudEvent = new ParkourCloudEvent(this, rewardService);
        CoreOverheatEvent coreOverheatEvent = new CoreOverheatEvent(this, rewardService);
        CastleDominationEvent castleDominationEvent = new CastleDominationEvent(this, rewardService);
        this.eventSchedulerService = new EventSchedulerService(this, pluginConfig, arenaGemHuntEvent, parkourCloudEvent, coreOverheatEvent, castleDominationEvent);
        EventStartCommand eventStartCommand = new EventStartCommand(pluginConfig, arenaGemHuntEvent, parkourCloudEvent, coreOverheatEvent, castleDominationEvent);

        Bukkit.getPluginManager().registerEvents(arenaGemHuntEvent, this);
        Bukkit.getPluginManager().registerEvents(parkourCloudEvent, this);
        Bukkit.getPluginManager().registerEvents(coreOverheatEvent, this);
        Bukkit.getPluginManager().registerEvents(castleDominationEvent, this);
        if (getCommand("eventstart") != null) {
            getCommand("eventstart").setExecutor(eventStartCommand);
            getCommand("eventstart").setTabCompleter(eventStartCommand);
        }
        eventSchedulerService.start();
        getLogger().info("FoxEvents enabled.");
    }

    @Override
    public void onDisable() {
        if (eventSchedulerService != null) {
            eventSchedulerService.stop();
        }
    }
}
