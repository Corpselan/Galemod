package gale.ui.raven;

import gale.b4port.util.Theme;
import gale.module.setting.TextSetting;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/** A text setting, drawn like B4's bind line: click to type, Enter to keep, Esc to drop. */
public class RavenText extends RavenComponent {
    public static boolean editing;
    public final TextSetting setting;
    public RavenModule p;
    public float o, x, y;
    private boolean typing;
    private String draft = "";

    public RavenText(TextSetting setting, RavenModule b, float o) {
        this.setting = setting;
        this.p = b;
        this.o = o;
    }

    @Override
    public float getHeight() { return this.setting.isVisible() ? 12.0f : 0.0f; }

    @Override
    public void setOffset(float n) { this.o = n; }

    @Override
    public void updatePosition(int mx, int my) {
        this.x = this.p.categoryComponent.getX();
        this.y = this.p.categoryComponent.getY() + this.o;
    }

    @Override
    public void onGuiClosed() { this.stop(false); }

    private void stop(boolean keep) {
        if (!this.typing) return;
        if (keep) this.setting.set(this.draft);
        this.typing = false;
        editing = false;
    }

    @Override
    public boolean mouseClicked(int mx, int my, int b) {
        if (!this.p.opened || !this.setting.isVisible()) return false;
        boolean over = (float) mx > this.x && (float) mx < this.x + this.p.categoryComponent.getWidth() && (float) my > this.y - 1.0f && (float) my < this.y + 12.0f;
        if (!over) {
            this.stop(true);
            return false;
        }
        if (b != 0) return false;
        if (this.typing) {
            this.stop(true);
        } else {
            this.typing = true;
            editing = true;
            this.draft = this.setting.get();
        }
        return true;
    }

    @Override
    public void keyTyped(char c, int k) {
        if (!this.typing) return;
        if (k == Keyboard.KEY_RETURN || k == Keyboard.KEY_NUMPADENTER) this.stop(true);
        else if (k == Keyboard.KEY_ESCAPE) this.stop(false);
        else if (k == Keyboard.KEY_BACK) {
            if (!this.draft.isEmpty()) this.draft = this.draft.substring(0, this.draft.length() - 1);
        } else if (ChatAllowedCharacters.isAllowedCharacter(c)) {
            this.draft += c;
        }
    }

    @Override
    public void render(int mouseX, int mouseY) {
        if (!this.setting.isVisible()) return;
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        float tx = (this.p.categoryComponent.getX() + 4.0f) * 2.0f;
        float ty = (this.p.categoryComponent.getY() + this.o + 3.0f) * 2.0f;
        String v = this.typing ? this.draft + (System.currentTimeMillis() / 500L % 2L == 0L ? "_" : "") : this.setting.get();
        String s = this.setting.getName() + ": '\u00a7e" + mc.fontRendererObj.trimStringToWidth(v, 150) + "\u00a7r'";
        mc.fontRendererObj.drawStringWithShadow(s, tx, ty, Theme.SKY.getRGB(0.0));
        GL11.glPopMatrix();
    }
}
