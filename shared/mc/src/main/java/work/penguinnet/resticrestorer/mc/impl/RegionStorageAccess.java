package work.penguinnet.resticrestorer.mc.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/**
 * Write access to one of a dimension's live per-chunk stores (entities or POI). The type behind it changed
 * in 1.20.5 — an {@code IOWorker} before, a {@code SimpleRegionStorage} wrapping one after — so each version
 * supplies its own implementation through {@link VersionRegionStorage}.
 */
@FunctionalInterface
interface RegionStorageAccess {

    /** Writes the chunk's raw NBT and blocks until it has hit the disk. */
    void writeAndFlush(ChunkPos pos, CompoundTag tag);
}
