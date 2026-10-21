package gale.script.api;

/** Registers every part of Gale's script API. One class per area; add new areas here. */
public final class ScriptApis {
    private ScriptApis() { }

    public static void install() {
        CoreApi.install();
        PacketApi.install();
        CombatApi.install();
        DataApi.install();
        RenderApi.install();
        InputApi.install();
        ScreenApi.install();
        SystemApi.install();
        PickApi.install();
    }

    /** Start of every tick: puts in (or takes out) the game objects Gale swaps while scripts listen to their events. */
    public static void tick() {
        InputApi.tick();
        PickApi.tick();
    }
}