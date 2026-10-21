package gale.module.impl.optimize;

import gale.bridge.FpsMaster;
import gale.core.HookedEffects;
import gale.event.impl.FrameEvent;
import gale.event.impl.TickEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.opengl.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Frame rate without mixins:
 * <ul>
 * <li>Entity culling: entities that full opaque blocks hide completely from the camera are not drawn. Done through
 * the entity's own render distance weight (0 = the game skips it before drawing anything); the few kinds that
 * ignore that weight (armour stands, thrown things) are skipped through Forge's RenderLivingEvent instead when
 * they are living. Players are left alone unless "Cull players" is on.</li>
 * <li>Render distance per kind of entity, as a share of the game's own distance.</li>
 * <li>A particle limit, and particles that appear outside your view are not added.</li>
 * <li>Sign text past the distance where it cannot be read is not drawn (the sign is).</li>
 * <li>A frame rate cap while the game window is in the background.</li>
 * </ul>
 * Each part gives way when FPSMaster's Performance module already does the same (both would only do the work twice).
 */
public final class Performance extends Module {
    private final BoolSetting culling = bool("Entity culling", true).describe("被整块不透明方块完全挡住的实体不画（每 1-8 tick 用射线检查一次）");
    private final BoolSetting cullPlayers = bool("Cull players", false).visibleWhen(culling::get).describe("玩家也剔除（检查比画面慢一点点：从墙后走出来的人可能晚 1-2 tick 出现）");
    private final NumberSetting players = number("Player distance", 1, 0.1, 1, 0.05).describe("玩家的渲染距离，是原版的几倍");
    private final NumberSetting passive = number("Passive distance", 1, 0.1, 1, 0.05).describe("动物、村民、鱿鱼、蝙蝠");
    private final NumberSetting hostile = number("Hostile distance", 1, 0.1, 1, 0.05).describe("怪物");
    private final NumberSetting misc = number("Misc distance", 1, 0.1, 1, 0.05).describe("掉落物、盔甲架、展示框、箭、经验球等");
    private final NumberSetting particleLimit = number("Particle limit", 1000, 0, 4000, 50).describe("最多同时有多少个粒子，0 = 不限");
    private final BoolSetting particleCulling = bool("Particle culling", true).describe("出现在视野外的粒子不加（第三人称正面视角时不管）");
    private final ModeSetting signText = mode("Sign text", "Auto", "Auto", "16", "24", "32", "48", "Off")
        .describe("多远以外不画告示牌上的字（牌子照画）。Auto：字小到看不清的距离，按窗口高度和视野算");
    private final NumberSetting backgroundFps = number("Background FPS", 30, 0, 120, 5).describe("窗口不在前台时最多几帧，0 = 不限");
    private final BoolSetting giveWay = bool("Give way to FPSMaster", true).describe("FPSMaster 的性能模块开着同样的功能时，Gale 的这一项停下");

    /** per entity: {weight it had before Gale touched it, tick last tested, hidden tests in a row, hidden (0/1), distance share, ignores the weight (0/1)} */
    private final Map<Entity, double[]> state = new WeakHashMap<>();
    /** classes whose render distance check ignores renderDistanceWeight (armour stands...) */
    private final Map<Class<?>, Boolean> ignoresWeight = new java.util.HashMap<>();
    private long tick, lastFrame;
    private int cursor;
    private int hidden, raysLastTick;
    private double particleCos = -1;
    private final List<String> givenWay = new ArrayList<>();
    private static final IChatComponent EMPTY = new ChatComponentText("");

    private static final int PLAYER = 0, PASSIVE = 1, HOSTILE = 2, MISC = 3;
    private static final int RAYS_PER_TICK = 400;

    public Performance() {
        super("Performance", Category.OPTIMIZE, "提高帧数：遮挡剔除、按类型的渲染距离、粒子上限、远处告示牌的字、后台限帧");
        on(TickEvent.Post.class, e -> update());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new LivingHook());
        onAlways(FrameEvent.class, gale.event.Priority.LOWEST, e -> background());
        HookedEffects.addFilter(new HookedEffects.Filter() {
            @Override
            public boolean active() { return isActive() && (limit() > 0 || cullParticles()); }

            @Override
            public boolean test(EntityFX fx) { return keep(fx); }
        });
    }

    @Override
    protected void onDisable() {
        restore();
        hidden = 0;
    }

    @Override
    public String getInfo() {
        if (!isEnabled()) return null;
        List<String> parts = new ArrayList<>();
        if (cullOthers() || cullPlayersNow()) parts.add("藏了 " + hidden + " 个实体");
        if (!givenWay.isEmpty()) parts.add("让给 FPSMaster：" + String.join("、", givenWay));
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    // ------------------------------------------------------------------ giving way to FPSMaster

    private boolean fm(String setting) { return giveWay.get() && FpsMaster.setting(setting); }

    private boolean cullOthers() { return culling.get() && !fm("entityCulling"); }

    private boolean cullPlayersNow() { return culling.get() && cullPlayers.get() && !(fm("entityCulling") && fm("cullPlayers")); }

    private boolean fmDistances() {
        if (!giveWay.get() || !FpsMaster.performanceOn()) return false;
        for (String f : new String[]{ "playerRenderDistance", "passiveRenderDistance", "hostileRenderDistance", "miscRenderDistance" })
            if (FpsMaster.number(f) > 0 && FpsMaster.number(f) < 1) return true;
        return false;
    }

    private int limit() { return giveWay.get() && FpsMaster.number("particlesLimit") > 0 ? 0 : particleLimit.getInt(); }

    private boolean cullParticles() { return particleCulling.get() && !fm("particleCulling"); }

    private double signDistance() {
        if (signText.is("Off") || fm("signTextCulling")) return 0;
        if (signText.is("Auto")) {
            // a line of sign text is 8 font pixels at 1/60 block each: past this it is under ~3 screen pixels tall
            double halfFov = Math.toRadians(Math.max(30, mc.gameSettings.fovSetting) / 2);
            return (8 / 60.0) * mc.displayHeight / (6 * Math.tan(halfFov));
        }
        return Double.parseDouble(signText.get());
    }

    private int backgroundCap() { return giveWay.get() && FpsMaster.number("fpsLimit") > 0 ? 0 : backgroundFps.getInt(); }

    // ------------------------------------------------------------------ every tick

    private void update() {
        tick++;
        givenWay.clear();
        if (culling.get() && fm("entityCulling")) givenWay.add("剔除");
        if (fmDistances()) givenWay.add("渲染距离");
        if (particleLimit.getInt() > 0 && limit() == 0) givenWay.add("粒子上限");
        if (particleCulling.get() && !cullParticles()) givenWay.add("粒子剔除");
        if (!signText.is("Off") && fm("signTextCulling")) givenWay.add("告示牌");
        if (backgroundFps.getInt() > 0 && backgroundCap() == 0) givenWay.add("后台限帧");
        cullEntities();
        installSignText();
        updateParticleView();
    }

    private void cullEntities() {
        Entity view = mc.getRenderViewEntity();
        World world = mc.theWorld;
        if (view == null || world == null) return;
        boolean others = cullOthers(), playersToo = cullPlayersNow();
        double[] share = fmDistances() ? new double[]{ 1, 1, 1, 1 } : new double[]{ players.get(), passive.get(), hostile.get(), misc.get() };
        Vec3 eye = view.getPositionEyes(1f);
        int rays = RAYS_PER_TICK, nowHidden = 0;
        List<Entity> all = new ArrayList<>(world.loadedEntityList);
        int size = all.size(), start = size == 0 ? 0 : cursor % size, stoppedAt = -1;
        // start where the ray budget ran out last tick, so every entity gets its turn
        for (int k = 0; k < size; k++) {
            int index = (start + k) % size;
            Entity e = all.get(index);
            if (e == view || e == mc.thePlayer) continue;
            double[] s = state.get(e);
            if (s == null) {
                s = new double[]{ e.renderDistanceWeight, -100, 0, 0, 1, ignoresWeight(e) ? 1 : 0 };
                state.put(e, s);
            }
            int kind = kind(e);
            boolean cullable = kind == PLAYER ? playersToo : others;
            if (e.riddenByEntity == mc.thePlayer || mc.thePlayer.ridingEntity == e || e.ignoreFrustumCheck) cullable = false;
            if (!cullable) {
                s[2] = s[3] = 0;
            } else {
                double d2 = e.getDistanceSq(eye.xCoord, eye.yCoord, eye.zCoord), max = maxRender(e, s[0]);
                if (d2 < 16) {
                    s[2] = s[3] = 0;
                } else if (d2 < max && tick - s[1] >= interval(d2, s[3] == 1)) {
                    if (rays <= 0) {
                        if (stoppedAt < 0) stoppedAt = index;
                    } else {
                        s[1] = tick;
                        int[] used = { 0 };
                        boolean seen = visible(world, eye, e.getEntityBoundingBox().expand(0.2, 0.2, 0.2), used);
                        rays -= used[0];
                        if (seen) s[2] = s[3] = 0;
                        else if (++s[2] >= 2) s[3] = 1;
                    }
                }
            }
            s[4] = share[kind];
            double weight = s[5] == 1 ? s[0] : s[3] == 1 ? 0 : s[0] * share[kind];
            if (e.renderDistanceWeight != weight) e.renderDistanceWeight = weight;
            if (s[3] == 1) nowHidden++;
        }
        hidden = nowHidden;
        raysLastTick = RAYS_PER_TICK - rays;
        cursor = stoppedAt >= 0 ? stoppedAt : start;
    }

    /** How often to look again: hidden ones often (they must reappear quickly), visible ones less (hiding them late only costs a little). */
    private static int interval(double d2, boolean hiddenNow) {
        if (hiddenNow) return d2 < 256 ? 2 : d2 < 1024 ? 4 : 8;
        return d2 < 256 ? 5 : d2 < 1024 ? 10 : 20;
    }

    /** Whether this kind of entity's render distance check ignores renderDistanceWeight (tried once per class). */
    private boolean ignoresWeight(Entity e) {
        Boolean b = ignoresWeight.get(e.getClass());
        if (b == null) {
            double w = e.renderDistanceWeight;
            e.renderDistanceWeight = 0;
            b = e.isInRangeToRenderDist(1);
            e.renderDistanceWeight = w;
            ignoresWeight.put(e.getClass(), b);
        }
        return b;
    }

    /** Living entities whose kind ignores the weight (armour stands): skipped here when hidden or too far. */
    public final class LivingHook {
        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGH)
        public void onLiving(net.minecraftforge.client.event.RenderLivingEvent.Pre<?> e) {
            if (!isActive() || e.isCanceled()) return;
            if (skipsLiving(e.entity, e.x * e.x + e.y * e.y + e.z * e.z)) e.setCanceled(true);
        }
    }

    /** Puts every entity's own render distance weight back. */
    private void restore() {
        for (Map.Entry<Entity, double[]> en : state.entrySet()) en.getKey().renderDistanceWeight = en.getValue()[0];
        state.clear();
    }

    private static int kind(Entity e) {
        if (e instanceof EntityPlayer) return PLAYER;
        if (e instanceof IMob) return HOSTILE;
        if (e instanceof EntityAgeable || e instanceof EntityAmbientCreature || e instanceof EntityWaterMob || e instanceof net.minecraft.entity.monster.EntityGolem) return PASSIVE;
        return MISC;
    }

    /** The squared distance within which the game would draw it at its own weight. */
    private static double maxRender(Entity e, double weight) {
        double edge = e.getEntityBoundingBox().getAverageEdgeLength();
        if (Double.isNaN(edge)) edge = 1;
        edge *= 64 * weight;
        return edge * edge;
    }

    /** Whether a straight line from the eye reaches the box (centre, then the 8 corners) past no opaque full block. */
    private static boolean visible(World w, Vec3 eye, AxisAlignedBB b, int[] rays) {
        double cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2, cz = (b.minZ + b.maxZ) / 2;
        rays[0]++;
        if (clear(w, eye.xCoord, eye.yCoord, eye.zCoord, cx, cy, cz)) return true;
        for (int i = 0; i < 8; i++) {
            rays[0]++;
            if (clear(w, eye.xCoord, eye.yCoord, eye.zCoord, (i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ)) return true;
        }
        return false;
    }

    /** Walks the blocks a line passes through (not the one it starts in); false at the first opaque full block. */
    static boolean clear(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = floor(x0), y = floor(y0), z = floor(z0);
        int ex = floor(x1), ey = floor(y1), ez = floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - x0 : x0 - x) * tdx;
        double ty = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - y0 : y0 - y) * tdy;
        double tz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - z0 : z0 - z) * tdz;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        Chunk chunk = null;
        for (int i = 0; i < steps; i++) {
            if (tx < ty && tx < tz) {
                x += sx;
                tx += tdx;
            } else if (ty < tz) {
                y += sy;
                ty += tdy;
            } else {
                z += sz;
                tz += tdz;
            }
            if (y < 0 || y > 255) continue;
            if (chunk == null || chunk.xPosition != x >> 4 || chunk.zPosition != z >> 4) chunk = w.getChunkFromChunkCoords(x >> 4, z >> 4);
            if (chunk.getBlock(x & 15, y, z & 15).isOpaqueCube()) return false;
        }
        return true;
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    // ------------------------------------------------------------------ particles

    /** The cosine of the widest angle from the view direction that can still be on screen (plus a margin). */
    private void updateParticleView() {
        double vFov = Math.toRadians(mc.gameSettings.fovSetting * 1.15);
        double aspect = mc.displayHeight == 0 ? 1 : (double) mc.displayWidth / mc.displayHeight;
        double hFov = 2 * Math.atan(Math.tan(vFov / 2) * aspect);
        double half = Math.sqrt(hFov * hFov + vFov * vFov) / 2 + Math.toRadians(10);
        particleCos = half >= Math.PI ? -1 : Math.cos(half);
    }

    private boolean keep(EntityFX fx) {
        int max = limit();
        if (max > 0 && HookedEffects.count() >= max) return false;
        if (!cullParticles() || mc.gameSettings.thirdPersonView == 2) return true;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return true;
        Vec3 eye = view.getPositionEyes(1f), look = view.getLook(1f);
        double dx = fx.posX - eye.xCoord, dy = fx.posY - eye.yCoord, dz = fx.posZ - eye.zCoord;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 3) return true;
        return (dx * look.xCoord + dy * look.yCoord + dz * look.zCoord) / len >= particleCos;
    }

    // ------------------------------------------------------------------ sign text

    private void installSignText() {
        Map<Class<? extends net.minecraft.tileentity.TileEntity>, TileEntitySpecialRenderer<? extends net.minecraft.tileentity.TileEntity>> map =
            TileEntityRendererDispatcher.instance.mapSpecialRenderers;
        TileEntitySpecialRenderer<?> r = map.get(TileEntitySign.class);
        if (r != null && !(r instanceof SignText)) {
            @SuppressWarnings("unchecked")
            SignText s = new SignText((TileEntitySpecialRenderer<TileEntitySign>) r);
            map.put(TileEntitySign.class, s);
        }
    }

    /** The game's sign renderer, drawing far signs without their text. */
    private final class SignText extends TileEntitySpecialRenderer<TileEntitySign> {
        private final TileEntitySpecialRenderer<TileEntitySign> inner;

        SignText(TileEntitySpecialRenderer<TileEntitySign> inner) {
            this.inner = inner;
            super.setRendererDispatcher(TileEntityRendererDispatcher.instance);
        }

        @Override
        public void setRendererDispatcher(TileEntityRendererDispatcher d) {
            super.setRendererDispatcher(d);
            inner.setRendererDispatcher(d);
        }

        @Override
        public boolean forceTileEntityRender() { return inner.forceTileEntityRender(); }

        @Override
        public void renderTileEntityAt(TileEntitySign te, double x, double y, double z, float partialTicks, int destroyStage) {
            double max = isEnabled() ? signDistance() : 0;
            if (max <= 0 || x * x + y * y + z * z < max * max) {
                inner.renderTileEntityAt(te, x, y, z, partialTicks, destroyStage);
                return;
            }
            IChatComponent[] text = te.signText, saved = text.clone();
            for (int i = 0; i < text.length; i++) text[i] = EMPTY;
            try {
                inner.renderTileEntityAt(te, x, y, z, partialTicks, destroyStage);
            } finally {
                System.arraycopy(saved, 0, text, 0, saved.length);
            }
        }
    }

    // ------------------------------------------------------------------ background frame rate

    /** At the start of each frame: holds the frame rate down while the window is in the background. */
    private void background() {
        long now = System.nanoTime();
        int cap = isEnabled() ? backgroundCap() : 0;
        if (cap > 0 && !Display.isActive()) {
            long wait = lastFrame + 1_000_000_000L / cap - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                now = System.nanoTime();
            }
        }
        lastFrame = now;
    }

    /** For the dev test: how many entities are hidden now, and how many rays the last tick cast. */
    public int hiddenCount() { return hidden; }

    /** Whether Gale hides this entity now (behind blocks). */
    public boolean isHidden(Entity e) {
        double[] s = state.get(e);
        return s != null && s[3] == 1;
    }

    /** For a kind that ignores the render distance weight: whether it is skipped at this squared distance from the camera. */
    public boolean skipsLiving(Entity e, double d2) {
        double[] s = state.get(e);
        if (s == null || s[5] != 1) return false;
        if (s[3] == 1) return true;
        return s[4] < 1 && !e.isInRangeToRenderDist(d2 / (s[4] * s[4]));
    }

    public int raysLastTick() { return raysLastTick; }
}
