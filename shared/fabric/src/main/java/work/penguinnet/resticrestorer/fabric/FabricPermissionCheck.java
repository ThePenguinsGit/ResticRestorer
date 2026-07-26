package work.penguinnet.resticrestorer.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import work.penguinnet.resticrestorer.core.permission.PermissionCheck;

/** Delegates to fabric-permissions-api, which LuckPerms and other permission mods hook into (see SPEC.md M5). */
public final class FabricPermissionCheck implements PermissionCheck {
    @Override
    public boolean check(Object source, String node, int fallbackOpLevel) {
        return Permissions.check((CommandSourceStack) source, node, fallbackOpLevel);
    }
}
