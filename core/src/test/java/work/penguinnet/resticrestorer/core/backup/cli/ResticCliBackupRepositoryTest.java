package work.penguinnet.resticrestorer.core.backup.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link ResticCliBackupRepository} against a real throwaway local restic repo (see SPEC.md M2's
 * verification step). Skipped entirely if the {@code restic} binary isn't on PATH.
 */
class ResticCliBackupRepositoryTest {

    @BeforeAll
    static void requireResticOnPath() {
        assumeTrue(isResticAvailable(), "restic binary not found on PATH; skipping CLI adapter integration test");
    }

    private static boolean isResticAvailable() {
        try {
            Process p = new ProcessBuilder("restic", "version").start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void listsAndFetchesFromARealLocalRepo(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path repoDir = tempDir.resolve("repo");
        Path worldDir = tempDir.resolve("world");
        Path restoreTarget = tempDir.resolve("restored");
        Files.createDirectories(repoDir);
        Files.createDirectories(worldDir.resolve("region"));
        Files.writeString(worldDir.resolve("region").resolve("r.0.0.mca"), "fake region data");

        RestoreConfig.ResticConfig config = new RestoreConfig.ResticConfig(
                "restic", repoDir.toString(), "test-password", null, List.of(), null, null);

        exec(config, "init");
        exec(config, "backup", worldDir.toString());

        ResticCliBackupRepository repository = new ResticCliBackupRepository(config, worldDir.toString());

        List<Snapshot> snapshots = repository.listSnapshots();
        assertEquals(1, snapshots.size());

        Snapshot snapshot = snapshots.get(0);
        assertTrue(repository.resolve(snapshot.id()).isPresent());
        assertTrue(repository.resolve(snapshot.shortId()).isPresent());

        repository.fetchFiles(snapshot.id(), List.of("region/r.0.0.mca"), restoreTarget);
        // Don't assume restic's exact absolute-path reconstruction scheme under --target; just confirm the
        // named file was restored somewhere under it, with content.
        Path restoredFile;
        try (var walk = Files.walk(restoreTarget)) {
            restoredFile = walk.filter(p -> p.getFileName().toString().equals("r.0.0.mca"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected r.0.0.mca to be restored under " + restoreTarget));
        }
        assertFalse(Files.readString(restoredFile).isEmpty());
    }

    private static void exec(RestoreConfig.ResticConfig config, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add(config.binary());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("RESTIC_REPOSITORY", config.repository());
        builder.environment().put("RESTIC_PASSWORD", config.password());
        builder.inheritIO();
        Process process = builder.start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("restic " + String.join(" ", args) + " failed with exit " + exit);
        }
    }
}
