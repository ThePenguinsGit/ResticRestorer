package work.penguinnet.resticrestorer.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupConfigTest {

    private static RestoreConfig.BackupConfig with(String snapshotPrefix, String serverPrefix) {
        return new RestoreConfig.BackupConfig("cli", RestoreConfig.ResticConfig.createDefault(),
                RestoreConfig.RestApiConfig.createDefault(), snapshotPrefix, serverPrefix);
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
}
