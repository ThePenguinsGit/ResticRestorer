package work.penguinnet.resticrestorer.core.permission;

import java.util.List;

/**
 * The permission nodes backing the mod's commands (see SPEC.md M5). Every loader needs this same table — the
 * command classes gate on it, and the Forge-family loaders additionally register each node with their
 * PermissionAPI — so it lives here instead of being spread across the command and loader classes.
 */
public final class PermissionNodes {
    /**
     * @param name            the full node name permission mods are configured with
     * @param path            the node name without the mod-id prefix, which is how the Forge-family
     *                        {@code PermissionNode} constructors want it
     * @param fallbackOpLevel vanilla op level required when no permission mod answers for the node
     */
    public record Node(String name, String path, int fallbackOpLevel) {
    }

    private static final String MOD_ID = "resticrestorer";
    private static final int ADMIN_OP_LEVEL = 3;

    public static final Node RESTORE_CHUNK = node("command.restorechunk");
    public static final Node RESTORE_PLAYER = node("command.restoreplayer");
    public static final Node RELOAD = node("command.reload");

    private static final List<Node> ALL = List.of(RESTORE_CHUNK, RESTORE_PLAYER, RELOAD);

    private PermissionNodes() {
    }

    /** Every node the mod defines, for loaders that have to register them up front. */
    public static List<Node> all() {
        return ALL;
    }

    private static Node node(String path) {
        return new Node(MOD_ID + "." + path, path, ADMIN_OP_LEVEL);
    }
}
