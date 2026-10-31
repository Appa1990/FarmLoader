package xyz.mayahive.farmloader;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Accessed only on the Paper server thread; disabled and unloaded records still count. */
public final class LoaderRegistry {
    private final Map<UUID, LoaderRecord> records = new LinkedHashMap<>();
    public LoaderRecord get(UUID id) { return records.get(id); }
    public Collection<LoaderRecord> all() { return List.copyOf(records.values()); }
    public long count(UUID owner) {
        return records.values().stream().filter(record -> record.owner().equals(owner)).count();
    }
    public boolean register(LoaderRecord record, int limit) {
        if (records.containsKey(record.stand()) || count(record.owner()) >= limit) return false;
        records.put(record.stand(), record);
        return true;
    }
    public void restore(LoaderRecord record) {
        if (records.putIfAbsent(record.stand(), record) != null) {
            throw new IllegalArgumentException("Duplicate loader " + record.stand());
        }
    }
    public LoaderRecord remove(UUID id) { return records.remove(id); }
    public LoaderRecord toggle(UUID id) {
        LoaderRecord old = records.get(id);
        if (old == null) return null;
        LoaderRecord changed = old.enabled(!old.enabled());
        records.put(id, changed);
        return changed;
    }
}
