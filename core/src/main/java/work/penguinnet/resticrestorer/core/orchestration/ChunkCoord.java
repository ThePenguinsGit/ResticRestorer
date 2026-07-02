package work.penguinnet.resticrestorer.core.orchestration;

/** Identifies a chunk within a dimension; also used as the in-flight concurrency guard key. */
public record ChunkCoord(String dimensionId, int chunkX, int chunkZ) {
}
