package work.penguinnet.resticrestorer.core.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Snapshot-argument handling for the restore commands: a periodically-rebuilt cache of snapshot times to offer as
 * completions, and parsing the chosen argument back into a {@link SnapshotRef}. Keeps {@code /restore-chunk} and
 * {@code /restore-player} consistent.
 *
 * <p>{@link #matching} is a pure read of the cache — it never queries the repository, not even to trigger a
 * refresh, so no amount of typing in the chat box can make the server shell out to restic. The cache is replaced
 * wholesale by a background thread every {@code backup.snapshotCacheRefreshMinutes} (see {@link #start}); until
 * the first refresh lands, and whenever a refresh fails, the previous list is served unchanged. The trade-off is
 * deliberate: completions can lag reality by up to one interval, and a snapshot taken since the last refresh has
 * to be typed out (or passed as an id) rather than picked from the list.
 */
public final class SnapshotSuggestions {
    // Matches the mod-id category the rest of the mod logs under, so operators can grep for one name.
    private static final Logger LOGGER = LoggerFactory.getLogger("resticrestorer");

    // Brigadier's unquoted-string parser rejects ':', so suggestions use a colon-free ISO-8601-ish stamp
    // (date + 'T' + time, UTC) instead of a true ISO-8601 instant string.
    private static final DateTimeFormatter SNAPSHOT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    private final Supplier<BackupRepository> backupRepository;

    /** Pre-formatted stamps in repository order — the only thing {@link #matching} ever reads. */
    private volatile List<String> cached = List.of();

    /** Null while stopped; only touched under {@code this}. */
    private ScheduledExecutorService refresher;

    public SnapshotSuggestions(Supplier<BackupRepository> backupRepository) {
        this.backupRepository = backupRepository;
    }

    /**
     * (Re)starts the background refresher: stops any previous one, then rebuilds the cache immediately and every
     * {@code refreshInterval} thereafter. Called on server start and again after {@code /restore-reload}, which may
     * have pointed the mod at a different repository or changed the interval.
     */
    public synchronized void start(Duration refreshInterval) {
        stop();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resticrestorer-snapshot-cache");
            thread.setDaemon(true);
            return thread;
        });
        refresher = executor;
        // Fixed *delay*, not rate: a refresh that takes longer than the interval must not queue up behind itself.
        executor.scheduleWithFixedDelay(this::refresh, 0, Math.max(1, refreshInterval.toMillis()), TimeUnit.MILLISECONDS);
    }

    /** Stops refreshing. The last-refreshed list stays readable; safe to call when not started. */
    public synchronized void stop() {
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
    }

    /** Cached snapshot stamps starting with {@code prefix} (case-insensitively), in repository order. */
    public List<String> matching(String prefix) {
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String suggestion : cached) {
            if (suggestion.toLowerCase(Locale.ROOT).startsWith(needle)) {
                matches.add(suggestion);
            }
        }
        return matches;
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

    /**
     * Rebuilds the cache. Must not let anything escape: an exception out of a {@code scheduleWithFixedDelay} task
     * silently cancels all future runs, which would freeze the completions until the next server start.
     */
    private void refresh() {
        try {
            List<String> formatted = new ArrayList<>();
            for (Snapshot snapshot : backupRepository.get().listSnapshots()) {
                formatted.add(SNAPSHOT_TIME_FORMAT.format(snapshot.time()));
            }
            cached = List.copyOf(formatted);
        } catch (Exception e) {
            LOGGER.warn("Snapshot suggestion refresh failed, keeping the previous list: {}", e.toString());
        }
    }
}
