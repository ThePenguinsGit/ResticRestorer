package work.penguinnet.resticrestorer.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import work.penguinnet.resticrestorer.RestoreServices;
import work.penguinnet.resticrestorer.core.orchestration.PlayerRestoreRequest;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;

/**
 * {@code /restore-player <Username|UUID> <Snapshot Time|Snapshot Id>} — restores a player's entire data file
 * from a backup, applying live to the player entity if they're currently online.
 */
public final class RestorePlayerCommand {
    public static final String PERMISSION_NODE = "resticrestorer.command.restoreplayer";
    private static final int FALLBACK_OP_LEVEL = 3;

    private final RestoreServices services;
    private final PermissionCheck permissionCheck;
    private final SnapshotSupport snapshots;

    public RestorePlayerCommand(RestoreServices services, PermissionCheck permissionCheck) {
        this.services = services;
        this.permissionCheck = permissionCheck;
        this.snapshots = new SnapshotSupport(services::backupRepository);
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("restore-player")
                .requires(source -> permissionCheck.check(source, PERMISSION_NODE, FALLBACK_OP_LEVEL))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                        .then(Commands.argument("snapshot", StringArgumentType.word())
                                .suggests((ctx, builder) -> snapshots.suggest(builder))
                                .executes(this::execute))));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String playerSelector = StringArgumentType.getString(ctx, "player");
        String snapshotArg = StringArgumentType.getString(ctx, "snapshot");

        SnapshotRef ref = snapshots.parse(snapshotArg);
        PlayerRestoreRequest request = new PlayerRestoreRequest(playerSelector, ref);

        source.sendSuccess(() -> Component.literal("Restoring player " + playerSelector + "..."), true);
        services.restoreService().submitPlayer(request).whenComplete((result, throwable) ->
                source.getServer().execute(() -> RestoreChunkCommand.reportResult(source, result, throwable)));
        return 1;
    }
}
