package work.penguinnet.resticrestorer.forge;

import work.penguinnet.resticrestorer.Resticrestorer;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Resticrestorer.MOD_ID)
public final class ResticrestorerForge {
    public ResticrestorerForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(Resticrestorer.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        ForgePermissionNodes.init();

        // Run our common setup.
        Resticrestorer.init(new ForgePermissionCheck());
    }
}
