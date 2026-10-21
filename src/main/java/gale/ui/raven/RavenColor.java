package gale.ui.raven;

import gale.b4port.util.RenderUtils;
import gale.module.setting.ColorSetting;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/** A colour, drawn like B4's slider over the hues (B4 has no colour setting): drag to pick the hue, right click for rainbow. */
public class RavenColor extends RavenComponent {
    public final ColorSetting setting;
    public RavenModule p;
    public float o, x, y;
    private boolean held;

    public RavenColor(ColorSetting setting, RavenModule b, float o) {
        this.setting = setting;
        this.p = b;
        this.o = o;
    }

    @Override
    public float getHeight() { return this.setting.isVisible() ? 17.0f : 0.0f; }

    @Override
    public void setOffset(float n) { this.o = n; }

    @Override
    public void mouseReleased(int x, int y, int m) { this.held = false; }

    @Override
    public void onGuiClosed() { this.held = false; }

    private float barWidth() { return this.p.categoryComponent.getWidth() - 8.0f; }

    private float[] hsb() {
        int c = this.setting.getValue();
        return Color.RGBtoHSB(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, null);
    }

    @Override
    public void updatePosition(int mouseX, int mouseY) {
        this.x = this.p.categoryComponent.getX();
        this.y = this.p.categoryComponent.getY() + this.o;
        if (!this.held) return;
        float hue = Math.min(this.barWidth(), Math.max(0.0f, (float) (mouseX - 2) - this.x)) / this.barWidth();
        float[] hsb = this.hsb();
        float s = hsb[1] < 0.05f ? 0.8f : hsb[1], b = hsb[2] < 0.05f ? 1.0f : hsb[2];
        int rgb = Color.HSBtoRGB(Math.min(hue, 0.999f), s, b) & 0xFFFFFF;
        this.setting.set(this.setting.getValue() & 0xFF000000 | rgb);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int b) {
        if (!this.p.opened || !this.setting.isVisible()) return false;
        if (!((float) mx > this.x && (float) mx < this.x + this.p.categoryComponent.getWidth() && (float) my > this.y && (float) my < this.y + 16.0f)) return false;
        if (b == 1) {
            this.setting.setRainbow(!this.setting.isRainbow());
            return true;
        }
        if (b != 0) return false;
        if (this.setting.isRainbow()) this.setting.setRainbow(false);
        this.held = true;
        return true;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        if (!this.setting.isVisible()) return;
        RavenCategory c = this.p.categoryComponent;
        float top = c.getY() + this.o + 11.0f, bottom = c.getY() + this.o + 15.0f, left = c.getX() + 4.0f, w = this.barWidth();
        RenderUtils.roundedRect(left, top, left + w, bottom, 4.0f, -12302777);
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            float a = left + 1.0f + (w - 2.0f) * i / steps, b = left + 1.0f + (w - 2.0f) * (i + 1) / steps;
            RenderUtils.rect(a, top + 1.0f, b, bottom - 1.0f, Color.HSBtoRGB((float) i / steps, 0.75f, 0.9f));
        }
        float hx = left + this.hsb()[0] * w;
        RenderUtils.rect(hx - 0.5f, top - 0.5f, hx + 0.5f, bottom + 0.5f, -1);
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        String v = this.setting.isRainbow() ? "\u00a7drainbow" : String.format("\u00a7b#%06X", this.setting.getValue() & 0xFFFFFF);
        mc.fontRendererObj.drawStringWithShadow(this.setting.getName() + ": " + v,
            (float) ((int) ((c.getX() + 4.0f) * 2.0f)), (float) ((int) ((c.getY() + this.o + 3.0f) * 2.0f)), -1);
        GL11.glPopMatrix();
        RenderUtils.resetColor();
    }
}
