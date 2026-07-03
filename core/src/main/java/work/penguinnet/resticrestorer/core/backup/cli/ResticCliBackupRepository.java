package work.penguinnet.resticrestorer.core.backup.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.backup.BackupException;
import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link BackupRepository} backed by shelling out to the restic CLI (see SPEC.md M2).
 */
public final class ResticCliBackupRepository implements BackupRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResticCliBackupRepository.class);
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>) (json, type, ctx) ->
                    OffsetDateTime.parse(json.getAsString()).toInstant())
            .create();

    private final RestoreConfig.ResticConfig config;
    private final String snapshotWorldPrefix;
    private volatile String resolvedBinary;

    public ResticCliBackupRepository(RestoreConfig.ResticConfig config, String snapshotWorldPrefix) {
        this.config = config;
        this.snapshotWorldPrefix = snapshotWorldPrefix;
    }

    /**
     * The restic executable to invoke: the jar-bundled binary (extracted on first use) when
     * {@code restic.binary} is {@code "bundled"}, otherwise the configured path/PATH name verbatim.
     */
    private String binary() {
        String cached = resolvedBinary;
        if (cached == null) {
            synchronized (this) {
                cached = resolvedBinary;
                if (cached == null) {
                    cached = BundledRestic.SENTINEL.equalsIgnoreCase(config.binary())
                            ? BundledRestic.resolve().toString()
                            : config.binary();
                    resolvedBinary = cached;
                }
            }
        }
        return cached;
    }

    @Override
    public List<Snapshot> listSnapshots() {
        List<String> command = new ArrayList<>(List.of(binary(), "snapshots", "--json"));
        if (config.hostFilter() != null && !config.hostFilter().isBlank()) {
            command.add("--host");
            command.add(config.hostFilter());
        }
        if (config.tagFilter() != null && !config.tagFilter().isBlank()) {
            command.add("--tag");
            command.add(config.tagFilter());
        }
        command.addAll(config.extraArgs());

        String stdout = run(command);
        try {
            ResticSnapshotJson[] parsed = GSON.fromJson(stdout, ResticSnapshotJson[].class);
            if (parsed == null) {
                return List.of();
            }
            List<Snapshot> snapshots = new ArrayList<>(parsed.length);
            for (ResticSnapshotJson s : parsed) {
                snapshots.add(new Snapshot(s.id, s.shortId, s.time,
                        s.paths == null ? List.of() : List.copyOf(s.paths),
                        s.tags == null ? List.of() : List.copyOf(s.tags),
                        s.hostname));
            }
            return snapshots;
        } catch (JsonSyntaxException e) {
            throw new BackupException("Failed to parse `restic snapshots --json` output", e);
        }
    }

    @Override
    public Optional<Snapshot> resolve(String idOrShortId) {
        return listSnapshots().stream()
                .filter(s -> s.id().equals(idOrShortId) || s.shortId().equals(idOrShortId) || s.id().startsWith(idOrShortId))
                .findFirst();
    }

    @Override
    public void fetchFiles(String snapshotId, List<String> worldRelativePaths, Path targetDir) {
        List<String> command = new ArrayList<>(List.of(binary(), "restore", snapshotId, "--target", targetDir.toString()));
        for (String relPath : worldRelativePaths) {
            command.add("--include");
            command.add(joinPath(snapshotWorldPrefix, relPath));
        }
        command.addAll(config.extraArgs());
        run(command);
    }

    private static String joinPath(String prefix, String relPath) {
        String normalizedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String normalizedRel = relPath.startsWith("/") ? relPath.substring(1) : relPath;
        return normalizedPrefix + "/" + normalizedRel;
    }

    private String run(List<String> command) {
        String commandLine = String.join(" ", command);
        LOGGER.info("Running restic command: {}", commandLine);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("RESTIC_REPOSITORY", config.repository());
        if (config.passwordFile() != null && !config.passwordFile().isBlank()) {
            builder.environment().put("RESTIC_PASSWORD_FILE", config.passwordFile());
        } else {
            builder.environment().put("RESTIC_PASSWORD", config.password());
        }

        try {
            Process process = builder.start();
            // Drain stdout/stderr concurrently on their own threads: reading one fully before the other (or
            // before waitFor()) can deadlock if the child fills the other stream's OS pipe buffer while
            // blocked writing to it and nobody's draining it yet.
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread stdoutReader = new Thread(() -> appendAll(process.getInputStream(), stdout), "restic-stdout");
            Thread stderrReader = new Thread(() -> appendAll(process.getErrorStream(), stderr), "restic-stderr");
            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BackupException("restic command timed out: " + commandLine);
            }
            stdoutReader.join(TimeUnit.SECONDS.toMillis(30));
            stderrReader.join(TimeUnit.SECONDS.toMillis(30));

            LOGGER.info("restic exited {} for `{}`\nstdout:\n{}\nstderr:\n{}",
                    process.exitValue(), commandLine, stdout, stderr);
            if (process.exitValue() != 0) {
                throw new BackupException("restic command failed (exit " + process.exitValue() + "): "
                        + commandLine + "\n" + stderr);
            }
            return stdout.toString();
        } catch (IOException e) {
            throw new BackupException("Failed to run restic command: " + commandLine, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("Interrupted while running restic command: " + commandLine, e);
        }
    }

    private static void appendAll(InputStream in, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // best-effort output capture; the exit-code check below is what actually gates success/failure
        }
    }

    /** Mirrors the subset of `restic snapshots --json` fields we need; field names match restic's JSON exactly. */
    private static final class ResticSnapshotJson {
        String id;
        @SerializedName("short_id")
        String shortId;
        Instant time;
        List<String> paths;
        List<String> tags;
        String hostname;
    }
}
