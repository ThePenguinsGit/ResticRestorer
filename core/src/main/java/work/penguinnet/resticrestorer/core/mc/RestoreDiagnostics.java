package work.penguinnet.resticrestorer.core.mc;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Operator-facing diagnostics for "the fetched backup mirror is empty where we expected data". These are the
 * mod's most-read error messages — a misconfigured prefix is by far the most common setup mistake — so they
 * live here rather than being retyped in each version adapter, where the wordings would drift apart.
 */
public final class RestoreDiagnostics {
    private RestoreDiagnostics() {
    }

    /**
     * All three chunk stores were absent. Not necessarily an error per the SPI contract (a store can
     * legitimately be absent for one chunk) — but ALL THREE absent almost always means the fetched backup
     * mirror is empty at this path, typically because {@code backup.snapshotWorldPrefix} doesn't match the
     * absolute path the world was actually backed up from. Failing loudly beats silently no-op'ing: without
     * it, a misconfigured prefix makes every restore "succeed" while writing nothing.
     */
    public static String missingChunkData(String dimensionId, int chunkX, int chunkZ, Path searchedRegionDir) {
        return "No chunk data found in the fetched backup for (" + chunkX + ", " + chunkZ
                + ") in " + dimensionId + " — searched " + searchedRegionDir + " (and entities/poi "
                + "siblings). This usually means a prefix in resticrestorer.json is wrong: backup.snapshotWorldPrefix "
                + "(what restic --include matches inside the snapshot) or backup.serverWorldPrefix (where the "
                + "restored files land under the target); check both against `restic ls <snapshot>`.";
    }

    /** The player's {@code <uuid>.dat} wasn't in the fetched mirror. */
    public static String missingPlayerData(UUID playerId, Path searchedDatFile) {
        return "No player data file for " + playerId + " found in the fetched backup at "
                + searchedDatFile + " — check backup.snapshotWorldPrefix / backup.serverWorldPrefix in "
                + "resticrestorer.json, and that this player existed at that snapshot (`restic ls <snapshot>`).";
    }
}
