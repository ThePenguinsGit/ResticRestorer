package work.penguinnet.resticrestorer.core.config;

import java.util.List;

/**
 * Root config tree for ResticRestorer. Plain data — no Minecraft types (see SPEC.md M1).
 */
public record RestoreConfig(BackupConfig backup, WorldConfig world, RabbitMqConfig rabbitmq, RestoreSettings restore) {

    public static RestoreConfig createDefault() {
        return new RestoreConfig(
                BackupConfig.createDefault(),
                WorldConfig.createDefault(),
                RabbitMqConfig.createDefault(),
                RestoreSettings.createDefault()
        );
    }

    /**
     * Two independently-configurable world-path prefixes — the split matters when the path a file is
     * <em>matched by</em> inside the snapshot differs from the path it <em>lands at</em> after restore:
     * <ul>
     *   <li>{@code snapshotWorldPrefix} — the world root <b>inside the repository/snapshot</b>. Used to build
     *       the restic {@code --include} patterns ({@code snapshotWorldPrefix + "/" + relpath}); it must match
     *       how the world was actually backed up (e.g. Backrest's source path {@code /data/world}).</li>
     *   <li>{@code serverWorldPrefix} — the world root <b>as reconstructed under the restore target on this
     *       server</b>, i.e. the base of the mirror root the bridge reads from ({@code targetDir + serverWorldPrefix}).
     *       For a plain {@code restic restore} this equals {@code snapshotWorldPrefix} (restic recreates the full
     *       stored path), so leave it blank to inherit; set it only when the restored files land at a different
     *       subpath than the include pattern.</li>
     * </ul>
     */
    public record BackupConfig(String provider, ResticConfig restic, RestApiConfig rest,
                               String snapshotWorldPrefix, String serverWorldPrefix) {
        public static BackupConfig createDefault() {
            return new BackupConfig("cli", ResticConfig.createDefault(), RestApiConfig.createDefault(), "/world", "");
        }

        /** The mirror-root base under the restore target; falls back to {@link #snapshotWorldPrefix} when unset. */
        public String effectiveServerWorldPrefix() {
            return (serverWorldPrefix == null || serverWorldPrefix.isBlank()) ? snapshotWorldPrefix : serverWorldPrefix;
        }
    }

    /**
     * restic backend + scoping config.
     *
     * <p>{@code repository} is the restic repository string, handed to restic verbatim as {@code RESTIC_REPOSITORY}.
     * It accepts any backend restic understands:
     * <ul>
     *   <li>a local filesystem path (e.g. {@code /srv/restic-repo}) — handy for local/testing runs;</li>
     *   <li>a REST-server address (e.g. {@code rest:http://backup-host:8000/} or
     *       {@code rest:https://user:pass@backup-host/}) — for the production Docker deployment, where the container
     *       has no filesystem access to the repo. The {@code rest:} scheme is required, exactly as restic expects.</li>
     * </ul>
     *
     * <p>{@code binary} is the restic executable: a path or PATH name (default {@code "restic"}), or the special
     * value {@code "bundled"} to use the restic binary shipped inside the mod jar (extracted on first use) — handy
     * for containers that don't have restic installed. The bundled binary is only available for the platforms
     * packaged at build time (Linux amd64/arm64); elsewhere, install restic and point {@code binary} at it.
     *
     * <p>{@code tagFilter}/{@code hostFilter} scope which snapshots this server may see — essential when a single
     * shared repository holds snapshots from several servers. Set {@code tagFilter} to the Backrest plan tag
     * (e.g. {@code plan:my-survival-server}) so other servers' snapshots are invisible to {@code listSnapshots}
     * (and therefore to {@code resolve}, which restore selection goes through).
     */
    public record ResticConfig(String binary, String repository, String password, String passwordFile,
                                List<String> extraArgs, String hostFilter, String tagFilter) {
        public static ResticConfig createDefault() {
            return new ResticConfig("restic", "", "", null, List.of(), null, null);
        }
    }

    /** Reserved for the future REST-API BackupRepository implementation (see SPEC.md M2). */
    public record RestApiConfig(String baseUrl, String apiKey) {
        public static RestApiConfig createDefault() {
            return new RestApiConfig(null, null);
        }
    }

    public record WorldConfig(String defaultDimension) {
        public static WorldConfig createDefault() {
            return new WorldConfig("minecraft:overworld");
        }
    }

    public record RabbitMqConfig(boolean enabled, String host, int port, String virtualHost, String username,
                                  String password, String queue, int prefetch) {
        public static RabbitMqConfig createDefault() {
            return new RabbitMqConfig(false, "localhost", 5672, "/", "guest", "guest", "resticrestorer.restore", 1);
        }
    }

    public record RestoreSettings(int maxConcurrent, String tempDir) {
        public static RestoreSettings createDefault() {
            return new RestoreSettings(1, null);
        }
    }
}
