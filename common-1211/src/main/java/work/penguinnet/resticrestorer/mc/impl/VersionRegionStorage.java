package work.penguinnet.resticrestorer.mc.impl;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/**
 * The 1.21.1 half of the store-write seam: the 1.20.5 refactor wrapped the {@code IOWorker} in a
 * {@code SimpleRegionStorage}, so this module's access widener opens that field instead.
 */
final class VersionRegionStorage {

    private VersionRegionStorage() {
    }

    static RegionStorageAccess entities(ServerLevel level) {
        return of(((EntityStorage) level.entityManager.permanentStorage).simpleRegionStorage);
    }

    static RegionStorageAccess poi(ServerLevel level) {
        return of(level.getPoiManager().simpleRegionStorage);
    }

    private static RegionStorageAccess of(SimpleRegionStorage storage) {
        return (pos, tag) -> {
            storage.write(pos, tag).join();
            storage.synchronize(true).join();
        };
    }
}
