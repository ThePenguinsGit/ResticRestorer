package work.penguinnet.resticrestorer.forge;

import work.penguinnet.resticrestorer.Resticrestorer;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Resticrestorer.MOD_ID)
public final class ResticrestorerForge {
    /**
     * FML injects the loading context when the mod class declares a constructor taking one; the older
     * {@code FMLJavaModLoadingContext.get()} is deprecated for removal. Injection needs Forge 47.3.10 or newer
     * (47.3.7 still only looks for a no-arg constructor), which is why {@code mods.toml} requires that version —
     * on anything older FML would fail to find a usable constructor.
     */
    public ResticrestorerForge(FMLJavaModLoadingContext context) {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(Resticrestorer.MOD_ID, context.getModEventBus());

        ForgePermissionNodes.init();

        // Run our common setup.
        Resticrestorer.init(new ForgePermissionCheck());
    }
}
