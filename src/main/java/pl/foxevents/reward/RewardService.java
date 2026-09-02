package pl.foxevents.reward;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.foxevents.config.PluginConfig;
import pl.foxevents.util.WeightedRandom;

import java.util.*;

public class RewardService {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final Random random = new Random();

    public RewardService(JavaPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
    }

    public void giveRewards(String eventKey, int place, Player player) {
        ConfigurationSection eventRewards = pluginConfig.getEventRewardsSection(eventKey);
        if (eventRewards == null) {
            return;
        }
        ConfigurationSection placeSection = eventRewards.getConfigurationSection(String.valueOf(place));
        if (placeSection == null) {
            return;
        }

        int rolls = placeSection.getInt("rolls", 0);
        String poolName = placeSection.getString("pool", "");
        List<RewardEntry> pool = readPool(poolName);

        for (int i = 0; i < rolls; i++) {
            RewardEntry picked = WeightedRandom.pick(pool, random::nextInt);
            if (picked == null) {
                continue;
            }
            String command = picked.command().replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private List<RewardEntry> readPool(String poolName) {
        ConfigurationSection rewardPools = pluginConfig.getRewardPoolsSection();
        if (rewardPools == null) {
            return List.of();
        }
        ConfigurationSection pool = rewardPools.getConfigurationSection(poolName + ".items");
        if (pool == null) {
            // .items as list
            List<Map<?, ?>> list = rewardPools.getMapList(poolName + ".items");
            List<RewardEntry> out = new ArrayList<>();
            for (Map<?, ?> map : list) {
                String cmd = Objects.toString(map.get("command"), "");
                int weight = Integer.parseInt(Objects.toString(map.get("weight"), "0"));
                out.add(new RewardEntry(cmd, weight));
            }
            return out;
        }
        return List.of();
    }

    private record RewardEntry(String command, int weight) implements WeightedRandom.Weighted {}
}
