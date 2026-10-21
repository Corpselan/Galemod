package gale.ui;

import gale.Gale;
import gale.module.Category;
import gale.module.Module;
import gale.module.ModuleManager;
import gale.module.impl.client.ClickGuiModule;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.RangeSetting;
import gale.module.setting.Setting;
import gale.module.setting.TextSetting;
import gale.util.ColorUtil;
import gale.util.KeyUtil;
import gale.util.MathUtil;
import gale.util.RenderUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The settings screen, laid out like FPSMaster's: the categories down the left, the chosen category's modules on the
 * right. Built from blocks: a solid wall of oak planks with glass panes set in it (see {@link Skin}).
 * <ul>
 * <li>a module row: click it to open its settings (two columns), right click switches it; the switch switches it;
 * the key box binds a key (then press a key or a mouse button; Esc, Delete or Backspace clears; right click it to clear)</li>
 * <li>settings: right click any of them to put it back to its default</li>
 * <li>typing searches every module (names, descriptions, setting names); Esc stops searching, then closes</li>
 * </ul>
 * It draws at its own whole-number scale (ClickGUI's Scale setting), not the game's GUI scale.
 */
public final class ClickGui extends GuiScreen {
    private static final int MAX_W = 512, MAX_H = 320, MIN_W = 384, MIN_H = 240;
    private static final int SIDE = 112, TAB = 22, TAB_GAP = 4, ROW = 22, CELL = 20, PICKER = 62;
    private static final List<String> BIND_MODES = Arrays.asList("切换", "按住");

    /** kept while the game runs */
    private static Category category;
    private static final Set<String> EXPANDED = new HashSet<>();
    private static final Map<Category, Float> SCROLLS = new EnumMap<>(Category.class);
    private static final Map<Category, ItemStack> ICONS = new EnumMap<>(Category.class);

    private enum Kind { TAB, SEARCH, LIST, SCROLLBAR, ROW, SWITCH, KEY, CELL, BOOL, SLIDER, SEGMENT, CYCLE, COLOR, PICKER, PICK_SB, PICK_HUE, PICK_ALPHA, RAINBOW, TEXT, BIND_MODE, HIDE, INFO, CONFIG }

    /** right click puts these settings back to their default */
    private static final Set<Kind> RESETTABLE = EnumSet.of(Kind.CELL, Kind.BOOL, Kind.SLIDER, Kind.SEGMENT, Kind.CYCLE, Kind.COLOR, Kind.TEXT);

    /** Something clickable as it was drawn last frame. */
    private static final class Hit {
        final Kind kind;
        final Object target;
        final int index;
        /** the part that can be clicked (inside the clip) */
        final float x, y, w, h;
        /** the whole control: sliders and the colour picker measure from it */
        final float ox, oy, ow, oh;

        Hit(Kind kind, Object target, int index, float x, float y, float w, float h, float ox, float oy, float ow, float oh) {
            this.kind = kind;
            this.target = target;
            this.index = index;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.ox = ox;
            this.oy = oy;
            this.ow = ow;
            this.oh = oh;
        }

        boolean contains(float mx, float my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    /** One setting's place in an open module. */
    private static final class Cell {
        final Kind kind;
        final Setting<?> setting;
        final float h;
        float x, y, w;

        Cell(Kind kind, Setting<?> setting, float h) {
            this.kind = kind;
            this.setting = setting;
            this.h = h;
        }
    }

    /** An open module's settings in two columns; wide cells take a whole row. */
    private static final class Layout {
        final List<Cell> cells = new ArrayList<>();
        final float inner, col;
        float y = 4, rowH, height;
        int column;

        Layout(float width) {
            inner = width - 16;
            col = (inner - 10) / 2;
        }

        void add(Cell c, boolean wide) {
            if (wide && column == 1) newRow();
            c.x = 8 + (wide ? 0 : column * (col + 10));
            c.y = y;
            c.w = wide ? inner : col;
            cells.add(c);
            rowH = Math.max(rowH, c.h);
            if (wide || ++column == 2) newRow();
        }

        void newRow() {
            y += rowH;
            rowH = 0;
            column = 0;
        }

        Layout done() {
            if (column > 0) newRow();
            height = y + 4;
            return this;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Deque<float[]> clips = new ArrayDeque<>();
    private final Map<Object, Float> anims = new HashMap<>();

    private int scale = 1;
    private float mouseX, mouseY, dt;
    private long lastFrame, openedAt;

    private String search = "";
    private boolean searchFocused;
    private TextSetting editing;
    private Module binding;
    private Setting<?> sliding;
    private boolean rangeHigh;
    private float slideX, slideW;
    private ColorSetting picker;
    private final float[] hsb = new float[3];
    private int pickerValue;
    private Kind picking;
    private float pickX, pickY, pickW, pickH;
    private float scroll, scrollTarget, contentHeight, listX, listW, listTop, listHeight;
    private boolean scrollDrag;
    private float scrollGrab;

    private String tooltip;
    private Object tipKey;
    private long tipSince;
    private boolean tipSeen;

    // ------------------------------------------------------------------ screen

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        scale = pickScale();
        if (openedAt == 0) {
            openedAt = System.nanoTime();
            if (category == null) category = firstCategory();
            scroll = scrollTarget = SCROLLS.getOrDefault(category, 0f);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (search.isEmpty()) SCROLLS.put(category, scrollTarget);
        Gale.config().markDirty();
        Gale.config().flush();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    private static Category firstCategory() {
        for (Category c : Category.values()) if (!Gale.modules().visibleIn(c).isEmpty()) return c;
        return Category.values()[0];
    }

    /** Pixels per unit: the ClickGUI setting, or by the window height; smaller when the panel would not fit. */
    private int pickScale() {
        ClickGuiModule m = Gale.modules().get(ClickGuiModule.class);
        String mode = m == null ? "Auto" : m.scale.get();
        int s = mode.equals("Auto") ? Math.round(mc.displayHeight / 540f) : Integer.parseInt(mode);
        s = Math.max(1, s);
        while (s > 1 && (mc.displayWidth / s < MIN_W + 16 || mc.displayHeight / s < MIN_H + 16)) s--;
        return s;
    }

    private static int guiKey() {
        ClickGuiModule m = Gale.modules().get(ClickGuiModule.class);
        return m == null ? 0 : m.getKey();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int unusedX, int unusedY, float partialTicks) {
        long now = System.nanoTime();
        dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        scale = pickScale();
        ClickGuiModule settings = Gale.modules().get(ClickGuiModule.class);
        Skin.tiledGlass = settings != null && settings.glass.is("Tiled");
        mouseX = Mouse.getX() / (float) scale;
        mouseY = (mc.displayHeight - Mouse.getY() - 1) / (float) scale;
        drag();
        hits.clear();
        tooltip = null;
        tipSeen = false;

        float open = ease(Math.min(1, (now - openedAt) / 180e6f));
        int sw = mc.displayWidth / scale, sh = mc.displayHeight / scale;
        int game = new ScaledResolution(mc).getScaleFactor();
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale / (float) game, scale / (float) game, 1);
        GlStateManager.disableDepth();
        RenderUtil.rect(0, 0, sw, sh, ColorUtil.withAlpha(0, (int) (0x78 * open)));
        int w = Math.max(16, Math.min(MAX_W, sw - 32) / 16 * 16), h = Math.max(16, Math.min(MAX_H, sh - 32) / 16 * 16);
        int x = (sw - w) / 2, y = (sh - h) / 2 + Math.round(12 * (1 - open));
        drawPanel(x, y, w, h);
        if (!tipSeen) tipKey = null;
        if (tooltip != null) drawTooltip(sw, sh);
        clips.clear();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        float bx = sw - 64, by = sh - 25;
        Skin.box(bx, by, 60, 20, false, over(bx, by, 60, 20));
        Skin.text("配置", bx + (60 - Skin.width("配置")) / 2f, by + 6, Skin.TEXT);
        hit(Kind.CONFIG, null, 0, bx, by, 60, 20);
        gale.ui.raven.RavenWatermark.render(4, sh - 12, false);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private void drawPanel(int x, int y, int w, int h) {
        // Only the wooden frame is opaque; no backing may cover the glass openings.
        RenderUtil.rect(x + 3, y + h + 1, x + w + 3, y + h + 3, 0x50000000);
        RenderUtil.rect(x + w + 1, y + 3, x + w + 3, y + h + 1, 0x50000000);
        RenderUtil.outline(x - 1, y - 1, x + w + 1, y + h + 1, 1, 0xFF33240F);
        Skin.planks(x, y, w, 8, x, y);
        Skin.planks(x, y + h - 8, w, 8, x, y);
        Skin.planks(x, y + 8, 8, h - 16, x, y);
        Skin.planks(x + w - 8, y + 8, 8, h - 16, x, y);
        Skin.planks(x + 8 + SIDE, y + 8, 8, h - 16, x, y);
        drawSidebar(x + 8, y + 8, h - 16);
        int cx = x + 8 + SIDE + 8;
        drawContent(cx, y + 8, x + w - 8 - cx, h - 16);
    }

    private void drawSidebar(int x, int y, int height) {
        // the categories that have modules (Extensions only once an extension is loaded); closer together when there are more than ten
        List<Category> cats = new ArrayList<>();
        for (Category c : Category.values()) if (!Gale.modules().visibleIn(c).isEmpty()) cats.add(c);
        int gap = cats.size() > 10 ? 2 : TAB_GAP;
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            int ty = y + i * (TAB + gap);
            int bottom = ty + TAB;
            int woodHeight = i + 1 < cats.size() ? gap : y + height - bottom;
            if (woodHeight > 0) Skin.planks(x, bottom, SIDE, woodHeight, x - 8, y - 8);
            boolean selected = search.isEmpty() && c == category, hover = over(x, ty, SIDE, TAB);
            Skin.shade(x, ty, SIDE, TAB);
            Skin.glass(x, ty, SIDE, TAB, selected ? Skin.GLASS_SELECTED : hover ? Skin.GLASS_HOVER : Skin.GLASS_FILL);
            if (selected) RenderUtil.rect(x + 1, ty + 5, x + 3, ty + TAB - 5, Skin.ACCENT);
            item(icon(c), x + 5, ty + 3);
            Skin.text(c.chinese, x + 26, ty + 7, selected ? Skin.TEXT : Skin.TEXT_2);
            String n = String.valueOf(Gale.modules().visibleIn(c).size());
            Skin.text(n, x + SIDE - 7 - Skin.width(n), ty + 7, selected ? Skin.ACCENT_DARK : Skin.TEXT_3);
            hit(Kind.TAB, c, 0, x, ty, SIDE, TAB);
        }
        // the name, on the wood
        int by = y + height - 26;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 2, by, 0);
        GlStateManager.scale(2, 2, 1);
        Skin.shadowText(Gale.NAME, 0, 0, 0xFFFFFFFF);
        GlStateManager.popMatrix();
        String key = guiKey() == 0 ? "" : "  " + KeyUtil.name(guiKey());
        Skin.shadowText("v" + Gale.VERSION + key, x + 3, by + 19, 0xFFE9DFC9);
    }

    private void drawContent(int x, int y, int w, int h) {
        Skin.shade(x, y, w, h);
        Skin.glass(x, y, w, h, Skin.GLASS_FILL);
        List<Module> list = shown();
        // the heading: what is shown, how many, and the search field
        String title = "§l" + (search.isEmpty() ? category.chinese : "搜索");
        int on = 0;
        for (Module m : list) if (m.isEnabled()) on++;
        String sub = search.isEmpty() ? list.size() + " 个模块 ∙ 已启用 " + on + " 个" : list.size() + " 个结果";
        Skin.text(title, x + 10, y + 10, Skin.TEXT);
        Skin.text(sub, x + 10 + Skin.width(title) + 8, y + 10, Skin.TEXT_3);
        int fw = 108, fh = 14, fx = x + w - 10 - fw, fy = y + 7;
        Skin.box(fx, fy, fw, fh, searchFocused, over(fx, fy, fw, fh));
        if (search.isEmpty() && !searchFocused) Skin.text("搜索模块...", fx + 5, fy + 3, Skin.TEXT_3);
        else field(search, searchFocused, fx, fy, fw, fh);
        hit(Kind.SEARCH, null, 0, fx, fy, fw, fh);
        RenderUtil.rect(x + 6, y + 27, x + w - 6, y + 28, Skin.LINE);

        listX = x + 6;
        listW = w - 12 - 5;
        listTop = y + 31;
        listHeight = h - 31 - 5;
        drawList(list);
    }

    private void drawList(List<Module> list) {
        float x = listX, w = listW, viewH = listHeight;
        scrollTarget = MathUtil.clamp(scrollTarget, 0, Math.max(0, contentHeight - viewH));
        scroll += (scrollTarget - scroll) * Math.min(1, dt * 16);
        if (Math.abs(scrollTarget - scroll) < 0.3f) scroll = scrollTarget;
        float offset = Math.round(scroll * scale) / (float) scale;

        pushClip(x, listTop, w, viewH);
        hit(Kind.LIST, null, 0, x, listTop, w, viewH);
        if (list.isEmpty()) {
            String t = search.isEmpty() ? "这个分类还没有模块" : "没有找到模块";
            Skin.text(t, x + (w - Skin.width(t)) / 2, listTop + 30, Skin.TEXT_3);
        }
        float y = listTop - offset;
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            boolean open = EXPANDED.contains(m.getName());
            float e = anim("open:" + m.getName(), open ? 1 : 0, 14);
            Layout lay = e > 0 ? layout(m, w) : null;
            float extra = lay == null ? 0 : lay.height * ease(e);
            float total = ROW + extra;
            if (y + total > listTop && y < listTop + viewH) {
                if (e > 0) RenderUtil.rect(x, y, x + w, y + total, Skin.GLASS_INSET);
                drawRow(m, x, y, w, open);
                if (lay != null && extra > 0.5f) {
                    pushClip(x, y + ROW, w, extra);
                    drawSettings(m, lay, x, y + ROW, w);
                    popClip();
                }
                boolean nextClosed = i + 1 < list.size() && !EXPANDED.contains(list.get(i + 1).getName());
                if (e == 0 && nextClosed) RenderUtil.rect(x + 4, y + ROW, x + w - 4, y + ROW + 1, Skin.LINE);
            }
            y += total + 2;
        }
        contentHeight = y + offset - listTop;
        popClip();

        if (contentHeight > viewH) {
            float bx = x + w + 2, thumb = Math.max(16, viewH * viewH / contentHeight);
            float ty = listTop + (viewH - thumb) * (scroll / (contentHeight - viewH));
            RenderUtil.rect(bx, listTop, bx + 2, listTop + viewH, Skin.LINE);
            RenderUtil.rect(bx, ty, bx + 2, ty + thumb, scrollDrag || over(bx - 1, listTop, 4, viewH) ? Skin.ACCENT : Skin.CONTROL_HOVER);
            hit(Kind.SCROLLBAR, null, 0, bx - 1, listTop, 4, viewH);
        }
    }

    private void drawRow(Module m, float x, float y, float w, boolean open) {
        boolean hover = over(x, y, w, ROW);
        if (hover) RenderUtil.rect(x, y, x + w, y + ROW, 0x18FFFFFF);
        hit(Kind.ROW, m, 0, x, y, w, ROW);
        if (m.isEnabled() && !m.isAlwaysOn()) RenderUtil.rect(x, y + 5, x + 2, y + ROW - 5, m.isDeferred() ? Skin.WARN : Skin.ACCENT);

        float right = x + w - 8;
        Skin.arrow(right - 5, y + 8, open, hover ? Skin.TEXT_2 : Skin.TEXT_3);
        right -= 5 + 8;
        if (m.isAlwaysOn()) {
            String t = "常开";
            Skin.text(t, right - Skin.width(t), y + 7, Skin.TEXT_3);
            right -= Skin.width(t) + 10;
        } else {
            Skin.toggle(right - 20, y + 6, anim(m, m.isEnabled() ? 1 : 0, 18));
            hit(Kind.SWITCH, m, 0, right - 22, y + 3, 24, ROW - 6);
            right -= 20 + 8;
        }
        boolean listening = binding == m;
        String key = listening ? "按一个键" : m.getKey() == 0 ? "无" : KeyUtil.name(m.getKey());
        if (!listening && m.getKey() != 0 && m.getBindMode() == Module.BindMode.HOLD) key += " 按住";
        int kw = Math.max(24, Skin.width(key) + 10);
        boolean keyHover = over(right - kw, y + 5, kw, 12);
        Skin.box(right - kw, y + 5, kw, 12, listening, keyHover);
        Skin.text(key, right - kw + (kw - Skin.width(key)) / 2f, y + 7, listening ? Skin.ACCENT_DARK : Skin.TEXT_2);
        hit(Kind.KEY, m, 0, right - kw, y + 5, kw, 12);
        if (keyHover && !listening) tip("key:" + m.getName(), "左键换键（键盘或鼠标侧键）\n右键清除");
        right -= kw + 10;

        float tx = x + 9;
        Skin.text(m.getName(), tx, y + 7, m.isEnabled() || m.isAlwaysOn() ? Skin.TEXT : Skin.TEXT_2);
        tx += Skin.width(m.getName()) + 7;
        if (m.isDeferred()) {
            String tag = "让给另一个端";
            Skin.text(tag, tx, y + 7, Skin.WARN);
            tx += Skin.width(tag) + 7;
        }
        String info = m.getInfo();
        if (info != null && !info.isEmpty()) {
            String shown = Skin.fit(info, Math.min(140, right - tx));
            Skin.text(shown, tx, y + 7, Skin.ACCENT_DARK);
            tx += Skin.width(shown) + 7;
            if (hover && !keyHover && !shown.equals(info)) tip("info:" + m.getName(), info);
        }
        String desc = Skin.fit(m.getDescription(), right - tx);
        Skin.text(desc, tx, y + 7, Skin.TEXT_3);
        if (hover && !keyHover && !desc.equals(m.getDescription())) tip(m, m.getDescription());
    }

    private Layout layout(Module m, float w) {
        Layout l = new Layout(w);
        for (Setting<?> s : m.getSettings()) {
            if (!s.isVisible()) continue;
            if (s instanceof BoolSetting) l.add(new Cell(Kind.BOOL, s, CELL), false);
            else if (s instanceof NumberSetting || s instanceof RangeSetting) l.add(new Cell(Kind.SLIDER, s, CELL), false);
            else if (s instanceof ModeSetting) {
                float need = Skin.width(s.getName()) + 16 + segmentWidth(((ModeSetting) s).getVisibleModes());
                if (need <= l.col) l.add(new Cell(Kind.SEGMENT, s, CELL), false);
                else if (need <= l.inner) l.add(new Cell(Kind.SEGMENT, s, CELL), true);
                else {
                    float longest = 0;
                    for (String option : ((ModeSetting) s).getVisibleModes()) longest = Math.max(longest, Skin.width(option));
                    l.add(new Cell(Kind.CYCLE, s, CELL), longest + 26 + Skin.width(s.getName()) + 16 > l.col);
                }
            } else if (s instanceof ColorSetting) {
                l.add(new Cell(Kind.COLOR, s, CELL), false);
                if (picker == s) l.add(new Cell(Kind.PICKER, s, PICKER), true);
            } else if (s instanceof TextSetting) {
                l.add(new Cell(Kind.TEXT, s, CELL), true);
            }
        }
        if (!m.isAlwaysOn()) {
            l.add(new Cell(Kind.BIND_MODE, null, CELL), false);
            l.add(new Cell(Kind.HIDE, null, CELL), false);
        }
        if (l.cells.isEmpty()) l.add(new Cell(Kind.INFO, null, CELL), true);
        return l.done();
    }

    private void drawSettings(Module m, Layout lay, float x, float y, float w) {
        RenderUtil.rect(x + 6, y, x + w - 6, y + 1, Skin.LINE);
        for (Cell c : lay.cells) {
            float cx = x + c.x, cy = y + c.y;
            if (cy + c.h < listTop || cy > listTop + listHeight) continue;
            drawCell(m, c, cx, cy, c.w, c.h);
        }
    }

    private void drawCell(Module m, Cell c, float x, float y, float w, float h) {
        Setting<?> s = c.setting;
        boolean hover = over(x, y, w, h);
        if (hover && c.kind != Kind.PICKER && c.kind != Kind.INFO) RenderUtil.rect(x, y, x + w, y + h, 0x18FFFFFF);
        if (s != null && c.kind != Kind.PICKER) {
            hit(Kind.CELL, s, 0, x, y, w, h);
            if (hover) tip(s, (s.getDescription().isEmpty() ? "" : s.getDescription() + "\n") + "§7右键恢复默认");
        }
        switch (c.kind) {
            case BOOL:
                label(s.getName(), x, y, w - 30);
                Skin.toggle(x + w - 25, y + 5, anim(s, ((BoolSetting) s).get() ? 1 : 0, 18));
                hit(Kind.BOOL, s, 0, x, y, w, h);
                break;
            case SLIDER: {
                boolean range = s instanceof RangeSetting;
                String v = s.display();
                Skin.text(Skin.fit(s.getName(), w - 16 - Skin.width(v)), x + 5, y + 3, Skin.TEXT);
                Skin.text(v, x + w - 5 - Skin.width(v), y + 3, sliding == s ? Skin.ACCENT_DARK : Skin.TEXT_2);
                float a = 0, b;
                if (range) {
                    RangeSetting r = (RangeSetting) s;
                    a = fraction(r.low(), r.min, r.max);
                    b = fraction(r.high(), r.min, r.max);
                } else {
                    b = (float) ((NumberSetting) s).fraction();
                }
                float tx = x + 6, tw = w - 12;
                Skin.slider(tx, y + 14, tw, a, b, range, sliding == s);
                hit(Kind.SLIDER, s, 0, x, y + 9, w, h - 9, tx, y + 9, tw, h - 9);
                break;
            }
            case SEGMENT: {
                ModeSetting ms = (ModeSetting) s;
                float segW = segmentWidth(ms.getVisibleModes());
                label(s.getName(), x, y, w - segW - 14);
                segment(Kind.SEGMENT, s, ms.getVisibleModes(), ms.visibleIndex(), x + w - 5 - segW, y + 4);
                break;
            }
            case CYCLE: {
                ModeSetting ms = (ModeSetting) s;
                float bw = 0;
                for (String o : ms.getVisibleModes()) bw = Math.max(bw, Skin.width(o));
                bw = Math.min(bw + 26, Math.max(30, w - 10));
                float bx = x + w - 5 - bw, by = y + 4;
                label(s.getName(), x, y, w - bw - 14);
                Skin.box(bx, by, bw, 12, false, over(bx, by, bw, 12));
                Skin.text("<", bx + 4, by + 2, Skin.TEXT_3);
                Skin.text(">", bx + bw - 4 - Skin.width(">"), by + 2, Skin.TEXT_3);
                String selected = Skin.fit(ms.visibleDisplay(), bw - 26);
                Skin.text(selected, bx + (bw - Skin.width(selected)) / 2, by + 2, Skin.TEXT);
                if (hover && !selected.equals(ms.visibleDisplay())) tip(ms, ms.visibleDisplay());
                hit(Kind.CYCLE, s, -1, bx, by, bw / 2, 12);
                hit(Kind.CYCLE, s, 1, bx + bw / 2, by, bw / 2, 12);
                break;
            }
            case COLOR: {
                ColorSetting cs = (ColorSetting) s;
                float bx = x + w - 5 - 18, by = y + 5;
                String v = cs.isRainbow() ? "彩虹" : String.format("#%06X", cs.getValue() & 0xFFFFFF);
                label(s.getName(), x, y, w - 36 - Skin.width(v));
                Skin.text(v, bx - 5 - Skin.width(v), y + 6, Skin.TEXT_3);
                RenderUtil.rect(bx, by, bx + 18, by + 10, picker == cs ? Skin.ACCENT : Skin.CONTROL);
                Skin.checker(bx + 1, by + 1, 16, 8);
                RenderUtil.rect(bx + 1, by + 1, bx + 17, by + 9, cs.get());
                hit(Kind.COLOR, s, 0, x, y, w, h);
                break;
            }
            case PICKER:
                drawPicker((ColorSetting) s, x, y, h);
                break;
            case TEXT: {
                TextSetting ts = (TextSetting) s;
                float fw = Math.min(180, w / 2), fx = x + w - 5 - fw, fy = y + 3;
                label(s.getName(), x, y, w - fw - 14);
                boolean focused = editing == ts;
                Skin.box(fx, fy, fw, 14, focused, over(fx, fy, fw, 14));
                field(ts.get(), focused, fx, fy, fw, 14);
                hit(Kind.TEXT, s, 0, fx, fy, fw, 14);
                break;
            }
            case BIND_MODE: {
                float segW = segmentWidth(BIND_MODES);
                label("按键方式", x, y, w - segW - 14);
                segment(Kind.BIND_MODE, m, BIND_MODES, m.getBindMode() == Module.BindMode.HOLD ? 1 : 0, x + w - 5 - segW, y + 4);
                if (hover) tip("bindmode", "切换：按一下开，再按一下关\n按住：只在按着键时开着");
                break;
            }
            case HIDE:
                label("不在列表里显示", x, y, w - 30);
                Skin.toggle(x + w - 25, y + 5, anim("hide:" + m.getName(), m.isHidden() ? 1 : 0, 18));
                hit(Kind.HIDE, m, 0, x, y, w, h);
                if (hover) tip("hide", "HUD 的模块列表里不显示它");
                break;
            default: {
                String t = "没有可以调的设置";
                Skin.text(t, x + (w - Skin.width(t)) / 2, y + 6, Skin.TEXT_3);
            }
        }
    }

    private void label(String text, float x, float y, float room) { Skin.text(Skin.fit(text, room), x + 5, y + 6, Skin.TEXT); }

    private static float segmentWidth(List<String> options) {
        float w = 0;
        for (String o : options) w += Skin.width(o) + 10;
        return w;
    }

    /** Options side by side, the chosen one filled. */
    private void segment(Kind kind, Object target, List<String> options, int selected, float x, float y) {
        float w = segmentWidth(options);
        RenderUtil.rect(x, y, x + w, y + 12, Skin.CONTROL);
        RenderUtil.rect(x + 1, y + 1, x + w - 1, y + 11, Skin.FIELD);
        float ox = x;
        for (int i = 0; i < options.size(); i++) {
            String o = options.get(i);
            float ow = Skin.width(o) + 10;
            if (i == selected) RenderUtil.rect(ox, y, ox + ow, y + 12, Skin.ACCENT);
            else if (over(ox, y, ow, 12)) RenderUtil.rect(ox + 1, y + 1, ox + ow - 1, y + 11, Skin.GLASS_HOVER);
            if (i > 0 && i != selected && i - 1 != selected) RenderUtil.rect(ox, y + 3, ox + 1, y + 9, Skin.LINE);
            Skin.text(o, ox + 5, y + 2, i == selected ? 0xFFFFFFFF : Skin.TEXT_2);
            hit(kind, target, i, ox, y, ow, 12);
            ox += ow;
        }
    }

    /** Saturation and brightness square, hue bar, alpha bar, then a preview, the code and the rainbow switch. */
    private void drawPicker(ColorSetting c, float x, float y, float h) {
        if (c.getValue() != pickerValue) loadPicker(c, false);
        float px = x + 6, py = y + 4, pw = 120, ph = h - 10;
        RenderUtil.rect(px - 1, py - 1, px + pw + 1, py + ph + 1, Skin.CONTROL);
        RenderUtil.gradientSideways(px, py, px + pw, py + ph, 0xFFFFFFFF, ColorUtil.hsb(hsb[0], 1, 1));
        RenderUtil.gradient(px, py, px + pw, py + ph, 0x00000000, 0xFF000000);
        float mx = Math.round(px + hsb[1] * pw), my = Math.round(py + (1 - hsb[2]) * ph);
        RenderUtil.outline(mx - 2, my - 2, mx + 3, my + 3, 1, hsb[2] > 0.6f && hsb[1] < 0.5f ? 0xFF000000 : 0xFFFFFFFF);
        hit(Kind.PICK_SB, c, 0, px, py, pw, ph, px, py, pw, ph);

        float hx = px + pw + 7;
        RenderUtil.rect(hx - 1, py - 1, hx + 9, py + ph + 1, Skin.CONTROL);
        for (int i = 0; i < 6; i++)
            RenderUtil.gradient(hx, py + ph * i / 6f, hx + 8, py + ph * (i + 1) / 6f, ColorUtil.hsb(i / 6f, 1, 1), ColorUtil.hsb((i + 1) / 6f, 1, 1));
        marker(hx, py + hsb[0] * ph);
        hit(Kind.PICK_HUE, c, 0, hx - 2, py, 12, ph, hx, py, 8, ph);

        int value = c.getValue();
        float ax = hx + 15;
        RenderUtil.rect(ax - 1, py - 1, ax + 9, py + ph + 1, Skin.CONTROL);
        Skin.checker(ax, py, 8, ph);
        RenderUtil.gradient(ax, py, ax + 8, py + ph, ColorUtil.withAlpha(value, 255), ColorUtil.withAlpha(value, 0));
        marker(ax, py + (1 - ColorUtil.alpha(value) / 255f) * ph);
        hit(Kind.PICK_ALPHA, c, 0, ax - 2, py, 12, ph, ax, py, 8, ph);

        float ix = ax + 18;
        RenderUtil.rect(ix - 1, py - 1, ix + 31, py + 17, Skin.CONTROL);
        Skin.checker(ix, py, 30, 16);
        RenderUtil.rect(ix, py, ix + 30, py + 16, c.get());
        Skin.text(String.format("#%08X", value), ix, py + 22, Skin.TEXT_2);
        String rainbow = "彩虹";
        Skin.text(rainbow, ix, py + 37, Skin.TEXT);
        Skin.toggle(ix + Skin.width(rainbow) + 6, py + 36, anim("rainbow", c.isRainbow() ? 1 : 0, 18));
        hit(Kind.RAINBOW, c, 0, ix, py + 33, Skin.width(rainbow) + 28, 16);
    }

    private static void marker(float x, float y) {
        y = Math.round(y);
        RenderUtil.rect(x - 2, y - 1, x + 10, y + 2, Skin.TEXT);
        RenderUtil.rect(x - 1, y, x + 9, y + 1, 0xFFFFFFFF);
    }

    /** Text in a field, its end shown when it is too long, with a blinking caret when focused. */
    private void field(String text, boolean focused, float x, float y, float w, float h) {
        String shown = text;
        while (shown.length() > 0 && Skin.width(shown) > w - 11) shown = shown.substring(1);
        Skin.text(shown, x + 5, y + (h - 8) / 2, Skin.TEXT);
        if (focused && System.currentTimeMillis() / 500 % 2 == 0) {
            float cx = x + 5 + Skin.width(shown) + 1;
            RenderUtil.rect(cx, y + 3, cx + 1, y + h - 3, Skin.TEXT);
        }
    }

    private void drawTooltip(int sw, int sh) {
        List<String> lines = new ArrayList<>();
        for (String part : tooltip.split("\n")) lines.addAll(mc.fontRendererObj.listFormattedStringToWidth(part, 220));
        int w = 0;
        for (String l : lines) w = Math.max(w, Skin.width(l));
        float bw = w + 12, bh = lines.size() * 10 + 8;
        float x = Math.min(mouseX + 10, sw - bw - 4), y = mouseY + 14;
        if (y + bh > sh - 4) y = mouseY - bh - 6;
        x = Math.round(x);
        y = Math.round(y);
        Skin.shade(x, y, bw, bh);
        Skin.glass(x, y, bw, bh, 0xE00C2029);
        for (int i = 0; i < lines.size(); i++) Skin.text(lines.get(i), x + 6, y + 5 + i * 10, Skin.TEXT);
    }

    private static void item(ItemStack stack, int x, int y) {
        GlStateManager.enableDepth();
        RenderUtil.item(stack, x, y);
        GlStateManager.disableDepth();
    }

    private static ItemStack icon(Category c) {
        ItemStack s = ICONS.get(c);
        if (s == null) {
            switch (c) {
                case COMBAT: s = new ItemStack(Items.diamond_sword); break;
                case MOVEMENT: s = new ItemStack(Items.diamond_boots); break;
                case PLAYER: s = new ItemStack(Items.golden_apple); break;
                case WORLD: s = new ItemStack(Blocks.grass); break;
                case RENDER: s = new ItemStack(Items.ender_eye); break;
                case MINIGAMES: s = new ItemStack(Items.bed); break;
                case OPTIMIZE: s = new ItemStack(Items.clock); break;
                case ASSIST: s = new ItemStack(Items.lead); break;
                case SCRIPTS: s = new ItemStack(Items.writable_book); break;
                case EXTENSIONS: s = new ItemStack(Items.nether_star); break;
                default: s = new ItemStack(Blocks.command_block); break;
            }
            ICONS.put(c, s);
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers for drawing

    /** Remembers a clickable part, cut to the current clip (what scrolled out cannot be clicked). */
    private void hit(Kind kind, Object target, int index, float x, float y, float w, float h) { hit(kind, target, index, x, y, w, h, x, y, w, h); }

    private void hit(Kind kind, Object target, int index, float x, float y, float w, float h, float ox, float oy, float ow, float oh) {
        float[] c = clips.peek();
        if (c != null) {
            float x2 = Math.min(x + w, c[0] + c[2]), y2 = Math.min(y + h, c[1] + c[3]);
            x = Math.max(x, c[0]);
            y = Math.max(y, c[1]);
            w = x2 - x;
            h = y2 - y;
            if (w <= 0 || h <= 0) return;
        }
        hits.add(new Hit(kind, target, index, x, y, w, h, ox, oy, ow, oh));
    }

    /** Whether the mouse is over the rectangle and inside the current clip. */
    private boolean over(float x, float y, float w, float h) {
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
        float[] c = clips.peek();
        return c == null || mouseX >= c[0] && mouseX < c[0] + c[2] && mouseY >= c[1] && mouseY < c[1] + c[3];
    }

    private void pushClip(float x, float y, float w, float h) {
        float[] c = clips.peek();
        if (c != null) {
            float x2 = Math.min(x + w, c[0] + c[2]), y2 = Math.min(y + h, c[1] + c[3]);
            x = Math.max(x, c[0]);
            y = Math.max(y, c[1]);
            w = Math.max(0, x2 - x);
            h = Math.max(0, y2 - y);
        }
        clips.push(new float[]{ x, y, w, h });
        applyClip();
    }

    private void popClip() {
        clips.pop();
        applyClip();
    }

    private void applyClip() {
        float[] c = clips.peek();
        if (c == null) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(Math.round(c[0] * scale), Math.round(mc.displayHeight - (c[1] + c[3]) * scale), Math.round(c[2] * scale), Math.round(c[3] * scale));
    }

    /** Eases a value toward target (0..1) at speed per second, by real time; starts at the target the first time it is asked for. */
    private float anim(Object key, float target, float speed) {
        Float v = anims.get(key);
        float now = v == null ? target : v + (target - v) * (float) (1.0 - Math.exp(-dt * speed));
        if (Math.abs(target - now) < 0.004f) now = target;
        anims.put(key, now);
        return now;
    }

    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }

    private static float fraction(double v, double min, double max) { return max == min ? 0 : (float) ((v - min) / (max - min)); }

    /** A tooltip once the mouse has stayed on the same thing for a moment. */
    private void tip(Object key, String text) {
        if (text == null || text.isEmpty()) return;
        tipSeen = true;
        if (!Objects.equals(key, tipKey)) {
            tipKey = key;
            tipSince = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - tipSince >= 400) tooltip = text;
    }

    private List<Module> shown() {
        if (search.isEmpty()) return Gale.modules().visibleIn(category);
        String q = ModuleManager.key(search);
        List<Module> out = new ArrayList<>();
        for (Module m : Gale.modules().visible()) if (matches(m, q)) out.add(m);
        return out;
    }

    private static boolean matches(Module m, String q) {
        if (ModuleManager.key(m.getName()).contains(q) || ModuleManager.key(m.getDescription()).contains(q)) return true;
        for (Setting<?> s : m.getSettings()) if (ModuleManager.key(s.getName()).contains(q)) return true;
        return false;
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleMouseInput() {
        float mx = Mouse.getEventX() / (float) scale, my = (mc.displayHeight - Mouse.getEventY() - 1) / (float) scale;
        int button = Mouse.getEventButton();
        if (button >= 0) {
            if (Mouse.getEventButtonState()) press(mx, my, button);
            else release(button);
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && mx >= listX && mx < listX + listW + 5 && my >= listTop && my < listTop + listHeight) scrollTarget -= wheel / 120f * 32;
    }

    private Hit hitAt(float mx, float my) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(mx, my)) return hits.get(i);
        return null;
    }

    private void press(float mx, float my, int button) {
        if (binding != null) {
            // a left click cancels (binding the attack button would switch the module on every hit)
            if (button != 0) binding.setKey(button - 100);
            binding = null;
            return;
        }
        Hit h = hitAt(mx, my);
        if (h == null || h.kind != Kind.TEXT) editing = null;
        if (h == null || h.kind != Kind.SEARCH) searchFocused = false;
        if (h == null) return;
        Object t = h.target;
        if (button == 1 && t instanceof Setting && RESETTABLE.contains(h.kind)) {
            reset((Setting<?>) t);
            return;
        }
        switch (h.kind) {
            case CONFIG:
                if (button == 0) mc.displayGuiScreen(new ConfigScreen());
                return;
            case TAB:
                if (button == 0) select((Category) t);
                return;
            case SEARCH:
                searchFocused = true;
                if (button == 1) setSearch("");
                return;
            case ROW: {
                Module m = (Module) t;
                if (button == 0) setOpen(m, !EXPANDED.contains(m.getName()));
                else if (button == 1 && !m.isAlwaysOn()) m.toggle();
                return;
            }
            case KEY:
                if (button == 0) binding = (Module) t;
                else if (button == 1) ((Module) t).setKey(0);
                return;
            case SCROLLBAR:
                if (button != 0) return;
                float thumb = Math.max(16, listHeight * listHeight / contentHeight);
                float ty = listTop + (listHeight - thumb) * (scroll / (contentHeight - listHeight));
                scrollGrab = my >= ty && my < ty + thumb ? my - ty : thumb / 2;
                scrollDrag = true;
                scrollTo(my);
                return;
            default:
        }
        if (button != 0) return;
        switch (h.kind) {
            case SWITCH:
                ((Module) t).toggle();
                break;
            case BOOL:
                ((BoolSetting) t).toggle();
                break;
            case SLIDER:
                sliding = (Setting<?>) t;
                slideX = h.ox;
                slideW = h.ow;
                if (t instanceof RangeSetting) {
                    RangeSetting r = (RangeSetting) t;
                    double at = r.min + (mx - slideX) / slideW * (r.max - r.min);
                    rangeHigh = at > r.high() || Math.abs(at - r.high()) < Math.abs(at - r.low());
                }
                slide(mx);
                break;
            case SEGMENT: {
                ModeSetting ms = (ModeSetting) t;
                ms.setVisibleIndex(h.index);
                break;
            }
            case CYCLE:
                ((ModeSetting) t).cycleVisible(h.index > 0);
                break;
            case COLOR:
                picker = picker == t ? null : (ColorSetting) t;
                if (picker != null) loadPicker(picker, true);
                break;
            case PICK_SB:
            case PICK_HUE:
            case PICK_ALPHA:
                picking = h.kind;
                pickX = h.ox;
                pickY = h.oy;
                pickW = h.ow;
                pickH = h.oh;
                pick(mx, my);
                break;
            case RAINBOW:
                ((ColorSetting) t).setRainbow(!((ColorSetting) t).isRainbow());
                break;
            case TEXT:
                editing = (TextSetting) t;
                break;
            case BIND_MODE:
                ((Module) t).setBindMode(h.index == 1 ? Module.BindMode.HOLD : Module.BindMode.TOGGLE);
                break;
            case HIDE:
                ((Module) t).setHidden(!((Module) t).isHidden());
                break;
            default:
        }
    }

    private void release(int button) {
        if (button != 0) return;
        sliding = null;
        picking = null;
        scrollDrag = false;
    }

    /** Follows the mouse while a slider, the picker or the scroll bar is held. */
    private void drag() {
        boolean down = Mouse.isButtonDown(0);
        if (sliding != null) {
            if (down) slide(mouseX);
            else sliding = null;
        }
        if (picking != null) {
            if (down) pick(mouseX, mouseY);
            else picking = null;
        }
        if (scrollDrag) {
            if (down) scrollTo(mouseY);
            else scrollDrag = false;
        }
    }

    private void slide(float mx) {
        float f = MathUtil.clamp((mx - slideX) / slideW, 0f, 1f);
        if (sliding instanceof NumberSetting) {
            ((NumberSetting) sliding).setFraction(f);
        } else if (sliding instanceof RangeSetting) {
            RangeSetting r = (RangeSetting) sliding;
            double v = r.min + f * (r.max - r.min);
            if (rangeHigh) r.set(r.low(), Math.max(v, r.low()));
            else r.set(Math.min(v, r.high()), r.high());
        }
    }

    private void loadPicker(ColorSetting c, boolean fresh) {
        int v = c.getValue();
        float[] f = Color.RGBtoHSB(ColorUtil.red(v), ColorUtil.green(v), ColorUtil.blue(v), null);
        // greys and black have no hue (and black no saturation): keep what the picker had
        if (fresh || f[1] > 0 && f[2] > 0) hsb[0] = f[0];
        if (fresh || f[2] > 0) hsb[1] = f[1];
        hsb[2] = f[2];
        pickerValue = v;
    }

    private void pick(float mx, float my) {
        if (picker == null) return;
        float fx = MathUtil.clamp((mx - pickX) / pickW, 0f, 1f), fy = MathUtil.clamp((my - pickY) / pickH, 0f, 1f);
        int alpha = ColorUtil.alpha(picker.getValue());
        if (picking == Kind.PICK_SB) {
            hsb[1] = fx;
            hsb[2] = 1 - fy;
        } else if (picking == Kind.PICK_HUE) {
            hsb[0] = Math.min(fy, 0.999f);
        } else {
            alpha = Math.round((1 - fy) * 255);
        }
        picker.setRainbow(false);
        picker.set(ColorUtil.withAlpha(ColorUtil.hsb(hsb[0], hsb[1], hsb[2]), alpha));
        pickerValue = picker.getValue();
    }

    private void scrollTo(float my) {
        float thumb = Math.max(16, listHeight * listHeight / contentHeight);
        float f = MathUtil.clamp((my - scrollGrab - listTop) / (listHeight - thumb), 0f, 1f);
        scrollTarget = scroll = f * Math.max(0, contentHeight - listHeight);
    }

    private static void reset(Setting<?> s) {
        s.reset();
        if (s instanceof ColorSetting) ((ColorSetting) s).setRainbow(false);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (binding != null) {
            binding.setKey(key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK ? 0 : key);
            binding = null;
            return;
        }
        if (editing != null) {
            String t = type(editing.get(), c, key);
            if (t == null) editing = null;
            else editing.set(t);
            return;
        }
        if (searchFocused) {
            String t = type(search, c, key);
            if (t != null) {
                setSearch(t);
            } else {
                if (key == Keyboard.KEY_ESCAPE) setSearch("");
                searchFocused = false;
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE || key == guiKey()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (c != ' ' && ChatAllowedCharacters.isAllowedCharacter(c)) {
            searchFocused = true;
            setSearch(search + c);
        }
    }

    /** The text after a key; null when the key ends typing (Enter, Esc). */
    private static String type(String text, char c, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) return null;
        if (key == Keyboard.KEY_BACK) return text.isEmpty() ? text : isCtrlKeyDown() ? "" : text.substring(0, text.length() - 1);
        if (isKeyComboCtrlV(key)) return text + ChatAllowedCharacters.filterAllowedCharacters(getClipboardString());
        if (ChatAllowedCharacters.isAllowedCharacter(c)) return text + c;
        return text;
    }

    private void setSearch(String s) {
        if (search.isEmpty() && !s.isEmpty()) SCROLLS.put(category, scrollTarget);
        search = s;
        scroll = scrollTarget = s.isEmpty() ? SCROLLS.getOrDefault(category, 0f) : 0;
    }

    private void setOpen(Module m, boolean open) {
        if (open) {
            EXPANDED.add(m.getName());
        } else {
            EXPANDED.remove(m.getName());
            if (editing != null && m.getSettings().contains(editing)) editing = null;
        }
    }

    // ------------------------------------------------------------------ for the dev test and other code

    /** Shows a category (also when the screen is not open). */
    public void select(Category c) {
        if (search.isEmpty() && category != null) SCROLLS.put(category, scrollTarget);
        category = c;
        search = "";
        searchFocused = false;
        scroll = scrollTarget = SCROLLS.getOrDefault(c, 0f);
    }

    /** Opens or closes a module's settings. */
    public static void setExpanded(Module m, boolean open) {
        if (open) EXPANDED.add(m.getName());
        else EXPANDED.remove(m.getName());
    }

    /** Clicks a control as it was last drawn: kind is TAB, ROW, SWITCH, KEY, BOOL, SLIDER, SEGMENT, COLOR, TEXT...; fx is where across it (0..1). */
    public boolean click(Object target, String kind, int index, int button, float fx) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.target != target || !h.kind.name().equals(kind) || index != Integer.MIN_VALUE && h.index != index) continue;
            press(h.x + Math.min(h.w - 0.01f, h.w * fx), h.y + h.h / 2, button);
            release(button);
            return true;
        }
        return false;
    }

    /** Types a key as if pressed. */
    public void type(char c, int key) { keyTyped(c, key); }

    /** Scrolls the module list to its end or its start at once (as drawn last). */
    public void scrollToEnd(boolean end) { scrollTarget = scroll = end ? Math.max(0, contentHeight - listHeight) : 0; }

    public int shownCount() { return shown().size(); }

    public Module bindingModule() { return binding; }

    public TextSetting editingSetting() { return editing; }

    public ColorSetting openPicker() { return picker; }
}
