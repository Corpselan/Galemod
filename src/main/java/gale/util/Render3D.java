package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

/**
 * Drawing in the world, in {@link gale.event.impl.Render3DEvent}. Positions are world coordinates; each call sets up
 * and restores its own GL state. Filled boxes use the colour's alpha, outlines are drawn opaque.
 */
public final class Render3D {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private Render3D() { }

    /** Where the camera is (world coordinates of the drawing origin) this frame. */
    public static Vec3 camera() {
        RenderManager rm = mc.getRenderManager();
        return new Vec3(rm.viewerPosX, rm.viewerPosY, rm.viewerPosZ);
    }

    private static void begin(boolean throughWalls, float lineWidth) {
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        if (throughWalls) GlStateManager.disableDepth();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(lineWidth);
    }

    private static void end() {
        GL11.glLineWidth(1f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }

    private static void color(int c) {
        float[] f = ColorUtil.toFloats(c);
        GlStateManager.color(f[0], f[1], f[2], f[3]);
    }

    public static void box(AxisAlignedBB worldBox, int color, boolean filled, boolean outline, float lineWidth, boolean throughWalls) {
        Vec3 cam = camera();
        AxisAlignedBB bb = worldBox.offset(-cam.xCoord, -cam.yCoord, -cam.zCoord);
        begin(throughWalls, lineWidth);
        if (filled) {
            color(color);
            fill(bb);
        }
        if (outline) {
            color(ColorUtil.withAlpha(color, 255));
            RenderGlobal.drawSelectionBoundingBox(bb);
        }
        end();
    }

    public static void block(BlockPos pos, int color, boolean filled, boolean outline, boolean throughWalls) {
        box(new AxisAlignedBB(pos, pos.add(1, 1, 1)), color, filled, outline, 1.5f, throughWalls);
    }

    /** An entity's hitbox where it is drawn this frame. */
    public static void entity(Entity e, float partialTicks, int color, boolean filled, boolean outline, boolean throughWalls) {
        float border = e.getCollisionBorderSize();
        box(EntityUtil.interpolatedBox(e, partialTicks).expand(border, border, border), color, filled, outline, 1.5f, throughWalls);
    }

    public static void line(Vec3 from, Vec3 to, int color, float width, boolean throughWalls) {
        Vec3 cam = camera();
        begin(throughWalls, width);
        color(color);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        w.pos(from.xCoord - cam.xCoord, from.yCoord - cam.yCoord, from.zCoord - cam.zCoord).endVertex();
        w.pos(to.xCoord - cam.xCoord, to.yCoord - cam.yCoord, to.zCoord - cam.zCoord).endVertex();
        t.draw();
        end();
    }

    /** A line from the middle of the screen to the entity. */
    public static void tracer(Entity e, float partialTicks, int color, float width) {
        Vec3 cam = camera();
        Vec3 look = RotationUtil.look(RotationUtil.camera()[0], RotationUtil.camera()[1]);
        Vec3 start = new Vec3(cam.xCoord + look.xCoord, cam.yCoord + mc.thePlayer.getEyeHeight() + look.yCoord, cam.zCoord + look.zCoord);
        Vec3 at = EntityUtil.interpolated(e, partialTicks);
        line(start, at.addVector(0, e.height / 2, 0), color, width, true);
    }

    private static void fill(AxisAlignedBB b) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        // bottom, top
        w.pos(b.minX, b.minY, b.minZ).endVertex(); w.pos(b.maxX, b.minY, b.minZ).endVertex(); w.pos(b.maxX, b.minY, b.maxZ).endVertex(); w.pos(b.minX, b.minY, b.maxZ).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).endVertex(); w.pos(b.minX, b.maxY, b.maxZ).endVertex(); w.pos(b.maxX, b.maxY, b.maxZ).endVertex(); w.pos(b.maxX, b.maxY, b.minZ).endVertex();
        // north, south
        w.pos(b.minX, b.minY, b.minZ).endVertex(); w.pos(b.minX, b.maxY, b.minZ).endVertex(); w.pos(b.maxX, b.maxY, b.minZ).endVertex(); w.pos(b.maxX, b.minY, b.minZ).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).endVertex(); w.pos(b.maxX, b.minY, b.maxZ).endVertex(); w.pos(b.maxX, b.maxY, b.maxZ).endVertex(); w.pos(b.minX, b.maxY, b.maxZ).endVertex();
        // west, east
        w.pos(b.minX, b.minY, b.minZ).endVertex(); w.pos(b.minX, b.minY, b.maxZ).endVertex(); w.pos(b.minX, b.maxY, b.maxZ).endVertex(); w.pos(b.minX, b.maxY, b.minZ).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).endVertex(); w.pos(b.maxX, b.maxY, b.minZ).endVertex(); w.pos(b.maxX, b.maxY, b.maxZ).endVertex(); w.pos(b.maxX, b.minY, b.maxZ).endVertex();
        t.draw();
    }
}
