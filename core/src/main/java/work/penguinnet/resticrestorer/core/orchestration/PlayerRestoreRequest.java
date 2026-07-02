package work.penguinnet.resticrestorer.core.orchestration;

/**
 * Request to restore a single player's data file. {@code playerSelector} is the raw username-or-UUID string
 * from the command; the {@code MinecraftBridge} resolves it to a concrete UUID at submit time.
 */
public record PlayerRestoreRequest(String playerSelector, SnapshotRef snapshotRef) {
}
