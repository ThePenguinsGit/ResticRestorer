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

    public record BackupConfig(String provider, ResticConfig restic, RestApiConfig rest, String snapshotWorldPrefix) {
        public static BackupConfig createDefault() {
            return new BackupConfig("cli", ResticConfig.createDefault(), RestApiConfig.createDefault(), "/world");
        }
    }

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
