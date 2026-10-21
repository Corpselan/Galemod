package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.*;
import gale.module.impl.render.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MouseHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import java.nio.FloatBuffer;

/** Native use/release, world projection, hand suppression and scope cleanup checks. */
final class ScopeTest {
    private static final Minecraft mc=Minecraft.getMinecraft();
    private static final String[] SKINS={"Vandal","Sheriff","Marshal","Spectre"};
    private static final FloatBuffer PROJECTION=BufferUtils.createFloatBuffer(16);
    private static String shot;
    private static boolean fixed,clean=true;
    private static MouseHelper mouse;
    private static float originalFov,originalSensitivity,baseProjection,currentProjection,nativeChargeProjection;
    private static long frames,handFrames,modelFrames;
    private static volatile int serverArrows;
    private static BowModel bows(){return Gale.modules().get(BowModel.class);}
    private static JettModel jett(){return Gale.modules().get(JettModel.class);}
    private static void check(String name,boolean ok,Object detail){DevTest.check(name,ok,String.valueOf(detail));}
    @SubscribeEvent public void world(RenderWorldLastEvent e){
        PROJECTION.clear();GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX,PROJECTION);
        currentProjection=(float)Math.toDegrees(2*Math.atan(1/PROJECTION.get(5)));
    }
    static void phases(){
        MinecraftForge.EVENT_BUS.register(new ScopeTest());
        Gale.BUS.listen(MouseEvent.class,Priority.HIGHEST,e->{if(fixed)e.cancel();},"scope-test");
        Gale.BUS.listen(InputEvent.class,Priority.LOWEST,e->{if(fixed){e.stop();e.setSneak(false);e.jump=false;}},"scope-test");
        Gale.BUS.listen(FrameEvent.class,Priority.MONITOR,e->{if(shot!=null){ScreenShotHelper.saveScreenshot(mc.mcDataDir,shot+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());DevTest.line("screenshot screenshots/"+shot+".png");shot=null;}},"scope-test");
        Gale.BUS.listen(Render2DEvent.class,Priority.MONITOR,e->{if(GL11.glGetError()!=0)clean=false;},"scope-test");
        phase("baseline",25,()->{
            for(gale.module.Module m:Gale.modules().all())if(!m.isAlwaysOn())m.setEnabled(false);
            bows().setEnabled(true);bows().scope.set(true);bows().scopeZoom.set(3);bows().skin.set("Vandal");
            frames=bows().scopeFrames();
            fixed=true;mouse=mc.mouseHelper;
            mc.mouseHelper=new MouseHelper(){@Override public void mouseXYChange(){mouse.mouseXYChange();deltaX=deltaY=0;}@Override public void grabMouseCursor(){mouse.grabMouseCursor();}@Override public void ungrabMouseCursor(){mouse.ungrabMouseCursor();}};
            originalFov=mc.gameSettings.fovSetting;originalSensitivity=mc.gameSettings.mouseSensitivity;
            mc.gameSettings.thirdPersonView=0;mc.gameSettings.hideGUI=false;
            mc.thePlayer.rotationYaw=mc.thePlayer.prevRotationYaw=0;mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=0;
            equip(new ItemStack(Items.bow));mc.thePlayer.inventory.setInventorySlotContents(9,new ItemStack(Items.arrow,64));
            DevTest.server(p->p.inventory.setInventorySlotContents(9,new ItemStack(Items.arrow,64)));
        },t->{if(t==18)shot="scope-00-unscoped";},()->{
            baseProjection=currentProjection;check("idle bow has no scope",bows().scopeAmount()==0 && bows().scopeFrames()==frames,bows().scopeAmount());
        });
        phase("native hold to scope",30,()->{frames=bows().scopeFrames();modelFrames=bows().draws();use();},t->{if(t==23)shot="scope-01-native";},()->{
            check("native right use opens scope",bows().scopeAmount()>.99f && bows().scopeFrames()>frames+10,bows().scopeAmount());
            check("native hand is hidden",bows().hiddenHands()>10,bows().hiddenHands());
            check("actual world projection zooms",currentProjection<baseProjection*.5f,"FOV "+baseProjection+" -> "+currentProjection);
            check("scope leaves configured FOV untouched",mc.gameSettings.fovSetting==originalFov,mc.gameSettings.fovSetting);
            release();
        });
        phase("release shoots and restores",25,()->{},t->{if(t==12)DevTest.server(p->{int count=0;for(Entity e:p.worldObj.loadedEntityList)if(e instanceof EntityArrow && ((EntityArrow)e).shootingEntity==p)count++;serverArrows=count;});if(t==20)shot="scope-02-release";},()->{
            check("release exits scope",bows().scopeAmount()==0,bows().scopeAmount());
            check("release restores world projection",Math.abs(currentProjection-baseProjection)<1,"FOV "+currentProjection);
            check("native server still fires an arrow",serverArrows>0,serverArrows);
            check("held item remains bow",mc.thePlayer.getHeldItem().getItem()==Items.bow,mc.thePlayer.getHeldItem());
            jett().setEnabled(true);
        });
        for(int i=0;i<SKINS.length;i++){final String skin=SKINS[i];
            phase("Jett "+skin,25,()->{bows().skin.set(skin);equip(new ItemStack(Items.bow));handFrames=jett().firstFrames();frames=bows().scopeFrames();use();},t->{if(t==18)shot="scope-03-"+skin.toLowerCase();},()->{
                check(skin+" opens scope with Jett",bows().scopeAmount()>.99f && bows().scopeFrames()>frames+10,bows().scopeAmount());
                check(skin+" suppresses Jett hands",jett().firstFrames()<=handFrames+3,jett().firstFrames()-handFrames);
                release();
            });
        }
        phase("item switch cleanup",25,()->use(),t->{if(t==10)equip(new ItemStack(Items.diamond_sword));if(t==20)shot="scope-04-switch";},()->{
            check("switch to sword immediately clears scope",bows().scopeAmount()==0,bows().scopeAmount());
            check("sword restores normal projection",Math.abs(currentProjection-baseProjection)<1,currentProjection);
        });
        phase("inventory cleanup",25,()->{equip(new ItemStack(Items.bow));use();},t->{if(t==10)mc.displayGuiScreen(new GuiInventory(mc.thePlayer));if(t==20)shot="scope-05-inventory";},()->{check("opening inventory clears scope",bows().scopeAmount()==0,bows().scopeAmount());mc.displayGuiScreen(null);release();});
        phase("third person cleanup",25,()->use(),t->{if(t==10)mc.gameSettings.thirdPersonView=2;if(t==20)shot="scope-06-third";},()->{check("third person never scopes",bows().scopeAmount()==0,bows().scopeAmount());mc.gameSettings.thirdPersonView=0;release();});
        phase("disabled scope setting",25,()->{bows().scope.set(false);frames=bows().scopeFrames();use();},t->{if(t==18)shot="scope-07-setting-off";},()->{
            check("scope setting off keeps charging gun visible",bows().scopeFrames()==frames && bows().scopeAmount()==0,"no mask");
            check("scope setting off keeps Jett rendering",jett().firstFrames()>handFrames+10,jett().firstFrames());
            nativeChargeProjection=currentProjection;
            release();bows().scope.set(true);
        });
        phase("disable module cleanup",25,()->use(),t->{if(t==10)bows().setEnabled(false);},()->{check("disabling module clears scope",bows().scopeAmount()==0,bows().scopeAmount());check("disabled module restores native charging FOV",Math.abs(currentProjection-nativeChargeProjection)<1,"native "+nativeChargeProjection+" -> "+currentProjection);release();bows().setEnabled(true);});
        phase("zoom setting",25,()->{bows().scopeZoom.set(6);use();},t->{if(t==18)shot="scope-08-six-times";},()->{
            check("six times setting changes actual projection",currentProjection<baseProjection*.25f,currentProjection);
            check("scope leaves sensitivity untouched",mc.gameSettings.mouseSensitivity==originalSensitivity,mc.gameSettings.mouseSensitivity);
            check("scope leaves configured FOV untouched after cleanup",mc.gameSettings.fovSetting==originalFov,mc.gameSettings.fovSetting);
            check("scope has no OpenGL errors",clean,"mask, native hands, Jett, switches and GUI");release();
        });
        phase("finish",15,()->{},t->{},()->{
            check("final release clears scope",bows().scopeAmount()==0,bows().scopeAmount());
            bows().scopeZoom.set(3);bows().skin.set("Vandal");jett().setEnabled(false);fixed=false;mc.mouseHelper=mouse;
        });
    }
    private static void use(){KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),true);mc.playerController.sendUseItem(mc.thePlayer,mc.theWorld,mc.thePlayer.getHeldItem());}
    private static void release(){KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),false);mc.playerController.onStoppedUsingItem(mc.thePlayer);}
    private static void equip(ItemStack s){release();mc.thePlayer.inventory.currentItem=0;mc.thePlayer.inventory.setInventorySlotContents(0,s);DevTest.server(p->p.inventory.setInventorySlotContents(0,s.copy()));}
    private static void phase(String n,int ticks,Runnable enter,java.util.function.IntConsumer tick,Runnable exit){DevTest.PHASES.add(new DevTest.Phase("Scope "+n,ticks,enter,tick,exit));}
}
