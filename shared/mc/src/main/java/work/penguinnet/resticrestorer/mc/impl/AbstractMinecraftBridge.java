package work.penguinnet.resticrestorer.mc.impl;

import com.mojang.authlib.GameProfile;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.mc.MinecraftBridge;
import work.penguinnet.resticrestorer.core.mc.PreparedChunk;
import work.penguinnet.resticrestorer.core.mc.PreparedPlayer;
import work.penguinnet.resticrestorer.core.mc.RestoreDiagnostics;
import work.penguinnet.resticrestorer.core.mc.WorldLayout;
import work.penguinnet.resticrestorer.core.util.PlayerIds;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * The whole of {@link MinecraftBridge} (see SPEC.md M4) except the handful of calls whose signatures differ
 * between Minecraft versions. Those are the abstract methods at the bottom of this class; each supported
 * version implements them in its own {@code VersionBridge}, which is the only Minecraft-version-specific
 * code in the mod besides {@link VersionRegionStorage} and the access widener.
 *
 * <p>Everything here is compiled once per supported version, so it may only use Minecraft API that is
 * present and identical in all of them — see the package documentation of the shared source tree.
 */
public abstract class AbstractMinecraftBridge implements MinecraftBridge {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final Supplier<MinecraftServer> serverSupplier;
    private final Queue<ChunkRestoreJob> activeJobs = new ConcurrentLinkedQueue<>();

    protected AbstractMinecraftBridge(Supplier<MinecraftServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
        TickEvent.SERVER_POST.register(this::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        activeJobs.removeIf(ChunkRestoreJob::tick);
    }

    @Override
    public List<String> filesForChunk(String dimensionId, int chunkX, int chunkZ) {
        MinecraftServer server = requireServer();
        ServerLevel level = requireLevel(server, dimensionId);
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path dimBase = DimensionType.getStorageFolder(level.dimension(), worldRoot);
        return WorldLayout.chunkFiles(WorldLayout.relativize(worldRoot, dimBase), chunkX, chunkZ);
    }

    @Override
    public PreparedChunk prepare(Path backupMirrorRoot, String dimensionId, int chunkX, int chunkZ) {
        ResourceKey<Level> dimensionKey = dimensionKey(dimensionId);
        // The mirror root replicates the world root's own relative layout (see BackupRepository#fetchFiles),
        // so treating it as a pseudo world-root here yields the same per-dimension subfolder vanilla would use.
        Path dimBase = DimensionType.getStorageFolder(dimensionKey, backupMirrorRoot);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);

        byte[] region = readBlob(dimBase.resolve(WorldLayout.REGION_DIR), pos, dimensionKey, "chunk");
        byte[] entities = readBlob(dimBase.resolve(WorldLayout.ENTITIES_DIR), pos, dimensionKey, "entities");
        byte[] poi = readBlob(dimBase.resolve(WorldLayout.POI_DIR), pos, dimensionKey, "poi");

        if (region == null && entities == null && poi == null) {
            throw new IllegalStateException(
                    RestoreDiagnostics.missingChunkData(dimensionId, chunkX, chunkZ, dimBase.resolve(WorldLayout.REGION_DIR)));
        }
        return new PreparedChunk(dimensionId, chunkX, chunkZ, region, entities, poi);
    }

    @Override
    public CompletableFuture<Void> apply(PreparedChunk prepared) {
        MinecraftServer server = requireServer();
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerLevel level = requireLevel(server, prepared.dimensionId());
                ChunkRestoreJob job = new ChunkRestoreJob(level, prepared, future);
                activeJobs.add(job);
                job.start();
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public boolean dimensionExists(String dimensionId) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return false;
        }
        try {
            return server.getLevel(dimensionKey(dimensionId)) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Optional<UUID> resolvePlayerId(String playerSelector) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return Optional.empty();
        }
        Optional<UUID> direct = PlayerIds.tryParse(playerSelector);
        if (direct.isPresent()) {
            return direct;
        }
        // Online players first (authoritative, and covers first-join players not yet in the cache)...
        ServerPlayer online = server.getPlayerList().getPlayerByName(playerSelector);
        if (online != null) {
            return Optional.of(online.getUUID());
        }
        // ...then the server's profile cache for offline, previously-seen players.
        return server.getProfileCache().get(playerSelector).map(GameProfile::getId);
    }

    @Override
    public List<String> filesForPlayer(UUID playerId) {
        return List.of(WorldLayout.playerFile(playerId));
    }

    @Override
    public PreparedPlayer preparePlayer(Path backupMirrorRoot, UUID playerId) {
        Path datFile = backupMirrorRoot.resolve(WorldLayout.PLAYERDATA_DIR).resolve(playerId + ".dat");
        if (!Files.isRegularFile(datFile)) {
            throw new IllegalStateException(RestoreDiagnostics.missingPlayerData(playerId, datFile));
        }
        try {
            return new PreparedPlayer(playerId, Files.readAllBytes(datFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read backup player data from " + datFile, e);
        }
    }

    @Override
    public CompletableFuture<Void> applyPlayer(PreparedPlayer prepared) {
        MinecraftServer server = requireServer();
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                applyPlayerOnMainThread(server, prepared);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void applyPlayerOnMainThread(MinecraftServer server, PreparedPlayer prepared) throws IOException {
        UUID playerId = prepared.playerId();

        // 1. Overwrite the on-disk <uuid>.dat. This is the whole restore for an offline player (their next
        //    login reads the full restored file, including dimension). The bytes are the original .dat
        //    verbatim, so whatever compression the server used is preserved.
        Path dest = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(playerId + ".dat");
        Files.createDirectories(dest.getParent());
        Files.write(dest, prepared.playerDataFile());

        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online == null) {
            logger.info("Restored offline player {} ({} bytes) to {}", playerId, prepared.playerDataFile().length, dest);
            return;
        }

        // 2. Player is online: apply the restored NBT to the live entity and re-sync the client (a pure disk
        //    write would be clobbered by their next save). This deliberately uses only public API —
        //    NbtIo.readCompressed + Entity.load — rather than MinecraftServer's protected PlayerDataStorage,
        //    so it works on a production Forge server (Forge doesn't honour Fabric access wideners at runtime).
        //    Note: no data-fixing is applied, so restoring a .dat from an *older* Minecraft version than the
        //    running server is not supported for online players (offline disk restore still works, since the
        //    login path data-fixes on next join).
        CompoundTag tag = readCompressedPlayerNbt(prepared.playerDataFile());
        online.load(tag);
        if (tag.contains("playerGameType")) {
            online.setGameMode(GameType.byId(tag.getInt("playerGameType")));
        }
        online.closeContainer(); // make inventoryMenu the active container so the resend below covers it
        online.inventoryMenu.sendAllDataToRemote();
        online.onUpdateAbilities();
        online.resetSentInfo(); // forces health/food/experience to resend next tick
        online.connection.teleport(online.getX(), online.getY(), online.getZ(), online.getYRot(), online.getXRot());
        for (MobEffectInstance effect : online.getActiveEffects()) {
            sendEffectUpdate(online, effect);
        }
        logger.info("Restored online player {} ({}) and re-synced client", online.getGameProfile().getName(), playerId);
    }

    private MinecraftServer requireServer() {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            throw new IllegalStateException("Server is not running");
        }
        return server;
    }

    private ServerLevel requireLevel(MinecraftServer server, String dimensionId) {
        ServerLevel level = server.getLevel(dimensionKey(dimensionId));
        if (level == null) {
            throw new IllegalArgumentException("Unknown dimension: " + dimensionId);
        }
        return level;
    }

    private ResourceKey<Level> dimensionKey(String dimensionId) {
        return ResourceKey.create(Registries.DIMENSION, resourceLocation(dimensionId));
    }

    private byte[] readBlob(Path storeDir, ChunkPos pos, ResourceKey<Level> dimensionKey, String storeType) {
        if (!Files.isDirectory(storeDir)) {
            return null;
        }
        try (ChunkStorage storage = openChunkStorage(storeDir, dimensionKey, storeType)) {
            Optional<CompoundTag> tag = storage.read(pos).join();
            if (tag.isEmpty()) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.write(tag.get(), new DataOutputStream(baos));
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read backup chunk NBT from " + storeDir, e);
        }
    }

    // --- Version-specific seam ------------------------------------------------------------------------
    // Six lines of Minecraft API that changed shape between the supported versions. Everything above is
    // identical on every version; keep it that way by only ever widening this seam, never the code above.

    /** {@code ResourceLocation}'s parse entry point (a constructor before 1.21, a static factory after). */
    protected abstract ResourceLocation resourceLocation(String id);

    /**
     * Opens a read-only {@link ChunkStorage} over one store directory of the fetched backup mirror.
     * {@code dimensionKey} and {@code storeType} are only used by versions whose {@code ChunkStorage} wants a
     * {@code RegionStorageInfo}; earlier versions ignore them.
     */
    protected abstract ChunkStorage openChunkStorage(Path storeDir, ResourceKey<Level> dimensionKey, String storeType)
            throws IOException;

    /** Reads a gzipped player {@code .dat} payload (the NBT size accounting API changed in 1.20.5). */
    protected abstract CompoundTag readCompressedPlayerNbt(byte[] playerDataFile) throws IOException;

    /** Sends one active effect to the restored player's client (the packet gained a field in 1.20.5). */
    protected abstract void sendEffectUpdate(ServerPlayer player, MobEffectInstance effect);
}
