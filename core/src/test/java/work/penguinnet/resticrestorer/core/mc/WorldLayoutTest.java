package work.penguinnet.resticrestorer.core.mc;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldLayoutTest {

    @Test
    void listsRegionAndOversizedFilesForEveryStore() {
        List<String> files = WorldLayout.chunkFiles("", 0, 0);

        assertEquals(List.of(
                "region/r.0.0.mca", "region/c.0.0.mcc",
                "entities/r.0.0.mca", "entities/c.0.0.mcc",
                "poi/r.0.0.mca", "poi/c.0.0.mcc"), files);
    }

    @Test
    void prefixesTheDimensionFolderWhenNotTheOverworld() {
        List<String> files = WorldLayout.chunkFiles("DIM-1", 33, 64);

        assertEquals("DIM-1/region/r.1.2.mca", files.get(0));
        assertEquals("DIM-1/region/c.33.64.mcc", files.get(1));
    }

    @Test
    void mapsNegativeChunkCoordinatesWithArithmeticShift() {
        // -1 >> 5 == -1, not 0: chunk (-1, -1) lives in region r.-1.-1, not r.0.0.
        List<String> files = WorldLayout.chunkFiles("", -1, -33);

        assertEquals("region/r.-1.-2.mca", files.get(0));
        assertEquals("region/c.-1.-33.mcc", files.get(1));
    }

    @Test
    void buildsPlayerDataPath() {
        UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        assertEquals("playerdata/069a79f4-44e9-4726-a5be-fca90e38aaf5.dat", WorldLayout.playerFile(id));
    }

    @Test
    void relativizesToSlashJoinedPathRegardlessOfHostSeparator() {
        Path root = Path.of("").toAbsolutePath().resolve("world");

        assertEquals("DIM-1/region", WorldLayout.relativize(root, root.resolve("DIM-1").resolve("region")));
        assertEquals("", WorldLayout.relativize(root, root));
    }
}
