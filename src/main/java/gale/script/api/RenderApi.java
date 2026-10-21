package gale.script.api;

import gale.core.HookedEffects;
import gale.script.B4Link;
import gale.script.Functions;
import gale.script.ScriptEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drawing: hide or see entities through walls, hide nametags, the hand, parts of the HUD and the block outline; change
 * the FOV and the camera; remove particles; read and change game options (brightness, FOV, sensitivity, third person...).
 */
public final class RenderApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "render";
    private static final Map<String, Option> OPTIONS = new LinkedHashMap<>();

    private RenderApi() { }

    private static final class Option {
        final Supplier<Object> get;
        final Consumer<Object> set;

        Option(Supplier<Object> get, Consumer<Object> set) {
            this.get = get;
            this.set = set;
        }
    }

    public static void install() {
        ScriptEvents.define(AREA, "renderLiving", "{Entity 实体, Double x, Double y, Double z, Boolean 透视}",
            "要画一个生物（玩家、怪物、动物）之前，每帧每个生物一次，游戏线程。x/y/z 是相对相机的位置。返回非 0 = 不画它；"
                + "args[4] 设成 true = 这一次隔着墙也画出来（透视）。");
        ScriptEvents.define(AREA, "renderLivingPost", "{Entity 实体, Double x, Double y, Double z}", "画完一个生物之后（可以接着画自己的东西）。");
        ScriptEvents.define(AREA, "nametag", "{Entity 实体, Double x, Double y, Double z}", "要画生物头上的名字之前。返回非 0 = 不画原版名字。");
        ScriptEvents.define(AREA, "hand", "{Double partialTicks}", "要画第一人称的手之前。返回非 0 = 不画手。");
        ScriptEvents.define(AREA, "overlay", "{String 部分}",
            "要画 HUD 的一部分之前：CROSSHAIRS、HEALTH、FOOD、ARMOR、AIR、HOTBAR、EXPERIENCE、BOSSHEALTH、HELMET、PORTAL、"
                + "CHAT、PLAYER_LIST、DEBUG、TEXT、JUMPBAR、HEALTHMOUNT。返回非 0 = 不画这部分。");
        ScriptEvents.define(AREA, "blockHighlight", "{Vec3 方块}", "要画准星指着的方块的框之前。返回非 0 = 不画。");
        ScriptEvents.define(AREA, "fov", "{Double 倍数}", "视野倍数在变（疾跑、速度药水、拉弓会变）。args[0] 改成 1.0 = 不让它变。");
        ScriptEvents.define(AREA, "camera", "{Double yaw, Double pitch, Double roll}", "每帧摆相机的时候，改 args 只改画面的角度（不改你的朝向）。");
        ScriptEvents.define(AREA + ".particles", "particle", "{String 种类, Vec3 位置}",
            "要加一个粒子（暴击、方块碎屑、药水...），种类是类名，例如 \"EntityCrit2FX\"。返回非 0 = 不加。只在有脚本监听时接管粒子系统。");

        Functions.add(AREA, "option.get", "<名字>", "读游戏选项（option.list 看全部名字）。", a -> option(a.str(0)).get.get());
        Functions.add(AREA, "option.set", "<名字> <值>", "改游戏选项，马上生效（不写进 options.txt，除非你之后在选项界面里保存）。", a -> {
            CombatApi.mainThread("option.set");
            option(a.str(0)).set.accept(a.raw(1));
            return true;
        });
        Functions.add(AREA, "option.list", "", "所有能读写的选项和现在的值（Map）。", a -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Option> e : OPTIONS.entrySet()) out.put(e.getKey(), e.getValue().get.get());
            return out;
        });

        GameSettings s = mc.gameSettings;
        number("gamma", () -> s.gammaSetting, v -> s.gammaSetting = (float) v);
        number("fov", () -> s.fovSetting, v -> s.fovSetting = (float) v);
        number("sensitivity", () -> s.mouseSensitivity, v -> s.mouseSensitivity = (float) Math.max(0, Math.min(1, v)));
        bool("viewBobbing", () -> s.viewBobbing, v -> s.viewBobbing = v);
        number("thirdPerson", () -> s.thirdPersonView, v -> s.thirdPersonView = ((int) v % 3 + 3) % 3);
        number("renderDistance", () -> s.renderDistanceChunks, v -> s.setOptionFloatValue(GameSettings.Options.RENDER_DISTANCE, (float) v));
        number("maxFps", () -> s.limitFramerate, v -> s.limitFramerate = (int) v);
        number("guiScale", () -> s.guiScale, v -> s.guiScale = (int) v);
        bool("smoothCamera", () -> s.smoothCamera, v -> s.smoothCamera = v);
        bool("hideGui", () -> s.hideGUI, v -> s.hideGUI = v);
        bool("showDebug", () -> s.showDebugInfo, v -> s.showDebugInfo = v);
        bool("invertMouse", () -> s.invertMouse, v -> s.invertMouse = v);
        bool("entityShadows", () -> s.entityShadows, v -> s.entityShadows = v);
        number("particles", () -> s.particleSetting, v -> s.particleSetting = (int) v);
        number("clouds", () -> s.clouds, v -> s.clouds = (int) v);
        number("chatOpacity", () -> s.chatOpacity, v -> s.chatOpacity = (float) v);

        MinecraftForge.EVENT_BUS.register(new Hooks());
        HookedEffects.addFilter(new HookedEffects.Filter() {
            @Override
            public boolean active() { return ScriptEvents.has("particle"); }

            @Override
            public boolean test(EntityFX fx) {
                return !ScriptEvents.fire("particle", new Object[]{ fx.getClass().getSimpleName(), B4Link.wrap(new Vec3(fx.posX, fx.posY, fx.posZ)) });
            }
        });
    }

    private static void number(String name, Supplier<Number> get, java.util.function.DoubleConsumer set) {
        OPTIONS.put(name, new Option(() -> get.get().doubleValue(), v -> set.accept(toDouble(name, v))));
    }

    private static void bool(String name, Supplier<Boolean> get, Consumer<Boolean> set) {
        OPTIONS.put(name, new Option(get::get, v -> set.accept(v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v).trim()))));
    }

    private static double toDouble(String name, Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 要数字");
        }
    }

    private static Option option(String name) {
        for (Map.Entry<String, Option> e : OPTIONS.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        throw new IllegalArgumentException("没有选项 " + name + "（有：" + String.join(", ", OPTIONS.keySet()) + "）");
    }

    /** Forge's drawing events, passed to scripts only while one listens. */
    public static final class Hooks {
        private Entity seeThrough;

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onLiving(RenderLivingEvent.Pre<?> e) {
            if (e.isCanceled() || !ScriptEvents.has("renderLiving")) return;
            Object[] a = { B4Link.wrap(e.entity), e.x, e.y, e.z, false };
            if (ScriptEvents.fire("renderLiving", a)) {
                e.setCanceled(true);
                return;
            }
            if (Boolean.TRUE.equals(a[4])) {
                seeThrough = e.entity;
                GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                GL11.glPolygonOffset(1f, -1100000f);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onLivingPost(RenderLivingEvent.Post<?> e) {
            if (seeThrough == e.entity) {
                GL11.glPolygonOffset(1f, 1100000f);
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                seeThrough = null;
            }
            if (ScriptEvents.has("renderLivingPost")) ScriptEvents.fire("renderLivingPost", new Object[]{ B4Link.wrap(e.entity), e.x, e.y, e.z });
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onNametag(RenderLivingEvent.Specials.Pre<?> e) {
            if (e.isCanceled() || !ScriptEvents.has("nametag")) return;
            if (ScriptEvents.fire("nametag", new Object[]{ B4Link.wrap(e.entity), e.x, e.y, e.z })) e.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onHand(RenderHandEvent e) {
            if (e.isCanceled() || !ScriptEvents.has("hand")) return;
            if (ScriptEvents.fire("hand", new Object[]{ (double) e.partialTicks })) e.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onOverlay(RenderGameOverlayEvent.Pre e) {
            if (e.isCanceled() || !ScriptEvents.has("overlay")) return;
            if (ScriptEvents.fire("overlay", new Object[]{ e.type.name() })) e.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onHighlight(DrawBlockHighlightEvent e) {
            if (e.isCanceled() || !ScriptEvents.has("blockHighlight") || e.target == null) return;
            if (e.target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
            if (ScriptEvents.fire("blockHighlight", new Object[]{ B4Link.wrap(e.target.getBlockPos()) })) e.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onFov(FOVUpdateEvent e) {
            if (!ScriptEvents.has("fov")) return;
            Object[] a = { (double) e.newfov };
            ScriptEvents.fire("fov", a);
            if (a[0] instanceof Number) e.newfov = ((Number) a[0]).floatValue();
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onCamera(EntityViewRenderEvent.CameraSetup e) {
            if (!ScriptEvents.has("camera")) return;
            Object[] a = { (double) e.yaw, (double) e.pitch, (double) e.roll };
            ScriptEvents.fire("camera", a);
            e.yaw = num(a[0], e.yaw);
            e.pitch = num(a[1], e.pitch);
            e.roll = num(a[2], e.roll);
        }

        private static float num(Object o, float fallback) { return o instanceof Number ? ((Number) o).floatValue() : fallback; }
    }

    static List<String> optionNames() { return new ArrayList<>(OPTIONS.keySet()); }
}
