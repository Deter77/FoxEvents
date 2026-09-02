package pl.foxevents.util;

public final class TimeFormat {

    private TimeFormat() {}

    public static String formatSeconds(int totalSeconds) {
        int min = totalSeconds / 60;
        int sec = totalSeconds % 60;
        if (min <= 0) {
            return sec + "s";
        }
        return min + "min " + sec + "s";
    }
}
