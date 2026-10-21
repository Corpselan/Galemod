package gale.ui.raven;

import gale.b4port.module.setting.DescriptionSetting;
import gale.b4port.util.Animation;
import gale.b4port.util.RenderUtils;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.RangeSetting;
import gale.module.setting.Setting;
import gale.module.setting.TextSetting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** B4's ModuleComponent: a module's name (left click switches it, right click opens its settings) and its settings below. */
public class RavenModule extends RavenComponent {
    public long hoverEndedAt;
    public RavenCategory categoryComponent;
    public boolean opening;
    public boolean wasHovered;
    public Animation animation;
    public long animationDuration = 200L;
    public double hoverFadeMs = 150.0;
    public boolean opened;
    public boolean closing;
    public float height = 16.0f;
    public ArrayList<RavenComponent> settings;
    public float yPos;
    public float offset;
    public boolean hovered;
    public double colorFadeMs = 75.0;
    public Module mod;
    // B4 keeps when a module was switched on or off for the colour fade; Gale's modules do not, so it is noticed here
    private boolean lastEnabled;
    private long enabledAt, disabledAt;

    public RavenModule(Module mod, RavenCategory p, float o) {
        this.mod = mod;
        this.categoryComponent = p;
        this.offset = o;
        this.settings = new ArrayList<>();
        this.lastEnabled = mod.isEnabled();
        float y = o + 12.0f;
        Set<Setting<?>> added = new HashSet<>();
        // a module ported from B4: its settings in B4's order, with B4's section titles
        if (mod instanceof gale.b4port.module.Module) {
            for (gale.b4port.module.setting.Setting s : ((gale.b4port.module.Module) mod).settings) {
                RavenComponent c = null;
                if (s instanceof DescriptionSetting) {
                    c = new RavenDescription(((DescriptionSetting) s).getDesc(), this, y);
                } else if (s.gale() != null && mod.getSettings().contains(s.gale())) {
                    c = component(s.gale(), y);
                    added.add(s.gale());
                }
                if (c == null) continue;
                y += c.getHeight();
                this.settings.add(c);
            }
        }
        for (Setting<?> s : mod.getSettings()) {
            if (added.contains(s)) continue;
            RavenComponent c = component(s, y);
            if (c == null) continue;
            y += c.getHeight();
            this.settings.add(c);
        }
        this.settings.add(new RavenBind(this, mod, y));
    }

    private RavenComponent component(Setting<?> s, float y) {
        if (s instanceof BoolSetting) return new RavenToggle((BoolSetting) s, this, y);
        if (s instanceof NumberSetting) return new RavenSlider(s, this, y);
        if (s instanceof ModeSetting) return new RavenSlider(s, this, y);
        if (s instanceof RangeSetting) return new RavenRange((RangeSetting) s, this, y);
        if (s instanceof ColorSetting) return new RavenColor((ColorSetting) s, this, y);
        if (s instanceof TextSetting) return new RavenText((TextSetting) s, this, y);
        return null;
    }

    public float top() { return this.categoryComponent.getY() + this.offset - 1.0f; }

    public float bottom() { return this.categoryComponent.getY() + 16.0f + this.offset; }

    public float left() { return this.categoryComponent.getX() - 1.0f; }

    public float right() { return this.categoryComponent.getX() + this.categoryComponent.getWidth() + 1.0f; }

    @Override
    public void setOffset(float n) {
        this.offset = this.yPos = n;
        float y = this.offset + 16.0f;
        for (RavenComponent c : this.settings) {
            c.setOffset(y);
            y += c.getHeight();
        }
    }

    @Override
    public void onGuiClosed() {
        if (!this.opened && !this.closing) return;
        for (RavenComponent c : this.settings) c.onGuiClosed();
    }

    public void toggleOpen() {
        this.opened = !this.opened;
        if (this.opened) {
            this.opening = true;
            this.closing = false;
        } else {
            this.opening = false;
            this.closing = true;
        }
        if (this.animation == null) this.animation = new Animation(this.animationDuration);
        this.animation.start();
        this.categoryComponent.updateHeight();
    }

    @Override
    public void mouseReleased(int x, int y, int m) {
        if (!this.opened && !this.closing) return;
        for (RavenComponent c : this.settings) c.mouseReleased(x, y, m);
    }

    public boolean isHovered(int x, int y) {
        return (float) x > this.left() && (float) x < this.right() && (float) y > this.top() && (float) y < this.bottom();
    }

    @Override
    public float getHeight() {
        float h = this.height;
        if (this.opened || this.closing) {
            for (RavenComponent c : this.settings) h += c.getHeight();
            if (this.animation != null) {
                if (this.opening) {
                    float v = this.animation.getValue(this.height, h, 2);
                    if (v == h) this.opening = false;
                    h = v;
                } else if (this.closing) {
                    float v = this.height + (h - this.animation.getValue(this.height, h, 2));
                    if (v == this.height) this.closing = false;
                    h = v;
                }
            }
        }
        return h;
    }

    @Override
    public boolean mouseClicked(int x, int y, int b) {
        if (this.categoryComponent.opening || this.categoryComponent.closing) return false;
        if (this.hovered) {
            if (b == 0) {
                this.mod.toggle();
                return true;
            }
            if (b == 1) {
                this.toggleOpen();
                return true;
            }
        }
        if (this.opening || this.closing) return false;
        for (RavenComponent c : this.settings) if (c.mouseClicked(x, y, b)) return true;
        return false;
    }

    @Override
    public void updatePosition(int x, int y) {
        for (RavenComponent c : this.settings) c.updatePosition(x, y);
    }

    @Override
    public void keyTyped(char t, int k) {
        if (!this.opened && !this.closing) return;
        for (RavenComponent c : this.settings) c.keyTyped(t, k);
    }

    public void render(int mouseX, int mouseY, float y) {
        int color;
        double d;
        boolean on = this.mod.isEnabled();
        if (on != this.lastEnabled) {
            if (on) this.enabledAt = System.currentTimeMillis();
            else this.disabledAt = System.currentTimeMillis();
            this.lastEnabled = on;
        }
        if (on) {
            if (this.enabledAt > 0L) {
                d = 1.0 - Math.min(1.0, (double) (System.currentTimeMillis() - this.enabledAt) / this.colorFadeMs);
                if (d == 0.0) this.enabledAt = 0L;
                color = RenderUtils.blend(RavenStyle.disabledColor.color, RavenStyle.enabledColor.color, d);
            } else {
                color = RavenStyle.enabledColor.rgb;
            }
        } else if (this.disabledAt > 0L) {
            d = 1.0 - Math.min(1.0, (double) (System.currentTimeMillis() - this.disabledAt) / this.colorFadeMs);
            if (d == 0.0) this.disabledAt = 0L;
            color = RenderUtils.blend(RavenStyle.enabledColor.color, RavenStyle.disabledColor.color, d);
        } else {
            color = RavenStyle.disabledColor.rgb;
        }
        this.wasHovered = this.hovered;
        this.hovered = this.isHovered(mouseX, mouseY);
        int alpha;
        if (this.hovered) {
            alpha = 90;
        } else {
            if (this.wasHovered) this.hoverEndedAt = System.currentTimeMillis();
            if (this.hoverEndedAt > 0L) {
                double f = Math.max(0.0, 1.0 - (double) (System.currentTimeMillis() - this.hoverEndedAt) / this.hoverFadeMs);
                if (f == 0.0) this.hoverEndedAt = 0L;
                alpha = RenderUtils.clampAlpha((int) (f * (double) 90));
            } else {
                alpha = 0;
            }
        }
        if (alpha > 0) {
            int bg = RenderUtils.withAlpha(Color.black.getRGB(), alpha);
            RenderUtils.rect(this.left(), this.top() + 1.0f, this.right(), this.bottom(), bg);
        }
        float x = this.categoryComponent.getX() + this.categoryComponent.getWidth() / 2.0f - (float) mc.fontRendererObj.getStringWidth(this.mod.getName()) / 2.0f;
        float ty = this.categoryComponent.getY() + this.yPos + 4.0f;
        mc.fontRendererObj.drawStringWithShadow(this.mod.getName(), x, ty, color);
        if ((this.opened || this.closing) && !this.settings.isEmpty()) {
            float end = y - this.height + this.getHeight();
            for (RavenComponent c : this.settings) {
                if (y + c.getHeight() - 4.0f > end) break;
                c.render(mouseX, mouseY);
                y += c.getHeight();
            }
        }
    }
}
