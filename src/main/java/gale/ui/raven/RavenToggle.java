package gale.ui.raven;

import gale.b4port.util.RenderUtils;
import gale.module.setting.BoolSetting;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/** B4's ButtonComponent: an on/off setting, its name green when on. */
public class RavenToggle extends RavenComponent {
    public float o;
    public float x;
    public RavenModule p;
    public Color color = new Color(20, 255, 0);
    public BoolSetting setting;
    public double toggleFadeMs = 75.0;
    public float y;
    private boolean last;
    private long enabledAt, disabledAt;

    public RavenToggle(BoolSetting setting, RavenModule b, float o) {
        this.setting = setting;
        this.p = b;
        this.x = b.categoryComponent.getX() + b.categoryComponent.getWidth();
        this.y = b.categoryComponent.getY() + b.offset;
        this.o = o;
        this.last = setting.get();
    }

    @Override
    public void setOffset(float n) { this.o = n; }

    @Override
    public void render(int mouseX, int mouseY) {
        if (!this.setting.isVisible()) return;
        boolean on = this.setting.get();
        if (on != this.last) {
            if (on) this.enabledAt = System.currentTimeMillis();
            else this.disabledAt = System.currentTimeMillis();
            this.last = on;
        }
        int c;
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        float tx = (this.p.categoryComponent.getX() + 4.0f) * 2.0f;
        float ty = (this.p.categoryComponent.getY() + this.o + 4.0f) * 2.0f;
        if (on) {
            if (this.enabledAt > 0L) {
                double d = 1.0 - Math.min(1.0, (double) (System.currentTimeMillis() - this.enabledAt) / this.toggleFadeMs);
                if (d == 0.0) this.enabledAt = 0L;
                c = RenderUtils.blend(Color.WHITE, this.color, d);
            } else {
                c = this.color.getRGB();
            }
        } else if (this.disabledAt > 0L) {
            double d = 1.0 - Math.min(1.0, (double) (System.currentTimeMillis() - this.disabledAt) / this.toggleFadeMs);
            if (d == 0.0) this.disabledAt = 0L;
            c = RenderUtils.blend(this.color, Color.WHITE, d);
        } else {
            c = -1;
        }
        mc.fontRendererObj.drawString(this.setting.getName(), tx, ty, c, false);
        GL11.glPopMatrix();
    }

    public boolean isHovered(int x, int y) {
        return (float) x > this.x && (float) x < this.x + this.p.categoryComponent.getWidth() && (float) y > this.y && (float) y < this.y + 11.0f;
    }

    @Override
    public void updatePosition(int x, int y) {
        this.x = this.p.categoryComponent.getX();
        this.y = this.p.categoryComponent.getY() + this.o;
    }

    @Override
    public float getHeight() { return this.setting.isVisible() ? 12.0f : 0.0f; }

    @Override
    public boolean mouseClicked(int x, int y, int b) {
        if (!this.p.opened || b != 0 || !this.setting.isVisible() || !this.isHovered(x, y)) return false;
        this.setting.toggle();
        return true;
    }
}
