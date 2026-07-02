package work.penguinnet.resticrestorer.core.backup;

import work.penguinnet.resticrestorer.core.backup.cli.ResticCliBackupRepository;
import work.penguinnet.resticrestorer.core.backup.rest.RestApiBackupRepository;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;

/** Picks the {@link BackupRepository} implementation named by {@code config.backup.provider} (see SPEC.md M2). */
public final class BackupRepositoryFactory {
    private BackupRepositoryFactory() {
    }

    public static BackupRepository create(RestoreConfig.BackupConfig config) {
        return switch (config.provider()) {
            case "cli" -> new ResticCliBackupRepository(config.restic(), config.snapshotWorldPrefix());
            case "rest" -> new RestApiBackupRepository(config.rest().baseUrl(), config.rest().apiKey());
            default -> throw new IllegalArgumentException("Unknown backup provider: " + config.provider());
        };
    }
}
