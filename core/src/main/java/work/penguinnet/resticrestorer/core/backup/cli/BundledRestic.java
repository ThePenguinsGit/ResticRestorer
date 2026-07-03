package work.penguinnet.resticrestorer.core.backup.cli;

import work.penguinnet.resticrestorer.core.backup.BackupException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * Extracts the restic binary bundled into the jar (see {@code core/build.gradle}'s {@code bundleRestic} task) so
 * the mod can run without restic installed on the host. Selected by setting {@code restic.binary} to
 * {@link #SENTINEL} ({@code "bundled"}) in the config. The matching {@code restic/<os>-<arch>/restic.gz} resource
 * is streamed out once to a cached, executable file under the OS temp dir (keyed by restic version + platform so
 * a mod update re-extracts) and reused thereafter.
 */
final class BundledRestic {
    /** Config value for {@code restic.binary} that selects the jar-bundled binary. */
    static final String SENTINEL = "bundled";

    private static final AtomicReference<Path> CACHED = new AtomicReference<>();

    private BundledRestic() {
    }

    /** Extracts (once) and returns the path to the bundled restic executable for this platform. */
    static Path resolve() {
        Path existing = CACHED.get();
        if (existing != null) {
            return existing;
        }
        synchronized (BundledRestic.class) {
            existing = CACHED.get();
            if (existing != null) {
                return existing;
            }
            Path extracted = extract();
            CACHED.set(extracted);
            return extracted;
        }
    }

    private static Path extract() {
        String os = osKey();
        String arch = archKey();
        boolean windows = "windows".equals(os);
        String resource = "/restic/" + os + "-" + arch + "/restic.gz";

        Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "resticrestorer");
        Path target = cacheDir.resolve("restic-" + version() + "-" + os + "-" + arch + (windows ? ".exe" : ""));
        if (usable(target, windows)) {
            return target;
        }

        try (InputStream raw = BundledRestic.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new BackupException("No bundled restic binary for " + os + "-" + arch + " (looked for " + resource
                        + " on the classpath). Install restic and set restic.binary to its path instead of \""
                        + SENTINEL + "\".");
            }
            Files.createDirectories(cacheDir);
            Path tmp = Files.createTempFile(cacheDir, "restic-", ".part");
            try {
                try (InputStream in = new GZIPInputStream(raw); OutputStream out = Files.newOutputStream(tmp)) {
                    in.transferTo(out);
                }
                makeExecutable(tmp, windows);
                moveIntoPlace(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return target;
        } catch (IOException e) {
            throw new BackupException("Failed to extract bundled restic binary (" + resource + ")", e);
        }
    }

    private static boolean usable(Path target, boolean windows) {
        try {
            return Files.isRegularFile(target) && Files.size(target) > 0 && (windows || Files.isExecutable(target));
        } catch (IOException e) {
            return false;
        }
    }

    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UnsupportedOperationException atomicUnsupported) {
            // Some filesystems don't support atomic moves; a plain move is fine (a concurrent extractor may win
            // the race, but every copy is byte-identical).
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(Path path, boolean windows) throws IOException {
        if (windows) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException nonPosix) {
            if (!path.toFile().setExecutable(true, true)) {
                throw new IOException("Could not mark " + path + " executable");
            }
        }
    }

    private static String version() {
        try (InputStream in = BundledRestic.class.getResourceAsStream("/restic/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through to "unknown"
        }
        return "unknown";
    }

    private static String osKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    private static String archKey() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }
}
