package work.penguinnet.resticrestorer.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupConfigTest {

    private static RestoreConfig.BackupConfig with(String snapshotPrefix, String serverPrefix) {
        return withRefreshMinutes(snapshotPrefix, serverPrefix,
                RestoreConfig.BackupConfig.DEFAULT_SNAPSHOT_CACHE_REFRESH_MINUTES);
    }

    private static RestoreConfig.BackupConfig withRefreshMinutes(String snapshotPrefix, String serverPrefix,
                                                                 int refreshMinutes) {
        return new RestoreConfig.BackupConfig("cli", RestoreConfig.ResticConfig.createDefault(),
                RestoreConfig.RestApiConfig.createDefault(), snapshotPrefix, serverPrefix, refreshMinutes);
    }

    @Test
    void serverPrefixInheritsSnapshotPrefixWhenBlank() {
        assertEquals("/data/world", with("/data/world", "").effectiveServerWorldPrefix());
        assertEquals("/data/world", with("/data/world", null).effectiveServerWorldPrefix());
        assertEquals("/data/world", with("/data/world", "  ").effectiveServerWorldPrefix());
    }

    @Test
    void serverPrefixOverridesSnapshotPrefixWhenSet() {
        assertEquals("/restored/world", with("/backup/data/world", "/restored/world").effectiveServerWorldPrefix());
    }

    @Test
    void snapshotCacheRefreshDefaultsToThirtyMinutes() {
        assertEquals(30, RestoreConfig.BackupConfig.DEFAULT_SNAPSHOT_CACHE_REFRESH_MINUTES);
        assertEquals(30, RestoreConfig.BackupConfig.createDefault().effectiveSnapshotCacheRefreshMinutes());
    }

    @Test
    void snapshotCacheRefreshFallsBackForNonPositiveValues() {
        // 0 is what Gson leaves behind for a config file written before this key existed.
        assertEquals(30, withRefreshMinutes("/world", "", 0).effectiveSnapshotCacheRefreshMinutes());
        assertEquals(30, withRefreshMinutes("/world", "", -5).effectiveSnapshotCacheRefreshMinutes());
    }

    @Test
    void snapshotCacheRefreshHonoursAConfiguredValue() {
        assertEquals(5, withRefreshMinutes("/world", "", 5).effectiveSnapshotCacheRefreshMinutes());
    }
}
