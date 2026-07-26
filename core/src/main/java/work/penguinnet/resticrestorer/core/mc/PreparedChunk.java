package work.penguinnet.resticrestorer.core.mc;

/**
 * Raw chunk NBT blobs pulled from a backup mirror, ready to write back on the server thread.
 * A {@code null} blob means that store had no data for this chunk in the backup.
 */
public record PreparedChunk(String dimensionId, int chunkX, int chunkZ, byte[] regionNbt, byte[] entityNbt, byte[] poiNbt) {
}
