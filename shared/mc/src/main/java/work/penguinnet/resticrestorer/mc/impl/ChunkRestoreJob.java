package work.penguinnet.resticrestorer.mc.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.mc.PreparedChunk;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

/**
 * One in-flight evict -> write -> reload -> restore-tickets run for a single chunk (see SPEC.md M4c).
 * {@link #start()} runs once on the main thread; {@link #tick()} is then polled once per server tick
 * (also main-thread, via {@code TickEvent.SERVER_POST}) until it returns {@code true}. The entity/POI
 * writes go through {@link VersionRegionStorage}, the one part of this that differs per Minecraft version.
 *
 * <p>Eviction relies on vanilla's own ticket-driven unload: we drop our forced-chunk ticket and wait for
 * the chunk to naturally disappear from {@link ServerLevel#hasChunk}. This is correct and low-risk when no
 * player/other ticket is keeping the chunk loaded (the expected case for an admin restore). If something
 * else keeps the chunk loaded, we time out and fail cleanly rather than forcibly splicing ChunkMap's
 * internal holder maps — that path would skip vanilla's own unload callbacks (entity/POI section unload,
 * player untracking) and risk desyncing server-side state in a way we can't fully validate here. Operators
 * should ensure no players are near the target chunk before restoring it.</p>
 */
final class ChunkRestoreJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkRestoreJob.class);
    private static final int MAX_WAIT_TICKS = 200; // ~10s at 20 tps

    private final ServerLevel level;
    private final PreparedChunk prepared;
    private final CompletableFuture<Void> future;
    private final ChunkPos pos;

    private boolean wasForced;
    private int ticksWaited;
    private boolean waiting;

    ChunkRestoreJob(ServerLevel level, PreparedChunk prepared, CompletableFuture<Void> future) {
        this.level = level;
        this.prepared = prepared;
        this.future = future;
        this.pos = new ChunkPos(prepared.chunkX(), prepared.chunkZ());
    }

    /** CAPTURE + start of EVICT. Must run on the main thread. */
    void start() {
        try {
            LOGGER.info("Restoring chunk ({}, {}) in {}: region={} entities={} poi={} (present/absent in backup)",
                    pos.x, pos.z, level.dimension().location(),
                    presence(prepared.regionNbt()), presence(prepared.entityNbt()), presence(prepared.poiNbt()));
            wasForced = level.getForcedChunks().contains(pos.toLong());
            if (wasForced) {
                level.setChunkForced(pos.x, pos.z, false);
            }
            // Flushes any dirty in-memory state to disk before we (or vanilla's own unload path) touch the
            // region files directly.
            level.getChunkSource().save(false);
            waiting = true;
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Advances the state machine by one tick. Returns true once this job is finished (success or failure). */
    boolean tick() {
        if (!waiting) {
            return true;
        }
        if (!level.hasChunk(pos.x, pos.z)) {
            LOGGER.info("Chunk ({}, {}) unloaded after {} tick(s); writing backup data now", pos.x, pos.z, ticksWaited);
            finishWriteReload();
            return true;
        }
        if (++ticksWaited > MAX_WAIT_TICKS) {
            restoreTicketBestEffort();
            fail(new IllegalStateException("Chunk (" + pos.x + ", " + pos.z + ") in " + level.dimension().location()
                    + " did not unload naturally after " + MAX_WAIT_TICKS + " ticks — a player or another mod "
                    + "may be keeping it loaded. Move players away and retry."));
            return true;
        }
        return false;
    }

    private void finishWriteReload() {
        try {
            boolean wroteRegion = writeRegion();
            boolean wroteEntities = writeEntities();
            boolean wrotePoi = writePoi();
            level.getChunk(pos.x, pos.z);
            restoreTicketBestEffort();
            LOGGER.info("Restore of chunk ({}, {}) in {} complete: wrote region={} entities={} poi={}",
                    pos.x, pos.z, level.dimension().location(), wroteRegion, wroteEntities, wrotePoi);
            future.complete(null);
        } catch (RuntimeException e) {
            restoreTicketBestEffort();
            fail(e);
        }
    }

    private boolean writeRegion() {
        if (prepared.regionNbt() == null) {
            return false;
        }
        CompoundTag tag = readTag(prepared.regionNbt());
        var chunkMap = level.getChunkSource().chunkMap;
        chunkMap.write(pos, tag);
        chunkMap.flushWorker();
        return true;
    }

    private boolean writeEntities() {
        if (prepared.entityNbt() == null) {
            return false;
        }
        VersionRegionStorage.entities(level).writeAndFlush(pos, readTag(prepared.entityNbt()));
        return true;
    }

    private boolean writePoi() {
        if (prepared.poiNbt() == null) {
            return false;
        }
        VersionRegionStorage.poi(level).writeAndFlush(pos, readTag(prepared.poiNbt()));
        return true;
    }

    private static String presence(byte[] blob) {
        return blob == null ? "absent" : "present(" + blob.length + "b)";
    }

    private void restoreTicketBestEffort() {
        if (!wasForced) {
            return;
        }
        try {
            level.setChunkForced(pos.x, pos.z, true);
        } catch (RuntimeException ignored) {
            // best-effort: don't let ticket restoration mask the underlying restore result
        }
    }

    private void fail(Throwable t) {
        future.completeExceptionally(t);
    }

    private static CompoundTag readTag(byte[] bytes) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
