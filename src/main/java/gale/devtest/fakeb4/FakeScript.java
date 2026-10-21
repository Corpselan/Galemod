package gale.devtest.fakeb4;

import java.util.Comparator;
import java.util.Map;

/**
 * For the self test only: a "B4 script". Like a real one it extends the defaults class, links with
 * client.runForgeCommand("gale link") and talks to Gale only through the Map from bridge.get("gale"), with types a
 * B4 script may use (Map, Object[], Comparator lambdas).
 */
public final class FakeScript extends FakeDefaults {
    public Map<String, Object> gale;

    @SuppressWarnings("unchecked")
    public boolean link() {
        client.runForgeCommand("gale link");
        gale = (Map<String, Object>) bridge.get("gale");
        return gale != null;
    }

    public Object call(String text) { return gale.get(text); }

    public Object call(String name, Object... args) { return gale.put(name, args); }

    public void on(String event, Comparator<Object> callback) { gale.put("on", new Object[]{ event, "DevTestScript", callback }); }

    public void off() { gale.put("off", new Object[]{ "DevTestScript" }); }

    public Entity wrap(net.minecraft.entity.Entity e) { return Entity.of(e); }

    public Vec3 vec(double x, double y, double z) { return new Vec3(x, y, z); }
}
