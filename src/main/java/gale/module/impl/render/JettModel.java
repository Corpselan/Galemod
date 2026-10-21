package gale.module.impl.render;

import gale.Gale;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.NumberSetting;
import gale.render.model.JettPose;
import gale.render.model.JettRenderer;
import gale.util.ChatUtil;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.item.ItemBow;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

/** Local visual replacement. Game items still use Minecraft's item renderer. */
public final class JettModel extends Module {
    private final BoolSetting firstPerson = bool("First person", true).describe("Jett 双臂，手持当前 Minecraft 物品；地图保留原版双手显示");
    private final BoolSetting thirdPerson = bool("Third person", true).describe("仅替换自己的第三人称模型，不改变碰撞箱");
    private final NumberSetting handX = number("Hand X", 0, -0.3, 0.3, 0.01);
    private final NumberSetting handY = number("Hand Y", 0, -0.3, 0.3, 0.01);
    private final NumberSetting handZ = number("Hand Z", 0, -0.3, 0.3, 0.01);
    private final NumberSetting handFov = number("Hand FOV", 70, 50, 100, 1);
    private JettRenderer renderer;
    private boolean failed;
    private long firstFrames, thirdFrames, renderNanos;

    public JettModel() {
        super("JettModel", Category.RENDER, "Jett 人物与第一人称双臂，使用当前 Minecraft 物品");
        MinecraftForge.EVENT_BUS.register(this);
    }

    private boolean ready() {
        if (renderer != null) return true;
        if (failed) return false;
        try { renderer = new JettRenderer(); return true; }
        catch (Throwable error) {
            failed = true;
            Gale.LOG.error("Jett model unavailable; keeping vanilla rendering", error);
            ChatUtil.error("Jett 模型无法加载，已保留原版显示；详细原因见游戏日志");
            return false;
        }
    }

    @SubscribeEvent
    public void player(RenderPlayerEvent.Pre event) {
        if (!isActive() || !thirdPerson.get() || event.entityPlayer != mc.thePlayer
            || mc.thePlayer.isInvisible() || mc.thePlayer.isSpectator() || mc.thePlayer.isPlayerSleeping() || !ready()) return;
        long start = System.nanoTime();
        AbstractClientPlayer p = mc.thePlayer;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(event.x, event.y, event.z);
            GlStateManager.rotate(180 - JettPose.interpolateAngle(p.prevRenderYawOffset, p.renderYawOffset, event.partialRenderTick), 0, 1, 0);
            renderer.pose.third(p, event.partialRenderTick);
            renderer.draw(false, p.getBrightness(event.partialRenderTick), p.hurtTime > 0);
            ItemStack held = p.getHeldItem();
            if (held != null) {
                GlStateManager.pushMatrix();
                try {
                    float[] h = renderer.pose.rightHand;
                    GlStateManager.translate(h[0], h[1], h[2]);
                    GlStateManager.rotate(180, 0, 1, 0);
                    GlStateManager.rotate(-15, 1, 0, 0);
                    drawItem(held, false);
                } finally { GlStateManager.popMatrix(); }
            }
            event.setCanceled(true);
            thirdFrames++;
        } finally { GlStateManager.popMatrix(); renderNanos += System.nanoTime() - start; }
    }

    @SubscribeEvent
    public void hand(RenderHandEvent event) {
        if (!isActive() || !firstPerson.get() || mc.gameSettings.thirdPersonView != 0 || mc.gameSettings.hideGUI
            || mc.getRenderViewEntity() != mc.thePlayer || mc.thePlayer.isSpectator() || mc.thePlayer.isInvisible()) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        // Live maps use the original two-handed renderer, including its dynamic map contents.
        if (held != null && held.getItem() instanceof ItemMap || !ready()) return;
        long start = System.nanoTime();
        int previousMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        Project.gluPerspective(handFov.getFloat(), (float) mc.displayWidth / mc.displayHeight, 0.05f, 64);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        try {
            // Forge dispatches this event before the vanilla hand camera and depth clear.
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.enableDepth();
            GlStateManager.translate(handX.get(), handY.get(), handZ.get());
            renderer.pose.first(mc.thePlayer, event.partialTicks);
            renderer.draw(true, mc.thePlayer.getBrightness(event.partialTicks), mc.thePlayer.hurtTime > 0);
            if (held != null) {
                GlStateManager.pushMatrix();
                try {
                    float[] h = renderer.pose.rightHand;
                    GlStateManager.translate(h[0], h[1], h[2]);
                    drawItem(held, true);
                } finally { GlStateManager.popMatrix(); }
            }
            GlStateManager.loadIdentity();
            mc.getItemRenderer().renderOverlays(event.partialTicks);
            firstFrames++;
            event.setCanceled(true);
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
            GL11.glMatrixMode(previousMode);
            if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            GlStateManager.color(1,1,1,1);
            renderNanos += System.nanoTime() - start;
        }
    }

    private void drawItem(ItemStack held, boolean first) {
        boolean block = held.getItem() instanceof ItemBlock;
        boolean tool = held.getItem() instanceof ItemSword || held.getItem() instanceof ItemTool;
        SwordModel swords=Gale.modules().get(SwordModel.class);
        boolean gun=Gale.modules().get(BowModel.class).replaces(held);
        boolean katana=swords!=null && swords.replaces(held);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean light0 = GL11.glIsEnabled(GL11.GL_LIGHT0), light1 = GL11.glIsEnabled(GL11.GL_LIGHT1);
        boolean colorMaterial = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
        boolean rescale = GL11.glIsEnabled(0x803A);
        int shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        float oldLightX = OpenGlHelper.lastBrightnessX, oldLightY = OpenGlHelper.lastBrightnessY;
        int activeTexture = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1,1,1,1);
            // Define the lights in camera space, before scaling the item normals.
            if (!lighting) RenderHelper.enableStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            mc.entityRenderer.enableLightmap();
            int light = mc.theWorld.getCombinedLight(new BlockPos(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ), 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light & 65535, light >> 16);
            if (first) {
                GlStateManager.rotate(renderer.pose.itemPitch, 1, 0, 0);
                GlStateManager.rotate(renderer.pose.itemYaw, 0, 1, 0);
                if (tool) GlStateManager.rotate(renderer.pose.itemRoll-(katana?20:0), 0, 0, 1);
            } else if (tool) GlStateManager.rotate(katana?-20:40, 0, 0, 1);
            float size = gun ? (first && !Gale.modules().get(BowModel.class).skin.is("Sheriff")?.70f:.85f) : katana ? (first?.46f:.75f) : block ? 0.23f : tool ? (first ? 0.32f : 0.46f) : held.getItem() instanceof ItemBow ? 0.40f : 0.27f;
            GlStateManager.scale(size, size, size);
            if (katana || gun) { /* Grip is already the converted mesh origin. */ }
            else if (tool) GlStateManager.translate(0.30f, 0.30f, 0);
            else GlStateManager.translate(0, 0.17f, 0);
            mc.getRenderItem().renderItemModelForEntity(held, mc.thePlayer, ItemCameraTransforms.TransformType.NONE);
        } finally {
            if (!lightmap) mc.entityRenderer.disableLightmap();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, oldLightX, oldLightY);
            if (rescale) GlStateManager.enableRescaleNormal(); else GlStateManager.disableRescaleNormal();
            if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (light0) GlStateManager.enableLight(0); else GlStateManager.disableLight(0);
            if (light1) GlStateManager.enableLight(1); else GlStateManager.disableLight(1);
            if (colorMaterial) GlStateManager.enableColorMaterial(); else GlStateManager.disableColorMaterial();
            GlStateManager.shadeModel(shade);
            GlStateManager.setActiveTexture(activeTexture);
            GlStateManager.popMatrix();
        }
    }

    public boolean isModelReady() { return renderer != null && !failed; }
    public long firstFrames() { return firstFrames; }
    public long thirdFrames() { return thirdFrames; }
    public double averageRenderMicros() { return renderNanos / (double) Math.max(1, firstFrames + thirdFrames) / 1000; }
    public int firstTriangles() { return renderer == null ? 0 : renderer.firstPersonTriangles; }
    public int thirdTriangles() { return renderer == null ? 0 : renderer.triangles; }
    public float maxArmJointError() { return renderer == null ? Float.POSITIVE_INFINITY : renderer.pose.maxArmError(); }
}
