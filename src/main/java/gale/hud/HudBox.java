package gale.hud;

import com.google.gson.JsonObject;
import gale.module.Module;
import net.minecraft.client.renderer.GlStateManager;

/**
 * A HUD element placed by an anchor (left / centre / right, top / middle / bottom) and its distance from that edge,
 * with a scale. It stays put when the window or GUI scale changes, and keeps the anchored edge when its own size
 * changes: one on the right grows to the left, one at the bottom grows upward. Moving it in the editor picks the
 * anchor from the third of the screen it is in. Saved in galemod/hud.json.
 *
 * Drawing: {@link #layout} with the content's size (before scaling) each frame, then {@link #push}, draw from 0, 0,
 * {@link #pop}.
 */
public final class HudBox extends HudElement {
    public static final int LEFT = 0, CENTER = 1, RIGHT = 2, TOP = 0, MIDDLE = 1, BOTTOM = 2;
    private final int defAx, defAy;
    private final float defDx, defDy, defW, defH;
    int ax, ay;
    float dx, dy, scale = 1f;
    private float w, h, x, y;

    HudBox(String id, String name, Module owner, int ax, int ay, float dx, float dy, float defW, float defH) {
        super(id, name, owner);
        this.defAx = this.ax = ax;
        this.defAy = this.ay = ay;
        this.defDx = this.dx = dx;
        this.defDy = this.dy = dy;
        this.defW = this.w = defW;
        this.defH = this.h = defH;
    }

    private float left(float sw, float W) { return ax == LEFT ? dx : ax == CENTER ? sw / 2f + dx - W / 2f : sw - dx - W; }

    private float top(float sh, float H) {
        return ay == TOP ? dy : ay == MIDDLE ? sh / 2f + dy - H / 2f : sh - dy - H - HudLayout.chatLift();
    }

    /** This frame's content size (before scaling) and the screen's; afterwards x(), y() are its top-left. */
    public void layout(float w, float h, float sw, float sh) {
        this.w = Math.max(1f, w);
        this.h = Math.max(1f, h);
        x = left(sw, this.w * scale);
        y = top(sh, this.h * scale);
        drawn();
    }

    public void push() {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
    }

    public void pop() { GlStateManager.popMatrix(); }

    public float x() { return x; }

    public float y() { return y; }

    public int anchorX() { return ax; }

    public int anchorY() { return ay; }

    /** Anchored on the right: right-align the content. */
    public boolean right() { return ax == RIGHT; }

    public boolean centered() { return ax == CENTER; }

    /** Anchored at the bottom: stack upward. */
    public boolean bottom() { return ay == BOTTOM; }

    @Override
    public float[] bounds(float sw, float sh) {
        float W = (drawnRecently() ? w : defW) * scale, H = (drawnRecently() ? h : defH) * scale;
        return new float[]{ left(sw, W), top(sh, H), W, H };
    }

    @Override
    public void moveTo(float nx, float ny, float sw, float sh) {
        float[] b = bounds(sw, sh);
        float W = b[2], H = b[3], cx = nx + W / 2f, cy = ny + H / 2f;
        ax = cx < sw / 3f ? LEFT : cx > sw * 2f / 3f ? RIGHT : CENTER;
        ay = cy < sh / 3f ? TOP : cy > sh * 2f / 3f ? BOTTOM : MIDDLE;
        dx = ax == LEFT ? nx : ax == CENTER ? cx - sw / 2f : sw - nx - W;
        dy = ay == TOP ? ny : ay == MIDDLE ? cy - sh / 2f : sh - ny - H - HudLayout.chatLift();
    }

    @Override
    public float scale() { return scale; }

    @Override
    public void scaleTo(float s, float sw, float sh) { scale = Math.max(minScale(), Math.min(maxScale(), s)); }

    @Override
    public void reset() {
        ax = defAx;
        ay = defAy;
        dx = defDx;
        dy = defDy;
        scale = 1f;
    }

    @Override
    public boolean handleRight() { return ax != RIGHT; }

    @Override
    public boolean handleBottom() { return ay != BOTTOM; }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("anchorX", ax);
        o.addProperty("anchorY", ay);
        o.addProperty("x", dx);
        o.addProperty("y", dy);
        o.addProperty("scale", scale);
        return o;
    }

    void fromJson(JsonObject o) {
        try {
            ax = Math.max(0, Math.min(2, o.get("anchorX").getAsInt()));
            ay = Math.max(0, Math.min(2, o.get("anchorY").getAsInt()));
            dx = o.get("x").getAsFloat();
            dy = o.get("y").getAsFloat();
            scale = Math.max(minScale(), Math.min(maxScale(), o.get("scale").getAsFloat()));
        } catch (RuntimeException ignored) {
            reset();
        }
    }
}
