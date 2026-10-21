package gale.core;

import gale.Gale;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The game's particle system with a look at every particle before it is added (scripts' "particle" event, the
 * particle limit). While some filter is registered and active, Gale puts its own subclass in place of the game's,
 * taking over the old one's particles and state; a filter that returns false drops the particle.
 */
public final class HookedEffects extends EffectRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<Filter> FILTERS = new CopyOnWriteArrayList<>();
    private static final Field LAYERS = ReflectUtil.field(EffectRenderer.class, "fxLayers", "field_78876_b");

    /** A look at particles: keep returns false to drop one; active says whether it wants particles at all now. */
    public interface Filter extends Predicate<EntityFX> {
        boolean active();
    }

    private HookedEffects(World world) { super(world, mc.getTextureManager()); }

    public static void addFilter(Filter f) { FILTERS.add(f); }

    private static boolean wanted() {
        for (Filter f : FILTERS) if (f.active()) return true;
        return false;
    }

    /** Every tick: puts Gale's particle system in while a filter is active (it stays afterwards; it passes everything then). */
    static void tick() {
        if (!wanted() || mc.effectRenderer == null || mc.effectRenderer instanceof HookedEffects || mc.theWorld == null) return;
        try {
            HookedEffects h = new HookedEffects(mc.theWorld);
            for (Field f : EffectRenderer.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(h, f.get(mc.effectRenderer));
            }
            mc.effectRenderer = h;
        } catch (Exception e) {
            Gale.LOG.warn("Could not hook the particles", e);
        }
    }

    /** How many particles the game has now (all layers). */
    public static int count() {
        EffectRenderer r = mc.effectRenderer;
        Object layers = r == null ? null : ReflectUtil.get(LAYERS, r);
        if (!(layers instanceof List[][])) return 0;
        int n = 0;
        for (List<?>[] row : (List<?>[][]) layers) for (List<?> l : row) n += l.size();
        return n;
    }

    @Override
    public void addEffect(EntityFX fx) {
        if (fx != null) {
            for (Filter f : FILTERS) {
                if (f.active() && !f.test(fx)) return;
            }
        }
        super.addEffect(fx);
    }
}
