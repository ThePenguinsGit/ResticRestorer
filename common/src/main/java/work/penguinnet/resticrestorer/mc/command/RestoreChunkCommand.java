package work.penguinnet.resticrestorer.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import work.penguinnet.resticrestorer.core.backup.BackupRepository;
import work.penguinnet.resticrestorer.core.orchestration.RestoreRequest;
import work.penguinnet.resticrestorer.core.orchestration.RestoreResult;
import work.penguinnet.resticrestorer.core.orchestration.RestoreService;
import work.penguinnet.resticrestorer.core.orchestration.SnapshotRef;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;

/**
 * {@code /restorechunk <x> <z> <snapshot> [dimension]} (see SPEC.md M5).
 */
public final class RestoreChunkCommand {
    public static final String PERMISSION_NODE = "resticrestorer.command.restorechunk";
    private static final int FALLBACK_OP_LEVEL = 3;

    private final RestoreService restoreService;
    private final PermissionCheck permissionCheck;
    private final String defaultDimensionId;
    private final SnapshotSupport snapshots;

    public RestoreChunkCommand(RestoreService restoreService, BackupRepository backupRepository,
                                PermissionCheck permissionCheck, String defaultDimensionId) {
        this.restoreService = restoreService;
        this.permissionCheck = permissionCheck;
        this.defaultDimensionId = defaultDimensionId;
        this.snapshots = new SnapshotSupport(backupRepository);
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("restore-chunk")
                .requires(source -> permissionCheck.check(source, PERMISSION_NODE, FALLBACK_OP_LEVEL))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("snapshot", StringArgumentType.word())
                                        .suggests((ctx, builder) -> snapshots.suggest(builder))
                                        .executes(ctx -> execute(ctx, null))
                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .executes(ctx -> execute(ctx, DimensionArgument.getDimension(ctx, "dimension"))))))));
    }

    private int execute(CommandContext<CommandSourceStack> ctx, ServerLevel dimensionOverride) {
        CommandSourceStack source = ctx.getSource();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        String snapshotArg = StringArgumentType.getString(ctx, "snapshot");
        String dimensionId = dimensionOverride != null
                ? dimensionOverride.dimension().location().toString()
                : defaultDimensionId;

        SnapshotRef ref = snapshots.parse(snapshotArg);
        RestoreRequest request = new RestoreRequest(dimensionId, x, z, ref);

        source.sendSuccess(() -> Component.literal("Restoring chunk (" + x + ", " + z + ") in " + dimensionId + "..."), true);
        restoreService.submit(request).whenComplete((result, throwable) ->
                source.getServer().execute(() -> reportResult(source, result, throwable)));
        return 1;
    }

    static void reportResult(CommandSourceStack source, RestoreResult result, Throwable throwable) {
        if (throwable != null) {
            source.sendFailure(Component.literal("Restore failed: " + throwable.getMessage()));
        } else if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
        } else {
            source.sendFailure(Component.literal(result.message()));
        }
    }
}
