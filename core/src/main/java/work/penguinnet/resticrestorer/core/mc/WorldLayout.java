package work.penguinnet.resticrestorer.core.mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vanilla's on-disk world layout, expressed without any Minecraft types: the per-dimension chunk store
 * directory names and the region/entity/POI file naming scheme. This is stable across every Minecraft version
 * the mod supports, so the version adapters share it rather than each carrying a copy.
 *
 * <p>Paths produced here are always "/"-joined and world-root-relative, because that is the shape
 * {@code BackupRepository#fetchFiles} expects regardless of host OS.
 */
public final class WorldLayout {
    public static final String REGION_DIR = "region";
    public static final String ENTITIES_DIR = "entities";
    public static final String POI_DIR = "poi";
    public static final String PLAYERDATA_DIR = "playerdata";

    /** The three per-chunk stores, in the order the bridge reads and reports them. */
    public static final List<String> CHUNK_STORE_DIRS = List.of(REGION_DIR, ENTITIES_DIR, POI_DIR);

    private WorldLayout() {
    }

    /**
     * Every file that can hold this chunk's data, relative to the world root: for each of the three stores,
     * the region file the chunk lives in plus its oversized {@code c.<x>.<z>.mcc} sibling.
     *
     * @param baseRel the dimension's storage folder relative to the world root ({@code ""} for the overworld)
     */
    public static List<String> chunkFiles(String baseRel, int chunkX, int chunkZ) {
        String regionFile = "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
        String mccFile = "c." + chunkX + "." + chunkZ + ".mcc";
        List<String> files = new ArrayList<>(CHUNK_STORE_DIRS.size() * 2);
        for (String storeDir : CHUNK_STORE_DIRS) {
            files.add(join(baseRel, storeDir, regionFile));
            files.add(join(baseRel, storeDir, mccFile));
        }
        return files;
    }

    /** The world-root-relative path of a player's data file. */
    public static String playerFile(UUID playerId) {
        return PLAYERDATA_DIR + "/" + playerId + ".dat";
    }

    /** Converts {@code base}'s path relative to {@code root} into a "/"-joined string, regardless of host OS. */
    public static String relativize(Path root, Path base) {
        Path rel = root.toAbsolutePath().normalize().relativize(base.toAbsolutePath().normalize());
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String join(String baseRel, String storeDir, String fileName) {
        return baseRel.isEmpty() ? storeDir + "/" + fileName : baseRel + "/" + storeDir + "/" + fileName;
    }
}
