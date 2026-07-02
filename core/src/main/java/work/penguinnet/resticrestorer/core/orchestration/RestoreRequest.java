package work.penguinnet.resticrestorer.core.orchestration;

public record RestoreRequest(String dimensionId, int chunkX, int chunkZ, SnapshotRef snapshotRef) {

    public ChunkCoord chunk() {
        return new ChunkCoord(dimensionId, chunkX, chunkZ);
    }
}
