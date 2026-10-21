package gale.ui.raven;

import gale.Gale;
import gale.b4port.util.Animation;
import gale.b4port.util.RenderUtils;
import gale.b4script.Blur;
import gale.module.Category;
import gale.module.Module;
import gale.module.impl.client.ClickGuiModule;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B4's ClickGui (from OpenB4) over Gale's modules: a draggable frame per category, the title (B4's "raven b4", here "gale dev"), the player
 * model and the watermark. One instance for the session, so frames stay where they were dragged.
 */
public class RavenGui extends GuiScreen {
    private static RavenGui instance;
    public static long openedAt;
    public final ArrayList<RavenCategory> categories = new ArrayList<>();
    private List<Module> built = new ArrayList<>();
    private int previousGuiScale = -1;
    private boolean movementActive;
    private net.minecraft.client.gui.GuiButton configButton;

    public Animation watermarkAnimation;
    public Animation backgroundAnimation;

    public static RavenGui instance() {
        if (instance == null) instance = new RavenGui();
        instance.rebuildIfChanged();
        return instance;
    }

    /** Opens it like B4 does: its own GUI scale first, then the screen. */
    public static void open() {
        RavenGui gui = instance();
        gui.previousGuiScale = mc().gameSettings.guiScale;
        int scale = RavenStyle.guiScale();
        if (gui.previousGuiScale != scale) mc().gameSettings.guiScale = scale;
        RenderUtils.updateResolution();
        mc().displayGuiScreen(gui);
        gui.initMain();
    }

    private static net.minecraft.client.Minecraft mc() { return net.minecraft.client.Minecraft.getMinecraft(); }

    private RavenGui() { }

    /** (Re)makes the frames when modules came or went (scripts), keeping where each frame was and whether it was open. */
    private void rebuildIfChanged() {
        List<Module> now = new ArrayList<>(Gale.modules().visible());
        if (now.equals(this.built)) return;
        this.built = now;
        Map<Category, float[]> where = new HashMap<>();
        for (RavenCategory c : this.categories) where.put(c.category, new float[]{ c.x, c.y, c.opened ? 1 : 0 });
        this.categories.clear();
        float y = 5.0f;
        for (Category c : Category.values()) {
            if (Gale.modules().visibleIn(c).isEmpty()) continue;
            RavenCategory f = new RavenCategory(c);
            float[] w = where.get(c);
            if (w != null) {
                f.setX(w[0]);
                f.setY(w[1]);
                if (w[2] == 1) f.setOpened(true);
            } else {
                f.setY(y);
            }
            this.categories.add(f);
            y += 20.0f;
        }
    }

    public void initMain() {
        openedAt = System.currentTimeMillis();
        (this.backgroundAnimation = new Animation(500.0f)).start();
        (this.watermarkAnimation = new Animation(500.0f)).start();
    }

    /** B4's "Reset positions". */
    public static void resetPositions() {
        if (instance == null) return;
        float y = 5.0f;
        for (RavenCategory c : instance.categories) {
            for (RavenComponent comp : c.modules) {
                RavenModule m = (RavenModule) comp;
                if (m.opened) m.toggleOpen();
            }
            c.setX(5.0f);
            c.setY(y);
            c.setOpened(false);
            y += 20.0f;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        configButton = new net.minecraft.client.gui.GuiButton(90, width - 64, height - 25, 60, 20, "配置");
        RenderUtils.updateResolution();
        if (this.backgroundAnimation == null) this.initMain();
    }

    @Override
    public void drawScreen(int x, int y, float p) {
        rebuildIfChanged();
        ClickGuiModule settings = RavenStyle.settings();
        int alpha = (int) (this.backgroundAnimation.getValue(0.0f, 0.75f, 2) * 255.0f);
        if (settings.darkBackground.get()) drawRect(0, 0, this.width, this.height, 0x101010 | alpha << 24);
        if (settings.backgroundBlur.get() > 0.0) {
            Blur.get().startStencil();
            Gui.drawRect(0, 0, RenderUtils.scaledWidth, RenderUtils.scaledHeight, -1);
            Blur.get().render(2, RavenStyle.blurStrength() * ((float) alpha / 191.0f));
        }
        if (!settings.hideWatermark.get()) this.drawTitle((float) this.width / 2.0f, (float) this.height / 4.0f);
        for (RavenCategory c : this.categories) {
            c.render(this.fontRendererObj, x, y);
            c.mousePosition(x, y);
            for (RavenComponent m : c.modules) m.updatePosition(x, y);
        }
        GL11.glColor3f(1.0f, 1.0f, 1.0f);
        if (!settings.hidePlayerModel.get() && this.mc.thePlayer != null) {
            GuiInventory.drawEntityOnScreen(this.width + 15 - this.backgroundAnimation.getValueInt(0, 40, 2), this.height - 10, 40,
                (float) (this.width - 25 - x), (float) (this.height - 50 - y), this.mc.thePlayer);
        }
        int wy = this.height + 18 - this.watermarkAnimation.getValueInt(0, 30, 2);
        RavenWatermark.render(4, wy, true);
        configButton.drawButton(mc, x, y);
    }

    public void drawTitle(float cx, float cy) {
        long now = System.currentTimeMillis();
        float since = now - openedAt;
        String title = "gale";
        String tag = "dev";
        float intro = this.clamp(since / 950.0f);
        float base = 2.5f;
        float pulse = 1.0f + (float) Math.sin((double) now * 0.0017) * 0.012f;
        float scale = base * pulse;
        float spacing = 0.8f;
        float width = 0.0f;
        for (int i = 0; i < title.length(); ++i) width += ((float) this.fontRendererObj.getCharWidth(title.charAt(i)) + spacing) * scale;
        float startX = cx - (width -= spacing * scale) / 2.0f;
        float bob = (float) Math.sin((double) now * 0.0021) * 1.2f;
        GL11.glEnable(3042);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        this.drawSparkles(cx, cy + 3.0f, width * 0.58f, 20.0f, intro, now);
        float px = startX;
        for (int i = 0; i < title.length(); ++i) {
            char c = title.charAt(i);
            float cw = this.fontRendererObj.getCharWidth(c);
            float t = this.clamp((since - (float) i * 70.0f) / 760.0f);
            if (t <= 0.0f) {
                px += (cw + spacing) * scale;
                continue;
            }
            float e = this.easeOutBack(t);
            float ang = -1.3f + (float) i * 0.48f;
            float dist = (1.0f - e) * (36.0f + (float) i * 3.5f);
            float bx = px + cw * scale / 2.0f;
            float by = cy + bob;
            float lx = bx + (float) Math.cos(ang) * dist;
            float ly = by + (float) Math.sin(ang) * dist * 0.55f + (1.0f - e) * 14.0f;
            float rot = (1.0f - e) * ((i & 1) == 0 ? -86.0f : 86.0f);
            float s = scale * (0.72f + 0.28f * e);
            int color = RenderUtils.rainbow(2L, (long) (-i) * 320L);
            this.drawGlowText(String.valueOf(c), lx, ly, s, rot, color, (int) (255.0f * t));
            px += (cw + spacing) * scale;
        }
        float tagT = this.clamp((since - 420.0f) / 850.0f);
        if (tagT > 0.0f) {
            float e = this.easeOutCubic(tagT);
            float s = 1.05f * (0.82f + 0.18f * e);
            float tw = (float) this.fontRendererObj.getStringWidth(tag) * s;
            float sway = (float) Math.sin((double) now * 0.00115) * 2.2f;
            float tbob = (float) Math.sin((double) now * 0.0021) * 0.9f;
            float phase = (float) (now % 4200L) / 4200.0f;
            float rock = (float) Math.sin((double) phase * Math.PI * 2.0) * 7.0f;
            float tx = cx + width * 0.28f + sway + 6.0f;
            float ty = cy + 17.0f + (1.0f - e) * 10.0f + tbob;
            float rot = (1.0f - e) * 8.0f + rock;
            this.drawGlowText(tag, tx + tw / 2.0f, ty, s, rot, RenderUtils.rainbow(2L, -1700L), (int) (235.0f * tagT));
        }
        RenderUtils.resetColor();
        GL11.glDisable(3042);
    }

    public void drawSparkles(float cx, float cy, float rx, float ry, float intro, long now) {
        char[] chars = new char[]{'.', '+', 'x', '*', '.', '+'};
        for (int i = 0; i < chars.length; ++i) {
            float scale;
            boolean even = (i & 1) == 0;
            double ang = (double) i * (Math.PI * 2 / (double) chars.length) + (even ? 0.0 : Math.PI / (double) chars.length);
            double speed = even ? 4.2E-4 : -3.1E-4;
            double a = ang + (double) now * speed;
            float ex = rx * (even ? 1.02f : 0.78f);
            float ey = ry * (even ? 1.0f : 0.72f);
            float sx = cx + (float) Math.cos(a) * ex;
            float sy = cy + (float) Math.sin(a) * ey;
            float twinkle = 0.65f + 0.35f * (float) Math.sin((double) now * 0.0042 + (double) i * 1.37);
            int alpha = (int) (120.0f * intro * twinkle);
            if (alpha <= 3) continue;
            alpha = RenderUtils.clampAlpha(alpha);
            int color = RenderUtils.withAlpha(RenderUtils.rainbow(2L, (long) i * 430L), alpha);
            char c = chars[i];
            float rot = 0.0f;
            switch (c) {
                case '.':
                    scale = 0.85f;
                    break;
                case '+':
                    scale = 0.72f;
                    rot = (float) (((double) now * 0.045 + (double) (i * 37)) % 360.0);
                    break;
                case 'x':
                    scale = 0.72f;
                    rot = (float) (((double) now * 0.04 + (double) (i * 53)) % 360.0);
                    break;
                default:
                    scale = 0.78f;
                    rot = (float) (((double) now * 0.03 + (double) (i * 29)) % 360.0);
            }
            GlStateManager.pushMatrix();
            GlStateManager.translate(sx, sy, 0.0f);
            if (rot != 0.0f) GlStateManager.rotate(rot, 0.0f, 0.0f, 1.0f);
            GlStateManager.scale(scale, scale, 1.0f);
            String s = String.valueOf(c);
            this.fontRendererObj.drawString(s, (float) (-this.fontRendererObj.getStringWidth(s)) / 2.0f, (float) (-this.fontRendererObj.FONT_HEIGHT) / 2.0f, color, true);
            GlStateManager.popMatrix();
        }
    }

    public void drawGlowText(String s, float x, float y, float scale, float rot, int color, int alpha) {
        if (alpha <= 3) return;
        alpha = RenderUtils.clampAlpha(alpha);
        int w = this.fontRendererObj.getStringWidth(s);
        int h = this.fontRendererObj.FONT_HEIGHT;
        float ox = (float) (-w) / 2.0f;
        float oy = (float) (-h) / 2.0f;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.rotate(rot, 0.0f, 0.0f, 1.0f);
        GlStateManager.scale(scale, scale, 1.0f);
        for (int i = 2; i >= 1; --i) {
            float gs = 1.0f + (float) i * 0.055f;
            int ga = (int) ((float) alpha * (0.1f + (float) i * 0.035f));
            if (ga < 4) continue;
            ga = RenderUtils.clampAlpha(ga);
            GlStateManager.pushMatrix();
            GlStateManager.scale(gs, gs, 1.0f);
            this.fontRendererObj.drawString(s, ox, oy, RenderUtils.withAlpha(color, ga), false);
            GlStateManager.popMatrix();
        }
        this.fontRendererObj.drawString(s, ox, oy, RenderUtils.withAlpha(color, alpha), true);
        GlStateManager.popMatrix();
    }

    public float easeOutCubic(float f) {
        float t = 1.0f - f;
        return 1.0f - t * t * t;
    }

    public float clamp(float f) { return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f); }

    public float easeOutBack(float f) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float t = f - 1.0f;
        return 1.0f + c3 * t * t * t + c1 * t * t;
    }

    @Override
    public void mouseClicked(int x, int y, int m) throws IOException {
        if (m == 0 && configButton != null && configButton.mousePressed(mc, x, y)) {
            configButton.playPressSound(mc.getSoundHandler());
            mc.displayGuiScreen(new gale.ui.ConfigScreen());
            return;
        }
        for (RavenCategory c : this.categories) {
            if (m == 0) {
                if (c.overCategoryIcon(x, y)) {
                    c.setOpened(!c.opened);
                    return;
                }
                if (c.overTitle(x, y)) {
                    c.overTitle(true);
                    c.xOffset = (float) x - c.getX();
                    c.dragY = (float) y - c.getY();
                    return;
                }
            } else if ((m == 1 || m == 2) && c.overTitle(x, y)) {
                c.setOpened(!c.opened);
                return;
            }
            if (!c.opened || c.modules.isEmpty()) continue;
            for (RavenComponent comp : c.modules) if (comp.mouseClicked(x, y, m)) return;
        }
    }

    @Override
    public void mouseReleased(int x, int y, int s) {
        if (s != 0) return;
        for (RavenCategory c : this.categories) c.overTitle(false);
        for (RavenCategory c : this.categories) {
            if (!c.opened || c.modules.isEmpty()) continue;
            for (RavenComponent comp : c.modules) comp.mouseReleased(x, y, s);
        }
    }

    @Override
    public void keyTyped(char t, int k) {
        boolean typing = RavenBind.binding || RavenText.editing;
        int guiKey = RavenStyle.settings().getKey();
        if (!typing && (k == Keyboard.KEY_ESCAPE || guiKey != 0 && k == guiKey)) {
            this.mc.displayGuiScreen(null);
            return;
        }
        for (RavenCategory c : this.categories) {
            if (!c.opened || c.modules.isEmpty()) continue;
            for (RavenComponent comp : c.modules) comp.keyTyped(t, k);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            dWheel = dWheel >> 31 | -dWheel >>> 31;
            boolean computed = false;
            int mx = 0, my = 0;
            for (RavenCategory c : this.categories) {
                if (!c.opened || c.opening) continue;
                if (!computed) {
                    mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
                    my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
                    computed = true;
                }
                c.onScroll(mx, my, dWheel);
            }
        }
    }

    // B4's Gui module, every tick while open: walk with the GUI open, turn with the arrow keys
    @Override
    public void updateScreen() {
        ClickGuiModule settings = RavenStyle.settings();
        boolean typing = RavenBind.binding || RavenText.editing;
        boolean move = settings.allowMovement.get() && !typing;
        if (move) setMoveKeys(false);
        else if (this.movementActive) setMoveKeys(true);
        this.movementActive = move;
        if (settings.allowRotating.get() && !typing && this.mc.thePlayer != null) rotate();
    }

    private void setMoveKeys(boolean release) {
        KeyBinding[] keys = { this.mc.gameSettings.keyBindForward, this.mc.gameSettings.keyBindBack, this.mc.gameSettings.keyBindRight,
            this.mc.gameSettings.keyBindLeft, this.mc.gameSettings.keyBindJump, this.mc.gameSettings.keyBindSneak };
        for (KeyBinding k : keys) KeyBinding.setKeyBindState(k.getKeyCode(), !release && GameSettings.isKeyDown(k));
    }

    private void rotate() {
        float yaw = 0.0f, pitch = 0.0f;
        if (Keyboard.isKeyDown(205)) yaw += 8.0f;
        if (Keyboard.isKeyDown(203)) yaw -= 8.0f;
        if (Keyboard.isKeyDown(200)) pitch -= 5.0f;
        if (Keyboard.isKeyDown(208)) pitch += 5.0f;
        if (yaw != 0.0f) this.mc.thePlayer.rotationYaw += (float) ((double) yaw + (Math.random() - 0.5));
        if (pitch != 0.0f) {
            pitch = (float) ((double) pitch + (Math.random() - 0.5));
            this.mc.thePlayer.rotationPitch = MathHelper.clamp_float(this.mc.thePlayer.rotationPitch + pitch, -90.0f, 90.0f);
        }
    }

    @Override
    public void onGuiClosed() {
        if (this.previousGuiScale >= 0) {
            this.mc.gameSettings.guiScale = this.previousGuiScale;
            this.previousGuiScale = -1;
            RenderUtils.updateResolution();
        }
        if (this.movementActive) {
            setMoveKeys(true);
            this.movementActive = false;
        }
        this.watermarkAnimation = null;
        this.backgroundAnimation = null;
        for (RavenCategory c : this.categories) {
            if (!c.opened || c.opening) continue;
            c.mouseReleased();
        }
        RavenBind.binding = false;
        RavenText.editing = false;
        Gale.config().markDirty();
        Gale.config().flush();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
