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
 */
public final class Resticrestorer {
    public static final String MOD_ID = "resticrestorer";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer currentServer;
    private static RestoreService restoreService;
    private static RabbitConsumer rabbitConsumer;

    private Resticrestorer() {
    }

    public static void init(PermissionCheck permissionCheck) {
        RestoreConfig config;
        try {
            config = ConfigLoader.loadOrCreate(Platform.getConfigFolder());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + MOD_ID + " config", e);
        }

        BackupRepository backupRepository = BackupRepositoryFactory.create(config.backup());
        MinecraftBridge1201 bridge = new MinecraftBridge1201(() -> currentServer);
        restoreService = new RestoreService(backupRepository, bridge, config);
        rabbitConsumer = new RabbitConsumer(config.rabbitmq(), config.world().defaultDimension(), restoreService);

        RestoreChunkCommand chunkCommand = new RestoreChunkCommand(restoreService, backupRepository, permissionCheck,
                config.world().defaultDimension());
        RestorePlayerCommand playerCommand = new RestorePlayerCommand(restoreService, backupRepository, permissionCheck);
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) -> {
            chunkCommand.register(dispatcher);
            playerCommand.register(dispatcher);
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
}
