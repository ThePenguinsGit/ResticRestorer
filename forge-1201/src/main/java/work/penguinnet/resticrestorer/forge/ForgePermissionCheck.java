package work.penguinnet.resticrestorer.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;

/**
 * Queries a registered {@link PermissionNode} (LuckPerms and other permission mods provide values for it via
 * Forge's PermissionAPI); falls back to the vanilla op-level check for non-player sources or if the node
 * hasn't been registered yet.
 */
public final class ForgePermissionCheck implements PermissionCheck {
    @Override
    public boolean check(Object source, String node, int fallbackOpLevel) {
        CommandSourceStack css = (CommandSourceStack) source;
        PermissionNode<Boolean> registered = ForgePermissionNodes.node(node);
        if (registered != null && css.getEntity() instanceof ServerPlayer player) {
            return PermissionAPI.getPermission(player, registered);
        }
        return css.hasPermission(fallbackOpLevel);
    }
}
