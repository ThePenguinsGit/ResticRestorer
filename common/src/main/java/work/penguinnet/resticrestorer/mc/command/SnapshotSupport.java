package work.penguinnet.resticrestorer.mc.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared snapshot-argument handling for the restore commands: async, cached suggestions of snapshot times
 * and parsing the chosen argument back into a {@link SnapshotRef}. Keeps both {@code /restorechunk} and
 * {@code /restore-player} consistent and avoids shelling out to restic on the Netty suggestion thread.
 */
public final class SnapshotSupport {
    // Brigadier's unquoted-string parser rejects ':', so suggestions use a colon-free ISO-8601-ish stamp
    // (date + 'T' + time, UTC) instead of a true ISO-8601 instant string.
    private static final DateTimeFormatter SNAPSHOT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    private final BackupRepository backupRepository;
    private final SnapshotCache snapshotCache = new SnapshotCache();

    public SnapshotSupport(BackupRepository backupRepository) {
        this.backupRepository = backupRepository;
    }

    public CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        snapshotCache.refreshAsync(backupRepository);
        return CompletableFuture.supplyAsync(() -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (Snapshot snapshot : snapshotCache.cached()) {
                String suggestion = SNAPSHOT_TIME_FORMAT.format(snapshot.time());
                if (suggestion.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(suggestion);
                }
            }
            return builder.build();
        });
    }

    /** Parses the argument as a formatted snapshot time, falling back to treating it as a snapshot id. */
    public SnapshotRef parse(String snapshotArg) {
        try {
            Instant instant = SNAPSHOT_TIME_FORMAT.parse(snapshotArg, Instant::from);
            return SnapshotRef.ofTime(instant);
        } catch (DateTimeException e) {
            return SnapshotRef.ofId(snapshotArg);
        }
    }

    /** Cached snapshot list refreshed off-thread, so suggestions never shell out to restic on the Netty thread. */
    private static final class SnapshotCache {
        private volatile List<Snapshot> cached = List.of();
        private volatile boolean refreshing;

        List<Snapshot> cached() {
            return cached;
        }

        void refreshAsync(BackupRepository backupRepository) {
            if (refreshing) {
                return;
            }
            refreshing = true;
            CompletableFuture.runAsync(() -> {
                try {
                    cached = new CopyOnWriteArrayList<>(backupRepository.listSnapshots());
                } catch (RuntimeException ignored) {
                    // keep serving the last known-good list
                } finally {
                    refreshing = false;
                }
            });
        }
    }
}
