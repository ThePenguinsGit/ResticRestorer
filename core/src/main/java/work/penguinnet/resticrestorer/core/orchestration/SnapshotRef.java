package work.penguinnet.resticrestorer.core.orchestration;

import java.time.Instant;

/** A snapshot reference that's either already resolved to an id, or a point in time to resolve against. */
public sealed interface SnapshotRef {

    static SnapshotRef ofId(String idOrShortId) {
        return new ById(idOrShortId);
    }

    static SnapshotRef ofTime(Instant time) {
        return new ByTime(time);
    }

    record ById(String idOrShortId) implements SnapshotRef {
    }

    record ByTime(Instant time) implements SnapshotRef {
    }
}
