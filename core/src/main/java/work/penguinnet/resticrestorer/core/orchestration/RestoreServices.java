package work.penguinnet.resticrestorer.core.orchestration;

import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.SnapshotSuggestions;
import work.penguinnet.resticrestorer.core.wiring.ResticRestorerRuntime;

/**
 * Mutable holder for the config-derived services the commands use. Brigadier command handlers are registered
 * exactly once, so they can't be handed fresh {@link RestoreService}/{@link BackupRepository} instances on a
 * config reload — instead they read through this holder every time they execute, and
 * {@link ResticRestorerRuntime#reload()} swaps the contents. Fields are {@code volatile} because the swap
 * happens on the server thread while suggestion threads read {@link #backupRepository()}.
 */
public final class RestoreServices {
    private volatile RestoreService restoreService;
    private volatile BackupRepository backupRepository;
    private volatile String defaultDimensionId;

    /**
     * One shared suggestion cache for every command that takes a snapshot argument. Not part of {@link #update}:
     * it reads the repository back through this holder, so a reload swaps what it refreshes from without the
     * commands (or the cache itself) being rebuilt. Its refresh schedule is driven by
     * {@link ResticRestorerRuntime}.
     */
    private final SnapshotSuggestions snapshotSuggestions = new SnapshotSuggestions(this::backupRepository);

    public RestoreServices(RestoreService restoreService, BackupRepository backupRepository, String defaultDimensionId) {
        update(restoreService, backupRepository, defaultDimensionId);
    }

    public void update(RestoreService restoreService, BackupRepository backupRepository, String defaultDimensionId) {
        this.restoreService = restoreService;
        this.backupRepository = backupRepository;
        this.defaultDimensionId = defaultDimensionId;
    }

    public RestoreService restoreService() {
        return restoreService;
    }

    public BackupRepository backupRepository() {
        return backupRepository;
    }

    public String defaultDimensionId() {
        return defaultDimensionId;
    }

    public SnapshotSuggestions snapshotSuggestions() {
        return snapshotSuggestions;
    }
}
