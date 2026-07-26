package work.penguinnet.resticrestorer.mc.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import work.penguinnet.resticrestorer.core.backup.SnapshotSuggestions;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;

import java.util.concurrent.CompletableFuture;

/**
 * Brigadier glue over the shared {@link SnapshotSuggestions} cache. Serving a completion is a prefix filter over
 * an in-memory list, so it runs inline on the Netty suggestion thread and returns an already-completed future —
 * the refreshing (and the restic call it makes) happens on the cache's own schedule, never here. All the
 * snapshot-argument logic itself lives in :core.
 */
public final class SnapshotSupport {
    private final SnapshotSuggestions suggestions;

    public SnapshotSupport(SnapshotSuggestions suggestions) {
        this.suggestions = suggestions;
    }

    public CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        for (String suggestion : suggestions.matching(builder.getRemaining())) {
            builder.suggest(suggestion);
        }
        return builder.buildFuture();
    }

    /** Parses the argument as a formatted snapshot time, falling back to treating it as a snapshot id. */
    public SnapshotRef parse(String snapshotArg) {
        return suggestions.parse(snapshotArg);
    }
}
