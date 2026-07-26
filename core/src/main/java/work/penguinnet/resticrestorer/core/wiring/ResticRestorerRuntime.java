package work.penguinnet.resticrestorer.core.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.BackupRepositoryFactory;
import work.penguinnet.resticrestorer.core.config.ConfigLoader;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.mc.MinecraftBridge;
import work.penguinnet.resticrestorer.core.orchestration.RestoreService;
import work.penguinnet.resticrestorer.core.orchestration.RestoreServices;
import work.penguinnet.resticrestorer.core.rabbit.RabbitConsumer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Owns everything derived from {@code resticrestorer.json}: the backup repository, the restore pipeline and
 * the RabbitMQ consumer, plus the server-lifecycle and {@code /restore-reload} transitions between them. The
 * loader modules supply a config directory and a {@link MinecraftBridge} and drive this from their events;
 * nothing here touches Minecraft, so the reload swap is the same code on every version and loader.
 *
 * <p>The {@link MinecraftBridge} is deliberately <em>not</em> owned here: it is config-independent (it holds
 * the tick handler and any in-flight chunk jobs) and must survive a reload.
 */
public final class ResticRestorerRuntime {
    // Named after the mod id rather than this class so log lines keep the category operators already grep for.
    private static final Logger LOGGER = LoggerFactory.getLogger("resticrestorer");

    private final Path configDir;
    private final MinecraftBridge bridge;

    private RestoreServices services;
    private volatile RestoreService restoreService;
    private volatile RabbitConsumer rabbitConsumer;
    private volatile Duration snapshotCacheRefresh;
    private volatile boolean serverRunning;

    public ResticRestorerRuntime(Path configDir, MinecraftBridge bridge) {
        this.configDir = configDir;
        this.bridge = bridge;
    }

    /**
     * Reads the config and builds the services. Returns the loaded config so the caller can log a startup
     * summary; throws if the config can't be read or parsed.
     */
    public RestoreConfig start() {
        RestoreConfig config = loadConfig();

        BackupRepository backupRepository = BackupRepositoryFactory.create(config.backup());
        restoreService = new RestoreService(backupRepository, bridge, config);
        rabbitConsumer = new RabbitConsumer(config.rabbitmq(), config.world().defaultDimension(), restoreService);
        services = new RestoreServices(restoreService, backupRepository, config.world().defaultDimension());
        snapshotCacheRefresh = Duration.ofMinutes(config.backup().effectiveSnapshotCacheRefreshMinutes());

        return config;
    }

    /** The holder the commands read through; stable across reloads. */
    public RestoreServices services() {
        return services;
    }

    /**
     * Server is up: start refreshing the snapshot suggestion cache (nothing lists snapshots while no server is
     * running) and connect the RabbitMQ consumer — a broker problem must not abort server startup, nor keep the
     * completions from being populated.
     */
    public void onServerStarted() {
        serverRunning = true;
        services.snapshotSuggestions().start(snapshotCacheRefresh);
        try {
            rabbitConsumer.start();
        } catch (Exception e) {
            LOGGER.error("Failed to start RabbitMQ consumer", e);
        }
    }

    /** Server is going down: disconnect and drain. */
    public void onServerStopping() {
        services.snapshotSuggestions().stop();
        rabbitConsumer.stop();
        restoreService.shutdown();
        serverRunning = false;
    }

    /**
     * Re-reads the config and swaps in freshly-built services. Called on the server thread by
     * {@code /restore-reload}: the config read + object construction happen synchronously (so a parse error
     * aborts before anything changes and leaves the old config running), while tearing down the previous
     * RabbitMQ connection / restore thread-pool and connecting the new consumer happen on a background thread
     * so a slow broker or a draining restore never stalls the server. Returns a short summary of the new config.
     */
    public synchronized String reload() {
        RestoreConfig config = loadConfig();

        BackupRepository backupRepository = BackupRepositoryFactory.create(config.backup());
        RestoreService newService = new RestoreService(backupRepository, bridge, config);
        RabbitConsumer newRabbit = new RabbitConsumer(config.rabbitmq(), config.world().defaultDimension(), newService);

        RestoreService oldService = restoreService;
        RabbitConsumer oldRabbit = rabbitConsumer;

        // Publish the new wiring first so commands submitted after this point use it.
        restoreService = newService;
        rabbitConsumer = newRabbit;
        services.update(newService, backupRepository, config.world().defaultDimension());

        // Restart the suggestion cache against the repository we just published, so a reload that repoints the mod
        // (or changes the interval) doesn't leave stale completions up for another full interval.
        snapshotCacheRefresh = Duration.ofMinutes(config.backup().effectiveSnapshotCacheRefreshMinutes());
        if (serverRunning) {
            services.snapshotSuggestions().start(snapshotCacheRefresh);
        }

        Thread swap = new Thread(() -> {
            oldRabbit.stop();
            oldService.shutdown();
            if (serverRunning) {
                try {
                    newRabbit.start();
                } catch (Exception e) {
                    LOGGER.error("Failed to start RabbitMQ consumer after reload", e);
                }
            }
        }, "resticrestorer-reload");
        swap.setDaemon(true);
        swap.start();

        LOGGER.info("ResticRestorer config reloaded (backup provider: {}, rabbitmq: {})",
                config.backup().provider(), (config.rabbitmq().enabled() ? "enabled" : "disabled"));
        return "provider: " + config.backup().provider()
                + ", rabbitmq: " + (config.rabbitmq().enabled() ? "enabled" : "disabled");
    }

    private RestoreConfig loadConfig() {
        try {
            return ConfigLoader.loadOrCreate(configDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load resticrestorer config", e);
        }
    }
}
