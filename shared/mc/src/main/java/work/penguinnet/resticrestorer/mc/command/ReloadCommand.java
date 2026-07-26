package work.penguinnet.resticrestorer.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;
import work.penguinnet.resticrestorer.core.permission.PermissionNodes;

/**
 * {@code /restore-reload} — re-reads {@code resticrestorer.json} and rebuilds the config-derived services
 * (backup repository, restore pipeline, RabbitMQ consumer) without restarting the server.
 */
public final class ReloadCommand {
    private static final PermissionNodes.Node NODE = PermissionNodes.RELOAD;

    private final ReloadAction reloadAction;
    private final PermissionCheck permissionCheck;

    public ReloadCommand(ReloadAction reloadAction, PermissionCheck permissionCheck) {
        this.reloadAction = reloadAction;
        this.permissionCheck = permissionCheck;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("restore-reload")
                .requires(source -> permissionCheck.check(source, NODE.name(), NODE.fallbackOpLevel()))
                .executes(this::execute));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            String summary = reloadAction.reload();
            source.sendSuccess(() -> Component.literal("ResticRestorer config reloaded (" + summary + ")"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("ResticRestorer config reload failed (previous config kept): "
                    + e.getMessage()));
            return 0;
        }
    }
}
