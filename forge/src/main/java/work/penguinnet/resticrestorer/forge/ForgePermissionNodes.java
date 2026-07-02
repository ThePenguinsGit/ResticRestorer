package work.penguinnet.resticrestorer.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import work.penguinnet.resticrestorer.Resticrestorer;
import work.penguinnet.resticrestorer.mc.command.RestoreChunkCommand;
import work.penguinnet.resticrestorer.mc.command.RestorePlayerCommand;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the LuckPerms-compatible {@link PermissionNode}s backing the mod's commands, keyed by their
 * full node name so {@link ForgePermissionCheck} can look each one up.
 */
public final class ForgePermissionNodes {
    private static final Map<String, PermissionNode<Boolean>> NODES = new HashMap<>();

    private ForgePermissionNodes() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ForgePermissionNodes::onGatherNodes);
    }

    /** Returns the registered node for {@code fullNodeName}, or null if none/not gathered yet. */
    public static PermissionNode<Boolean> node(String fullNodeName) {
        return NODES.get(fullNodeName);
    }

    private static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        register(event, RestoreChunkCommand.PERMISSION_NODE, "command.restorechunk");
        register(event, RestorePlayerCommand.PERMISSION_NODE, "command.restoreplayer");
    }

    private static void register(PermissionGatherEvent.Nodes event, String fullNodeName, String nodePath) {
        PermissionNode<Boolean> node = new PermissionNode<>(Resticrestorer.MOD_ID, nodePath, PermissionTypes.BOOLEAN,
                (player, playerUuid, context) -> false);
        NODES.put(fullNodeName, node);
        event.addNodes(node);
    }
}
