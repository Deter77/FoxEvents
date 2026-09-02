package pl.foxevents.util;

import java.util.List;
import java.util.random.RandomGenerator;

public final class WeightedRandom {

    private WeightedRandom() {
    }

    public static <T extends Weighted> T pick(List<T> entries, RandomGenerator random) {
        int total = entries.stream().mapToInt(Weighted::weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total) + 1;
        for (T entry : entries) {
            roll -= entry.weight();
            if (roll <= 0) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    public interface Weighted {
        int weight();
    }
}
