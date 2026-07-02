package work.penguinnet.resticrestorer.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads (or generates, on first run) {@code resticrestorer.json} from a config directory handed in by the
 * loader module — kept MC-free so :core doesn't need to know about {@code Platform.getConfigFolder()}.
 */
public final class ConfigLoader {
    private static final String FILE_NAME = "resticrestorer.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigLoader() {
    }

    /**
     * Loads {@code <configDir>/resticrestorer.json}, creating it with default values if absent.
     */
    public static RestoreConfig loadOrCreate(Path configDir) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            RestoreConfig defaults = RestoreConfig.createDefault();
            save(configDir, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, RestoreConfig.class);
        }
    }

    public static void save(Path configDir, RestoreConfig config) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve(FILE_NAME);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
    }

    public static String toJson(RestoreConfig config) {
        return GSON.toJson(config);
    }

    public static RestoreConfig fromJson(String json) {
        return GSON.fromJson(json, RestoreConfig.class);
    }
}
