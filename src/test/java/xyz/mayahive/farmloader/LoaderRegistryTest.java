package xyz.mayahive.farmloader;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class LoaderRegistryTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private LoaderRecord record(UUID owner) {
        return new LoaderRecord(UUID.randomUUID(), owner, world, -0.5, 200, -16.1, true);
    }

    @Test public void twoPerOwnerEvenWhenDisabledAndUnloaded() {
        LoaderRegistry registry = new LoaderRegistry();
        var first = record(owner);
        assertTrue(registry.register(first, 2));
        registry.toggle(first.stand());
        assertTrue(registry.register(record(owner), 2));
        assertFalse(registry.register(record(owner), 2));
        assertEquals(2, registry.count(owner));
        assertTrue(registry.register(record(UUID.randomUUID()), 2));
    }

    @Test public void anybodyMayToggleButOriginalOwnerKeepsTheSlot() {
        LoaderRegistry registry = new LoaderRegistry();
        var first = record(owner);
        registry.register(first, 2);
        assertFalse(registry.toggle(first.stand()).enabled());
        assertEquals(owner, registry.get(first.stand()).owner());
        assertTrue(registry.toggle(first.stand()).enabled());
        assertEquals(1, registry.count(owner));
    }

    @Test public void dismantlingFreesOriginalOwnersSlotAndCannotDoubleRegister() {
        LoaderRegistry registry = new LoaderRegistry();
        var first = record(owner);
        assertTrue(registry.register(first, 2));
        assertFalse(registry.register(first, 2));
        registry.register(record(owner), 2);
        assertNotNull(registry.remove(first.stand()));
        assertTrue(registry.register(record(owner), 2));
        assertEquals(2, registry.count(owner));
    }

    @Test public void negativeCoordinatesUseFloorRatherThanTruncation() {
        var record = record(owner);
        assertEquals(-1, record.chunkX());
        assertEquals(-2, record.chunkZ());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonfinitePositionCannotBeRestored() {
        new LoaderRecord(UUID.randomUUID(), owner, world, Double.NaN, 70, 1, true);
    }

    @Test public void botIdentityIsStableAndSeparateForEveryStand() {
        UUID stand = UUID.randomUUID();
        assertEquals(VirtualAfkPlayer.idFor(stand), VirtualAfkPlayer.idFor(stand));
        assertNotEquals(stand, VirtualAfkPlayer.idFor(stand));
        assertNotEquals(VirtualAfkPlayer.idFor(stand), VirtualAfkPlayer.idFor(UUID.randomUUID()));
    }
}
