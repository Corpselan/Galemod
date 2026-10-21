package gale.ui.raven;

import gale.b4port.util.RenderUtils;
import gale.module.setting.RangeSetting;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/** A low-high range, drawn like B4's slider (B4 uses two sliders; Gale has one setting): drag the nearer end. */
public class RavenRange extends RavenComponent {
    public final RangeSetting setting;
    public RavenModule p;
    public float o, x, y;
    private int held = -1;

    public RavenRange(RangeSetting setting, RavenModule b, float o) {
        this.setting = setting;
        this.p = b;
        this.o = o;
    }

    @Override
    public float getHeight() { return this.setting.isVisible() ? 17.0f : 0.0f; }

    @Override
    public void setOffset(float n) { this.o = n; }

    @Override
    public void mouseReleased(int x, int y, int m) { this.held = -1; }

    @Override
    public void onGuiClosed() { this.held = -1; }

    private float barWidth() { return this.p.categoryComponent.getWidth() - 8.0f; }

    private float pos(double v) {
        return (float) ((v - this.setting.min) / (this.setting.max - this.setting.min)) * this.barWidth();
    }

    @Override
    public void updatePosition(int mouseX, int mouseY) {
        this.x = this.p.categoryComponent.getX();
        this.y = this.p.categoryComponent.getY() + this.o;
        if (this.held < 0) return;
        double d = Math.min(this.barWidth(), Math.max(0.0f, (float) (mouseX - 2) - this.x));
        double n = RavenSlider.roundToPrecision(d / this.barWidth() * (this.setting.max - this.setting.min) + this.setting.min, 2);
        if (this.held == 0) this.setting.set(Math.min(n, this.setting.high()), this.setting.high());
        else this.setting.set(this.setting.low(), Math.max(n, this.setting.low()));
    }

    @Override
    public boolean mouseClicked(int mx, int my, int b) {
        if (!this.p.opened || b != 0 || !this.setting.isVisible()) return false;
        if (!((float) mx > this.x && (float) mx < this.x + this.p.categoryComponent.getWidth() && (float) my > this.y && (float) my < this.y + 16.0f)) return false;
        float px = mx - 2 - this.x;
        this.held = Math.abs(px - this.pos(this.setting.low())) <= Math.abs(px - this.pos(this.setting.high())) ? 0 : 1;
        return true;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        if (!this.setting.isVisible()) return;
        RavenCategory c = this.p.categoryComponent;
        float top = c.getY() + this.o + 11.0f, bottom = c.getY() + this.o + 15.0f;
        RenderUtils.roundedRect(c.getX() + 4.0f, top, c.getX() + c.getWidth() - 4.0f, bottom, 4.0f, -12302777);
        float l = c.getX() + 4.0f + this.pos(this.setting.low()), r = c.getX() + 4.0f + this.pos(this.setting.high());
        if (r < l + 1.0f) r = l + 1.0f;
        RenderUtils.roundedRect(l, top, r, bottom, 4.0f, Color.getHSBColor((float) (System.currentTimeMillis() % 11000L) / 11000.0f, 0.75f, 0.9f).getRGB());
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        String v = this.setting.display();
        mc.fontRendererObj.drawStringWithShadow(this.setting.getName() + ": \u00a7b" + v,
            (float) ((int) ((c.getX() + 4.0f) * 2.0f)), (float) ((int) ((c.getY() + this.o + 3.0f) * 2.0f)), -1);
        GL11.glPopMatrix();
    }
}
