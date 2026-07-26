package work.penguinnet.resticrestorer.core.backup;

import org.junit.jupiter.api.Test;
import work.penguinnet.resticrestorer.core.backup.model.Snapshot;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotSuggestionsTest {
    private static final Instant NOON = Instant.parse("2026-03-04T12:30:45Z");
    private static final Instant EVENING = Instant.parse("2026-03-04T19:00:00Z");

    /** Real intervals are minutes; the tests drive the same scheduler at a pace they can observe. */
    private static final Duration FAST = Duration.ofMillis(20);

    @Test
    void servesFormattedSnapshotTimesOnceTheFirstRefreshLands() throws Exception {
        FakeRepository repository = new FakeRepository(snapshot("a", NOON), snapshot("b", EVENING));
        SnapshotSuggestions suggestions = new SnapshotSuggestions(() -> repository);

        suggestions.start(FAST);
        try {
            awaitCalls(repository, 1);
            awaitSuggestions(suggestions, List.of("2026-03-04T12-30-45", "2026-03-04T19-00-00"));
        } finally {
            suggestions.stop();
        }
    }

    @Test
    void filtersByPrefixCaseInsensitively() throws Exception {
        FakeRepository repository = new FakeRepository(snapshot("a", NOON), snapshot("b", EVENING));
        SnapshotSuggestions suggestions = new SnapshotSuggestions(() -> repository);

        suggestions.start(FAST);
        try {
            awaitSuggestions(suggestions, List.of("2026-03-04T12-30-45", "2026-03-04T19-00-00"));

            assertEquals(List.of("2026-03-04T19-00-00"), suggestions.matching("2026-03-04T19"));
            assertEquals(List.of("2026-03-04T12-30-45", "2026-03-04T19-00-00"), suggestions.matching("2026-03-04t"));
            assertTrue(suggestions.matching("1999").isEmpty());
        } finally {
            suggestions.stop();
        }
    }

    @Test
    void replacesTheCacheOnEveryRefresh() throws Exception {
        FakeRepository repository = new FakeRepository(snapshot("a", NOON));
        SnapshotSuggestions suggestions = new SnapshotSuggestions(() -> repository);

        suggestions.start(FAST);
        try {
            awaitSuggestions(suggestions, List.of("2026-03-04T12-30-45"));

            repository.snapshots = List.of(snapshot("b", EVENING));
            awaitSuggestions(suggestions, List.of("2026-03-04T19-00-00"));
        } finally {
            suggestions.stop();
        }
    }

    @Test
    void keepsTheLastKnownGoodListWhenTheRepositoryKeepsThrowing() throws Exception {
        FakeRepository repository = new FakeRepository(snapshot("a", NOON));
        SnapshotSuggestions suggestions = new SnapshotSuggestions(() -> repository);

        suggestions.start(FAST);
        try {
            awaitSuggestions(suggestions, List.of("2026-03-04T12-30-45"));

            repository.failing = true;
            // Several failed refreshes must neither clear the cache nor cancel the schedule.
            int before = repository.calls.get();
            awaitCalls(repository, before + 3);

            assertEquals(List.of("2026-03-04T12-30-45"), suggestions.matching(""));
        } finally {
            suggestions.stop();
        }
    }

    @Test
    void neverQueriesTheRepositoryFromMatching() {
        SnapshotSuggestions suggestions = new SnapshotSuggestions(() -> {
            throw new AssertionError("suggestions must be served from the cache alone");
        });

        assertTrue(suggestions.matching("").isEmpty(), "nothing is cached before the first refresh");
    }

    @Test
    void stopEndsTheRefreshSchedule() throws Exception {
        FakeRepository repository = new FakeRepository(snapshot("a", NOON));
        SnapshotSuggestions suggestions = new SnapshotSuggestions(() -> repository);

        suggestions.start(FAST);
        awaitCalls(repository, 2);
        suggestions.stop();

        int after = repository.calls.get();
        Thread.sleep(FAST.toMillis() * 10);
        assertEquals(after, repository.calls.get(), "a stopped cache must not keep listing snapshots");
        // The last-refreshed list stays readable after stopping.
        assertEquals(List.of("2026-03-04T12-30-45"), suggestions.matching(""));
    }

    @Test
    void parsesAFormattedTimeAndFallsBackToASnapshotId() {
        SnapshotSuggestions suggestions = new SnapshotSuggestions(FakeRepository::new);

        assertEquals(new SnapshotRef.ByTime(NOON), suggestions.parse("2026-03-04T12-30-45"));
        assertEquals(new SnapshotRef.ById("abc123"), suggestions.parse("abc123"));
    }

    private static void awaitCalls(FakeRepository repository, int atLeast) throws Exception {
        for (int i = 0; i < 500 && repository.calls.get() < atLeast; i++) {
            Thread.sleep(10);
        }
        assertTrue(repository.calls.get() >= atLeast,
                "expected at least " + atLeast + " refreshes, saw " + repository.calls.get());
    }

    private static void awaitSuggestions(SnapshotSuggestions suggestions, List<String> expected) throws Exception {
        for (int i = 0; i < 500 && !expected.equals(suggestions.matching("")); i++) {
            Thread.sleep(10);
        }
        assertEquals(expected, suggestions.matching(""));
    }

    private static Snapshot snapshot(String id, Instant time) {
        return new Snapshot(id + "full", id, time, List.of("/world"), List.of(), "host");
    }

    private static final class FakeRepository implements BackupRepository {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<Snapshot> snapshots;
        volatile boolean failing;

        FakeRepository(Snapshot... snapshots) {
            this.snapshots = List.of(snapshots);
        }

        @Override
        public List<Snapshot> listSnapshots() {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("restic is unreachable");
            }
            return snapshots;
        }

        @Override
        public Optional<Snapshot> resolve(String idOrShortId) {
            return Optional.empty();
        }

        @Override
        public void fetchFiles(String snapshotId, List<String> worldRelativePaths, Path targetDir) {
            throw new UnsupportedOperationException();
        }
    }
}
