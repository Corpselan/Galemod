package gale.ui.raven;

import gale.ui.ClickGui;
import gale.security.ReleaseIntegrity;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import java.nio.FloatBuffer;

/** Draw after either GUI has finished. GUI scale, hide switches and opening animations cannot remove this block. */
public final class MandatoryWatermarkOverlay {
    private static boolean installed;
    private static final FloatBuffer color = BufferUtils.createFloatBuffer(16);
    private static WeakReference<GuiScreenEvent.DrawScreenEvent.Post> lastDraw = new WeakReference<>(null);
    private MandatoryWatermarkOverlay() { }
    public static void install() {
        ReleaseIntegrity.assertTrustedClass(MandatoryWatermarkOverlay.class);
        if (!installed) { MinecraftForge.EVENT_BUS.register(new MandatoryWatermarkOverlay()); installed = true; }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void draw(GuiScreenEvent.DrawScreenEvent.Post event) { enforce(event); }

    public static void enforce(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.gui instanceof ClickGui) && !(event.gui instanceof RavenGui)) return;
        ReleaseIntegrity.assertTrustedClass(MandatoryWatermarkOverlay.class);
        ReleaseIntegrity.assertTrustedClass(event.gui instanceof ClickGui ? ClickGui.class : RavenGui.class);
        if (lastDraw.get() == event) return;
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int sr = GL11.glGetInteger(0x80C9), dr = GL11.glGetInteger(0x80C8), sa = GL11.glGetInteger(0x80CB), da = GL11.glGetInteger(0x80CA);
        color.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        GlStateManager.pushMatrix();
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.disableDepth(); GlStateManager.depthMask(false); GlStateManager.disableLighting();
            GlStateManager.enableTexture2D(); GlStateManager.enableAlpha(); GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); GlStateManager.color(1, 1, 1, 1);
            ScaledResolution resolution = new ScaledResolution(Minecraft.getMinecraft());
            RavenWatermark.render(4, resolution.getScaledHeight() - 12, false);
            lastDraw = new WeakReference<>(event);
        } finally {
            GlStateManager.popMatrix(); GlStateManager.depthMask(write);
            if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (texture) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            GlStateManager.tryBlendFuncSeparate(sr, dr, sa, da);
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GlStateManager.bindTexture(bound);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
        }
    }
}
