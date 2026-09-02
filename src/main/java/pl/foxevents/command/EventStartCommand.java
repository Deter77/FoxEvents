package pl.foxevents.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import pl.foxevents.config.PluginConfig;
import pl.foxevents.event.ArenaGemHuntEvent;
import pl.foxevents.event.CoreOverheatEvent;
import pl.foxevents.event.CastleDominationEvent;
import pl.foxevents.event.ParkourCloudEvent;
import pl.foxevents.util.WeightedRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class EventStartCommand implements CommandExecutor, TabCompleter {

    private final PluginConfig pluginConfig;
    private final ArenaGemHuntEvent arenaGemHuntEvent;
    private final ParkourCloudEvent parkourCloudEvent;
    private final CoreOverheatEvent coreOverheatEvent;
    private final CastleDominationEvent castleDominationEvent;
    private final Random random = new Random();

    public EventStartCommand(PluginConfig pluginConfig, ArenaGemHuntEvent arenaGemHuntEvent, ParkourCloudEvent parkourCloudEvent, CoreOverheatEvent coreOverheatEvent, CastleDominationEvent castleDominationEvent) {
        this.pluginConfig = pluginConfig;
        this.arenaGemHuntEvent = arenaGemHuntEvent;
        this.parkourCloudEvent = parkourCloudEvent;
        this.coreOverheatEvent = coreOverheatEvent;
        this.castleDominationEvent = castleDominationEvent;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Użycie: /eventstart <random|event>");
            return true;
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        String eventKey;

        if (input.equals("random")) {
            eventKey = rollRandomEvent();
            if (eventKey == null) {
                sender.sendMessage(ChatColor.RED + "Brak eventów do losowania w configu (event-types).");
                return true;
            }
        } else {
            eventKey = input;
        }

        if (!isKnownEvent(eventKey)) {
            sender.sendMessage(ChatColor.RED + "Nieznany event: " + eventKey);
            return true;
        }

        if (eventKey.equals("arena_pvp")) {
            boolean started = arenaGemHuntEvent.startFromCommand();
            if (!started) {
                sender.sendMessage(ChatColor.RED + "Ten event już trwa.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Uruchomiono event: " + eventKey);
            return true;
        }
        if (eventKey.equals("parkour")) {
            boolean started = parkourCloudEvent.startFromCommand();
            if (!started) {
                sender.sendMessage(ChatColor.RED + "Ten event już trwa.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Uruchomiono event: " + eventKey);
            return true;
        }
        if (eventKey.equals("core_overheat")) {
            boolean started = coreOverheatEvent.startFromCommand();
            if (!started) {
                sender.sendMessage(ChatColor.RED + "Ten event już trwa.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Uruchomiono event: " + eventKey);
            return true;
        }
        if (eventKey.equals("castle_domination")) {
            boolean started = castleDominationEvent.startFromCommand();
            if (!started) {
                sender.sendMessage(ChatColor.RED + "Ten event już trwa.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Uruchomiono event: " + eventKey);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Ten typ eventu nie ma jeszcze implementacji: " + eventKey);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        out.add("random");
        out.addAll(pluginConfig.getWeightedEventTypes().stream().map(PluginConfig.WeightedEventType::key).toList());
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }

    private String rollRandomEvent() {
        List<WeightedEvent> list = pluginConfig.getWeightedEventTypes().stream()
                .map(w -> new WeightedEvent(w.key(), Math.max(0, w.weight())))
                .toList();
        WeightedEvent selected = WeightedRandom.pick(list, random::nextInt);
        return selected == null ? null : selected.key();
    }

    private boolean isKnownEvent(String eventKey) {
        return pluginConfig.getWeightedEventTypes().stream()
                .anyMatch(type -> type.key().equalsIgnoreCase(eventKey));
    }

    private record WeightedEvent(String key, int weight) implements WeightedRandom.Weighted {}
}
