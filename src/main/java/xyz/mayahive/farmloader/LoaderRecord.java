package xyz.mayahive.farmloader;

import java.util.UUID;

public record LoaderRecord(UUID stand, UUID owner, UUID world,
                           double x, double y, double z, boolean enabled) {
    public LoaderRecord {
        if (stand == null || owner == null || world == null
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Invalid loader record");
        }
    }
    public LoaderRecord enabled(boolean value) {
        return new LoaderRecord(stand, owner, world, x, y, z, value);
    }
    public int chunkX() { return ((int) Math.floor(x)) >> 4; }
    public int chunkZ() { return ((int) Math.floor(z)) >> 4; }
}
