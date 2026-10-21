package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.*;
import gale.module.impl.render.*;
import gale.util.BowBallistics;
import gale.util.ProjectileSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Native EntityArrow integration and a real networked shot, rather than a reticle-only check. */
final class BallisticsTest {
    private static final Minecraft mc=Minecraft.getMinecraft();
    private static boolean fixed;private static MouseHelper mouse;private static String shot;
    private static volatile boolean capture;
    private static volatile EntityArrow serverArrow;
    private static volatile int serverCount;
    private static volatile double velocityY,miss,controlDistance;
    private static volatile float restoredPitch;
    private static int wallX,wallZ;
    private static double targetX,targetY;
    private static long before;
    private static BowModel bows(){return Gale.modules().get(BowModel.class);}
    private static void check(String n,boolean ok,Object d){DevTest.check(n,ok,String.valueOf(d));}
    @SubscribeEvent public void spawn(EntityJoinWorldEvent event){
        if(capture && !event.world.isRemote && event.entity instanceof EntityArrow){
            EntityArrow arrow=(EntityArrow)event.entity;
            if(arrow.shootingEntity!=null && arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID())){
                serverCount++;serverArrow=arrow;velocityY=arrow.motionY;
            }
        }
    }
    static void phases(){
        MinecraftForge.EVENT_BUS.register(new BallisticsTest());
        Gale.BUS.listen(MouseEvent.class,Priority.HIGHEST,e->{if(fixed)e.cancel();},"ballistics-test");
        Gale.BUS.listen(InputEvent.class,Priority.LOWEST,e->{if(fixed){e.stop();e.setSneak(false);e.jump=false;}},"ballistics-test");
        Gale.BUS.listen(FrameEvent.class,Priority.MONITOR,e->{if(shot!=null){ScreenShotHelper.saveScreenshot(mc.mcDataDir,shot+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());DevTest.line("screenshot screenshots/"+shot+".png");shot=null;}},"ballistics-test");
        phase("native deterministic trajectory",25,()->{
            for(gale.module.Module m:Gale.modules().all())if(!m.isAlwaysOn())m.setEnabled(false);
            bows().setEnabled(true);bows().scope.set(true);bows().dropCompensation.set(true);
            fixed=true;mouse=mc.mouseHelper;
            mc.mouseHelper=new MouseHelper(){@Override public void mouseXYChange(){mouse.mouseXYChange();deltaX=deltaY=0;}@Override public void grabMouseCursor(){mouse.grabMouseCursor();}@Override public void ungrabMouseCursor(){mouse.ungrabMouseCursor();}};
            mc.gameSettings.thirdPersonView=0;mc.gameSettings.hideGUI=false;mc.thePlayer.rotationYaw=mc.thePlayer.prevRotationYaw=0;mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=0;
            double[][] cases={{20,0,0,20},{50,0,73,20},{80,5,-45,20},{15,0,40,10},{30,0,120,14},{40,-15,-110,20},{30,12,15,20}};
            Vec3 eye=new Vec3(mc.thePlayer.posX,110,mc.thePlayer.posZ);
            for(int i=0;i<cases.length;i++){
                double[] c=cases[i];double yaw=Math.toRadians(c[2]),charge=ProjectileSim.bowCharge((int)c[3]);
                Vec3 target=eye.addVector(-Math.sin(yaw)*c[0],c[1],Math.cos(yaw)*c[0]);
                BowBallistics.Aim aim=BowBallistics.solve(eye,target,charge);
                double error=aim==null?Double.POSITIVE_INFINITY:nativeMiss(eye,target,aim,charge);
                check("native arrow integration case "+i,error<.025,"miss="+error+" blocks, distance="+c[0]+", chargeTicks="+c[3]);
            }
            check("insufficient charge is not forced",BowBallistics.solve(eye,eye.addVector(0,0,20),.05)==null,"native minimum charge");
            check("unreachable low-charge point is not forced",BowBallistics.solve(eye,eye.addVector(0,0,80),ProjectileSim.bowCharge(6))==null,"no low-arc solution");
            wallX=MathHelper.floor_double(mc.thePlayer.posX);wallZ=MathHelper.floor_double(mc.thePlayer.posZ)+40;targetX=mc.thePlayer.posX;targetY=mc.thePlayer.posY+mc.thePlayer.getEyeHeight();
            wall(true);equip();mc.thePlayer.inventory.setInventorySlotContents(9,new ItemStack(Items.arrow,64));DevTest.server(p->p.inventory.setInventorySlotContents(9,new ItemStack(Items.arrow,64)));
            DevTest.server(p->{for(net.minecraft.entity.Entity e:new java.util.ArrayList<>(p.worldObj.loadedEntityList))if(e instanceof net.minecraft.entity.EntityLivingBase && e!=p && Math.abs(e.posX-targetX)<6 && e.posZ>p.posZ && e.posZ<wallZ)e.setDead();});
        },t->{},()->{});
        phase("automatic compensated release",28,()->{before=bows().compensatedShots();serverArrow=null;serverCount=0;miss=Double.POSITIVE_INFINITY;capture=true;use();},t->{if(t==22)shot="ballistics-01-center-scope";},()->{
            check("scope keeps fixed central camera",mc.thePlayer.rotationYaw==0 && mc.thePlayer.rotationPitch==0,"yaw="+mc.thePlayer.rotationYaw+", pitch="+mc.thePlayer.rotationPitch);release();
        });
        phase("server wall impact",36,()->{},t->{if(t==29)DevTest.server(p->{if(serverArrow!=null)miss=serverArrow.getPositionVector().distanceTo(new Vec3(targetX,targetY,wallZ));restoredPitch=p.rotationPitch;});if(t==31)shot="ballistics-02-after-shot";},()->{
            check("release uses drop compensation",bows().compensatedShots()==before+1,bows().compensatedShots()-before);
            check("server fires exactly one compensated arrow",serverCount==1,serverCount);
            check("native server arrow launches upward",velocityY>.1,velocityY);
            check("native arrow reaches central crosshair wall point",miss<1.75,"miss="+miss+" blocks at ~40 blocks, native random spread enabled");
            check("local camera never changed for shot",mc.thePlayer.rotationYaw==0 && mc.thePlayer.rotationPitch==0,mc.thePlayer.rotationPitch);
            check("server rotation restored after release",Math.abs(restoredPitch)<.001,restoredPitch);
            capture=false;bows().dropCompensation.set(false);
        });
        phase("native uncompensated control",28,()->{equip();serverArrow=null;serverCount=0;controlDistance=999;capture=true;before=bows().compensatedShots();use();},t->{},()->release());
        phase("control drops short",36,()->{},t->{if(t==29)DevTest.server(p->{if(serverArrow!=null)controlDistance=serverArrow.posZ;});},()->{
            check("setting off sends ordinary bow shot",bows().compensatedShots()==before && serverCount==1,serverCount);
            check("ordinary level arrow drops before target wall",controlDistance<wallZ-4,"arrow z="+controlDistance+", target z="+wallZ);
            capture=false;bows().dropCompensation.set(true);wall(false);fixed=false;mc.mouseHelper=mouse;
        });
    }
    private static double nativeMiss(Vec3 eye,Vec3 target,BowBallistics.Aim aim,double charge){
        float y=aim.yaw/180f*(float)Math.PI,p=aim.pitch/180f*(float)Math.PI;
        EntityArrow arrow=new EntityArrow(mc.theWorld);
        arrow.setPosition(eye.xCoord-MathHelper.cos(y)*.16f,eye.yCoord-.1,eye.zCoord-MathHelper.sin(y)*.16f);
        arrow.setThrowableHeading(-MathHelper.sin(y)*MathHelper.cos(p),-MathHelper.sin(p),MathHelper.cos(y)*MathHelper.cos(p),((float)charge*2f)*1.5f,0);
        double sx=arrow.posX,sz=arrow.posZ,dx=target.xCoord-sx,dz=target.zCoord-sz,d=Math.hypot(dx,dz);dx/=d;dz/=d;
        for(int i=0;i<201;i++){
            Vec3 prev=arrow.getPositionVector();double before=(prev.xCoord-sx)*dx+(prev.zCoord-sz)*dz;
            arrow.onUpdate();double after=(arrow.posX-sx)*dx+(arrow.posZ-sz)*dz;
            if(after>=d){double f=(d-before)/(after-before);return prev.addVector((arrow.posX-prev.xCoord)*f,(arrow.posY-prev.yCoord)*f,(arrow.posZ-prev.zCoord)*f).distanceTo(target);}
        }return Double.POSITIVE_INFINITY;
    }
    private static void wall(boolean stone){
        for(int x=wallX-5;x<=wallX+5;x++)for(int y=4;y<=11;y++)mc.theWorld.setBlockState(new BlockPos(x,y,wallZ),stone?Blocks.stone.getDefaultState():Blocks.air.getDefaultState(),3);
        DevTest.server(p->{for(int x=wallX-5;x<=wallX+5;x++)for(int y=4;y<=11;y++)p.worldObj.setBlockState(new BlockPos(x,y,wallZ),stone?Blocks.stone.getDefaultState():Blocks.air.getDefaultState(),3);});
    }
    private static void equip(){release();ItemStack s=new ItemStack(Items.bow);mc.thePlayer.inventory.currentItem=0;mc.thePlayer.inventory.setInventorySlotContents(0,s);DevTest.server(p->p.inventory.setInventorySlotContents(0,s.copy()));}
    private static void use(){KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),true);mc.playerController.sendUseItem(mc.thePlayer,mc.theWorld,mc.thePlayer.getHeldItem());}
    private static void release(){KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),false);mc.playerController.onStoppedUsingItem(mc.thePlayer);}
    private static void phase(String n,int ticks,Runnable enter,java.util.function.IntConsumer tick,Runnable exit){DevTest.PHASES.add(new DevTest.Phase("Ballistics "+n,ticks,enter,tick,exit));}
}
