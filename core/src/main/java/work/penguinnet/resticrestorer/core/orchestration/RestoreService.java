package work.penguinnet.resticrestorer.core.orchestration;

import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.mc.MinecraftBridge;
import work.penguinnet.resticrestorer.core.mc.PreparedChunk;
import work.penguinnet.resticrestorer.core.mc.PreparedPlayer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Trigger-agnostic async restore pipeline (see SPEC.md M3). Both the command and the RabbitMQ consumer
 * build a {@link RestoreRequest} and call {@link #submit}; this class owns the concurrency guard, the
 * fetch/prepare/apply ordering, and temp-dir cleanup.
 */
public final class RestoreService {
    private final BackupRepository backupRepository;
    private final MinecraftBridge bridge;
    private final RestoreConfig config;
    private final ExecutorService executor;
    private final Set<ChunkCoord> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inFlightPlayers = ConcurrentHashMap.newKeySet();

    public RestoreService(BackupRepository backupRepository, MinecraftBridge bridge, RestoreConfig config) {
        this.backupRepository = backupRepository;
        this.bridge = bridge;
        this.config = config;
        this.executor = Executors.newFixedThreadPool(Math.max(1, config.restore().maxConcurrent()));
    }

    /**
     * Submits a restore. Must be called from a thread where {@link MinecraftBridge#filesForChunk} is safe
     * to invoke (that call happens synchronously, before this method returns) — everything after is async.
     */
    public CompletableFuture<RestoreResult> submit(RestoreRequest request) {
        if (!bridge.dimensionExists(request.dimensionId())) {
            return CompletableFuture.completedFuture(RestoreResult.failure("Unknown dimension: " + request.dimensionId()));
        }

        ChunkCoord chunk = request.chunk();
        if (!inFlight.add(chunk)) {
            return CompletableFuture.completedFuture(
                    RestoreResult.failure("Restore already in progress for chunk " + describe(chunk)));
        }

        List<String> files;
        try {
            files = bridge.filesForChunk(request.dimensionId(), request.chunkX(), request.chunkZ());
        } catch (RuntimeException e) {
            inFlight.remove(chunk);
            return CompletableFuture.completedFuture(RestoreResult.failure("Failed to resolve files for chunk: " + e.getMessage()));
        }

        CompletableFuture<RestoreResult> future = CompletableFuture
                .supplyAsync(() -> runRestore(request, files), executor)
                .thenCompose(Function.identity())
                .exceptionally(t -> RestoreResult.failure(describeFailure(t)));
        future.whenComplete((result, throwable) -> inFlight.remove(chunk));
        return future;
    }

    /**
     * Submits a player-data restore. Like {@link #submit}, this must be called from a thread where the
     * bridge's player-resolution/file-listing is safe to invoke (the server main thread for a command) —
     * those calls happen synchronously before this returns; everything after is async.
     */
    public CompletableFuture<RestoreResult> submitPlayer(PlayerRestoreRequest request) {
        Optional<UUID> resolved;
        try {
            resolved = bridge.resolvePlayerId(request.playerSelector());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(RestoreResult.failure("Failed to resolve player: " + e.getMessage()));
        }
        if (resolved.isEmpty()) {
            return CompletableFuture.completedFuture(
                    RestoreResult.failure("Unknown player (never seen / not in profile cache): " + request.playerSelector()));
        }

        UUID playerId = resolved.get();
        if (!inFlightPlayers.add(playerId)) {
            return CompletableFuture.completedFuture(
                    RestoreResult.failure("Restore already in progress for player " + playerId));
        }

        List<String> files;
        try {
            files = bridge.filesForPlayer(playerId);
        } catch (RuntimeException e) {
            inFlightPlayers.remove(playerId);
            return CompletableFuture.completedFuture(RestoreResult.failure("Failed to resolve files for player: " + e.getMessage()));
        }

        CompletableFuture<RestoreResult> future = CompletableFuture
                .supplyAsync(() -> runPlayerRestore(request, playerId, files), executor)
                .thenCompose(Function.identity())
                .exceptionally(t -> RestoreResult.failure(describeFailure(t)));
        future.whenComplete((result, throwable) -> inFlightPlayers.remove(playerId));
        return future;
    }

    /** True if a restore for this chunk is currently in flight (fetch, prepare, or apply). */
    public boolean isInFlight(ChunkCoord chunk) {
        return inFlight.contains(chunk);
    }

    /** True if a restore for this player is currently in flight. */
    public boolean isPlayerInFlight(UUID playerId) {
        return inFlightPlayers.contains(playerId);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<RestoreResult> runRestore(RestoreRequest request, List<String> files) {
        Path tempDir;
        try {
            tempDir = createTempDir();
        } catch (IOException e) {
            return CompletableFuture.completedFuture(RestoreResult.failure("Failed to create temp dir: " + e.getMessage()));
        }

        try {
            Snapshot snapshot = resolveSnapshot(request.snapshotRef());
            backupRepository.fetchFiles(snapshot.id(), files, tempDir);
            Path mirrorRoot = tempDir.resolve(stripLeadingSlashes(config.backup().effectiveServerWorldPrefix()));
            PreparedChunk prepared = bridge.prepare(mirrorRoot, request.dimensionId(), request.chunkX(), request.chunkZ());

            return bridge.apply(prepared)
                    .thenApply(v -> RestoreResult.success("Restored chunk (" + request.chunkX() + ", " + request.chunkZ()
                            + ") in " + request.dimensionId() + " from snapshot " + snapshot.shortId()))
                    .exceptionally(t -> RestoreResult.failure(describeFailure(t)))
                    .whenComplete((result, throwable) -> deleteRecursively(tempDir));
        } catch (RuntimeException e) {
            deleteRecursively(tempDir);
            return CompletableFuture.completedFuture(RestoreResult.failure(describeFailure(e)));
        }
    }

    private CompletableFuture<RestoreResult> runPlayerRestore(PlayerRestoreRequest request, UUID playerId, List<String> files) {
        Path tempDir;
        try {
            tempDir = createTempDir();
        } catch (IOException e) {
            return CompletableFuture.completedFuture(RestoreResult.failure("Failed to create temp dir: " + e.getMessage()));
        }

        try {
            Snapshot snapshot = resolveSnapshot(request.snapshotRef());
            backupRepository.fetchFiles(snapshot.id(), files, tempDir);
            Path mirrorRoot = tempDir.resolve(stripLeadingSlashes(config.backup().effectiveServerWorldPrefix()));
            PreparedPlayer prepared = bridge.preparePlayer(mirrorRoot, playerId);

            return bridge.applyPlayer(prepared)
                    .thenApply(v -> RestoreResult.success("Restored player " + playerId
                            + " from snapshot " + snapshot.shortId()))
                    .exceptionally(t -> RestoreResult.failure(describeFailure(t)))
                    .whenComplete((result, throwable) -> deleteRecursively(tempDir));
        } catch (RuntimeException e) {
            deleteRecursively(tempDir);
            return CompletableFuture.completedFuture(RestoreResult.failure(describeFailure(e)));
        }
    }

    private Snapshot resolveSnapshot(SnapshotRef ref) {
        if (ref instanceof SnapshotRef.ById byId) {
            return backupRepository.resolve(byId.idOrShortId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown snapshot: " + byId.idOrShortId()));
        }
        if (ref instanceof SnapshotRef.ByTime byTime) {
            return backupRepository.listSnapshots().stream()
                    .filter(s -> !s.time().isAfter(byTime.time().plusSeconds(1)))
                    .max(Comparator.comparing(Snapshot::time))
                    .orElseThrow(() -> new IllegalArgumentException("No snapshot found at or before " + byTime.time()));
        }
        throw new IllegalStateException("Unknown SnapshotRef type: " + ref);
    }

    private Path createTempDir() throws IOException {
        String prefix = "resticrestorer-";
        if (config.restore().tempDir() != null) {
            Path base = Path.of(config.restore().tempDir());
            Files.createDirectories(base);
            return Files.createTempDirectory(base, prefix);
        }
        return Files.createTempDirectory(prefix);
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a scratch temp dir
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripLeadingSlashes(String path) {
        int i = 0;
        while (i < path.length() && (path.charAt(i) == '/' || path.charAt(i) == '\\')) {
            i++;
        }
        return path.substring(i);
    }

    private static String describe(ChunkCoord chunk) {
        return chunk.dimensionId() + " (" + chunk.chunkX() + ", " + chunk.chunkZ() + ")";
    }

    private static String describeFailure(Throwable t) {
        Throwable cause = (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
