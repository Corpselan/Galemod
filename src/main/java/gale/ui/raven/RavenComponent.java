package gale.ui.raven;

import net.minecraft.client.Minecraft;

/** B4's Component: a row of a module's settings. */
public abstract class RavenComponent {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    public void updatePosition(int mouseX, int mouseY) { }

    public void setOffset(float offset) { }

    public void mouseReleased(int x, int y, int button) { }

    public void render(int mouseX, int mouseY) { }

    public void keyTyped(char c, int key) { }

    public void onGuiClosed() { }

    public boolean mouseClicked(int x, int y, int button) { return false; }

    public abstract float getHeight();
}
