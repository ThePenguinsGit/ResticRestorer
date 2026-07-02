package work.penguinnet.resticrestorer.core.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.mc.MinecraftBridge;
import work.penguinnet.resticrestorer.core.mc.PreparedChunk;
import work.penguinnet.resticrestorer.core.mc.PreparedPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreServiceTest {

    private static RestoreConfig configWithTempDir(Path tempDir) {
        RestoreConfig defaults = RestoreConfig.createDefault();
        return new RestoreConfig(defaults.backup(), defaults.world(), defaults.rabbitmq(),
                new RestoreConfig.RestoreSettings(1, tempDir.toString()));
    }

    @Test
    void happyPathFetchesBeforeApplyAndCleansUpTempDir(@TempDir Path tempDir) throws Exception {
        List<String> order = new ArrayList<>();
        FakeBackupRepository backupRepository = new FakeBackupRepository(order);
        AtomicReferenceToTempDir capturedTempDir = new AtomicReferenceToTempDir();
        FakeMinecraftBridge bridge = new FakeMinecraftBridge(order, capturedTempDir);

        RestoreService service = new RestoreService(backupRepository, bridge, configWithTempDir(tempDir));
        RestoreRequest request = new RestoreRequest("minecraft:overworld", 1, 1, SnapshotRef.ofId("abc123"));

        RestoreResult result = service.submit(request).get(10, TimeUnit.SECONDS);

        assertTrue(result.success(), result.message());
        assertEquals(List.of("fetch", "prepare", "apply"), order);
        assertFalse(service.isInFlight(request.chunk()));
        assertTrue(capturedTempDir.path != null && !Files.exists(capturedTempDir.path),
                "temp dir should be cleaned up after completion");

        service.shutdown();
    }

    @Test
    void unknownDimensionFailsWithoutTouchingBackupOrBridge(@TempDir Path tempDir) throws Exception {
        List<String> order = new ArrayList<>();
        FakeBackupRepository backupRepository = new FakeBackupRepository(order);
        FakeMinecraftBridge bridge = new FakeMinecraftBridge(order, new AtomicReferenceToTempDir());
        bridge.dimensionKnown = false;

        RestoreService service = new RestoreService(backupRepository, bridge, configWithTempDir(tempDir));
        RestoreResult result = service.submit(new RestoreRequest("minecraft:nope", 0, 0, SnapshotRef.ofId("abc123")))
                .get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(order.isEmpty());
        service.shutdown();
    }

    @Test
    void concurrentRestoreOfSameChunkIsRejected(@TempDir Path tempDir) throws Exception {
        FakeBackupRepository backupRepository = new FakeBackupRepository(new ArrayList<>());
        CountDownLatch releaseApply = new CountDownLatch(1);
        BlockingMinecraftBridge bridge = new BlockingMinecraftBridge(releaseApply);

        RestoreService service = new RestoreService(backupRepository, bridge, configWithTempDir(tempDir));
        RestoreRequest request = new RestoreRequest("minecraft:overworld", 5, 5, SnapshotRef.ofId("abc123"));

        CompletableFuture<RestoreResult> first = service.submit(request);
        bridge.applyStarted.await(5, TimeUnit.SECONDS);

        RestoreResult second = service.submit(request).get(5, TimeUnit.SECONDS);
        assertFalse(second.success());
        assertTrue(second.message().toLowerCase().contains("progress"));

        releaseApply.countDown();
        RestoreResult firstResult = first.get(5, TimeUnit.SECONDS);
        assertTrue(firstResult.success());

        assertFalse(service.isInFlight(request.chunk()));
        service.shutdown();
    }

    @Test
    void playerHappyPathFetchesBeforeApplyAndCleansUpTempDir(@TempDir Path tempDir) throws Exception {
        List<String> order = new ArrayList<>();
        FakeBackupRepository backupRepository = new FakeBackupRepository(order);
        AtomicReferenceToTempDir capturedTempDir = new AtomicReferenceToTempDir();
        FakeMinecraftBridge bridge = new FakeMinecraftBridge(order, capturedTempDir);

        RestoreService service = new RestoreService(backupRepository, bridge, configWithTempDir(tempDir));
        PlayerRestoreRequest request = new PlayerRestoreRequest("Steve", SnapshotRef.ofId("abc123"));

        RestoreResult result = service.submitPlayer(request).get(10, TimeUnit.SECONDS);

        assertTrue(result.success(), result.message());
        assertEquals(List.of("fetch", "prepare", "apply"), order);
        assertFalse(service.isPlayerInFlight(FakeMinecraftBridge.KNOWN_PLAYER));
        assertTrue(capturedTempDir.path != null && !Files.exists(capturedTempDir.path),
                "temp dir should be cleaned up after completion");

        service.shutdown();
    }

    @Test
    void unknownPlayerFailsWithoutTouchingBackupOrBridge(@TempDir Path tempDir) throws Exception {
        List<String> order = new ArrayList<>();
        FakeBackupRepository backupRepository = new FakeBackupRepository(order);
        FakeMinecraftBridge bridge = new FakeMinecraftBridge(order, new AtomicReferenceToTempDir());
        bridge.playerKnown = false;

        RestoreService service = new RestoreService(backupRepository, bridge, configWithTempDir(tempDir));
        RestoreResult result = service.submitPlayer(new PlayerRestoreRequest("Nobody", SnapshotRef.ofId("abc123")))
                .get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(order.isEmpty());
        service.shutdown();
    }

    @Test
    void concurrentRestoreOfSamePlayerIsRejected(@TempDir Path tempDir) throws Exception {
        FakeBackupRepository backupRepository = new FakeBackupRepository(new ArrayList<>());
        CountDownLatch releaseApply = new CountDownLatch(1);
        BlockingMinecraftBridge bridge = new BlockingMinecraftBridge(releaseApply);

        RestoreService service = new RestoreService(backupRepository, bridge, configWithTempDir(tempDir));
        PlayerRestoreRequest request = new PlayerRestoreRequest("Steve", SnapshotRef.ofId("abc123"));

        CompletableFuture<RestoreResult> first = service.submitPlayer(request);
        bridge.applyStarted.await(5, TimeUnit.SECONDS);

        RestoreResult second = service.submitPlayer(request).get(5, TimeUnit.SECONDS);
        assertFalse(second.success());
        assertTrue(second.message().toLowerCase().contains("progress"));

        releaseApply.countDown();
        RestoreResult firstResult = first.get(5, TimeUnit.SECONDS);
        assertTrue(firstResult.success());

        service.shutdown();
    }

    private static final class AtomicReferenceToTempDir {
        volatile Path path;
    }

    private static final class FakeBackupRepository implements BackupRepository {
        private final List<String> order;

        FakeBackupRepository(List<String> order) {
            this.order = order;
        }

        @Override
        public List<Snapshot> listSnapshots() {
            return List.of(new Snapshot("abc123full", "abc123", Instant.now(), List.of("/world"), List.of(), "host"));
        }

        @Override
        public Optional<Snapshot> resolve(String idOrShortId) {
            return listSnapshots().stream().filter(s -> s.id().equals(idOrShortId) || s.shortId().equals(idOrShortId)).findFirst();
        }

        @Override
        public void fetchFiles(String snapshotId, List<String> worldRelativePaths, Path targetDir) {
            synchronized (order) {
                order.add("fetch");
            }
            try {
                Files.createDirectories(targetDir.resolve("world"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class FakeMinecraftBridge implements MinecraftBridge {
        static final UUID KNOWN_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

        final List<String> order;
        final AtomicReferenceToTempDir capturedTempDir;
        boolean dimensionKnown = true;
        boolean playerKnown = true;

        FakeMinecraftBridge(List<String> order, AtomicReferenceToTempDir capturedTempDir) {
            this.order = order;
            this.capturedTempDir = capturedTempDir;
        }

        @Override
        public List<String> filesForChunk(String dimensionId, int chunkX, int chunkZ) {
            return List.of("region/r.0.0.mca");
        }

        @Override
        public PreparedChunk prepare(Path backupMirrorRoot, String dimensionId, int chunkX, int chunkZ) {
            synchronized (order) {
                order.add("prepare");
            }
            capturedTempDir.path = backupMirrorRoot.getParent();
            return new PreparedChunk(dimensionId, chunkX, chunkZ, new byte[]{1}, null, null);
        }

        @Override
        public CompletableFuture<Void> apply(PreparedChunk prepared) {
            synchronized (order) {
                order.add("apply");
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean dimensionExists(String dimensionId) {
            return dimensionKnown;
        }

        @Override
        public Optional<UUID> resolvePlayerId(String playerSelector) {
            return playerKnown ? Optional.of(KNOWN_PLAYER) : Optional.empty();
        }

        @Override
        public List<String> filesForPlayer(UUID playerId) {
            return List.of("playerdata/" + playerId + ".dat");
        }

        @Override
        public PreparedPlayer preparePlayer(Path backupMirrorRoot, UUID playerId) {
            synchronized (order) {
                order.add("prepare");
            }
            capturedTempDir.path = backupMirrorRoot.getParent();
            return new PreparedPlayer(playerId, new byte[]{1});
        }

        @Override
        public CompletableFuture<Void> applyPlayer(PreparedPlayer prepared) {
            synchronized (order) {
                order.add("apply");
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class BlockingMinecraftBridge implements MinecraftBridge {
        private final CountDownLatch releaseApply;
        final CountDownLatch applyStarted = new CountDownLatch(1);

        BlockingMinecraftBridge(CountDownLatch releaseApply) {
            this.releaseApply = releaseApply;
        }

        @Override
        public List<String> filesForChunk(String dimensionId, int chunkX, int chunkZ) {
            return List.of("region/r.0.0.mca");
        }

        @Override
        public PreparedChunk prepare(Path backupMirrorRoot, String dimensionId, int chunkX, int chunkZ) {
            return new PreparedChunk(dimensionId, chunkX, chunkZ, new byte[]{1}, null, null);
        }

        @Override
        public CompletableFuture<Void> apply(PreparedChunk prepared) {
            return blockUntilReleased();
        }

        @Override
        public boolean dimensionExists(String dimensionId) {
            return true;
        }

        @Override
        public Optional<UUID> resolvePlayerId(String playerSelector) {
            return Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        }

        @Override
        public List<String> filesForPlayer(UUID playerId) {
            return List.of("playerdata/" + playerId + ".dat");
        }

        @Override
        public PreparedPlayer preparePlayer(Path backupMirrorRoot, UUID playerId) {
            return new PreparedPlayer(playerId, new byte[]{1});
        }

        @Override
        public CompletableFuture<Void> applyPlayer(PreparedPlayer prepared) {
            return blockUntilReleased();
        }

        private CompletableFuture<Void> blockUntilReleased() {
            applyStarted.countDown();
            return CompletableFuture.runAsync(() -> {
                try {
                    releaseApply.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}
