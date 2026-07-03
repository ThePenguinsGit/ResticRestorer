package work.penguinnet.resticrestorer.mc.impl;

import com.mojang.authlib.GameProfile;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * 1.20.1 implementation of {@link MinecraftBridge} (see SPEC.md M4). This is the only class in the mod that
 * is allowed to know about Minecraft internals — everything else in :core talks to it through the SPI.
 */
public final class MinecraftBridge1201 implements MinecraftBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftBridge1201.class);
    private static final String REGION_DIR = "region";
    private static final String ENTITIES_DIR = "entities";
    private static final String POI_DIR = "poi";
    private static final String PLAYERDATA_DIR = "playerdata";

    private final Supplier<MinecraftServer> serverSupplier;
    private final Queue<ChunkRestoreJob> activeJobs = new ConcurrentLinkedQueue<>();

    public MinecraftBridge1201(Supplier<MinecraftServer> serverSupplier) {
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
        String baseRel = relativize(worldRoot, dimBase);
        return chunkFileList(baseRel, chunkX, chunkZ);
    }

    @Override
    public PreparedChunk prepare(Path backupMirrorRoot, String dimensionId, int chunkX, int chunkZ) {
        ResourceKey<Level> dimensionKey = dimensionKey(dimensionId);
        // The mirror root replicates the world root's own relative layout (see BackupRepository#fetchFiles),
        // so treating it as a pseudo world-root here yields the same per-dimension subfolder vanilla would use.
        Path dimBase = DimensionType.getStorageFolder(dimensionKey, backupMirrorRoot);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);

        byte[] region = readBlob(dimBase.resolve(REGION_DIR), pos);
        byte[] entities = readBlob(dimBase.resolve(ENTITIES_DIR), pos);
        byte[] poi = readBlob(dimBase.resolve(POI_DIR), pos);

        if (region == null && entities == null && poi == null) {
            // Not necessarily an error per the SPI contract (a store can legitimately be absent for one
            // chunk) — but ALL THREE absent almost always means the fetched backup mirror is empty at this
            // path, typically because `backup.snapshotWorldPrefix` in the config doesn't match the absolute
            // path the world was actually backed up from. Fail loudly instead of silently no-op'ing: without
            // this, a misconfigured prefix makes every restore "succeed" while writing nothing.
            throw new IllegalStateException("No chunk data found in the fetched backup for (" + chunkX + ", " + chunkZ
                    + ") in " + dimensionId + " — searched " + dimBase.resolve(REGION_DIR) + " (and entities/poi "
                    + "siblings). This usually means a prefix in resticrestorer.json is wrong: backup.snapshotWorldPrefix "
                    + "(what restic --include matches inside the snapshot) or backup.serverWorldPrefix (where the "
                    + "restored files land under the target); check both against `restic ls <snapshot>`.");
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
        UUID direct = tryParseUuid(playerSelector);
        if (direct != null) {
            return Optional.of(direct);
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
        return List.of(PLAYERDATA_DIR + "/" + playerId + ".dat");
    }

    @Override
    public PreparedPlayer preparePlayer(Path backupMirrorRoot, UUID playerId) {
        Path datFile = backupMirrorRoot.resolve(PLAYERDATA_DIR).resolve(playerId + ".dat");
        if (!Files.isRegularFile(datFile)) {
            throw new IllegalStateException("No player data file for " + playerId + " found in the fetched backup at "
                    + datFile + " — check backup.snapshotWorldPrefix / backup.serverWorldPrefix in resticrestorer.json, "
                    + "and that this player existed at that snapshot (`restic ls <snapshot>`).");
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
            LOGGER.info("Restored offline player {} ({} bytes) to {}", playerId, prepared.playerDataFile().length, dest);
            return;
        }

        // 2. Player is online: apply the restored NBT to the live entity and re-sync the client (a pure disk
        //    write would be clobbered by their next save). This deliberately uses only public API —
        //    NbtIo.readCompressed + Entity.load — rather than MinecraftServer's protected PlayerDataStorage,
        //    so it works on a production Forge server (Forge doesn't honour Fabric access wideners at runtime).
        //    Note: no data-fixing is applied, so restoring a .dat from an *older* Minecraft version than the
        //    running server is not supported for online players (offline disk restore still works, since the
        //    login path data-fixes on next join).
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(prepared.playerDataFile()));
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
            online.connection.send(new ClientboundUpdateMobEffectPacket(online.getId(), effect));
        }
        LOGGER.info("Restored online player {} ({}) and re-synced client", online.getGameProfile().getName(), playerId);
    }

    /** Accepts a dashed UUID or a bare 32-hex-digit form; returns null if it isn't a UUID. */
    private static UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException dashed) {
            if (value.length() == 32 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                String withDashes = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                        + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
                try {
                    return UUID.fromString(withDashes);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            return null;
        }
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

    private static ResourceKey<Level> dimensionKey(String dimensionId) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimensionId));
    }

    private static List<String> chunkFileList(String baseRel, int chunkX, int chunkZ) {
        String regionFile = "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
        String mccFile = "c." + chunkX + "." + chunkZ + ".mcc";
        List<String> files = new ArrayList<>(6);
        for (String storeDir : List.of(REGION_DIR, ENTITIES_DIR, POI_DIR)) {
            files.add(join(baseRel, storeDir, regionFile));
            files.add(join(baseRel, storeDir, mccFile));
        }
        return files;
    }

    private static String join(String baseRel, String storeDir, String fileName) {
        return baseRel.isEmpty() ? storeDir + "/" + fileName : baseRel + "/" + storeDir + "/" + fileName;
    }

    /** Converts {@code base}'s path relative to {@code root} into a "/"-joined string, regardless of host OS. */
    private static String relativize(Path root, Path base) {
        Path rel = root.toAbsolutePath().normalize().relativize(base.toAbsolutePath().normalize());
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static byte[] readBlob(Path storeDir, ChunkPos pos) {
        if (!Files.isDirectory(storeDir)) {
            return null;
        }
        try (ChunkStorage storage = new ChunkStorage(storeDir, DataFixers.getDataFixer(), false)) {
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
}
