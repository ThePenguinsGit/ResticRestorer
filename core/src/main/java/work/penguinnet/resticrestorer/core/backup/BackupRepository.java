package work.penguinnet.resticrestorer.core.backup;

import work.penguinnet.resticrestorer.core.backup.model.Snapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Swappable backup source (see SPEC.md M2). The CLI-based restic implementation is the only one shipped
 * today; a future REST-API implementation drops in here with no changes to the rest of the mod.
 */
public interface BackupRepository {

    /** Lists snapshots, filtered by the configured host/tag/path. */
    List<Snapshot> listSnapshots();

    /** Accepts either a full or short snapshot id. */
    Optional<Snapshot> resolve(String idOrShortId);

    /**
     * Fetches the given world-relative paths at a snapshot into {@code targetDir}, mirroring the snapshot's
     * on-disk structure. Missing paths (e.g. an absent {@code .mcc}) are skipped, not errors. The restored
     * mirror root is deterministic from config: {@code targetDir.resolve(snapshotWorldPrefix)}.
     */
    void fetchFiles(String snapshotId, List<String> worldRelativePaths, Path targetDir);
}
