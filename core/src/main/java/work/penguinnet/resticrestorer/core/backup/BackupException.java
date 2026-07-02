package work.penguinnet.resticrestorer.core.backup;

/** Thrown when a {@link BackupRepository} operation fails (e.g. a non-zero restic exit code). */
public class BackupException extends RuntimeException {
    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
