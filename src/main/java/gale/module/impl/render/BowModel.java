package gale.module.impl.render;

import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.WorldEvent;
import gale.event.impl.TickEvent;
import gale.event.impl.MouseEvent;
import gale.event.impl.PacketEvent;
import gale.module.setting.BoolSetting;
import gale.module.setting.NumberSetting;
import gale.render.model.ScopeOverlay;
import gale.util.BowShotCompensation;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Bow skins, hold-to-scope and optional launch-angle drop compensation. */
public final class BowModel extends WeaponModel {
    public final BoolSetting scope=bool("Scope",true).describe("第一人称按住弓右键开镜，松开仍按原版射箭");
    public final NumberSetting scopeZoom=number("Scope zoom",3,1.5,8,.5).describe("瞄准镜放大倍率");
    public final BoolSetting dropCompensation=bool("Drop compensation",true).describe("松开开镜弓时自动补偿准星位置的下坠，镜头保持不动");
    private final BowShotCompensation shotCompensation=new BowShotCompensation(this);
    private float amount;
    private long lastFrame,scopeFrames,hiddenHands;

    public BowModel(){
        super("BowModel",true,"Vandal","Vandal","Sheriff","Marshal","Spectre");
        onAlways(FrameEvent.class,Priority.NORMAL,e->advance());
        onAlways(WorldEvent.Load.class,Priority.NORMAL,e->resetScope());
        onAlways(WorldEvent.Unload.class,Priority.NORMAL,e->resetScope());
        on(TickEvent.Pre.class,e->{if(scopeHeld())shotCompensation.prepare();});
        on(MouseEvent.class,Priority.LOWEST,e->{if(e.button==1 && !e.pressed && !e.isCancelled())shotCompensation.prepare();});
        on(PacketEvent.Send.class,Priority.LOWEST,shotCompensation::release);
    }
    private boolean eligible(){
        return isActive() && scope.get() && mc.thePlayer!=null && mc.theWorld!=null && mc.currentScreen==null
            && mc.gameSettings.thirdPersonView==0 && !mc.gameSettings.hideGUI && mc.getRenderViewEntity()==mc.thePlayer
            && !mc.thePlayer.isSpectator() && !mc.thePlayer.isDead && mc.thePlayer.getHealth()>0 && replaces(mc.thePlayer.getHeldItem());
    }
    public boolean scopeHeld(){
        if(!eligible() || mc.thePlayer.getItemInUseCount()<=0)return false;
        ItemStack using=mc.thePlayer.getItemInUse();
        return using!=null && using==mc.thePlayer.getHeldItem() && using.getItemUseAction()==EnumAction.BOW;
    }
    public float scopeAmount(){return eligible()?amount:0;}
    public long scopeFrames(){return scopeFrames;}
    public long hiddenHands(){return hiddenHands;}
    public long compensatedShots(){return shotCompensation.shots();}
    private boolean visible(){return scopeAmount()>.005f;}
    private void advance(){
        if(!eligible()){resetScope();return;}
        long now=System.nanoTime();
        float dt=lastFrame==0?1/60f:Math.min(.1f,(now-lastFrame)/1_000_000_000f);lastFrame=now;
        float target=scopeHeld()?1:0;
        amount+=(target-amount)*(1-(float)Math.exp(-dt*20));
        if(Math.abs(target-amount)<.003f)amount=target;
    }
    private void resetScope(){amount=0;lastFrame=0;shotCompensation.clear();}
    @Override protected void onDisable(){resetScope();}

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void scopeFov(EntityViewRenderEvent.FOVModifier event){
        if(!visible() || event.entity!=mc.thePlayer)return;
        float t=amount*amount*(3-2*amount);
        double zoom=1+(scopeZoom.get()-1)*t;
        // Tangent-based magnification preserves the requested optical ratio.
        event.setFOV((float)Math.toDegrees(2*Math.atan(Math.tan(Math.toRadians(event.getFOV())*.5)/zoom)));
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void scopeHands(RenderHandEvent event){
        if(visible()){event.setCanceled(true);hiddenHands++;}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void scopeHud(RenderGameOverlayEvent.Pre event){
        if(!visible())return;
        if(event.type==RenderGameOverlayEvent.ElementType.CROSSHAIRS)event.setCanceled(true);
        // Same layer as the native pumpkin mask: hotbar, health and chat stay readable.
        if(event.type==RenderGameOverlayEvent.ElementType.HELMET){
            ScopeOverlay.draw(event.resolution.getScaledWidth(),event.resolution.getScaledHeight(),amount);
            scopeFrames++;
        }
    }
}
