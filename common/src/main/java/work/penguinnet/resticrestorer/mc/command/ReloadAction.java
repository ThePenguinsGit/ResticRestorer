package work.penguinnet.resticrestorer.mc.command;

/**
 * Reloads {@code resticrestorer.json} and rebuilds the config-derived services. Implemented by the mod entrypoint;
 * kept as an interface so {@link ReloadCommand} doesn't depend on the wiring class directly.
 */
@FunctionalInterface
public interface ReloadAction {
    /**
     * Performs the reload and returns a short human-readable summary of the new config.
     *
     * @throws Exception if the config can't be read/parsed; the previous config stays in effect.
     */
    String reload() throws Exception;
}
