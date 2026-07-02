package work.penguinnet.resticrestorer.core.permission;

/**
 * LuckPerms-style permission gate (see SPEC.md M5), implemented per loader. {@code source} is whatever the
 * loader's command system passes (e.g. a Brigadier {@code CommandSourceStack}) — kept as {@code Object} so
 * :core stays Minecraft-free.
 */
public interface PermissionCheck {
    boolean check(Object source, String node, int fallbackOpLevel);
}
