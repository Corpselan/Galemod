package gale.module.impl.optimize;

import gale.core.FrameSampler;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.Render2DEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.NumberSetting;
import gale.util.RenderUtil;

import java.util.List;

/**
 * Shows where each frame's time goes: B4, each B4 script, FPSMaster, Gale, and the parts of the game itself
 * (terrain, entities, particles, text, waiting for the graphics card...), plus GC pauses. Numbers are milliseconds
 * per frame over the last 3 seconds; /gale perf prints the last 10 seconds with what is inside each part and saves it.
 * Looking costs about 1-2% of a frame while it is on.
 */
public final class Profiler extends Module {
    private final NumberSetting rate = number("Sample rate", 200, 50, 1000, 50).suffix("/s").describe("每秒看几次游戏线程在干什么，越多越准、开销越大");
    private final NumberSetting rows = number("Rows", 8, 3, 16, 1).describe("HUD 上显示几行");
    private final gale.hud.HudBox box;

    public Profiler() {
        super("Profiler", Category.OPTIMIZE, "看每帧的时间花在哪：B4、每个脚本、FPSMaster、Gale、画地形、画实体、等显卡等（/gale perf 看详细）");
        box = gale.hud.HudLayout.box("profiler", "Profiler", this, gale.hud.HudBox.LEFT, gale.hud.HudBox.TOP, 4, 16, 160, 90);
        onAlways(FrameEvent.class, Priority.HIGHEST, e -> FrameSampler.frame());
        on(Render2DEvent.class, this::draw);
        rate.onChange(v -> {
            if (isEnabled()) FrameSampler.start(rate.getInt());
        });
    }

    @Override
    protected void onEnable() { FrameSampler.start(rate.getInt()); }

    @Override
    protected void onDisable() { FrameSampler.stop(); }

    @Override
    public String getInfo() {
        FrameSampler.Report r = FrameSampler.recent();
        if (!isEnabled() || r == null) return isEnabled() ? "正在取样..." : null;
        StringBuilder sb = new StringBuilder(String.format("%.0f FPS", r.fps));
        for (int i = 0; i < Math.min(2, r.rows.size()); i++) sb.append(String.format(" · %s %.1fms", r.rows.get(i).name, r.rows.get(i).ms));
        return sb.toString();
    }

    private void draw(Render2DEvent e) {
        if (mc.gameSettings.showDebugInfo) return;
        FrameSampler.Report r = FrameSampler.recent();
        // drawn from 0, 0 in its HUD box (open the chat to move or resize it)
        float x = 2, y = 2;
        if (r == null) {
            String wait = "&7帧时间分析：正在取样...";
            box.layout(RenderUtil.textWidth(wait) + 4, 13, e.width(), e.height());
            box.push();
            RenderUtil.text(wait, x, y, -1);
            box.pop();
            return;
        }
        String head = String.format("&f%.2f ms &7每帧  &f%.0f &7FPS  &71%% 低 &f%.0f", r.frameMs, r.fps, r.low1Fps);
        List<FrameSampler.Row> list = r.rows;
        int n = Math.min(rows.getInt(), list.size());
        float nameW = 0;
        for (int i = 0; i < n; i++) nameW = Math.max(nameW, RenderUtil.textWidth(list.get(i).name));
        float barX = x + nameW + 46, barW = 60;
        float w = Math.max(RenderUtil.textWidth(head), barX + barW - x) + 6;
        box.layout(w + 2, 13 + n * 10, e.width(), e.height());
        box.push();
        RenderUtil.rect(x - 2, y - 2, x + w, y + 11 + n * 10, 0x90000000);
        RenderUtil.text(head, x + 1, y, -1);
        for (int i = 0; i < n; i++) {
            FrameSampler.Row row = list.get(i);
            float ly = y + 11 + i * 10;
            int color = row.name.equals(FrameSampler.GC) ? 0xFFFF8855 : row.name.startsWith("脚本") ? 0xFFFFDD55 : 0xFFDDDDDD;
            RenderUtil.text(row.name, x + 1, ly, color);
            RenderUtil.text(String.format("&f%.2f", row.ms), x + nameW + 8, ly, -1);
            RenderUtil.rect(barX, ly + 2, barX + barW, ly + 7, 0x40FFFFFF);
            RenderUtil.rect(barX, ly + 2, barX + barW * (float) Math.min(1, row.share), ly + 7, color);
        }
        box.pop();
    }
}
