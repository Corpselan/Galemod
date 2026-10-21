package gale.module.impl.client;

import gale.Gale;
import gale.bridge.External;
import gale.core.Notifications;
import gale.event.impl.Render2DEvent;
import gale.hud.HudBox;
import gale.hud.HudLayout;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.util.Anim;
import gale.util.ColorUtil;
import gale.util.Mc;
import gale.util.PlayerUtil;
import gale.util.RenderUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watermark, the list of modules that are on, a little info line, and the switch for notifications. Each is a HUD
 * element: open the chat to move or resize it. Modules slide in and out of the list, and the others glide into place.
 */
public final class HUD extends Module {
    private static final float ROW = 11f;
    private final BoolSetting watermark = bool("Watermark", true);
    private final BoolSetting list = bool("Module list", true);
    private final ModeSetting colorMode = mode("Color", "Rainbow", "Rainbow", "Static", "Pulse");
    private final ColorSetting color = color("Static color", 0xFF55AAFF).visibleWhen(() -> !colorMode.is("Rainbow"));
    private final BoolSetting background = bool("Background", true);
    private final BoolSetting showDeferred = bool("Show waiting", true).describe("显示因为另一个端开着同类功能而在等的模块（灰色）");
    private final BoolSetting info = bool("Info", true).describe("左下角 FPS / 速度 / 延迟");
    private final BoolSetting external = bool("Other client", true).describe("左下角显示 B4 报告开着的模块");
    private final BoolSetting notifications = bool("Notifications", true);
    private final BoolSetting toggleNotes = bool("Toggle notes", true).visibleWhen(notifications::get);

    private final HudBox watermarkBox, listBox, infoBox, externalBox;
    // the module list: how far each entry is in (0..1) and where it is drawn, moving toward its place
    private final Map<Module, float[]> entries = new IdentityHashMap<>();
    private final Anim.Clock clock = new Anim.Clock();

    public HUD() {
        super("HUD", Category.RENDER, "水印、模块列表、提示（打开聊天框可以拖动和缩放）");
        notifications.onChange(v -> applyNotes());
        toggleNotes.onChange(v -> applyNotes());
        watermarkBox = HudLayout.box("watermark", "Watermark", this, HudBox.LEFT, HudBox.TOP, 4, 4, 70, 9);
        listBox = HudLayout.box("modulelist", "Module list", this, HudBox.RIGHT, HudBox.TOP, 2, 2, 60, 33);
        infoBox = HudLayout.box("info", "Info", this, HudBox.LEFT, HudBox.BOTTOM, 2, 1, 120, 9);
        externalBox = HudLayout.box("clients", "Other client", this, HudBox.LEFT, HudBox.BOTTOM, 2, 11, 120, 9);
        setEnabled(true);
        on(Render2DEvent.class, this::draw);
    }

    @Override
    protected void onEnable() { applyNotes(); }

    @Override
    protected void onDisable() { Notifications.onScreen = false; }

    private void applyNotes() {
        Notifications.onScreen = isEnabled() && notifications.get();
        Notifications.toggles = toggleNotes.get();
    }

    private int colorAt(int index) {
        switch (colorMode.get()) {
            case "Static": return color.get();
            case "Pulse": return ColorUtil.pulse(color.get(), 2000, index * 120L);
            default: return ColorUtil.rainbow(4000, index * 150L, 0.55f, 1f);
        }
    }

    private void draw(Render2DEvent e) {
        float dt = clock.tick();
        if (mc.gameSettings.showDebugInfo) return;
        float sw = e.width(), sh = e.height();
        if (watermark.get()) {
            String text = "&f" + Gale.NAME.charAt(0) + "&7" + Gale.NAME.substring(1) + " &8" + Gale.VERSION;
            watermarkBox.layout(RenderUtil.textWidth(text), 9, sw, sh);
            watermarkBox.push();
            RenderUtil.text(text, 0, 0, colorAt(0));
            watermarkBox.pop();
        }
        if (list.get()) drawList(sw, sh, dt);
        if (info.get()) {
            String text = "&7FPS &f" + Mc.fps() + "  &7BPS &f" + String.format("%.2f", PlayerUtil.bps()) + "  &7Ping &f" + PlayerUtil.ping();
            infoBox.layout(RenderUtil.textWidth(text), 9, sw, sh);
            infoBox.push();
            RenderUtil.text(text, 0, 0, -1);
            infoBox.pop();
        }
        if (external.get()) {
            List<String> lines = new ArrayList<>();
            for (String c : External.clients()) {
                List<String> on = External.enabled(c);
                lines.add("&7" + c.toUpperCase() + " &8" + on.size() + "&7: &f" + String.join(", ", on));
            }
            if (!lines.isEmpty()) {
                float w = 0;
                for (String l : lines) w = Math.max(w, RenderUtil.textWidth(l));
                externalBox.layout(w, lines.size() * 10 - 1, sw, sh);
                externalBox.push();
                // the first client nearest the anchored edge
                for (int i = 0; i < lines.size(); i++) {
                    float y = externalBox.bottom() ? (lines.size() - 1 - i) * 10 : i * 10;
                    String l = lines.get(i);
                    float x = externalBox.right() ? w - RenderUtil.textWidth(l) : externalBox.centered() ? (w - RenderUtil.textWidth(l)) / 2f : 0;
                    RenderUtil.text(l, x, y, -1);
                }
                externalBox.pop();
            }
        }
    }

    private boolean listed(Module m) {
        return m.isEnabled() && !m.isHidden() && (!m.isDeferred() || showDeferred.get());
    }

    private void drawList(float sw, float sh, float dt) {
        // every module that is on, and the ones still sliding out
        for (Module m : Gale.modules().all()) if (listed(m) && !entries.containsKey(m)) entries.put(m, new float[]{ 0f, -1f });
        List<Module> shown = new ArrayList<>();
        for (Map.Entry<Module, float[]> en : new ArrayList<>(entries.entrySet())) {
            Module m = en.getKey();
            float[] a = en.getValue();
            boolean in = listed(m) && Gale.modules().all().contains(m);
            a[0] = Anim.approach(a[0], in ? 1f : 0f, in ? 11f : 14f, dt);
            if (!in && a[0] <= 0.01f) entries.remove(m);
            else shown.add(m);
        }
        shown.sort((a, b) -> Integer.compare(RenderUtil.textWidth(label(b)), RenderUtil.textWidth(label(a))));
        float maxW = 0, total = 0;
        for (Module m : shown) {
            maxW = Math.max(maxW, RenderUtil.textWidth(label(m)) + 5);
            total += ROW * Anim.easeOutCubic(entries.get(m)[0]);
        }
        float boxH = Math.max(total, ROW);
        listBox.layout(Math.max(maxW, 20), boxH, sw, sh);
        boolean right = listBox.right(), centered = listBox.centered(), up = listBox.bottom();
        listBox.push();
        float slot = 0;
        int i = 0;
        for (Module m : shown) {
            float[] a = entries.get(m);
            float in = Anim.easeOutCubic(a[0]);
            // the place it glides to: stacked from the anchored edge
            float target = up ? boxH - slot - ROW : slot;
            a[1] = a[1] < -0.5f ? target : Anim.approach(a[1], target, 16f, dt);
            slot += ROW * in;
            String text = label(m);
            int w = RenderUtil.textWidth(text);
            // it slides in from the side it is anchored to
            float slide = (1f - in) * (w + 8);
            float x = right ? maxW - w - 2 + slide : centered ? (maxW - w) / 2f : 3 - slide;
            float y = a[1];
            int alpha = Math.max(8, (int) (255 * in));
            if (background.get()) RenderUtil.rect(x - 2, y, x + w + 2, y + ROW, ColorUtil.withAlpha(0x90000000, (int) (0x90 * in)));
            int c = m.isDeferred() ? 0xFF777777 : colorAt(i);
            float barX = right ? x + w + 2 : x - 3;
            if (!centered) RenderUtil.rect(barX, y, barX + 1, y + ROW, ColorUtil.withAlpha(c, alpha));
            RenderUtil.textBlended(text, x, y + 1, ColorUtil.withAlpha(c, alpha));
            i++;
        }
        listBox.pop();
    }

    /** How far a module is into the list (0..1, while it slides), or -1 if it is not in it. */
    public float listProgress(Module m) {
        float[] a = entries.get(m);
        return a == null ? -1f : a[0];
    }

    private static String label(Module m) {
        String suffix = m.getSuffix();
        return m.getName() + (suffix == null ? "" : " &7" + suffix) + (m.isDeferred() ? " &8(让给另一个端)" : "");
    }
}
