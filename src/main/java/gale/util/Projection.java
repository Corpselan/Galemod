package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * World position to screen position, for drawing labels over things in Render2D (nametags, ESP boxes). Uses the
 * camera of the last drawn frame (captured by the hooks after the world is drawn).
 */
public final class Projection {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16), PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer OUT = BufferUtils.createFloatBuffer(3);
    private static Vec3 camera = new Vec3(0, 0, 0);
    private static boolean valid;

    private Projection() { }

    /** Called by the hooks after the world is drawn. */
    public static void capture(float partialTicks) {
        MODELVIEW.clear();
        PROJECTION.clear();
        VIEWPORT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        camera = Render3D.camera();
        valid = true;
    }

    /** {x, y} in GUI coordinates, or null when the point is behind the camera or off the frame. */
    public static float[] toScreen(double x, double y, double z) {
        if (!valid) return null;
        OUT.clear();
        MODELVIEW.rewind();
        PROJECTION.rewind();
        VIEWPORT.rewind();
        boolean ok = GLU.gluProject((float) (x - camera.xCoord), (float) (y - camera.yCoord), (float) (z - camera.zCoord), MODELVIEW, PROJECTION, VIEWPORT, OUT);
        if (!ok) return null;
        float sx = OUT.get(0), sy = OUT.get(1), sz = OUT.get(2);
        if (sz < 0 || sz > 1) return null;
        int scale = new ScaledResolution(mc).getScaleFactor();
        return new float[]{ sx / scale, (mc.displayHeight - sy) / scale };
    }

    public static float[] toScreen(Vec3 v) { return toScreen(v.xCoord, v.yCoord, v.zCoord); }
}
