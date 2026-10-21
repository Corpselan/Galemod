package gale.module.impl.render;

import gale.module.Category;
import gale.module.Module;
import gale.module.setting.NumberSetting;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Render-only spin: no camera, entity rotation, movement or packet values are changed. */
public final class LocalSpin extends Module {
    private final NumberSetting speed = number("Speed",1440,90,3600,90).suffix(" deg/s")
        .describe("本地模型每秒旋转的角度，1440 为每秒 4 圈");
    private boolean nested;
    private long epoch, renderedFrames, poseMutations;

    public LocalSpin() {
        super("LocalSpin",Category.RENDER,"本地大陀螺：F5 看自己转圈，别人看到的朝向不变");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override protected void onEnable() { epoch=System.nanoTime(); }

    public float angle() { return (float)(((System.nanoTime()-epoch)/1_000_000_000d*speed.get())%360d); }
    public long renderedFrames() { return renderedFrames; }
    public long poseMutations() { return poseMutations; }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void render(RenderPlayerEvent.Pre event) {
        if (nested || !isActive() || event.entityPlayer!=mc.thePlayer) return;
        // Cancel the outer render and re-enter it inside a scoped graphics transform.
        // A finally block also restores the matrix when another renderer cancels the nested event.
        event.setCanceled(true);
        nested=true;
        float yaw=mc.thePlayer.rotationYaw, pitch=mc.thePlayer.rotationPitch;
        float body=mc.thePlayer.renderYawOffset, prevBody=mc.thePlayer.prevRenderYawOffset;
        float head=mc.thePlayer.rotationYawHead, prevHead=mc.thePlayer.prevRotationYawHead;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(event.x,event.y,event.z);
            GlStateManager.rotate(angle(),0,1,0);
            GlStateManager.translate(-event.x,-event.y,-event.z);
            event.renderer.doRender((AbstractClientPlayer)event.entityPlayer,event.x,event.y,event.z,
                event.entityPlayer.rotationYaw,event.partialRenderTick);
            renderedFrames++;
        } finally {
            if (yaw!=mc.thePlayer.rotationYaw || pitch!=mc.thePlayer.rotationPitch || body!=mc.thePlayer.renderYawOffset
                || prevBody!=mc.thePlayer.prevRenderYawOffset || head!=mc.thePlayer.rotationYawHead || prevHead!=mc.thePlayer.prevRotationYawHead) poseMutations++;
            GlStateManager.popMatrix(); nested=false;
        }
    }
}
