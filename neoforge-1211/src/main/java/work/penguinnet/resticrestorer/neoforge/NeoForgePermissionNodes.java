package work.penguinnet.resticrestorer.neoforge;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import work.penguinnet.resticrestorer.Resticrestorer;
import work.penguinnet.resticrestorer.core.permission.PermissionNodes;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the LuckPerms-compatible {@link PermissionNode}s backing the mod's commands, keyed by their
 * full node name so {@link NeoForgePermissionCheck} can look each one up. The node table itself lives in
 * :core's {@link PermissionNodes}, shared with the commands that gate on it.
 */
public final class NeoForgePermissionNodes {
    private static final Map<String, PermissionNode<Boolean>> NODES = new HashMap<>();

    private NeoForgePermissionNodes() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(NeoForgePermissionNodes::onGatherNodes);
    }

    /** Returns the registered node for {@code fullNodeName}, or null if none/not gathered yet. */
    public static PermissionNode<Boolean> node(String fullNodeName) {
        return NODES.get(fullNodeName);
    }

    private static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        for (PermissionNodes.Node spec : PermissionNodes.all()) {
            register(event, spec);
        }
    }

    private static void register(PermissionGatherEvent.Nodes event, PermissionNodes.Node spec) {
        PermissionNode<Boolean> node = new PermissionNode<>(Resticrestorer.MOD_ID, spec.path(), PermissionTypes.BOOLEAN,
                (player, playerUuid, context) -> false);
        NODES.put(spec.name(), node);
        event.addNodes(node);
    }
}
