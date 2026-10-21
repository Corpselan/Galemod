package gale.ui.raven;

import gale.b4port.util.RenderUtils;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.Setting;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/** B4's SliderComponent: a number, or a mode (B4's modes are sliders over their options). */
public class RavenSlider extends RavenComponent {
    public boolean heldDown;
    public float o;
    public float width;
    public final Setting<?> setting;
    private final NumberSetting number;
    private final ModeSetting mode;
    public float y;
    public float x;
    public int maxBar = 84;
    public RavenModule p;

    public RavenSlider(Setting<?> setting, RavenModule b, float o) {
        this.setting = setting;
        this.number = setting instanceof NumberSetting ? (NumberSetting) setting : null;
        this.mode = setting instanceof ModeSetting ? (ModeSetting) setting : null;
        this.p = b;
        this.x = b.categoryComponent.getX() + b.categoryComponent.getWidth();
        this.y = b.categoryComponent.getY() + b.offset;
        this.o = o;
    }

    private double min() { return this.number != null ? this.number.min : 0.0; }

    private double max() { return this.number != null ? this.number.max : Math.max(1, this.mode.getVisibleModes().size() - 1); }

    private double value() { return this.number != null ? this.number.get() : Math.max(0, this.mode.visibleIndex()); }

    private void set(double n) {
        if (this.number != null) this.number.set(roundToPrecision(n, 2));
        else this.mode.setVisibleIndex((int) Math.max(0, Math.min(this.mode.getVisibleModes().size() - 1, Math.round(n))));
    }

    private String display() {
        return this.number != null ? "\u00a7b" + this.number.display() : "\u00a7e" + this.mode.visibleDisplay();
    }

    @Override
    public float getHeight() { return this.setting.isVisible() ? 17.0f + (textLines().size() - 1) * 5.0f : 0.0f; }

    private java.util.List<String> textLines() {
        String text = this.setting.getName() + ": " + this.display();
        if (this.mode == null) return java.util.Collections.singletonList(text);
        return mc.fontRendererObj.listFormattedStringToWidth(text, (int) ((this.p.categoryComponent.getWidth() - 8) * 2));
    }

    @Override
    public void mouseReleased(int x, int y, int m) { this.heldDown = false; }

    @Override
    public void updatePosition(int mousePosX, int mousePosY) {
        if (!this.setting.isVisible()) { this.heldDown = false; this.width = 0; return; }
        float w = this.p.categoryComponent.getWidth();
        this.x = this.p.categoryComponent.getX();
        this.y = this.p.categoryComponent.getY() + this.o;
        double min = this.min(), max = this.max();
        if (this.heldDown) {
            double d = Math.min(w - 8.0f, Math.max(0.0f, (float) (mousePosX - 2) - this.x));
            double n = d / (double) (w - 8.0f) * (max - min) + min;
            this.set(n);
        }
        this.width = (float) ((double) (w - 8.0f) * (this.value() - min) / (max - min));
    }

    @Override
    public boolean mouseClicked(int x, int y, int b) {
        if (!this.p.opened || b != 0 || !this.setting.isVisible()) return false;
        if (!this.isLeftHalf(x, y) && !this.isRightHalf(x, y)) return false;
        this.heldDown = true;
        return true;
    }

    public static double roundToPrecision(double v, int p) {
        if (p < 0) return 0.0;
        return new java.math.BigDecimal(v).setScale(p, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    @Override
    public void setOffset(float n) { this.o = n; }

    @Override
    public void render(int mouseX, int mouseY) {
        if (!this.setting.isVisible()) return;
        RavenCategory c = this.p.categoryComponent;
        float barY = c.getY() + this.o + getHeight() - 6.0f;
        RenderUtils.roundedRect(c.getX() + 4.0f, barY, c.getX() + c.getWidth() - 4.0f, barY + 4.0f, 4.0f, -12302777);
        float l = c.getX() + 4.0f;
        float r = c.getX() + 4.0f + this.width;
        if (r - l > (float) this.maxBar) r = l + (float) this.maxBar;
        else if (r < l) r = l;
        RenderUtils.roundedRect(l, barY, r, barY + 4.0f, 4.0f,
            Color.getHSBColor((float) (System.currentTimeMillis() % 11000L) / 11000.0f, 0.75f, 0.9f).getRGB());
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        int line = 0;
        for (String text : textLines()) mc.fontRendererObj.drawStringWithShadow(text,
            (float) ((int) ((c.getX() + 4.0f) * 2.0f)), (float) ((int) ((c.getY() + this.o + 3.0f + line++ * 5.0f) * 2.0f)), -1);
        GL11.glPopMatrix();
    }

    public boolean isLeftHalf(int x, int y) {
        float w = this.p.categoryComponent.getWidth();
        return (float) x > this.x && (float) x < this.x + (w / 2.0f + 1.0f) && (float) y > this.y && (float) y < this.y + getHeight() - 1.0f;
    }

    public boolean isRightHalf(int x, int y) {
        float w = this.p.categoryComponent.getWidth();
        return (float) x > this.x + w / 2.0f && (float) x < this.x + w && (float) y > this.y && (float) y < this.y + getHeight() - 1.0f;
    }

    @Override
    public void onGuiClosed() { this.heldDown = false; }
}
