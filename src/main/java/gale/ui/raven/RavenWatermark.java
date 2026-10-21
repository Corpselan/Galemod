package gale.ui.raven;

import gale.b4port.util.RenderUtils;
import gale.util.GuiVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.MathHelper;

/**
 * B4's watermark in the GUI's bottom left, its look kept: "welcome, " and a rainbow name with the user number, the
 * edition line, and the dev line whose name unscrambles as the GUI opens. The user number shares the GUI visibility edition.
 */
public final class RavenWatermark {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static final String USER = "User", USER_ID = String.valueOf(GuiVisibility.LEVEL), EDITION = "galemod premium-beta", DEV = "Corpselan";

    private RavenWatermark() { }

    public static void render(int x, int y, boolean animate) {
        FontRenderer fr = mc.fontRendererObj;
        int cx = x;
        int color = RavenStyle.watermarkColor.rgb;
        fr.drawStringWithShadow("welcome, ", (float) cx, (float) (y - 20), color);
        int end = rainbowText(USER, cx += 45, y - 20, fr);
        fr.drawStringWithShadow(" #" + USER_ID, (float) end, (float) (y - 20), color);
        fr.drawStringWithShadow(EDITION, (float) x, (float) (y - 10), color);
        scramble("dev. ", DEV, x, y, animate, fr);
    }

    private static int rainbowText(String s, int x, int y, FontRenderer fr) {
        if (s.isEmpty()) return x;
        long offset = 0L;
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            fr.drawString(String.valueOf(c), (float) x, (float) y, RenderUtils.rainbow(3L, offset), true);
            x += fr.getCharWidth(c);
            if (c == ' ') continue;
            offset -= 90L;
        }
        RenderUtils.resetColor();
        return x;
    }

    private static void scramble(String prefix, String name, float x, float y, boolean animate, FontRenderer fr) {
        long now = System.currentTimeMillis();
        int base = RavenStyle.watermarkColor.rgb;
        fr.drawStringWithShadow(prefix, x, y, base);
        float cx = x + (float) fr.getStringWidth(prefix);
        long since = now - RavenGui.openedAt;
        for (int i = 0; i < name.length(); ++i) {
            char c = name.charAt(i);
            float reveal = animate ? MathHelper.clamp_float(((float) since - (float) i * 110.0f) / 400.0f, 0.0f, 1.0f) : 1.0f;
            if (reveal <= 0.0f) continue;
            char shown = c;
            if (reveal < 0.75f && (now / 45L + (long) i) % 3L != 0L) shown = (char) (97 + Math.abs((int) (now / 35L + (long) (i * 13))) % 26);
            float bob = (float) Math.sin((double) now / 220.0 + (double) i * 0.7) * 0.6f * reveal;
            double wave = (double) (now % 1800L) / 1800.0 * (double) (name.length() + 4) - 2.0;
            double dist = Math.abs((double) i - wave);
            float glow = (float) Math.max(0.0, 1.0 - dist / 1.5);
            int r = base >> 16 & 0xFF, g = base >> 8 & 0xFF, b = base & 0xFF;
            r = Math.min(255, (int) ((float) r + (float) (255 - r) * glow * 0.8f));
            g = Math.min(255, (int) ((float) g + (float) (255 - g) * glow * 0.8f));
            b = Math.min(255, (int) ((float) b + (float) (255 - b) * glow * 0.8f));
            int a = (int) (255.0f * reveal);
            fr.drawStringWithShadow(String.valueOf(shown), cx, y + bob, a << 24 | r << 16 | g << 8 | b);
            cx += (float) fr.getCharWidth(c);
        }
        RenderUtils.resetColor();
    }
}
