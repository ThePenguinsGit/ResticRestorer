package work.penguinnet.resticrestorer.core.orchestration;

/** Human-readable outcome of a restore, for feedback to the command sender / logs. */
public record RestoreResult(boolean success, String message) {

    public static RestoreResult success(String message) {
        return new RestoreResult(true, message);
    }

    public static RestoreResult failure(String message) {
        return new RestoreResult(false, message);
    }
}
