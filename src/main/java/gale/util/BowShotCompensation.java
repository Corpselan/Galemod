package gale.util;

import gale.core.NetHook;
import gale.core.RotationManager;
import gale.core.ServerState;
import gale.event.impl.PacketEvent;
import gale.module.impl.render.BowModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Main-thread target snapshot; ordered aim/release/restore at the actual outbound release. */
public final class BowShotCompensation {
    private static final Minecraft mc=Minecraft.getMinecraft();
    private final BowModel module;
    private volatile Snapshot pending;
    private volatile long compensatedShots;
    public BowShotCompensation(BowModel module){this.module=module;}
    public long shots(){return compensatedShots;}
    public void clear(){pending=null;}
    /** Only called from game-thread tick/input handlers. No per-frame raycasts or entity scans. */
    public void prepare(){
        if(!module.dropCompensation.get() || !module.scopeHeld()){pending=null;return;}
        float yaw=RotationManager.cameraYaw(),pitch=RotationManager.cameraPitch();
        double charge=ProjectileSim.bowCharge(mc.thePlayer.getItemInUseDuration());
        if(charge<.1){pending=null;return;}
        MovingObjectPosition hit=RaycastUtil.mouseOver(yaw,pitch,128,128);
        pending=hit==null || hit.hitVec==null || charge<.1?null:new Snapshot(hit.hitVec,charge,yaw,pitch);
    }
    /** PacketEvent runs on the network thread: uses immutable data and pure math, never world queries. */
    public void release(PacketEvent.Send event){
        C07PacketPlayerDigging release=event.as(C07PacketPlayerDigging.class);
        if(release==null || release.getStatus()!=C07PacketPlayerDigging.Action.RELEASE_USE_ITEM || event.isCancelled())return;
        Snapshot shot=pending;pending=null;
        if(shot==null || !module.isEnabled() || !module.scope.get() || !module.dropCompensation.get() || mc.currentScreen!=null
            || mc.gameSettings.thirdPersonView!=0 || mc.gameSettings.hideGUI || shot.player!=mc.thePlayer || shot.world!=mc.theWorld
            || shot.connection!=mc.getNetHandler() || shot.stack!=mc.thePlayer.getHeldItem() || System.nanoTime()-shot.created>250_000_000L
            || NetHook.OUT.isActive() || ServerState.lastMovementPacket()==0)return;
        // Never use a stale crosshair point after a quick turn between snapshot and release.
        if(Math.abs(MathHelper.wrapAngleTo180_float(RotationManager.cameraYaw()-shot.yaw))>.75f || Math.abs(RotationManager.cameraPitch()-shot.pitch)>.75f)return;
        Vec3 origin=new Vec3(ServerState.x(),ServerState.y()+shot.eyeHeight,ServerState.z());
        BowBallistics.Aim aim=BowBallistics.solve(origin,shot.target,shot.charge);
        if(aim==null)return;
        float restoreYaw=ServerState.yaw(),restorePitch=ServerState.pitch();boolean ground=ServerState.onGround();
        // These run synchronously on the same connection event loop, in this exact order.
        // No local player/camera rotations are assigned, and the native release is sent once.
        event.cancel();
        PacketUtil.sendNoEvent(new C05PacketPlayerLook(aim.yaw,aim.pitch,ground));
        PacketUtil.sendNoEvent(release);
        PacketUtil.sendNoEvent(new C05PacketPlayerLook(restoreYaw,restorePitch,ground));
        compensatedShots++;
    }
    private static final class Snapshot {
        final Vec3 target;final double charge;final float yaw,pitch,eyeHeight;final long created=System.nanoTime();
        final EntityPlayerSP player=mc.thePlayer;final WorldClient world=mc.theWorld;
        final NetHandlerPlayClient connection=mc.getNetHandler();final ItemStack stack=mc.thePlayer.getHeldItem();
        Snapshot(Vec3 target,double charge,float yaw,float pitch){this.target=target;this.charge=charge;this.yaw=yaw;this.pitch=pitch;eyeHeight=player.getEyeHeight();}
    }
}
