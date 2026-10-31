package xyz.mayahive.farmloader;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class LoaderStore {
    private final Path file;
    public LoaderStore(Path file) { this.file = file; }

    public LoaderRegistry load() throws IOException, InvalidConfigurationException {
        LoaderRegistry registry = new LoaderRegistry();
        if (!Files.exists(file)) return registry;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("loaders");
        if (root == null) {
            if (!yaml.getKeys(false).isEmpty()) throw new InvalidConfigurationException("Missing or invalid loaders section");
            return registry;
        }
        try {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) throw new IllegalArgumentException("Missing record " + id);
                for (String coordinate : new String[]{"x", "y", "z"}) {
                    if (!section.isDouble(coordinate) && !section.isInt(coordinate)) {
                        throw new IllegalArgumentException("Missing coordinate " + coordinate);
                    }
                }
                if (!section.isBoolean("enabled")) throw new IllegalArgumentException("Missing enabled flag");
                registry.restore(new LoaderRecord(UUID.fromString(id),
                        UUID.fromString(section.getString("owner", "")),
                        UUID.fromString(section.getString("world", "")),
                        section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                        section.getBoolean("enabled")));
            }
        } catch (IllegalArgumentException error) {
            throw new InvalidConfigurationException("Invalid loader data; refusing to reset limits", error);
        }
        return registry;
    }

    public void save(LoaderRegistry registry) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("loaders");
        for (LoaderRecord record : registry.all()) {
            String path = "loaders." + record.stand() + ".";
            yaml.set(path + "owner", record.owner().toString());
            yaml.set(path + "world", record.world().toString());
            yaml.set(path + "x", record.x());
            yaml.set(path + "y", record.y());
            yaml.set(path + "z", record.z());
            yaml.set(path + "enabled", record.enabled());
        }
        Files.createDirectories(file.getParent());
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(pending, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
