package work.penguinnet.resticrestorer.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void jsonRoundTripPreservesDefaultConfig() {
        RestoreConfig defaults = RestoreConfig.createDefault();
        String json = ConfigLoader.toJson(defaults);
        RestoreConfig parsed = ConfigLoader.fromJson(json);
        assertEquals(defaults, parsed);
    }

    @Test
    void loadOrCreateGeneratesDefaultFileOnFirstRun(@TempDir Path tempDir) throws IOException {
        RestoreConfig loaded = ConfigLoader.loadOrCreate(tempDir);
        assertEquals(RestoreConfig.createDefault(), loaded);
        assertTrue(Files.exists(tempDir.resolve("resticrestorer.json")));
    }

    @Test
    void loadOrCreateReloadsExistingFileUnchanged(@TempDir Path tempDir) throws IOException {
        ConfigLoader.loadOrCreate(tempDir);
        RestoreConfig reloaded = ConfigLoader.loadOrCreate(tempDir);
        assertEquals(RestoreConfig.createDefault(), reloaded);
    }
}
