package pl.foxevents.util;

import org.bukkit.Location;

public record Cuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public static Cuboid fromCorners(Location a, Location b) {
        return new Cuboid(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())
        );
    }

    public boolean contains(Location loc) {
        return loc.getX() >= minX && loc.getX() <= maxX
                && loc.getY() >= minY && loc.getY() <= maxY
                && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
}
