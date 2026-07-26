package work.penguinnet.resticrestorer.neoforge;

import net.neoforged.fml.common.Mod;
import work.penguinnet.resticrestorer.Resticrestorer;

@Mod(Resticrestorer.MOD_ID)
public final class ResticrestorerNeoForge {
    public ResticrestorerNeoForge() {
        // Architectury 13.x auto-discovers the NeoForge mod event bus (no manual registerModEventBus like the
        // 1.20.1 Forge entrypoint) — we register no game-object registries, only Architectury runtime
        // events/commands, so nothing here needs the mod bus.
        NeoForgePermissionNodes.init();

        // Run our common setup.
        Resticrestorer.init(new NeoForgePermissionCheck());
    }
}
