package work.penguinnet.resticrestorer.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;

/**
 * Queries a registered {@link PermissionNode} (LuckPerms and other permission mods provide values for it via
 * NeoForge's PermissionAPI); falls back to the vanilla op-level check for non-player sources or if the node
 * hasn't been registered yet (see SPEC.md M5).
 */
public final class NeoForgePermissionCheck implements PermissionCheck {
    @Override
    public boolean check(Object source, String node, int fallbackOpLevel) {
        CommandSourceStack css = (CommandSourceStack) source;
        PermissionNode<Boolean> registered = NeoForgePermissionNodes.node(node);
        if (registered != null && css.getEntity() instanceof ServerPlayer player) {
            return PermissionAPI.getPermission(player, registered);
        }
        return css.hasPermission(fallbackOpLevel);
    }
}
