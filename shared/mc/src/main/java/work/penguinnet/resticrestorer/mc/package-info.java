/**
 * Minecraft-facing code shared by <em>every</em> supported Minecraft version: it lives in {@code shared/mc}
 * and is compiled once per {@code common-<version>} module, against that version's mappings.
 *
 * <p>Two rules keep it compilable everywhere:
 * <ul>
 *   <li>Only use Minecraft API that exists, with the same signature, on every supported version. Anything
 *       that differs belongs behind the seam in {@code AbstractMinecraftBridge} / {@code VersionRegionStorage},
 *       which each version implements.</li>
 *   <li>Stay within the lowest Java language level in use — the 1.20.1 modules compile at Java 17, so no
 *       Java 21 language features here even though the 1.21.1 modules would accept them.</li>
 * </ul>
 */
package work.penguinnet.resticrestorer.mc;
