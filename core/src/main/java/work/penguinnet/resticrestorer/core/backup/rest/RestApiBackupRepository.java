package work.penguinnet.resticrestorer.core.backup.rest;

import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stub for a future REST-API-backed {@link BackupRepository} (see SPEC.md M2) — exists to make the swap
 * seam visible; not implemented yet.
 */
public final class RestApiBackupRepository implements BackupRepository {
    public RestApiBackupRepository(String baseUrl, String apiKey) {
    }

    @Override
    public List<Snapshot> listSnapshots() {
        throw new UnsupportedOperationException("REST API backup provider is not implemented yet");
    }

    @Override
    public Optional<Snapshot> resolve(String idOrShortId) {
        throw new UnsupportedOperationException("REST API backup provider is not implemented yet");
    }

    @Override
    public void fetchFiles(String snapshotId, List<String> worldRelativePaths, Path targetDir) {
        throw new UnsupportedOperationException("REST API backup provider is not implemented yet");
    }
}
