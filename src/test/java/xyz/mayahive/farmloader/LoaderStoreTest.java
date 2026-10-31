package xyz.mayahive.farmloader;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.Assert.*;

public class LoaderStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path file() { return folder.getRoot().toPath().resolve("loaders.yml"); }

    @Test public void restartPreservesOwnerLimitPositionsAndDisabledState() throws Exception {
        LoaderStore store = new LoaderStore(file());
        UUID owner = UUID.randomUUID();
        LoaderRegistry before = new LoaderRegistry();
        var active = new LoaderRecord(UUID.randomUUID(), owner, UUID.randomUUID(), -12.25, 185.5, 420, true);
        var inactive = new LoaderRecord(UUID.randomUUID(), owner, active.world(), 90, 60, 70, false);
        before.register(active, 2);
        before.register(inactive, 2);
        store.save(before);
        LoaderRegistry after = new LoaderStore(file()).load();
        assertEquals(active, after.get(active.stand()));
        assertEquals(inactive, after.get(inactive.stand()));
        assertEquals(2, after.count(owner));
        assertFalse(after.register(new LoaderRecord(UUID.randomUUID(), owner, active.world(), 0, 64, 0, true), 2));
        after.remove(active.stand());
        store.save(after);
        assertEquals(1, store.load().count(owner));
        assertFalse(Files.exists(file().resolveSibling("loaders.yml.tmp")));
    }

    @Test public void missingFileAndExplicitEmptyRegistryAreValid() throws Exception {
        LoaderStore store = new LoaderStore(file());
        assertTrue(store.load().all().isEmpty());
        store.save(new LoaderRegistry());
        assertTrue(store.load().all().isEmpty());
    }

    @Test public void malformedDataDoesNotResetLimitsOrOverwriteFile() throws Exception {
        for (String broken : new String[]{"loaders: broken", "loaders: [one, two]", "wrong: true",
                "loaders:\n  not-a-uuid:\n    owner: invalid\n", "loaders: ["}) {
            Files.writeString(file(), broken);
            try { new LoaderStore(file()).load(); fail("Corrupt data accepted: " + broken); }
            catch (InvalidConfigurationException expected) { assertEquals(broken, Files.readString(file())); }
        }
    }
}
