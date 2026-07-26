package work.penguinnet.resticrestorer;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import work.penguinnet.resticrestorer.core.config.RestoreConfig;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;
import work.penguinnet.resticrestorer.core.wiring.ResticRestorerRuntime;
import work.penguinnet.resticrestorer.mc.command.ReloadCommand;
import work.penguinnet.resticrestorer.mc.command.RestoreChunkCommand;
import work.penguinnet.resticrestorer.mc.command.RestorePlayerCommand;
import work.penguinnet.resticrestorer.mc.impl.VersionBridge;

/**
 * Wires core + the running version's adapter together. Called from each loader's entrypoint with
 * that loader's {@link PermissionCheck} implementation. Everything registered here is event-driven (command
 * registration, server lifecycle) so it's inert — and safe — on a pure client distribution that never starts
 * a server; an integrated (singleplayer) server still triggers it normally.
 *
 * <p>The config-derived services live in {@link ResticRestorerRuntime} so {@code /restore-reload} can rebuild
 * them in place — the {@link VersionBridge}, with its tick handler and any in-flight chunk jobs, is
 * config-independent and kept across a reload.
 */
public final class Resticrestorer {
    public static final String MOD_ID = "resticrestorer";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer currentServer;
    private static ResticRestorerRuntime runtime;

    private Resticrestorer() {
    }

    public static void init(PermissionCheck permissionCheck) {
        runtime = new ResticRestorerRuntime(Platform.getConfigFolder(), new VersionBridge(() -> currentServer));
        RestoreConfig config = runtime.start();

        RestoreChunkCommand chunkCommand = new RestoreChunkCommand(runtime.services(), permissionCheck);
        RestorePlayerCommand playerCommand = new RestorePlayerCommand(runtime.services(), permissionCheck);
        ReloadCommand reloadCommand = new ReloadCommand(runtime::reload, permissionCheck);
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) -> {
            chunkCommand.register(dispatcher);
            playerCommand.register(dispatcher);
            reloadCommand.register(dispatcher);
        });

        LifecycleEvent.SERVER_STARTED.register(server -> {
            currentServer = server;
            runtime.onServerStarted();
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            runtime.onServerStopping();
            currentServer = null;
        });

        LOGGER.info("ResticRestorer initialized (backup provider: {})", config.backup().provider());
    }
}
