package work.penguinnet.resticrestorer.core.backup.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Path restoreTarget = tempDir.resolve("restored");
        Files.createDirectories(repoDir);
        Files.createDirectories(tempDir.resolve("world").resolve("region"));
        Files.writeString(tempDir.resolve("world").resolve("region").resolve("r.0.0.mca"), "fake region data");

        RestoreConfig.ResticConfig config = new RestoreConfig.ResticConfig(
                "restic", repoDir.toString(), "test-password", null, List.of(), null, null);

        exec(config, tempDir, "init");
        // Back up "world" relative to tempDir rather than by absolute path. restic stores what it was given, so
        // this keeps the snapshot's internal layout close to a production one (/world/...) instead of embedding
        // the temp dir's absolute path — which on Windows also means the restore doesn't have to recreate
        // C:\Users, a read-only directory whose timestamp restic then fails to set (exit 1 on a good restore).
        exec(config, tempDir, "backup", "world");

        // Ask restic where it actually put the file rather than assuming: the mapping from a backup argument to
        // an in-snapshot path is platform-specific (Windows turns C:\x\world into /C/x/world), and this prefix is
        // exactly what an operator has to put in backup.snapshotWorldPrefix.
        String worldPrefix = storedWorldPrefix(config, tempDir);
        ResticCliBackupRepository repository = new ResticCliBackupRepository(config, worldPrefix);

        List<Snapshot> snapshots = repository.listSnapshots();
        assertEquals(1, snapshots.size());

        Snapshot snapshot = snapshots.get(0);
        assertTrue(repository.resolve(snapshot.id()).isPresent());
        assertTrue(repository.resolve(snapshot.shortId()).isPresent());

        repository.fetchFiles(snapshot.id(), List.of("region/r.0.0.mca"), restoreTarget);

        // restic recreates the full stored path under --target, so the file must land at exactly
        // <target>/<worldPrefix>/<relpath> — the layout RestoreService relies on when it builds the mirror root
        // as tempDir.resolve(stripLeadingSlashes(serverWorldPrefix)).
        Path restoredFile = restoreTarget.resolve(stripLeadingSlashes(worldPrefix)).resolve("region/r.0.0.mca");
        assertTrue(Files.exists(restoredFile), () -> "expected " + restoredFile + ", found: " + listFiles(restoreTarget));
        assertEquals("fake region data", Files.readString(restoredFile));
    }

    /** The in-snapshot directory holding {@code region/r.0.0.mca}, straight from {@code restic ls}. */
    private static String storedWorldPrefix(RestoreConfig.ResticConfig config, Path workingDir)
            throws IOException, InterruptedException {
        String suffix = "/region/r.0.0.mca";
        for (String line : capture(config, workingDir, "ls", "latest", "--json").split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject node = JsonParser.parseString(line).getAsJsonObject();
            if (node.has("path") && node.get("path").getAsString().endsWith(suffix)) {
                String path = node.get("path").getAsString();
                return path.substring(0, path.length() - suffix.length());
            }
        }
        throw new AssertionError("`restic ls` did not list the backed-up region file");
    }

    private static String stripLeadingSlashes(String path) {
        return path.replaceFirst("^/+", "");
    }

    private static String listFiles(Path root) {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(Path::toString).toList().toString();
        } catch (IOException e) {
            return "<unreadable: " + e + ">";
        }
    }

    private static void exec(RestoreConfig.ResticConfig config, Path workingDir, String... args)
            throws IOException, InterruptedException {
        Process process = start(config, workingDir, true, args);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("restic " + String.join(" ", args) + " failed with exit " + exit);
        }
    }

    private static String capture(RestoreConfig.ResticConfig config, Path workingDir, String... args)
            throws IOException, InterruptedException {
        Process process = start(config, workingDir, false, args);
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("restic " + String.join(" ", args) + " failed with exit " + exit);
        }
        return stdout;
    }

    private static Process start(RestoreConfig.ResticConfig config, Path workingDir, boolean inheritOutput,
                                 String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.binary());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.environment().put("RESTIC_REPOSITORY", config.repository());
        builder.environment().put("RESTIC_PASSWORD", config.password());
        if (inheritOutput) {
            builder.inheritIO();
        } else {
            builder.redirectErrorStream(false);
        }
        return builder.start();
    }
}
