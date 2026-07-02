package work.penguinnet.resticrestorer.core.mc;

import java.util.UUID;

/**
 * The raw bytes of a player's {@code <uuid>.dat} file pulled from a backup mirror, ready to write back on the
 * server thread (see the player-restore feature). Kept as opaque bytes so :core stays MC-free — the 1.20.1
 * adapter decodes/applies the NBT on its side.
 */
public record PreparedPlayer(UUID playerId, byte[] playerDataFile) {
}
