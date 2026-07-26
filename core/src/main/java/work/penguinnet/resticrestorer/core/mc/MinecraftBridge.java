package work.penguinnet.resticrestorer.core.mc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SPI implemented by the Minecraft adapters ({@code AbstractMinecraftBridge} in {@code shared/mc}, plus a
 * {@code VersionBridge} per supported version in {@code :common-<ver>}). No Minecraft
 * types in this signature — see SPEC.md's "one seam" note.
 */
public interface MinecraftBridge {

    /**
     * Files (world-relative) needed to restore this chunk in this dimension, including region/entities/poi
     * and the oversized {@code c.<x>.<z>.mcc} siblings. Must be safe to call from whatever thread
     * {@code RestoreService.submit} is invoked from — implementations should only read already-resolved
     * server/dimension state, not touch anything requiring main-thread confinement.
     */
    List<String> filesForChunk(String dimensionId, int chunkX, int chunkZ);

    /** Parses the fetched backup mirror into raw chunk NBT blobs (region/entities/poi), off-thread. */
    PreparedChunk prepare(Path backupMirrorRoot, String dimensionId, int chunkX, int chunkZ);

    /** Applies on the server thread; returns a future completing when reload+ticket-restore finishes. */
    CompletableFuture<Void> apply(PreparedChunk prepared);

    boolean dimensionExists(String dimensionId);

    // --- Player restore (see the /restore-player command) ---------------------------------------------

    /**
     * Resolves a username or UUID string to a concrete player UUID, or empty if it can't be resolved
     * (unknown/never-seen player). Reads the live server's player list and profile cache, so — like
     * {@link #filesForChunk} — this is expected to be called on the server main thread at submit time.
     */
    Optional<UUID> resolvePlayerId(String playerSelector);

    /** World-relative file(s) holding this player's data (i.e. {@code playerdata/<uuid>.dat}). */
    List<String> filesForPlayer(UUID playerId);

    /** Reads the player's data file out of the fetched backup mirror, off-thread. */
    PreparedPlayer preparePlayer(Path backupMirrorRoot, UUID playerId);

    /**
     * Applies on the server thread: overwrites the on-disk {@code <uuid>.dat}, and — if the player is
     * currently online — live-loads it into the player entity and re-syncs the client. Returns a future
     * completing when done.
     */
    CompletableFuture<Void> applyPlayer(PreparedPlayer prepared);
}
