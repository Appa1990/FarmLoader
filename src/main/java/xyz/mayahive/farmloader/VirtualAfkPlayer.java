package xyz.mayahive.farmloader;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Paper 26.3 internal adapter. This is an actual server player, never a tab-only NPC. */
final class VirtualAfkPlayer implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final SinkConnection connection;
    private final Location anchor;
    private boolean closed;

    static UUID idFor(UUID stand) {
        return UUID.nameUUIDFromBytes(("FarmLoader:" + stand).getBytes(StandardCharsets.UTF_8));
    }

    VirtualAfkPlayer(FarmLoaderPlugin plugin, LoaderRecord record, int radius) {
        server = ((CraftServer) plugin.getServer()).getServer();
        var world = plugin.getServer().getWorld(record.world());
        if (world == null) throw new IllegalStateException("Welt ist nicht geladen");
        level = ((CraftWorld) world).getHandle();
        anchor = new Location(world, record.x(), record.y(), record.z());
        UUID id = idFor(record.stand());
        if (server.getPlayerList().getPlayer(id) != null) throw new IllegalStateException("Doppelter AFK-Spieler");
        GameProfile profile = new GameProfile(id, "FL_" + record.stand().toString().replace("-", "").substring(0, 12));
        connection = new SinkConnection();
        player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        player.connection = new AfkListener(server, connection, player,
                CommonListenerCookie.createInitial(profile, false));
        player.isRealPlayer = true; // Required by Paper's player chunk loader and spawn accounting.
        player.setPos(record.x(), record.y(), record.z());
        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.setNoGravity(true);
        player.setInvisible(true);
        Player bukkit = player.getBukkitEntity();
        bukkit.setInvulnerable(true);
        bukkit.setVisibleByDefault(false);
        bukkit.setPersistent(false);
        bukkit.setCollidable(false);
        bukkit.setCanPickupItems(false);
        bukkit.setSilent(true);
        bukkit.setSleepingIgnored(true);
        bukkit.setAffectsSpawning(true);
        bukkit.setViewDistance(radius);
        bukkit.setSimulationDistance(radius);
        bukkit.setSendViewDistance(2);

        // Register in the authoritative player lists, without simulating a network login.
        // In particular this does not fire fake PlayerJoinEvents or run login/kit commands.
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(id, player);
        try {
            level.addNewPlayer(player);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    UUID id() { return player.getUUID(); }

    boolean healthy() {
        return !closed && !player.isRemoved() && connection.isConnected()
                && server.getPlayerList().getPlayer(player.getUUID()) == player
                && level.players().contains(player);
    }

    void tick() {
        if (!healthy()) throw new IllegalStateException("Virtueller Spieler wurde entfernt");
        if (player.moonrise$getChunkLoader() == null) {
            throw new IllegalStateException("Paper hat keinen Spieler-Chunkloader angelegt");
        }
        player.doTick();
        // There is no network input, so hold the AFK point exactly in place.
        player.setDeltaMovement(0, 0, 0);
        player.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
        player.getAbilities().flying = true;
        player.resetLastActionTime();
        level.getChunkSource().move(player);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        } finally {
            server.getPlayerList().getPlayers().remove(player);
            server.getPlayerList().getPlayersByUUID().remove(player.getUUID(), player);
            connection.channel.close();
            ((EmbeddedChannel) connection.channel).finishAndReleaseAll();
        }
    }

    private static final class AfkListener extends ServerGamePacketListenerImpl {
        AfkListener(MinecraftServer server, Connection connection, ServerPlayer player,
                    CommonListenerCookie cookie) {
            super(server, connection, player, cookie);
        }
        @Override public boolean hasClientLoaded() { return true; }
        @Override public void tickClientLoadTimeout() {}
    }

    /** No socket, outgoing queue or client packets; keeps player simulation local. */
    private static final class SinkConnection extends Connection {
        SinkConnection() {
            super(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel();
            address = new InetSocketAddress("127.0.0.1", 0);
        }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) {
            send(packet, listener, true);
        }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            if (listener != null) {
                try { listener.operationComplete(channel.newSucceededFuture()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        }
    }
}
