package work.penguinnet.resticrestorer;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.backup.BackupRepositoryFactory;
import work.penguinnet.resticrestorer.core.config.ConfigLoader;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.orchestration.RestoreService;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;
import work.penguinnet.resticrestorer.core.rabbit.RabbitConsumer;
import work.penguinnet.resticrestorer.mc.command.ReloadCommand;
import work.penguinnet.resticrestorer.mc.command.RestoreChunkCommand;
import work.penguinnet.resticrestorer.mc.command.RestorePlayerCommand;
import work.penguinnet.resticrestorer.mc.impl.MinecraftBridge1201;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Wires core + the 1.20.1 adapter together (see SPEC.md M7). Called from each loader's entrypoint with that
 * loader's {@link PermissionCheck} implementation. Everything registered here is event-driven (command
 * registration, server lifecycle) so it's inert — and safe — on a pure client distribution that never starts
 * a server; an integrated (singleplayer) server still triggers it normally.
 *
 * <p>The config-derived services (backup repository, restore pipeline, RabbitMQ consumer) live behind
 * {@link RestoreServices} so {@code /restore-reload} can rebuild them in place — the {@link MinecraftBridge1201},
 * with its tick handler and any in-flight chunk jobs, is config-independent and kept across a reload.
 */
public final class Resticrestorer {
    public static final String MOD_ID = "resticrestorer";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer currentServer;
    private static MinecraftBridge1201 bridge;
    private static RestoreServices services;
    private static volatile RestoreService restoreService;
    private static volatile RabbitConsumer rabbitConsumer;

    private Resticrestorer() {
    }

    public static void init(PermissionCheck permissionCheck) {
        RestoreConfig config = loadConfig();

        bridge = new MinecraftBridge1201(() -> currentServer);
        BackupRepository backupRepository = BackupRepositoryFactory.create(config.backup());
        restoreService = new RestoreService(backupRepository, bridge, config);
        rabbitConsumer = new RabbitConsumer(config.rabbitmq(), config.world().defaultDimension(), restoreService);
        services = new RestoreServices(restoreService, backupRepository, config.world().defaultDimension());

        RestoreChunkCommand chunkCommand = new RestoreChunkCommand(services, permissionCheck);
        RestorePlayerCommand playerCommand = new RestorePlayerCommand(services, permissionCheck);
        ReloadCommand reloadCommand = new ReloadCommand(Resticrestorer::reload, permissionCheck);
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) -> {
            chunkCommand.register(dispatcher);
            playerCommand.register(dispatcher);
            reloadCommand.register(dispatcher);
        });

        LifecycleEvent.SERVER_STARTED.register(server -> {
            currentServer = server;
            try {
                rabbitConsumer.start();
            } catch (Exception e) {
                LOGGER.error("Failed to start RabbitMQ consumer", e);
            }
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            rabbitConsumer.stop();
            restoreService.shutdown();
            currentServer = null;
        });

        LOGGER.info("ResticRestorer initialized (backup provider: {})", config.backup().provider());
    }

    /**
     * Re-reads the config and swaps in freshly-built services. Called on the server thread by
     * {@code /restore-reload}: the config read + object construction happen synchronously (so a parse error
     * aborts before anything changes and leaves the old config running), while tearing down the previous
     * RabbitMQ connection / restore thread-pool and connecting the new consumer happen on a background thread
     * so a slow broker or a draining restore never stalls the server. Returns a short summary of the new config.
     */
    public static synchronized String reload() {
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

        Thread swap = new Thread(() -> {
            oldRabbit.stop();
            oldService.shutdown();
            if (currentServer != null) {
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
                config.backup().provider(), config.rabbitmq().enabled() ? "enabled" : "disabled");
        return "provider: " + config.backup().provider()
                + ", rabbitmq: " + (config.rabbitmq().enabled() ? "enabled" : "disabled");
    }

    private static RestoreConfig loadConfig() {
        try {
            return ConfigLoader.loadOrCreate(Platform.getConfigFolder());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + MOD_ID + " config", e);
        }
    }
}
