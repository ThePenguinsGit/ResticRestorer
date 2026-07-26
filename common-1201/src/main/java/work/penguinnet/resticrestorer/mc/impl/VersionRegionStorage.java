package work.penguinnet.resticrestorer.mc.impl;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;

/**
 * The 1.20.1 half of the store-write seam: both the entity and POI stores expose a raw {@code IOWorker}
 * (reached through this module's access widener).
 */
final class VersionRegionStorage {

    private VersionRegionStorage() {
    }

    static RegionStorageAccess entities(ServerLevel level) {
        return of(((EntityStorage) level.entityManager.permanentStorage).worker);
    }

    static RegionStorageAccess poi(ServerLevel level) {
        return of(level.getPoiManager().worker);
    }

    private static RegionStorageAccess of(IOWorker worker) {
        return (pos, tag) -> {
            worker.store(pos, tag).join();
            worker.synchronize(true).join();
        };
    }
}
