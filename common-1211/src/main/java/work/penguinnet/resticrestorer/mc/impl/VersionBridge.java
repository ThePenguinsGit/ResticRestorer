package work.penguinnet.resticrestorer.mc.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The 1.21.1 half of the version seam (see {@link AbstractMinecraftBridge}). Everything the bridge does that
 * is <em>not</em> version-specific lives in the shared superclass; this class exists only because these four
 * calls changed shape in 1.20.5.
 */
public final class VersionBridge extends AbstractMinecraftBridge {

    public VersionBridge(Supplier<MinecraftServer> serverSupplier) {
        super(serverSupplier);
    }

    @Override
    protected ResourceLocation resourceLocation(String id) {
        return ResourceLocation.parse(id);
    }

    @Override
    protected ChunkStorage openChunkStorage(Path storeDir, ResourceKey<Level> dimensionKey, String storeType) {
        // 1.20.5+ tags every region storage with its origin; "backup" marks this one as the fetched mirror
        // rather than one of the live world's own stores.
        RegionStorageInfo info = new RegionStorageInfo("backup", dimensionKey, storeType);
        return new ChunkStorage(info, storeDir, DataFixers.getDataFixer(), false);
    }

    @Override
    protected CompoundTag readCompressedPlayerNbt(byte[] playerDataFile) throws IOException {
        return NbtIo.readCompressed(new ByteArrayInputStream(playerDataFile), NbtAccounter.unlimitedHeap());
    }

    @Override
    protected void sendEffectUpdate(ServerPlayer player, MobEffectInstance effect) {
        player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
    }
}
