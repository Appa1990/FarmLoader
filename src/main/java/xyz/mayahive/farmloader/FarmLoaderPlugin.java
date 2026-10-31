package xyz.mayahive.farmloader;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/** Paper-only first release. Registry, entity and NMS work stays on the main thread. */
public final class FarmLoaderPlugin extends JavaPlugin implements Listener {
    private LoaderRegistry registry;
    private LoaderStore store;
    private NamespacedKey markerKey;
    private final Map<UUID, VirtualAfkPlayer> running = new HashMap<>();
    private final Map<UUID, Object> loading = new HashMap<>();
    private final Map<UUID, ChunkKey> ticketOwners = new HashMap<>();
    private final Map<ChunkKey, Integer> ticketCounts = new HashMap<>();
    private final Map<UUID, Integer> clicks = new HashMap<>();
    private BukkitTask heartbeat;
    private int ticks;
    private int limit;
    private int radius;
    private boolean runWhenEmpty;
    private String tagName;
    private boolean failed;

    private record ChunkKey(UUID world, int x, int z) {}

    @Override public void onEnable() {
        if (!Bukkit.getMinecraftVersion().equals("26.3")) {
            getLogger().severe("Diese Version ist nur fuer Paper 26.3 gebaut.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        limit = Math.max(1, getConfig().getInt("max-per-player", 2));
        radius = Math.clamp(getConfig().getInt("chunk-radius", 8), 2, 16);
        runWhenEmpty = getConfig().getBoolean("run-when-empty", true);
        tagName = getConfig().getString("name-tag", "FarmLoader").trim();
        markerKey = new NamespacedKey(this, "loader");
        store = new LoaderStore(getDataFolder().toPath().resolve("loaders.yml"));
        try { registry = store.load(); }
        catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Loader-Daten konnten nicht geladen werden; Datei bleibt unveraendert.", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        heartbeat = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
        getLogger().info("FarmLoader 0.1.0: Paper-26.3-Prototyp; " + registry.all().size() + " registrierte Loader.");
    }

    private boolean allowRunning() {
        return runWhenEmpty || Bukkit.getOnlinePlayers().stream().anyMatch(player -> !isVirtual(player.getUniqueId()));
    }
    private boolean isVirtual(UUID player) {
        return running.values().stream().anyMatch(bot -> bot.id().equals(player));
    }

    private void tick() {
        if (failed) return;
        for (UUID id : Set.copyOf(running.keySet())) {
            try { running.get(id).tick(); }
            catch (RuntimeException | LinkageError error) { runtimeFailure(id, error); }
        }
        if (ticks++ % 20 != 0) return;
        boolean allowed = allowRunning();
        for (LoaderRecord record : registry.all()) {
            if (failed || !isEnabled()) return;
            if (!record.enabled() || !allowed) {
                stop(record.stand());
                refreshLoadedStand(record);
                continue;
            }
            if (!running.containsKey(record.stand()) && !loading.containsKey(record.stand())) start(record);
            else if (running.containsKey(record.stand())) {
                Entity entity = Bukkit.getEntity(record.stand());
                if (!(entity instanceof ArmorStand stand) || !stand.isValid() || !hasPumpkin(stand)) {
                    unregister(record.stand());
                } else {
                    Location original = location(record);
                    if (!stand.getWorld().getUID().equals(record.world())
                            || stand.getLocation().distanceSquared(original) > 0.01) stand.teleport(original);
                    display(stand, record);
                }
            }
        }
    }

    private Location location(LoaderRecord record) {
        return new Location(Bukkit.getWorld(record.world()), record.x(), record.y(), record.z());
    }

    private void start(LoaderRecord record) {
        World world = Bukkit.getWorld(record.world());
        if (world == null || loading.containsKey(record.stand()) || running.containsKey(record.stand())) return;
        Object token = new Object();
        loading.put(record.stand(), token);
        // Only load the marker here. Paper's player chunk loader handles the AFK radius.
        world.getChunkAtAsync(record.chunkX(), record.chunkZ(), false).whenComplete((chunk, error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                if (loading.get(record.stand()) != token) return;
                LoaderRecord current = registry.get(record.stand());
                if (current == null || !current.enabled() || !allowRunning()
                        || !world.equals(Bukkit.getWorld(record.world()))) { stop(record.stand()); return; }
                if (error != null || chunk == null) {
                    runtimeFailure(record.stand(), error == null ? new IllegalStateException("Marker-Chunk fehlt") : error);
                    return;
                }
                try {
                    acquireTicket(current);
                    finishStart(current.stand(), token, 10);
                } catch (RuntimeException | LinkageError failure) { runtimeFailure(current.stand(), failure); }
            });
        });
    }

    private void finishStart(UUID id, Object token, int remainingAttempts) {
        if (failed || !isEnabled() || loading.get(id) != token) return;
        LoaderRecord record = registry.get(id);
        if (record == null || !record.enabled() || !allowRunning()) { stop(id); return; }
        Entity entity = Bukkit.getEntity(id);
        // FULL chunk completion can precede entity loading. Do not delete valid records on restart.
        if (entity == null && remainingAttempts > 0) {
            Bukkit.getScheduler().runTaskLater(this, () -> finishStart(id, token, remainingAttempts - 1), 20);
            return;
        }
        if (!(entity instanceof ArmorStand stand) || !stand.isValid() || !hasPumpkin(stand)) {
            unregister(id);
            return;
        }
        mark(stand, record);
        try {
            VirtualAfkPlayer bot = new VirtualAfkPlayer(this, record, radius);
            running.put(id, bot);
            loading.remove(id);
            display(stand, record);
        } catch (RuntimeException | LinkageError failure) { runtimeFailure(id, failure); }
    }

    private void acquireTicket(LoaderRecord record) {
        ChunkKey chunk = new ChunkKey(record.world(), record.chunkX(), record.chunkZ());
        if (ticketOwners.putIfAbsent(record.stand(), chunk) != null) return;
        if (ticketCounts.merge(chunk, 1, Integer::sum) == 1) {
            Bukkit.getWorld(chunk.world()).addPluginChunkTicket(chunk.x(), chunk.z(), this);
        }
    }

    private void stop(UUID id) {
        loading.remove(id);
        VirtualAfkPlayer bot = running.remove(id);
        if (bot != null) {
            try { bot.close(); }
            catch (RuntimeException | LinkageError error) { getLogger().log(Level.SEVERE, "AFK-Spieler konnte nicht entfernt werden", error); }
        }
        ChunkKey chunk = ticketOwners.remove(id);
        if (chunk != null) {
            int count = ticketCounts.getOrDefault(chunk, 1) - 1;
            if (count <= 0) {
                ticketCounts.remove(chunk);
                World world = Bukkit.getWorld(chunk.world());
                if (world != null) world.removePluginChunkTicket(chunk.x(), chunk.z(), this);
            } else ticketCounts.put(chunk, count);
        }
    }

    private void runtimeFailure(UUID id, Throwable error) {
        getLogger().log(Level.SEVERE, "Loader " + id + " wurde wegen eines Fehlers ausgeschaltet", error);
        stop(id);
        LoaderRecord record = registry.get(id);
        if (record != null && record.enabled()) {
            registry.toggle(id);
            if (!persist()) return;
            refreshLoadedStand(registry.get(id));
        }
    }

    private void unregister(UUID id) {
        stop(id);
        LoaderRecord old = registry.remove(id);
        if (old == null) return;
        Entity entity = Bukkit.getEntity(id);
        if (entity instanceof ArmorStand stand) {
            stand.getPersistentDataContainer().remove(markerKey);
            stand.customName(null);
            stand.setCustomNameVisible(false);
            stand.setGravity(true);
        }
        persist();
    }

    private boolean persist() {
        try { store.save(registry); return true; }
        catch (IOException failure) {
            failed = true;
            getLogger().log(Level.SEVERE, "Speichern fehlgeschlagen. FarmLoader wird gestoppt.", failure);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private boolean hasPumpkin(ArmorStand stand) {
        ItemStack helmet = stand.getEquipment().getHelmet();
        return helmet != null && helmet.getType() == Material.CARVED_PUMPKIN;
    }
    private void mark(ArmorStand stand, LoaderRecord record) {
        stand.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, record.stand().toString());
        stand.setGravity(false);
        display(stand, record);
    }
    private void display(ArmorStand stand, LoaderRecord record) {
        String state = !record.enabled() ? "AUS" : running.containsKey(record.stand()) ? "AN" : "WARTET";
        NamedTextColor color = !record.enabled() ? NamedTextColor.RED
                : running.containsKey(record.stand()) ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
        stand.customName(Component.text("FarmLoader [" + state + "]", color));
        stand.setCustomNameVisible(true);
    }
    private void refreshLoadedStand(LoaderRecord record) {
        Entity entity = Bukkit.getEntity(record.stand());
        if (entity instanceof ArmorStand stand) mark(stand, record);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) { interact(event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) { interact(event); }

    private void interact(PlayerInteractEntityEvent event) {
        if (failed || event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ArmorStand stand)) return;
        Player player = event.getPlayer();
        LoaderRecord existing = registry.get(stand.getUniqueId());
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean nameTag = hand.getType() == Material.NAME_TAG && hand.hasItemMeta()
                && hand.getItemMeta().hasDisplayName()
                && PlainTextComponentSerializer.plainText().serialize(hand.getItemMeta().displayName()).trim().equalsIgnoreCase(tagName);
        if (existing == null && (!nameTag || !hasPumpkin(stand))) return;
        event.setCancelled(true);
        int now = Bukkit.getCurrentTick();
        Integer last = clicks.put(player.getUniqueId(), now);
        if (last != null && now - last < 5) return;
        if (existing != null) {
            LoaderRecord changed = registry.toggle(existing.stand());
            if (!persist()) return;
            if (changed.enabled() && allowRunning()) start(changed); else stop(changed.stand());
            display(stand, changed);
            player.sendMessage(Component.text(changed.enabled() ? "FarmLoader eingeschaltet (startet)." : "FarmLoader ausgeschaltet.", NamedTextColor.YELLOW));
            return;
        }
        Location point = stand.getLocation();
        LoaderRecord record = new LoaderRecord(stand.getUniqueId(), player.getUniqueId(), stand.getWorld().getUID(),
                point.getX(), point.getY(), point.getZ(), true);
        if (!registry.register(record, limit)) {
            player.sendMessage(Component.text("Du hast bereits " + limit + " FarmLoader. Ausgeschaltete zaehlen mit.", NamedTextColor.RED));
            return;
        }
        if (!persist()) { registry.remove(record.stand()); return; }
        if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        mark(stand, record);
        if (allowRunning()) start(record);
        player.sendMessage(Component.text("FarmLoader registriert (" + registry.count(player.getUniqueId()) + "/" + limit
                + "). Rechtsklick: umschalten. Schleichen + Linksklick: abbauen.", NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (registry.get(event.getRightClicked().getUniqueId()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand) || registry.get(stand.getUniqueId()) == null) return;
        event.setCancelled(true);
        if (!(event instanceof EntityDamageByEntityEvent byEntity)
                || !(byEntity.getDamager() instanceof Player player) || !player.isSneaking()) return;
        unregister(stand.getUniqueId());
        if (failed) return;
        Location point = stand.getLocation();
        for (ItemStack item : stand.getEquipment().getArmorContents()) drop(point, item);
        drop(point, stand.getEquipment().getItemInMainHand());
        drop(point, stand.getEquipment().getItemInOffHand());
        drop(point, new ItemStack(Material.ARMOR_STAND));
        ItemStack tag = new ItemStack(Material.NAME_TAG);
        tag.editMeta(meta -> meta.displayName(Component.text(tagName)));
        drop(point, tag);
        stand.remove();
        player.sendMessage(Component.text("FarmLoader abgebaut. Platz beim urspruenglichen Ersteller wieder frei.", NamedTextColor.YELLOW));
    }
    private void drop(Location location, ItemStack item) {
        if (item != null && !item.getType().isAir()) location.getWorld().dropItemNaturally(location, item.clone());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (registry.get(event.getEntity().getUniqueId()) != null) unregister(event.getEntity().getUniqueId());
    }
    @EventHandler public void onEntityLoad(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand)) return;
        LoaderRecord record = registry.get(stand.getUniqueId());
        if (record != null) mark(stand, record);
        else if (stand.getPersistentDataContainer().has(markerKey)) {
            stand.getPersistentDataContainer().remove(markerKey);
            stand.customName(null);
            stand.setCustomNameVisible(false);
            stand.setGravity(true);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        for (LoaderRecord record : registry.all()) {
            if (record.world().equals(event.getWorld().getUID())) stop(record.stand());
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { clicks.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isVirtual(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (isVirtual(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (registry == null) return true;
        sender.sendMessage(Component.text("FarmLoader: Armorstand + geschnitzter Kuerbis + Namensschild '" + tagName + "'.", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Rechtsklick: AN/AUS. Schleichen + Linksklick: abbauen. Limit: " + limit + " je Spieler."));
        sender.sendMessage(Component.text("Aktiv: " + running.size() + "; registriert: " + registry.all().size() + "."));
        if (sender instanceof Player player) sender.sendMessage(Component.text("Deine Loader: " + registry.count(player.getUniqueId()) + "/" + limit));
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            for (LoaderRecord record : registry.all()) {
                World world = Bukkit.getWorld(record.world());
                sender.sendMessage(Component.text((record.enabled() ? "AN " : "AUS ")
                        + (world == null ? record.world() : world.getName()) + " "
                        + (int) record.x() + " " + (int) record.y() + " " + (int) record.z()
                        + " | " + (running.containsKey(record.stand()) ? "laeuft" : "wartet/aus")));
            }
        }
        return true;
    }
    @Override public void onDisable() {
        if (heartbeat != null) heartbeat.cancel();
        loading.clear();
        Set<UUID> ids = new HashSet<>(running.keySet());
        ids.addAll(ticketOwners.keySet());
        ids.forEach(this::stop);
        // Every mutation was already persisted; never overwrite an unreadable source file.
        for (World world : Bukkit.getWorlds()) world.removePluginChunkTickets(this);
    }
}
