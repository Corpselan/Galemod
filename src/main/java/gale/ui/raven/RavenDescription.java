package gale.ui.raven;

import gale.b4port.util.Theme;
import org.lwjgl.opengl.GL11;

/** B4's DescriptionComponent: a section title in a B4 module's settings. */
public class RavenDescription extends RavenComponent {
    public final String desc;
    public RavenModule p;
    public float o;

    public RavenDescription(String desc, RavenModule b, float o) {
        this.desc = desc;
        this.p = b;
        this.o = o;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        mc.fontRendererObj.drawString(this.desc, (this.p.categoryComponent.getX() + 4.0f) * 2.0f,
            (this.p.categoryComponent.getY() + this.o + 4.0f) * 2.0f, Theme.SKY.getRGB(0.0), true);
        GL11.glPopMatrix();
    }

    @Override
    public void setOffset(float n) { this.o = n; }

    @Override
    public float getHeight() { return 12.0f; }
}
