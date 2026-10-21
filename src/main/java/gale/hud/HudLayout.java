package gale.hud;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.module.Module;
import gale.util.Anim;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The HUD's layout, and its editor: with the chat open, every HUD element of a module that is on gets a frame. Drag
 * it to move it (it snaps to the middle and edges of the screen and to the other elements, with a guide line; hold
 * Shift not to snap), drag its corner handle or turn the mouse wheel over it to resize it, right click it to put it
 * back. Elements anchored to the bottom rise above the chat box while it is open. Saved in galemod/hud.json.
 */
public final class HudLayout {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<HudElement> ELEMENTS = new CopyOnWriteArrayList<>();
    private static final float SNAP = 4f, PAD = 2f, HANDLE = 5f, CHAT_LIFT = 14f;
    private static JsonObject saved;
    private static float lift, overlay;
    private static final Anim.Clock CLOCK = new Anim.Clock();
    // the editor
    private static HudElement hover, dragging, resizing;
    private static float grabX, grabY, fixedX, fixedY, startSize, startScale;
    private static final List<float[]> guides = new ArrayList<>();

    private HudLayout() { }

    // ------------------------------------------------------------------ elements

    /** A HUD element placed by anchor and distance (left / centre / right, top / middle / bottom); see {@link HudBox}. */
    public static HudBox box(String id, String name, Module owner, int anchorX, int anchorY, float x, float y, float defaultW, float defaultH) {
        HudBox b = new HudBox(id, name, owner, anchorX, anchorY, x, y, defaultW, defaultH);
        JsonElement s = saved().get(id);
        if (s != null && s.isJsonObject()) b.fromJson(s.getAsJsonObject());
        ELEMENTS.add(b);
        return b;
    }

    /** An element that keeps its place itself (in its module's settings). */
    public static void register(HudElement e) { ELEMENTS.add(e); }

    public static List<HudElement> elements() { return ELEMENTS; }

    public static HudElement get(String id) {
        for (HudElement e : ELEMENTS) if (e.id.equals(id)) return e;
        return null;
    }

    /** How far bottom elements are lifted now (above the chat box while it is open). */
    public static float chatLift() { return lift; }

    public static boolean editing() { return mc.currentScreen instanceof GuiChat; }

    // ------------------------------------------------------------------ saving

    private static File file() { return new File(Gale.config().dir(), "hud.json"); }

    private static JsonObject saved() {
        if (saved == null) {
            saved = new JsonObject();
            File f = file();
            if (f.isFile()) {
                try {
                    JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    if (e.isJsonObject()) saved = e.getAsJsonObject();
                } catch (Exception ex) {
                    Gale.LOG.warn("Could not read {}", f, ex);
                }
            }
        }
        return saved;
    }

    public static void save() {
        JsonObject o = new JsonObject();
        // what was saved for elements not here now (a module from a script) is kept
        for (Map.Entry<String, JsonElement> e : saved().entrySet()) o.add(e.getKey(), e.getValue());
        for (HudElement e : ELEMENTS) if (e instanceof HudBox) o.add(e.id, ((HudBox) e).toJson());
        saved = o;
        try {
            Files.write(file().toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(o).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Gale.LOG.warn("Could not save {}", file(), ex);
        }
        Gale.config().markDirty();
    }

    /** Every element back to where it starts. */
    public static void resetAll() {
        for (HudElement e : ELEMENTS) e.reset();
        save();
    }

    // ------------------------------------------------------------------ the editor

    public static void install() {
        MinecraftForge.EVENT_BUS.register(new HudLayout.Events());
        Gale.BUS.listen(FrameEvent.class, Priority.HIGH, e -> {
            float dt = CLOCK.tick();
            boolean open = editing();
            lift = Anim.approach(lift, open ? CHAT_LIFT : 0f, 16f, dt);
            overlay = Anim.approach(overlay, open ? 1f : 0f, 14f, dt);
            if (!open && (dragging != null || resizing != null)) {
                dragging = resizing = null;
                guides.clear();
                save();
            }
        }, "hud");
    }

    private static List<HudElement> listed() {
        List<HudElement> out = new ArrayList<>();
        for (HudElement e : ELEMENTS) if (e.listed()) out.add(e);
        return out;
    }

    private static boolean inside(float[] b, float mx, float my) { return mx >= b[0] - 1 && mx <= b[0] + b[2] + 1 && my >= b[1] - 1 && my <= b[1] + b[3] + 1; }

    private static float[] handle(HudElement e, float[] b) {
        float hx = e.handleRight() ? b[0] + b[2] : b[0], hy = e.handleBottom() ? b[1] + b[3] : b[1];
        return new float[]{ hx - HANDLE / 2f, hy - HANDLE / 2f, HANDLE, HANDLE };
    }

    /** The element under the mouse (the last drawn is on top), or null. */
    private static HudElement at(float mx, float my, float sw, float sh) {
        List<HudElement> l = listed();
        for (int i = l.size() - 1; i >= 0; i--) {
            HudElement e = l.get(i);
            float[] b = e.bounds(sw, sh);
            if (inside(b, mx, my) || inside(handle(e, b), mx, my)) return e;
        }
        return null;
    }

    /** Moves the dragged element to x, y: snapped to the screen's middle and edges and to the others (unless Shift). */
    private static void drag(float nx, float ny, float sw, float sh) {
        float[] b = dragging.bounds(sw, sh);
        float W = b[2], H = b[3];
        guides.clear();
        if (!Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) && !Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
            xs.add(PAD);
            xs.add(sw / 2f);
            xs.add(sw - PAD);
            ys.add(PAD);
            ys.add(sh / 2f);
            ys.add(sh - PAD - lift);
            for (HudElement o : listed()) {
                if (o == dragging) continue;
                float[] ob = o.bounds(sw, sh);
                xs.add(ob[0]);
                xs.add(ob[0] + ob[2] / 2f);
                xs.add(ob[0] + ob[2]);
                ys.add(ob[1]);
                ys.add(ob[1] + ob[3] / 2f);
                ys.add(ob[1] + ob[3]);
            }
            float[] sx = snap(new float[]{ nx, nx + W / 2f, nx + W }, xs);
            if (sx != null) {
                nx += sx[0];
                guides.add(new float[]{ 0, sx[1] });
            }
            float[] sy = snap(new float[]{ ny, ny + H / 2f, ny + H }, ys);
            if (sy != null) {
                ny += sy[0];
                guides.add(new float[]{ 1, sy[1] });
            }
        }
        nx = Math.max(0f, Math.min(sw - W, nx));
        ny = Math.max(0f, Math.min(sh - H, ny));
        dragging.moveTo(nx, ny, sw, sh);
    }

    /** The smallest move (within SNAP) that puts one of these edges on one of the lines: {move, line}, or null. */
    private static float[] snap(float[] edges, List<Float> lines) {
        float best = SNAP + 1f, move = 0f, line = 0f;
        for (float e : edges) {
            for (float l : lines) {
                float d = l - e;
                if (Math.abs(d) < Math.abs(best) && Math.abs(d) <= SNAP) {
                    best = d;
                    move = d;
                    line = l;
                }
            }
        }
        return best <= SNAP ? new float[]{ move, line } : null;
    }

    private static void release() {
        if (dragging == null && resizing == null) return;
        dragging = resizing = null;
        guides.clear();
        save();
    }

    /**
     * One mouse event in the chat screen (GUI coordinates; button -1 is a move, wheel is the scroll): whether the
     * editor used it (then the chat does not see it).
     */
    public static boolean mouse(float mx, float my, int button, boolean down, int wheel, float sw, float sh) {
        if (dragging != null || resizing != null) {
            if (button == 0 && !down) release();
            else if (dragging != null) drag(mx - grabX, my - grabY, sw, sh);
            else {
                float size = Math.abs(mx - fixedX) + Math.abs(my - fixedY);
                resizing.scaleTo(startScale * size / Math.max(1f, startSize), sw, sh);
            }
            return true;
        }
        HudElement over = at(mx, my, sw, sh);
        if (over == null) return false;
        float[] b = over.bounds(sw, sh);
        if (button == 0 && down) {
            if (inside(handle(over, b), mx, my)) {
                resizing = over;
                fixedX = over.handleRight() ? b[0] : b[0] + b[2];
                fixedY = over.handleBottom() ? b[1] : b[1] + b[3];
                startSize = b[2] + b[3];
                startScale = over.scale();
            } else {
                dragging = over;
                grabX = mx - b[0];
                grabY = my - b[1];
            }
            return true;
        }
        if (button == 1 && down) {
            over.reset();
            save();
            return true;
        }
        if (wheel != 0) {
            over.scaleTo(over.scale() * (wheel > 0 ? 1.08f : 1f / 1.08f), sw, sh);
            save();
            return true;
        }
        return false;
    }

    /** The snap guides showing now: {0 = vertical / 1 = horizontal, where}. */
    public static List<float[]> guides() { return guides; }

    public static final class Events {
        @SubscribeEvent
        public void onMouse(GuiScreenEvent.MouseInputEvent.Pre e) {
            if (!(e.gui instanceof GuiChat)) return;
            ScaledResolution sr = new ScaledResolution(mc);
            float sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
            float mx = Mouse.getEventX() * sw / mc.displayWidth, my = sh - Mouse.getEventY() * sh / mc.displayHeight - 1;
            if (mouse(mx, my, Mouse.getEventButton(), Mouse.getEventButtonState(), Mouse.getEventDWheel(), sw, sh)) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onDraw(GuiScreenEvent.DrawScreenEvent.Post e) {
            if (!(e.gui instanceof GuiChat) || overlay <= 0.01f || mc.thePlayer == null) return;
            ScaledResolution sr = new ScaledResolution(mc);
            float sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
            float mx = Mouse.getX() * sw / mc.displayWidth, my = sh - Mouse.getY() * sh / mc.displayHeight - 1;
            hover = dragging != null ? dragging : resizing != null ? resizing : at(mx, my, sw, sh);
            FontRenderer fr = mc.fontRendererObj;
            int a = (int) (255 * overlay);
            GlStateManager.pushMatrix();
            GlStateManager.translate(0f, 0f, 300f);
            for (float[] g : guides) {
                if (g[0] == 0) RenderUtil.rect(g[1] - 0.25f, 0, g[1] + 0.25f, sh, ((a * 140 / 255) << 24) | 0x55AAFF);
                else RenderUtil.rect(0, g[1] - 0.25f, sw, g[1] + 0.25f, ((a * 140 / 255) << 24) | 0x55AAFF);
            }
            for (HudElement el : listed()) {
                float[] b = el.bounds(sw, sh);
                boolean on = el == hover;
                if (!el.drawnRecently()) {
                    // nothing to show now: where it would be
                    RoundRect.draw(b[0], b[1], b[0] + b[2], b[1] + b[3], 3, ((a * 40 / 255) << 24) | 0xFFFFFF);
                    String n = el.name;
                    fr.drawString(n, b[0] + (b[2] - fr.getStringWidth(n)) / 2f, b[1] + (b[3] - 8) / 2f, ((a * 170 / 255) << 24) | 0xFFFFFF, true);
                }
                RenderUtil.outline(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1] + b[3] + 1, on ? 1f : 0.5f,
                    ((on ? a : a * 90 / 255) << 24) | (on ? 0xFFFFFF : 0xDDDDDD));
                if (on) {
                    String label = el.name + (resizing == el || Math.abs(el.scale() - 1f) > 0.01f ? "  " + Math.round(el.scale() * 100) + "%" : "");
                    float ly = b[1] - 11 < 1 ? b[1] + b[3] + 3 : b[1] - 11;
                    float lw = fr.getStringWidth(label) + 6;
                    RoundRect.draw(b[0] - 1, ly - 1, b[0] - 1 + lw, ly + 9, 2, (a * 200 / 255) << 24);
                    fr.drawString(label, b[0] + 2, ly, (a << 24) | 0xFFFFFF, false);
                    float[] h = handle(el, b);
                    RoundRect.draw(h[0], h[1], h[0] + h[2], h[1] + h[3], 1, (a << 24) | 0xFFFFFF);
                }
            }
            if (dragging == null && resizing == null) {
                String help = "拖动移动 · 拖角或滚轮缩放 · 右键还原 · 按住 Shift 不吸附";
                float hw = fr.getStringWidth(help) + 10;
                RoundRect.draw(sw / 2f - hw / 2f, 3, sw / 2f + hw / 2f, 15, 4, (a * 150 / 255) << 24);
                fr.drawString(help, sw / 2f - hw / 2f + 5, 5, ((a * 220 / 255) << 24) | 0xFFFFFF, false);
            }
            GlStateManager.popMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }
}
