package gale.ui;

import gale.Gale;
import gale.config.ConfigManager;
import gale.module.impl.client.ClickGuiModule;
import gale.util.RenderUtil;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared profile manager, opened from either ClickGUI style. */
public final class ConfigScreen extends GuiScreen {
    private final List<String> profiles = new ArrayList<>();
    private GuiTextField nameField;
    private int left, top, panelWidth, panelHeight, listTop, rows, scroll, selected;
    private String message = "选择配置后点击加载；修改设置后会自动保存";
    private int messageColor = Skin.TEXT_3;
    private static final int ROW = 20;

    @Override public void initGui() {
        Keyboard.enableRepeatEvents(true);
        String text = nameField == null ? "" : nameField.getText();
        panelWidth = Math.min(380, width - 16);
        rows = Math.max(1, Math.min(8, (height - 166) / ROW));
        panelHeight = 162 + rows * ROW;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + 57;
        int listBottom = listTop + rows * ROW;
        int bw = (panelWidth - 32) / 3;
        buttonList.clear();
        buttonList.add(new GuiButton(1, left + 10, listBottom + 6, bw, 20, "加载所选"));
        buttonList.add(new GuiButton(2, left + 16 + bw, listBottom + 6, bw, 20, "保存当前"));
        buttonList.add(new GuiButton(4, left + 22 + bw * 2, listBottom + 6, bw, 20, "刷新列表"));
        nameField = new GuiTextField(10, fontRendererObj, left + 12, listBottom + 36, panelWidth - 114, 18);
        nameField.setMaxStringLength(48);
        nameField.setText(text);
        buttonList.add(new GuiButton(3, left + panelWidth - 94, listBottom + 35, 84, 20, "另存为"));
        buttonList.add(new GuiButton(0, left + 10, top + panelHeight - 26, panelWidth - 20, 20, "返回 GUI"));
        refresh(Gale.config().activeProfile());
    }

    private void refresh(String select) {
        profiles.clear();
        profiles.add(null); // config.json: the automatic save when no named profile is selected
        profiles.addAll(Gale.config().profiles());
        selected = Math.max(0, profiles.indexOf(select));
        scroll = Math.max(0, Math.min(selected, profiles.size() - rows));
    }

    private static String label(String profile) { return profile == null ? "自动保存（默认）" : profile; }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        RenderUtil.rect(0, 0, width, height, 0xA0000000);
        RenderUtil.rect(left, top, left + panelWidth, top + panelHeight, 0xE018252F);
        RenderUtil.outline(left, top, left + panelWidth, top + panelHeight, 1, Skin.LINE);
        Skin.text("配置管理", left + 12, top + 10, Skin.TEXT);
        String active = label(Gale.config().activeProfile());
        Skin.text(Skin.fit("当前：" + active, panelWidth - 24), left + 12, top + 26, Skin.TEXT_2);
        Skin.text(Gale.config().isDirty() ? "正在等待自动保存…" : "自动保存已开启 · 约 1 秒保存修改", left + 12, top + 41, Skin.TEXT_3);
        int right = left + panelWidth - 10;
        RenderUtil.rect(left + 10, listTop, right, listTop + rows * ROW, 0x70071018);
        for (int row = 0; row < rows && scroll + row < profiles.size(); row++) {
            int index = scroll + row, y = listTop + row * ROW;
            boolean hover = mouseX >= left + 10 && mouseX < right && mouseY >= y && mouseY < y + ROW;
            if (selected == index || hover) RenderUtil.rect(left + 11, y + 1, right - 1, y + ROW - 1,
                selected == index ? 0xA02A7898 : 0x403D5D70);
            boolean current = Objects.equals(profiles.get(index), Gale.config().activeProfile());
            Skin.text(Skin.fit(label(profiles.get(index)), panelWidth - 88), left + 17, y + 6, Skin.TEXT);
            if (current) Skin.text("使用中", right - 42, y + 6, Skin.ACCENT_DARK);
        }
        if (profiles.size() > rows) {
            float track = rows * ROW, thumb = track * rows / profiles.size();
            float y = listTop + (track - thumb) * scroll / (profiles.size() - rows);
            RenderUtil.rect(right - 3, y, right - 1, y + thumb, Skin.ACCENT_DARK);
        }
        if (nameField.getText().isEmpty() && !nameField.isFocused())
            Skin.text("新配置名称", nameField.xPosition + 4, nameField.yPosition - 10, Skin.TEXT_3);
        nameField.drawTextBox();
        Skin.text(Skin.fit(message, panelWidth - 24), left + 12, top + panelHeight - 40, messageColor);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override protected void actionPerformed(GuiButton button) {
        ConfigManager config = Gale.config();
        switch (button.id) {
            case 0: ClickGuiModule.open(); return;
            case 1:
                boolean loaded = config.load(profiles.get(selected));
                show(loaded, "已加载：" + label(config.activeProfile()));
                if (loaded) refresh(config.activeProfile());
                return;
            case 2: show(config.save(), "已保存：" + label(config.activeProfile())); return;
            case 3:
                boolean saved = config.saveAs(nameField.getText());
                show(saved, "已保存并启用：" + label(config.activeProfile()));
                if (saved) refresh(config.activeProfile());
                return;
            case 4:
                refresh(profiles.get(selected));
                message = "已刷新配置列表"; messageColor = Skin.TEXT_3;
        }
    }

    private void show(boolean ok, String success) {
        message = ok ? success : Gale.config().lastError();
        messageColor = ok ? Skin.ACCENT_DARK : 0xFFFF9999;
    }

    @Override protected void mouseClicked(int x, int y, int button) throws IOException {
        nameField.mouseClicked(x, y, button);
        if (button == 0 && x >= left + 10 && x < left + panelWidth - 10 && y >= listTop && y < listTop + rows * ROW) {
            int index = scroll + (y - listTop) / ROW;
            if (index < profiles.size()) selected = index;
        }
        super.mouseClicked(x, y, button);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (wheel != 0 && x >= left + 10 && x < left + panelWidth - 10 && y >= listTop && y < listTop + rows * ROW)
            scroll = Math.max(0, Math.min(Math.max(0, profiles.size() - rows), scroll + (wheel > 0 ? -1 : 1)));
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { ClickGuiModule.open(); return; }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            for (GuiButton b : buttonList) if (b.id == (nameField.isFocused() ? 3 : 1)) { actionPerformed(b); return; }
        }
        nameField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override public void updateScreen() { nameField.updateCursorCounter(); }
    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); Gale.config().flush(); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
