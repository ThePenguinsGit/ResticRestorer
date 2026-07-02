package work.penguinnet.resticrestorer.core.backup.model;

import java.time.Instant;
import java.util.List;

/**
 * A single restic snapshot, provider-agnostic (see SPEC.md M2).
 */
public record Snapshot(String id, String shortId, Instant time, List<String> paths, List<String> tags, String hostname) {
}
