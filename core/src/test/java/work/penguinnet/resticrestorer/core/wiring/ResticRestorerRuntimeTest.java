package work.penguinnet.resticrestorer.core.wiring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import work.penguinnet.resticrestorer.core.config.ConfigLoader;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.mc.MinecraftBridge;
import work.penguinnet.resticrestorer.core.mc.PreparedChunk;
import work.penguinnet.resticrestorer.core.mc.PreparedPlayer;
import work.penguinnet.resticrestorer.core.orchestration.RestoreService;
import work.penguinnet.resticrestorer.core.orchestration.RestoreServices;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResticRestorerRuntimeTest {

    @Test
    void startGeneratesTheDefaultConfigAndBuildsServices(@TempDir Path configDir) {
        ResticRestorerRuntime runtime = new ResticRestorerRuntime(configDir, new StubBridge());

        RestoreConfig config = runtime.start();

        assertTrue(Files.exists(configDir.resolve("resticrestorer.json")), "config should be generated on first run");
        assertEquals("cli", config.backup().provider());
        assertEquals("minecraft:overworld", runtime.services().defaultDimensionId());
        runtime.onServerStopping();
    }

    @Test
    void reloadSwapsTheServicesInsideTheSameHolder(@TempDir Path configDir) throws Exception {
        ResticRestorerRuntime runtime = new ResticRestorerRuntime(configDir, new StubBridge());
        runtime.start();

        RestoreServices holder = runtime.services();
        RestoreService before = holder.restoreService();

        RestoreConfig defaults = RestoreConfig.createDefault();
        ConfigLoader.save(configDir, new RestoreConfig(defaults.backup(),
                new RestoreConfig.WorldConfig("minecraft:the_nether"), defaults.rabbitmq(), defaults.restore()));

        String summary = runtime.reload();

        // The holder identity is what the already-registered commands captured — it must survive the swap.
        assertSame(holder, runtime.services());
        assertNotSame(before, holder.restoreService());
        assertEquals("minecraft:the_nether", holder.defaultDimensionId());
        assertEquals("provider: cli, rabbitmq: disabled", summary);
        runtime.onServerStopping();
    }

    @Test
    void reloadWithAnUnparseableConfigThrowsAndKeepsThePreviousServices(@TempDir Path configDir) throws Exception {
        ResticRestorerRuntime runtime = new ResticRestorerRuntime(configDir, new StubBridge());
        runtime.start();

        RestoreServices holder = runtime.services();
        RestoreService before = holder.restoreService();
        String dimensionBefore = holder.defaultDimensionId();

        Files.writeString(configDir.resolve("resticrestorer.json"), "{ this is not json");

        assertThrows(RuntimeException.class, runtime::reload);

        assertSame(before, holder.restoreService(), "a failed reload must not swap in half-built services");
        assertEquals(dimensionBefore, holder.defaultDimensionId());
        runtime.onServerStopping();
    }

    /** The runtime only stores the bridge and hands it to RestoreService; nothing here is ever called. */
    private static final class StubBridge implements MinecraftBridge {
        @Override
        public List<String> filesForChunk(String dimensionId, int chunkX, int chunkZ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PreparedChunk prepare(Path backupMirrorRoot, String dimensionId, int chunkX, int chunkZ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> apply(PreparedChunk prepared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean dimensionExists(String dimensionId) {
            return true;
        }

        @Override
        public Optional<UUID> resolvePlayerId(String playerSelector) {
            return Optional.empty();
        }

        @Override
        public List<String> filesForPlayer(UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PreparedPlayer preparePlayer(Path backupMirrorRoot, UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> applyPlayer(PreparedPlayer prepared) {
            throw new UnsupportedOperationException();
        }
    }
}
